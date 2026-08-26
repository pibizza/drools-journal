/*
 * Copyright (c) 2026 Drools Journal Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.journal.core;

import org.drools.journal.api.DurableSessionOption;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ModifyLambdaRegistry;
import org.drools.journal.api.ObjectStorageMode;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.core.RestoreEngine.ScanResult;
import org.drools.base.RuleBase;
import org.drools.core.SessionConfiguration;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.common.InternalWorkingMemoryEntryPoint;
import org.drools.core.common.TruthMaintenanceSystem;
import org.drools.core.common.TruthMaintenanceSystemFactory;
import org.drools.core.rule.consequence.InternalMatch;
import org.drools.kiesession.factory.RuntimeComponentFactoryImpl;
import org.drools.kiesession.rulebase.InternalKnowledgeBase;
import org.kie.api.KieBase;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.runtime.rule.Match;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JournalledRuntimeComponentFactory extends RuntimeComponentFactoryImpl {

    @Override
    public InternalWorkingMemory createStatefulSession(final RuleBase ruleBase,
                                                       final Environment environment,
                                                       final SessionConfiguration sessionConfig,
                                                       final boolean fromPool) {
        DurableSessionOption opt = environment != null
                ? (DurableSessionOption) environment.get(DurableSessionOption.PROPERTY_NAME)
                : null;
        if (opt == null) {
            return super.createStatefulSession(ruleBase, environment, sessionConfig, fromPool);
        }
        return createJournalledSession(ruleBase, environment, sessionConfig, fromPool, opt);
    }

    private InternalWorkingMemory createJournalledSession(final RuleBase ruleBase,
                                                          final Environment environment,
                                                          final SessionConfiguration sessionConfig,
                                                          final boolean fromPool,
                                                          final DurableSessionOption opt) {
        JournalStorage storage = opt.getJournalStorage();
        ObjectStorageStrategy strategy = buildStrategy(opt);
        InternalKnowledgeBase kbase = (InternalKnowledgeBase) ruleBase;

        if (!fromPool && kbase.getSessionPool() != null) {
            return (InternalWorkingMemory) kbase.getSessionPool().newKieSession(sessionConfig);
        } 
        JournalledKieSession session = new JournalledKieSession(
                kbase.nextWorkingMemoryCounter(), kbase, true, sessionConfig, environment, storage);

        if (sessionConfig.isKeepReference()) {
            kbase.addStatefulSession(session);
        }

        // the session is restored at startup
        if (!storage.isEmpty()) {
            restore(session, storage, opt.getModifyLambdaRegistry(), strategy);
        }

        // the listener generates the events that are stored in the journal
        // so it has to be added AFTER the session is restored
        JournallingRuntimeEventListener listener =
                new JournallingRuntimeEventListener(storage, strategy);
        session.addEventListener((org.kie.api.event.rule.RuleRuntimeEventListener) listener);
        session.addEventListener((org.kie.api.event.rule.AgendaEventListener) listener);
        
        // Hook for DRL precompiler: enables delta-based modify capture via journal.stageModify()
        if (hasJournalGlobal(kbase)) {
            session.setGlobal("journal", listener);
        }

        //setup of compaction coordinator, the component in charge of compacting the journal
        Duration interval = opt.getCompactionInterval();
        CompactionCoordinator coordinator = new CompactionCoordinator(storage, interval);
        if (!interval.isZero()) {
            coordinator.start();
        }
        session.setCompactionCoordinator(coordinator);

        return session;
    }

    // checks if the global "journal" is declared in a DRL
    // if declared, the DRL has been rewritten by the precompiler and we need to hook the listener
    private static boolean hasJournalGlobal(final KieBase kbase) {
        for (var pkg : kbase.getKiePackages()) {
            for (var global : pkg.getGlobalVariables()) {
                if ("journal".equals(global.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ObjectStorageStrategy buildStrategy(final DurableSessionOption opt) {
        if (opt.getObjectStorageMode() == ObjectStorageMode.EXTERNAL_REF) {
            return new ExternalRefStrategy(opt.getExternalRefKeySupplier(), opt.getExternalRefLoader());
        }
        return new EmbedStrategy();
    }

    private static void restore(final JournalledKieSession session,
                                final JournalStorage storage,
                                final ModifyLambdaRegistry registry,
                                final ObjectStorageStrategy strategy) {
        RestoreEngine.ScanResult scanResult = extractRecordsFromJournal(storage, registry, strategy);
        Map<Long, FactHandle> oldToNew = insertNonLogicalFacts(session, scanResult);
        ReplayFilter replayFilter = buildReplayFilter(scanResult, oldToNew);
        regenerateMatchObjects(session, replayFilter);
        if (!scanResult.pendingTmsLinks().isEmpty()) {
            TruthMaintenanceSystem tms = getTms(session);
            wireTms(tms, scanResult, oldToNew, replayFilter);
        }
        session.setReplayFilter(replayFilter);
    }

    // we extract the existing records from the journal
	private static ScanResult extractRecordsFromJournal(final JournalStorage storage,
			final ModifyLambdaRegistry registry, final ObjectStorageStrategy strategy) {
		return new RestoreEngine(storage, registry, strategy).scan();
	}

	// we insert non-logical facts 
	// we also create a map from the ids saved in the journal to the fact handles in memory after restoring
    private static Map<Long, FactHandle> insertNonLogicalFacts(final JournalledKieSession session,
                                                               final RestoreEngine.ScanResult scanResult) {
        Set<Long> logicalIds = new HashSet<>();
        for (RestoreEngine.PendingTmsLink link : scanResult.pendingTmsLinks()) {
            logicalIds.add(link.factHandleId());
        }
        Map<Long, FactHandle> oldToNew = new HashMap<>();
        for (Map.Entry<Long, Object> entry : scanResult.survivingFacts().entrySet()) {
            if (!logicalIds.contains(entry.getKey())) {
                oldToNew.put(entry.getKey(), session.insert(entry.getValue()));
            }
        }
        return oldToNew;
    }

    // the replay filter is required to prevent refiring of existing rules
    // the filter contains the rules to be ignored
    private static ReplayFilter buildReplayFilter(final RestoreEngine.ScanResult scanResult,
                                                  final Map<Long, FactHandle> oldToNew) {
        List<RuleMatchRecord> translatedMatches = new ArrayList<>(scanResult.firedMatches().size());
        for (RuleMatchRecord record : scanResult.firedMatches()) {
            long[] newIds = new long[record.factHandleIds().length];
            for (int i = 0; i < record.factHandleIds().length; i++) {
                FactHandle handle = oldToNew.get(record.factHandleIds()[i]);
                newIds[i] = handle != null ? handle.getId() : record.factHandleIds()[i];
            }
            translatedMatches.add(new RuleMatchRecord(record.id(), record.packageName(), record.ruleName(), newIds));
        }
        return new ReplayFilter(translatedMatches);
    }

    // this is required to regenerate the match objects for tms 
	private static void regenerateMatchObjects(final JournalledKieSession session, ReplayFilter replayFilter) {
		session.fireAllRules(replayFilter);
	}

	//last steo we restore the tms links in the truth management system. 
    private static void wireTms(final TruthMaintenanceSystem tms,
                                final RestoreEngine.ScanResult scanResult,
                                final Map<Long, FactHandle> oldToNew,
                                final ReplayFilter replayFilter) {

        for (RestoreEngine.PendingTmsLink link : scanResult.pendingTmsLinks()) {
            RuleMatchRecord justifier = scanResult.firedMatchesById().get(link.justifyingRuleMatchId());
            long[] newIds = new long[justifier.factHandleIds().length];
            for (int i = 0; i < justifier.factHandleIds().length; i++) {
                FactHandle h = oldToNew.get(justifier.factHandleIds()[i]);
                newIds[i] = h != null ? h.getId() : justifier.factHandleIds()[i];
            }
            Match cachedMatch = replayFilter.getCachedMatch(
                    justifier.packageName(), justifier.ruleName(), newIds);
            Object logicalObject = scanResult.survivingFacts().get(link.factHandleId());
            tms.insertPositive(logicalObject, (InternalMatch) cachedMatch);
        }
    }

	private static TruthMaintenanceSystem getTms(final JournalledKieSession session) {
		InternalWorkingMemoryEntryPoint defaultEP =
                (InternalWorkingMemoryEntryPoint) session.getDefaultEntryPoint();
        TruthMaintenanceSystem tms =
                TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem(defaultEP);
		return tms;
	}

    @Override
    public int servicePriority() {
        return 1;
    }
}

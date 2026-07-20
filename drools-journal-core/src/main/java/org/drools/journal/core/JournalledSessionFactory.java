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

import org.drools.journal.api.ModifyLambdaRegistry;

import org.drools.core.common.InternalWorkingMemoryEntryPoint;
import org.drools.core.common.TruthMaintenanceSystem;
import org.drools.core.common.TruthMaintenanceSystemFactory;
import org.drools.core.rule.consequence.InternalMatch;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RuleMatchRecord;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
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

public final class JournalledSessionFactory {

    static final String JOURNAL_KEY = "drools.journal.storage";

    private JournalledSessionFactory() {}

    public static JournalledKieSession open(final KieBase kbase, final JournalStorage storage) {
        return open(kbase, storage, new ModifyLambdaRegistry(), CompactionCoordinator.DEFAULT_INTERVAL);
    }

    public static JournalledKieSession open(final KieBase kbase,
                                            final JournalStorage storage,
                                            final Duration compactionInterval) {
        return open(kbase, storage, new ModifyLambdaRegistry(), compactionInterval);
    }

    public static JournalledKieSession open(final KieBase kbase,
                                            final JournalStorage storage,
                                            final ModifyLambdaRegistry registry,
                                            final Duration compactionInterval) {
        Environment env = KieServices.get().newEnvironment();
        env.set(JOURNAL_KEY, storage);
        JournalledKieSession session = (JournalledKieSession) kbase.newKieSession(null, env);
        if (!storage.isEmpty()) {
            restore(session, storage, registry);
        }
        JournallingRuntimeEventListener listener = new JournallingRuntimeEventListener(storage, new EmbedStrategy());
        session.addEventListener((org.kie.api.event.rule.RuleRuntimeEventListener) listener);
        session.addEventListener((org.kie.api.event.rule.AgendaEventListener) listener);
        if (hasJournalGlobal(kbase)) {
            session.setGlobal("journal", listener);
        }
        CompactionCoordinator coordinator = new CompactionCoordinator(storage, compactionInterval);
        coordinator.start();
        session.setCompactionCoordinator(coordinator);
        return session;
    }

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

    private static void restore(final JournalledKieSession session,
                                final JournalStorage storage,
                                final ModifyLambdaRegistry registry) {
        RestoreEngine.ScanResult scanResult = new RestoreEngine(storage, registry).scan();
        Map<Long, FactHandle> oldToNew = insertNonLogicalFacts(session, scanResult);
        ReplayFilter replayFilter = buildReplayFilter(scanResult, oldToNew);
        session.fireAllRules(replayFilter);
        wireTms(session, scanResult, oldToNew, replayFilter);
        session.setReplayFilter(replayFilter);
    }

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

    private static void wireTms(final JournalledKieSession session,
                                final RestoreEngine.ScanResult scanResult,
                                final Map<Long, FactHandle> oldToNew,
                                final ReplayFilter replayFilter) {
        if (scanResult.pendingTmsLinks().isEmpty()) {
            return;
        }
        InternalWorkingMemoryEntryPoint defaultEP =
                (InternalWorkingMemoryEntryPoint) session.getDefaultEntryPoint();
        TruthMaintenanceSystem tms =
                TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem(defaultEP);

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
}

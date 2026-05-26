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

import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.StorageDecision;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.rule.FactHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JournalledSessionFactory {

    static final String JOURNAL_KEY = "drools.journal.storage";

    private JournalledSessionFactory() {}

    public static JournalledKieSession open(final KieBase kbase, final JournalStorage storage) {
        final Environment env = KieServices.get().newEnvironment();
        env.set(JOURNAL_KEY, storage);
        final JournalledKieSession session = (JournalledKieSession) kbase.newKieSession(null, env);
        if (storage.latestPosition() >= 0) {
            final RestoreEngine.ScanResult scanResult = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

            final Map<Long, FactHandle> oldToNew = new HashMap<>();
            for (final Map.Entry<Long, Object> entry : scanResult.survivingFacts().entrySet()) {
                oldToNew.put(entry.getKey(), session.insert(entry.getValue()));
            }

            final List<RuleMatchRecord> translatedMatches = new ArrayList<>(scanResult.firedMatches().size());
            for (final RuleMatchRecord record : scanResult.firedMatches()) {
                final long[] newIds = new long[record.factHandleIds().length];
                for (int i = 0; i < record.factHandleIds().length; i++) {
                    final FactHandle handle = oldToNew.get(record.factHandleIds()[i]);
                    newIds[i] = handle != null ? handle.getId() : record.factHandleIds()[i];
                }
                translatedMatches.add(new RuleMatchRecord(record.id(), record.packageName(), record.ruleName(), newIds));
            }
            session.setReplayFilter(new ReplayFilter(translatedMatches));
        }
        session.addEventListener(new JournallingRuntimeEventListener(storage, (fact, handle) -> StorageDecision.EMBED));
        return session;
    }
}

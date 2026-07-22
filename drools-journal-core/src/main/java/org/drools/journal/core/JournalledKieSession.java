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

import org.drools.core.SessionConfiguration;
import org.drools.journal.api.JournalStorage;
import org.drools.kiesession.rulebase.InternalKnowledgeBase;
import org.drools.kiesession.session.StatefulKnowledgeSessionImpl;
import org.kie.api.runtime.Environment;

// NOT thread-safe — Drools sessions are single-threaded
public class JournalledKieSession extends StatefulKnowledgeSessionImpl {

    private final JournalStorage journal;
    private ReplayFilter replayFilter;
    private CompactionCoordinator compactionCoordinator;

    public JournalledKieSession(final long id,
                                final InternalKnowledgeBase kBase,
                                final boolean initInitFactHandle,
                                final SessionConfiguration config,
                                final Environment environment,
                                final JournalStorage storage) {
        super(id, kBase, initInitFactHandle, config, environment);
        this.journal = storage;
    }

    public JournalStorage getJournalStorage() {
        return journal;
    }

    void setReplayFilter(final ReplayFilter filter) {
        this.replayFilter = filter;
    }

    void setCompactionCoordinator(final CompactionCoordinator coordinator) {
        this.compactionCoordinator = coordinator;
    }

    public void compactNow() {
        if (compactionCoordinator != null) {
            compactionCoordinator.runCycle();
        }
    }

    @Override
    public int fireAllRules() {
        int fired = (replayFilter != null)
                ? super.fireAllRules(replayFilter)
                : super.fireAllRules();
        journal.safepoint();
        return fired;
    }

    @Override
    public void dispose() {
        if (compactionCoordinator != null) {
            compactionCoordinator.stop();
        }
        // JournalStorage is owned by the caller — closing it is their responsibility.
        super.dispose();
    }
}

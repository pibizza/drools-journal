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

import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.impl.InternalRuleBase;
import org.drools.core.rule.accessor.FactHandleFactory;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.kiesession.agenda.DefaultAgenda;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.AgendaEventListener;
import org.kie.api.event.rule.AgendaGroupPoppedEvent;
import org.kie.api.event.rule.AgendaGroupPushedEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.MatchCancelledEvent;
import org.kie.api.event.rule.MatchCreatedEvent;
import org.kie.api.event.rule.RuleFlowGroupActivatedEvent;
import org.kie.api.event.rule.RuleFlowGroupDeactivatedEvent;
import org.kie.api.runtime.rule.FactHandle;

import java.util.List;

// NOT thread-safe — Drools sessions fire on a single thread
public class JournalledAgenda extends DefaultAgenda {

    private final JournalStorage journal;
    private long nextActivationId = 0L;
    private final AgendaEventListener listener;
    // Resolved lazily: entryPointsManager is not yet set when the agenda is constructed
    private JournalledObjectStore store;

    public JournalledAgenda(final InternalRuleBase kieBase,
                            final InternalWorkingMemory workingMemory,
                            final FactHandleFactory factHandleFactory,
                            final JournalStorage journal) {
        super(kieBase, workingMemory, factHandleFactory);
        this.journal = journal;
        this.listener = new JournallingListener();
        workingMemory.getAgendaEventSupport().addEventListener(this.listener);
    }

    private JournalledObjectStore store() {
        if (store == null) {
            final Object objectStore = workingMemory.getDefaultEntryPoint().getObjectStore();
            store = objectStore instanceof JournalledObjectStore ? (JournalledObjectStore) objectStore : null;
        }
        return store;
    }

    @Override
    public boolean dispose(final InternalWorkingMemory wm) {
        wm.getAgendaEventSupport().removeEventListener(this.listener);
        return super.dispose(wm);
    }

    private class JournallingListener implements AgendaEventListener {

        @Override
        public void beforeMatchFired(final BeforeMatchFiredEvent event) {
            // If a consequence exception propagates uncaught, afterMatchFired never fires and currentActivationId
            // is left stale — acceptable because an uncaught consequence exception leaves the session unusable.
            final JournalledObjectStore s = store();
            if (s != null) {
                s.setCurrentActivationId(++nextActivationId);
            }
        }

        @Override
        public void afterMatchFired(final AfterMatchFiredEvent event) {
            final List<? extends FactHandle> handles = event.getMatch().getFactHandles();
            final long[] ids = handles.stream()
                    .mapToLong(h -> ((InternalFactHandle) h).getId())
                    .toArray();
            try {
                journal.append(new RuleMatchRecord(nextActivationId, event.getMatch().getRule().getPackageName(), event.getMatch().getRule().getName(), ids));
            } finally {
                final JournalledObjectStore s = store();
                if (s != null) {
                    s.clearCurrentActivationId();
                }
            }
        }

        @Override public void matchCreated(final MatchCreatedEvent event) {}
        @Override public void matchCancelled(final MatchCancelledEvent event) {}
        @Override public void agendaGroupPopped(final AgendaGroupPoppedEvent event) {}
        @Override public void agendaGroupPushed(final AgendaGroupPushedEvent event) {}
        @Override public void beforeRuleFlowGroupActivated(final RuleFlowGroupActivatedEvent event) {}
        @Override public void afterRuleFlowGroupActivated(final RuleFlowGroupActivatedEvent event) {}
        @Override public void beforeRuleFlowGroupDeactivated(final RuleFlowGroupDeactivatedEvent event) {}
        @Override public void afterRuleFlowGroupDeactivated(final RuleFlowGroupDeactivatedEvent event) {}
    }
}

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

import java.util.List;

import org.drools.core.common.InternalFactHandle;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ObjectStorageStrategy;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.AgendaEventListener;
import org.kie.api.event.rule.AgendaGroupPoppedEvent;
import org.kie.api.event.rule.AgendaGroupPushedEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.MatchCancelledEvent;
import org.kie.api.event.rule.MatchCreatedEvent;
import org.kie.api.event.rule.ObjectDeletedEvent;
import org.kie.api.event.rule.ObjectInsertedEvent;
import org.kie.api.event.rule.ObjectUpdatedEvent;
import org.kie.api.event.rule.RuleFlowGroupActivatedEvent;
import org.kie.api.event.rule.RuleFlowGroupDeactivatedEvent;
import org.kie.api.event.rule.RuleRuntimeEventListener;
import org.kie.api.runtime.rule.FactHandle;

class JournallingRuntimeEventListener implements RuleRuntimeEventListener, AgendaEventListener {

    private final JournalStorage journal;
    private final ObjectStorageStrategy strategy;
    private long currentActivationId = -1L;
    private long nextActivationId = 0L;

    JournallingRuntimeEventListener(final JournalStorage journal, final ObjectStorageStrategy strategy) {
        this.journal = journal;
        this.strategy = strategy;
    }

    // --- RuleRuntimeEventListener ---

    @Override
    public void objectInserted(final ObjectInsertedEvent event) {
        InternalFactHandle handle = (InternalFactHandle) event.getFactHandle();
        boolean logical = currentActivationId >= 0;
        journal.insert(handle.getId(), strategy.store(event.getObject(), handle),
                logical, currentActivationId);
    }

    @Override
    public void objectUpdated(final ObjectUpdatedEvent event) {
        InternalFactHandle handle = (InternalFactHandle) event.getFactHandle();
        journal.insert(handle.getId(), strategy.store(event.getObject(), handle), false, -1L);
    }

    @Override
    public void objectDeleted(final ObjectDeletedEvent event) {
        InternalFactHandle handle = (InternalFactHandle) event.getFactHandle();
        journal.retract(handle.getId());
    }

    // --- AgendaEventListener ---

    @Override
    public void beforeMatchFired(final BeforeMatchFiredEvent event) {
        // If a consequence exception propagates uncaught, afterMatchFired never fires and currentActivationId
        // is left stale — acceptable because an uncaught consequence exception leaves the session unusable.
        currentActivationId = ++nextActivationId;
    }

    @Override
    public void afterMatchFired(final AfterMatchFiredEvent event) {
        List<? extends FactHandle> handles = event.getMatch().getFactHandles();
        long[] ids = handles.stream()
                .mapToLong(h -> ((InternalFactHandle) h).getId())
                .toArray();
        try {
            journal.ruleMatch(currentActivationId,
                    event.getMatch().getRule().getPackageName(),
                    event.getMatch().getRule().getName(), ids);
        } finally {
            currentActivationId = -1L;
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

    // Package-private — used by tests to simulate an active activation context
    void setCurrentActivationId(final long id) {
        this.currentActivationId = id;
    }

    void clearCurrentActivationId() {
        this.currentActivationId = -1L;
    }
}

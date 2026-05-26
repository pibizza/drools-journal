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
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.RetractRecord;
import org.kie.api.event.rule.ObjectDeletedEvent;
import org.kie.api.event.rule.ObjectInsertedEvent;
import org.kie.api.event.rule.ObjectUpdatedEvent;
import org.kie.api.event.rule.RuleRuntimeEventListener;

class JournallingRuntimeEventListener implements RuleRuntimeEventListener {

    private final JournalStorage journal;
    private final ObjectStorageStrategy strategy;
    private long currentActivationId = -1L;

    JournallingRuntimeEventListener(final JournalStorage journal, final ObjectStorageStrategy strategy) {
        this.journal = journal;
        this.strategy = strategy;
    }

    void setCurrentActivationId(final long id) {
        this.currentActivationId = id;
    }

    void clearCurrentActivationId() {
        this.currentActivationId = -1L;
    }

    @Override
    public void objectInserted(final ObjectInsertedEvent event) {
        InternalFactHandle handle = (InternalFactHandle) event.getFactHandle();
        boolean logical = currentActivationId >= 0;
        journal.append(new InsertRecord(handle.getId(), logical, currentActivationId,
                JournalPayloadBuilder.build(event.getObject(), handle, strategy)));
    }

    @Override
    public void objectUpdated(final ObjectUpdatedEvent event) {
        InternalFactHandle handle = (InternalFactHandle) event.getFactHandle();
        journal.append(new InsertRecord(handle.getId(), false, -1L,
                JournalPayloadBuilder.build(event.getObject(), handle, strategy)));
    }

    @Override
    public void objectDeleted(final ObjectDeletedEvent event) {
        InternalFactHandle handle = (InternalFactHandle) event.getFactHandle();
        journal.append(new RetractRecord(handle.getId()));
    }
}

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

import org.drools.core.common.IdentityObjectStore;
import org.drools.core.common.InternalFactHandle;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.RetractRecord;

public class JournalledObjectStore extends IdentityObjectStore {

    private final JournalStorage journal;
    private final ObjectStorageStrategy strategy;
    private long currentActivationId = -1L;

    public JournalledObjectStore(final JournalStorage journal, final ObjectStorageStrategy strategy) {
        this.journal = journal;
        this.strategy = strategy;
    }

    public void setCurrentActivationId(final long id) {
        this.currentActivationId = id;
    }

    public void clearCurrentActivationId() {
        this.currentActivationId = -1L;
    }

    @Override
    public void addHandle(final InternalFactHandle handle, final Object object) {
        super.addHandle(handle, object);
        final boolean logical = currentActivationId >= 0;
        journal.append(new InsertRecord(handle.getId(), logical, currentActivationId, JournalPayloadBuilder.build(object, handle, strategy)));
    }

    @Override
    public void removeHandle(final InternalFactHandle handle) {
        journal.append(new RetractRecord(handle.getId()));
        super.removeHandle(handle);
    }

    @Override
    public void updateHandle(final InternalFactHandle handle, final Object object) {
        // Bypass journalled remove/add — an update is not a retract, and the InsertRecord snapshot
        // is written by JournalledNamedEntryPoint.beforeUpdate() which always sets logical=false.
        // Using addHandle() here would incorrectly mark the snapshot as logical when fired from a rule.
        super.removeHandle(handle);
        handle.setObject(object);
        super.addHandle(handle, object);
    }
}

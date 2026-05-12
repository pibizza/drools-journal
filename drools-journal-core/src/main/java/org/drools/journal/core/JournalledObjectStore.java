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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

import org.drools.core.common.IdentityObjectStore;
import org.drools.core.common.InternalFactHandle;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.ExternalRef;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ObjectStorageStrategy;
import org.drools.journal.api.Payload;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.StorageDecision;

public class JournalledObjectStore extends IdentityObjectStore {

    private final JournalStorage journal;
    private final ObjectStorageStrategy strategy;

    public JournalledObjectStore(final JournalStorage journal, final ObjectStorageStrategy strategy) {
        this.journal = journal;
        this.strategy = strategy;
    }

    @Override
    public void addHandle(final InternalFactHandle handle, final Object object) {
        super.addHandle(handle, object);
        journal.append(new InsertRecord(handle.getId(), false, -1L, buildPayload(object, handle)));
    }

    private Payload buildPayload(final Object object, final InternalFactHandle handle) {
        if (strategy.decide(object, handle) == StorageDecision.EXTERNAL_REF) {
            return new ExternalRef(object.getClass().getName(), String.valueOf(handle.getId()));
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(object);
            return new EmbeddedPayload(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize fact: " + object.getClass().getName(), e);
        }
    }

    @Override
    public void removeHandle(final InternalFactHandle handle) {
        journal.append(new RetractRecord(handle.getId()));
        super.removeHandle(handle);
    }
}

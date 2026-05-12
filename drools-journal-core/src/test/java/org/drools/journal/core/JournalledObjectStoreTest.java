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

import org.drools.core.common.DefaultFactHandle;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.ExternalRef;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.StorageDecision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalledObjectStoreTest {

    @Test
    void addHandle_appendsInsertRecord() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        final JournalledObjectStore store = new JournalledObjectStore(
                storage, (fact, handle) -> StorageDecision.EMBED);

        final DefaultFactHandle handle = new DefaultFactHandle(42L, "hello");

        store.addHandle(handle, "hello");

        assertThat(storage.size()).isEqualTo(1);
        final JournalRecord record = storage.scan(0).next();
        assertThat(record).isInstanceOf(InsertRecord.class);
        final InsertRecord insert = (InsertRecord) record;
        assertThat(insert.factHandleId()).isEqualTo(42L);
        assertThat(insert.logical()).isFalse();
        assertThat(insert.justifyingRuleMatchId()).isEqualTo(-1L);
        assertThat(insert.payload()).isInstanceOf(EmbeddedPayload.class);
    }

    @Test
    void removeHandle_appendsRetractRecord() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        final JournalledObjectStore store = new JournalledObjectStore(
                storage, (fact, handle) -> StorageDecision.EMBED);

        final DefaultFactHandle handle = new DefaultFactHandle(42L, "hello");

        store.addHandle(handle, "hello");
        store.removeHandle(handle);

        assertThat(storage.size()).isEqualTo(2);
        final JournalRecord record = storage.scan(1).next();
        assertThat(record).isInstanceOf(RetractRecord.class);
        assertThat(((RetractRecord) record).factHandleId()).isEqualTo(42L);
    }

    @Test
    void addHandle_withActiveActivation_appendsLogicalInsertRecord() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        final JournalledObjectStore store = new JournalledObjectStore(
                storage, (fact, handle) -> StorageDecision.EMBED);

        store.setCurrentActivationId(99L);
        final DefaultFactHandle handle = new DefaultFactHandle(42L, "hello");
        store.addHandle(handle, "hello");

        final InsertRecord insert = (InsertRecord) storage.scan(0).next();
        assertThat(insert.logical()).isTrue();
        assertThat(insert.justifyingRuleMatchId()).isEqualTo(99L);
    }

    @Test
    void addHandle_externalRef_appendsInsertRecordWithExternalRef() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        final JournalledObjectStore store = new JournalledObjectStore(
                storage, (fact, handle) -> StorageDecision.EXTERNAL_REF);

        final DefaultFactHandle handle = new DefaultFactHandle(42L, "hello");

        store.addHandle(handle, "hello");

        final InsertRecord insert = (InsertRecord) storage.scan(0).next();
        assertThat(insert.payload()).isInstanceOf(ExternalRef.class);
        final ExternalRef ref = (ExternalRef) insert.payload();
        assertThat(ref.typeName()).isEqualTo(String.class.getName());
    }
}

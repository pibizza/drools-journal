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
import org.drools.journal.api.StorageDecision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalledNamedEntryPointTest {

    @Test
    void beforeUpdate_appendsInsertRecordWithNewObjectPayload() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        final JournalledNamedEntryPoint ep = new JournalledNamedEntryPoint(
                storage, (fact, handle) -> StorageDecision.EMBED);

        final DefaultFactHandle handle = new DefaultFactHandle(7L, "newValue");

        ep.beforeUpdate(handle, "newValue", null, "oldValue", null);

        assertThat(storage.size()).isEqualTo(1);
        final JournalRecord record = storage.scan(0).next();
        assertThat(record).isInstanceOf(InsertRecord.class);
        final InsertRecord insert = (InsertRecord) record;
        assertThat(insert.factHandleId()).isEqualTo(7L);
        assertThat(insert.logical()).isFalse();
        assertThat(insert.payload()).isInstanceOf(EmbeddedPayload.class);
    }

    @Test
    void beforeUpdate_externalRef_appendsInsertRecordWithExternalRef() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        final JournalledNamedEntryPoint ep = new JournalledNamedEntryPoint(
                storage, (fact, handle) -> StorageDecision.EXTERNAL_REF);

        final DefaultFactHandle handle = new DefaultFactHandle(7L, "newValue");

        ep.beforeUpdate(handle, "newValue", null, "oldValue", null);

        final InsertRecord insert = (InsertRecord) storage.scan(0).next();
        assertThat(insert.payload()).isInstanceOf(ExternalRef.class);
        final ExternalRef ref = (ExternalRef) insert.payload();
        assertThat(ref.typeName()).isEqualTo(String.class.getName());
    }

}

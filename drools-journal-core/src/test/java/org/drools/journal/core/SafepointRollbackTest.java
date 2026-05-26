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

import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafepointRollbackTest {

    @Test
    void safepoint_flushesPendingRecords_factSurvivesRestore() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("hello")));
        storage.append(new SafepointRecord(0L, 1000L));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsKey(1L);
    }

    @Test
    void recordsAfterLastSafepoint_areDiscardedOnRestore() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("committed")));
        storage.append(new SafepointRecord(0L, 1000L));
        // These records come after the last safepoint — simulate crash mid-write
        storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("lost")));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsKey(1L);
        assertThat(result.survivingFacts()).doesNotContainKey(2L);
    }

    @Test
    void multipleSafepoints_sequenceNoIncrements_allFactsSurvive() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("a")));
        storage.append(new SafepointRecord(0L, 1000L));
        storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("b")));
        storage.append(new SafepointRecord(1L, 2000L));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsKeys(1L, 2L);
    }
}

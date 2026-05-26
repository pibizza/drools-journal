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
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RestoreEngineTest {

    @Test
    void emptyJournal_producesEmptySurvivingFacts() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).isEmpty();
    }

    @Test
    void insertBeforeSafepoint_factSurvives() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("hello")));
        storage.append(new SafepointRecord(1L, 0L));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "hello");
    }

    @Test
    void updateInsertBeforeSafepoint_replacesFactInSurvivingFacts() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("hello")));
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("world")));
        storage.append(new SafepointRecord(1L, 0L));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "world");
    }

    @Test
    void retractBeforeSafepoint_factRemovedFromSurvivingFacts() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("hello")));
        storage.append(new RetractRecord(1L));
        storage.append(new SafepointRecord(1L, 0L));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).isEmpty();
    }

    @Test
    void insertWithoutSafepoint_factIsDiscarded() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("hello")));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).isEmpty();
    }

}

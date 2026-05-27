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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        storage.insert(1L, "hello");
        storage.safepoint(1L);

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "hello");
    }

    @Test
    void updateInsertBeforeSafepoint_replacesFactInSurvivingFacts() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "hello");
        storage.insert(1L, "world");
        storage.safepoint(1L);

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "world");
    }

    @Test
    void retractBeforeSafepoint_factRemovedFromSurvivingFacts() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "hello");
        storage.retract(1L);
        storage.safepoint(1L);

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).isEmpty();
    }

    @Test
    void insertWithoutSafepoint_factIsDiscarded() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "hello");

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).isEmpty();
    }

    @Test
    void prepareWithoutCommit_originalPageRemainsCanonical() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "fact");
        storage.safepoint(0L);
        storage.compactionPrepare("m-1", "0");

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "fact");
    }

    @Test
    void prepareWithInsertButNoCommit_originalPageRemainsCanonical() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "fact");
        storage.safepoint(0L);
        storage.compactionPrepare("m-1", "0");
        storage.insert(1L, "fact");

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "fact");
    }

    @Test
    void modifyWithUnknownLambdaRef_throwsJournalSchemaEvolutionException() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "hello");
        storage.modify(1L, "Rule_Unknown_modify_0", new byte[0]);
        storage.safepoint(1L);

        assertThatThrownBy(() -> new RestoreEngine(storage, new ModifyLambdaRegistry()).scan())
                .isInstanceOf(JournalSchemaEvolutionException.class);
    }

}

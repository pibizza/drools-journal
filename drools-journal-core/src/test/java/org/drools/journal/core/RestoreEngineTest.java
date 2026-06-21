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
import org.junit.jupiter.api.Test;

import java.util.List;

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
        storage.compactionPrepare("m-1", new String[]{"0"});
        storage.writeMergedPage("m-1", List.of(embed(1L, "fact")));
        // no COMMIT → pendingCommits never sealed → pageIndex stays [P0]

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "fact");
    }

    @Test
    void prepareCommitAndSafepoint_mergedPageReplacesSources() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "fact");
        storage.safepoint(0L);
        storage.compactionPrepare("m-1", new String[]{"0"});
        storage.writeMergedPage("m-1", List.of(embed(1L, "fact")));
        storage.compactionCommit("m-1", new String[]{"0"});
        storage.safepoint(1L);   // seals commit → pageIndex = [Pm, P1]

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "fact");
    }

    @Test
    void prepareAndWriteButNoCommit_originalPageRemainsCanonical() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "fact");
        storage.safepoint(0L);
        storage.compactionPrepare("m-1", new String[]{"0"});
        storage.writeMergedPage("m-1", List.of(embed(1L, "merged")));
        // no COMMIT → pageIndex stays [P0]

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "fact");
    }

    @Test
    void commitWithoutSafepoint_originalPageRemainsCanonical() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "fact");
        storage.safepoint(0L);
        storage.compactionPrepare("m-1", new String[]{"0"});
        storage.writeMergedPage("m-1", List.of(embed(1L, "merged")));
        storage.compactionCommit("m-1", new String[]{"0"});
        // no sealing safepoint → pendingCommits not sealed → pageIndex stays [P0]

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsEntry(1L, "fact");
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static InsertRecord embed(final long id, final Object value) {
        return new InsertRecord(id, false, -1L, new EmbedStrategy().store(value, null));
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

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

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionCorrectnessTest {

    @Test
    void compact_phase1_writesPrepareRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);
        storage.retract(1L);
        storage.safepoint(1);

        CompactionCoordinator.compact(storage, Set.of("0", "1"));

        List<JournalRecord> records = drainAll(storage);
        long prepareCount = records.stream()
                .filter(r -> r instanceof CompactionPrepareRecord)
                .count();
        assertThat(prepareCount).isEqualTo(1);
    }

    @Test
    void compact_phase1_prepareRecord_referencesSourcePages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);
        storage.retract(1L);
        storage.safepoint(1);

        CompactionCoordinator.compact(storage, Set.of("0", "1"));

        List<JournalRecord> records = drainAll(storage);
        CompactionPrepareRecord prepare = records.stream()
                .filter(r -> r instanceof CompactionPrepareRecord)
                .map(r -> (CompactionPrepareRecord) r)
                .findFirst()
                .orElseThrow();
        assertThat(Arrays.asList(prepare.replacedPageIds())).containsExactlyInAnyOrder("0", "1");
    }

    @Test
    void compact_phase2_mergedPage_containsOnlyLiveFacts() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": 4 inserts, 3 later retracted → liveCount=1/totalCount=4 → 25%, sparse
        storage.insert(1L, "live");
        storage.insert(2L, "dead"); storage.insert(3L, "dead"); storage.insert(4L, "dead");
        storage.safepoint(0);
        // Page "1": 3 retracts → liveCount=0/totalCount=3 → 0%, sparse
        storage.retract(2L); storage.retract(3L); storage.retract(4L);
        storage.safepoint(1);

        CompactionCoordinator.compact(storage, Set.of("0", "1"));

        // Pm is a separate page in the raw journal (not inline between PREPARE and COMMIT).
        // Fact 1 (live) appears twice: once in P0, once in Pm.
        // Facts 2-4 (retracted) appear once each in P0 only — not in Pm.
        List<JournalRecord> records = drainAll(storage);
        long insertCount1 = records.stream()
                .filter(r -> r instanceof InsertRecord ir && ir.factHandleId() == 1L)
                .count();
        long insertCount2 = records.stream()
                .filter(r -> r instanceof InsertRecord ir && ir.factHandleId() == 2L)
                .count();
        assertThat(insertCount1).isEqualTo(2);
        assertThat(insertCount2).isEqualTo(1);
    }

    @Test
    void compact_afterSealingSafepoint_restoreShowsOnlyLiveFacts() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": 4 inserts, 3 later retracted → 25% live, sparse
        storage.insert(1L, "live");
        storage.insert(2L, "dead"); storage.insert(3L, "dead"); storage.insert(4L, "dead");
        storage.safepoint(0);
        // Page "1": retract those 3 → 0% live, sparse
        storage.retract(2L); storage.retract(3L); storage.retract(4L);
        storage.safepoint(1);

        CompactionCoordinator.compact(storage, Set.of("0", "1"));
        storage.safepoint(2); // seals the COMMIT — simulates next fireAllRules()

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();
        assertThat(result.survivingFacts()).hasSize(1);
        assertThat(result.survivingFacts()).containsKey(1L);
    }

    @Test
    void crashAfterPrepare_restoreUsesOriginalPages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": 4 inserts, 3 later retracted → 25% live, sparse
        storage.insert(1L, "a");
        storage.insert(2L, "b"); storage.insert(3L, "c"); storage.insert(4L, "d");
        storage.safepoint(0);
        // Page "1": retract those 3 → 0% live, sparse
        storage.retract(2L); storage.retract(3L); storage.retract(4L);
        storage.safepoint(1);
        // Simulate crash after PREPARE — no COMMIT written
        storage.compactionPrepare("m-crash", "0", "1");

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        // Original pages are canonical — fact 1 survives
        assertThat(result.survivingFacts()).hasSize(1);
        assertThat(result.survivingFacts()).containsKey(1L);
    }

    @Test
    void crashAfterCommit_beforeSafepoint_restoreUsesOriginalPages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": 4 inserts, 3 later retracted → 25% live, sparse
        storage.insert(1L, "a");
        storage.insert(2L, "b"); storage.insert(3L, "c"); storage.insert(4L, "d");
        storage.safepoint(0);
        // Page "1": retract those 3 → 0% live, sparse
        storage.retract(2L); storage.retract(3L); storage.retract(4L);
        storage.safepoint(1);
        // compact() runs but crashes before the sealing safepoint
        CompactionCoordinator.compact(storage, Set.of("0", "1"));
        // No safepoint — COMMIT is unsealed, original pages remain canonical

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        // COMMIT not sealed → original pages still canonical — fact 1 survives
        assertThat(result.survivingFacts()).hasSize(1);
        assertThat(result.survivingFacts()).containsKey(1L);
    }

    @Test
    void compact_writesPrepareThenCommit() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);
        storage.retract(1L);
        storage.safepoint(1);

        CompactionCoordinator.compact(storage, Set.of("0", "1"));

        List<JournalRecord> records = drainAll(storage);
        long prepareCount = records.stream().filter(r -> r instanceof CompactionPrepareRecord).count();
        long commitCount = records.stream().filter(r -> r instanceof CompactionCommitRecord).count();
        assertThat(prepareCount).isEqualTo(1);
        assertThat(commitCount).isEqualTo(1);
    }

    @Test
    void twoSequentialCompactions_eachSealedCorrectly() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        // Round 1: fact 1 survives, facts 2-4 die
        storage.insert(1L, "keep");
        storage.insert(2L, "drop"); storage.insert(3L, "drop"); storage.insert(4L, "drop");
        storage.safepoint(0);
        storage.retract(2L); storage.retract(3L); storage.retract(4L);
        storage.safepoint(1);
        CompactionCoordinator.compact(storage, Set.of("0", "1"));
        storage.safepoint(2); // seals round 1

        // Round 2: fact 5 survives, facts 6-8 die
        storage.insert(5L, "keep");
        storage.insert(6L, "drop"); storage.insert(7L, "drop"); storage.insert(8L, "drop");
        storage.safepoint(3);
        storage.retract(6L); storage.retract(7L); storage.retract(8L);
        storage.safepoint(4);
        CompactionCoordinator.compact(storage, Set.of("3", "4"));
        storage.safepoint(5); // seals round 2

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).hasSize(2);
        assertThat(result.survivingFacts()).containsKey(1L);
        assertThat(result.survivingFacts()).containsKey(5L);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static List<JournalRecord> drainAll(final InMemoryJournalStorage storage) {
        List<JournalRecord> records = new ArrayList<>();
        try (JournalScanner scanner = storage.scan(0)) {
            scanner.forEachRemaining(records::add);
        }
        return records;
    }
}

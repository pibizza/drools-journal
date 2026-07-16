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

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionCoordinatorTest {

    @Test
    void safepoint_whenAppended_rollsCurrentPage() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        assertThat(storage.currentPageNumber()).isEqualTo(0);
        storage.insert(1L, "a");
        storage.safepoint(0);
        assertThat(storage.currentPageNumber()).isEqualTo(1);
    }

    @Test
    void scan_emptyJournal_producesEmptyLivenessMap() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness).isEmpty();
    }

    @Test
    void page_withAllInsertsLive_isNotSparse() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(CompactionCoordinator.isSparse(liveness.get("0"))).isFalse();
    }

    @Test
    void page_withAllInsertsRetracted_isSparse() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.retract(1L);
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(CompactionCoordinator.isSparse(liveness.get("0"))).isTrue();
    }

    @Test
    void insertAndRetract_inSamePage_liveCountIsZero() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.retract(1L);
        storage.safepoint(0);        // page "0": [insert(1), retract(1)]

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness.get("0")[0]).isEqualTo(0L); // liveCount
    }

    @Test
    void retract_onLaterPageThanInsert_countedInRetractPageTotal() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);        // page "0": [insert(1)]
        storage.retract(1L);
        storage.safepoint(1);        // page "1": [retract(1)]

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness.get("1")[1]).isEqualTo(1L); // totalCount — retract occupies space
    }

    @Test
    void retract_onLaterPageThanInsert_decrementsLiveCountOnInsertPage() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);        // page "0": [insert(1)]
        storage.retract(1L);
        storage.safepoint(1);        // page "1": [retract(1)]

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness.get("0")[0]).isEqualTo(0L); // insert is no longer live
    }

    @Test
    void modifyRecord_inLivenessTracking_countsInTotalOnly() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.modify(1L, "Rule_MyRule_modify_0", new byte[0]);
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        long[] counts = liveness.get("0");
        assertThat(counts[0]).isEqualTo(1L); // liveCount — modify does not contribute
        assertThat(counts[1]).isEqualTo(2L); // totalCount — modify is counted
    }

    @Test
    void ruleMatchRecord_inLivenessTracking_countsInTotalOnly() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.ruleMatch(1L, "myRule", 1L);
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        long[] counts = liveness.get("0");
        assertThat(counts[0]).isEqualTo(1L); // liveCount — ruleMatch does not contribute
        assertThat(counts[1]).isEqualTo(2L); // totalCount — ruleMatch is counted
    }

    @Test
    void inserts_acrossTwoPages_trackedSeparately() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);        // page "0": [insert(1)]
        storage.insert(2L, "b");
        storage.safepoint(1);        // page "1": [insert(2)]

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness.get("0")[0]).isEqualTo(1L); // liveCount
        assertThat(liveness.get("1")[0]).isEqualTo(1L); // liveCount
    }

    @Test
    void singlePage_singleInsert_isLive() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        long[] counts = liveness.get("0");
        assertThat(counts[0]).isEqualTo(1L); // liveCount
        assertThat(counts[1]).isEqualTo(1L); // totalCount
    }

    @Test
    void scanLiveness_multiplePhysicalPagesPerSafepoint_trackedSeparately() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");  // page "0"
        storage.rollPage();        // size-triggered roll — page "0" closes
        storage.insert(2L, "b");  // page "1"
        storage.safepoint(0);     // page "1" closes with safepoint

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness).hasSize(2);
        assertThat(liveness.get("0")[0]).isEqualTo(1L); // live
        assertThat(liveness.get("0")[1]).isEqualTo(1L); // total
        assertThat(liveness.get("1")[0]).isEqualTo(1L); // live
        assertThat(liveness.get("1")[1]).isEqualTo(1L); // total
    }

    @Test
    void scanLiveness_retractOnRolledPage_decrementsInsertPageLiveCount() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");  // page "0"
        storage.rollPage();        // page "0" closes (size roll)
        storage.retract(1L);       // page "1"
        storage.safepoint(0);     // page "1" closes

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness.get("0")[0]).isEqualTo(0L); // insert was retracted
        assertThat(liveness.get("0")[1]).isEqualTo(1L); // 1 total record
        assertThat(liveness.get("1")[0]).isEqualTo(0L); // retract has no live count
        assertThat(liveness.get("1")[1]).isEqualTo(1L); // 1 total record
    }

    @Test
    void durationZero_start_doesNotCreateCompactorThread() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        CompactionCoordinator coordinator = new CompactionCoordinator(storage, Duration.ZERO);

        coordinator.start();

        boolean compactorThreadAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "drools-journal-compactor".equals(t.getName()));
        assertThat(compactorThreadAlive).isFalse();
    }

    @Test
    void scanLiveness_reinsertSameFactOnLaterPage_decrementsOldPageLiveCount() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "original");
        storage.safepoint(0);              // page "0": [insert(1)]
        storage.insert(1L, "updated");
        storage.safepoint(1);              // page "1": [insert(1)] — supersedes page "0"

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness.get("0")[0]).isEqualTo(0L); // old insert is dead
        assertThat(liveness.get("1")[0]).isEqualTo(1L); // new insert is live
    }

    @Test
    void retirePages_inMemory_removesSourcePagesFromJournal() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);           // page "0"
        storage.insert(2L, "b");
        storage.safepoint(1);           // page "1"

        assertThat(storage.currentPageNumber()).isEqualTo(2);

        storage.retirePages("0");

        assertThat(storage.currentPageNumber()).isEqualTo(1);
    }

    @Test
    void runCycle_afterSealedCompaction_retiresSourcePages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.retract(1L);
        storage.safepoint(0);           // page "0": 0% live

        CompactionCoordinator.onDemand(storage).compact(Set.of("0"));
        storage.safepoint(1);           // seals the COMMIT

        int pageCountBefore = storage.currentPageNumber();

        CompactionCoordinator.onDemand(storage).runCycle();

        assertThat(storage.currentPageNumber()).isLessThan(pageCountBefore);
    }

    @Test
    void retirePages_inMemory_unknownPageId_isIgnored() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);

        storage.retirePages("nonexistent");

        assertThat(storage.currentPageNumber()).isEqualTo(1);
    }

    @Test
    void stop_whenNeverStarted_isNoOp() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        CompactionCoordinator coordinator = new CompactionCoordinator(storage, Duration.ZERO);

        coordinator.stop(); // must not throw
    }

    @Test
    void scanLiveness_afterSealedCompaction_excludesRetiredPages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.insert(2L, "b");
        storage.insert(3L, "c");
        storage.insert(4L, "d");
        storage.safepoint(0);            // page "0": 4 inserts
        storage.retract(2L);
        storage.retract(3L);
        storage.retract(4L);
        storage.safepoint(1);            // page "1": 3 retracts

        CompactionCoordinator.onDemand(storage).compact(Set.of("0", "1"));
        storage.safepoint(2);            // seals the COMMIT — pages "0" and "1" are retired

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness).doesNotContainKey("0");
        assertThat(liveness).doesNotContainKey("1");
    }

    @Test
    void scanLiveness_afterSealedCompaction_includesMergedPage() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.insert(2L, "b");
        storage.insert(3L, "c");
        storage.insert(4L, "d");
        storage.safepoint(0);
        storage.retract(2L);
        storage.retract(3L);
        storage.retract(4L);
        storage.safepoint(1);

        CompactionCoordinator.onDemand(storage).compact(Set.of("0", "1"));
        storage.safepoint(2);

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        // The merged page should be present and contain the one surviving fact
        assertThat(liveness).hasSize(1);
        String mergedPageId = liveness.keySet().iterator().next();
        assertThat(mergedPageId).startsWith("m-");
        assertThat(liveness.get(mergedPageId)[0]).isEqualTo(1L);
    }

    @Test
    void scanLiveness_twoSequentialCompactions_excludesAllRetiredPages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        // Round 1: pages "0" and "1" compacted, fact 1 survives
        storage.insert(1L, "keep");
        storage.insert(2L, "drop");
        storage.insert(3L, "drop");
        storage.insert(4L, "drop");
        storage.safepoint(0);
        storage.retract(2L);
        storage.retract(3L);
        storage.retract(4L);
        storage.safepoint(1);
        CompactionCoordinator.onDemand(storage).compact(Set.of("0", "1"));
        storage.safepoint(2);  // seals round 1

        // Round 2: pages "3" and "4" compacted, fact 5 survives
        storage.insert(5L, "keep");
        storage.insert(6L, "drop");
        storage.insert(7L, "drop");
        storage.insert(8L, "drop");
        storage.safepoint(3);
        storage.retract(6L);
        storage.retract(7L);
        storage.retract(8L);
        storage.safepoint(4);
        CompactionCoordinator.onDemand(storage).compact(Set.of("3", "4"));
        storage.safepoint(5);  // seals round 2

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        assertThat(liveness).doesNotContainKey("0");
        assertThat(liveness).doesNotContainKey("1");
        assertThat(liveness).doesNotContainKey("3");
        assertThat(liveness).doesNotContainKey("4");
        // Only the two merged pages should remain
        assertThat(liveness).hasSize(2);
        assertThat(liveness.keySet()).allSatisfy(id -> assertThat(id).startsWith("m-"));
    }

    @Test
    void scanLiveness_unsealedCompaction_sourcePagesSurvive() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.insert(2L, "b");
        storage.safepoint(0);
        storage.retract(2L);
        storage.safepoint(1);

        CompactionCoordinator.onDemand(storage).compact(Set.of("0", "1"));
        // No safepoint after COMMIT — not sealed

        Map<String, long[]> liveness = CompactionCoordinator.onDemand(storage).scanLiveness();

        // Unsealed: source pages are still canonical
        assertThat(liveness).containsKey("0");
        assertThat(liveness).containsKey("1");
    }
}

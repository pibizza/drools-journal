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

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness).isEmpty();
    }

    @Test
    void page_withAllInsertsLive_isNotSparse() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(CompactionCoordinator.isSparse(liveness.get("0"))).isFalse();
    }

    @Test
    void page_withAllInsertsRetracted_isSparse() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.retract(1L);
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(CompactionCoordinator.isSparse(liveness.get("0"))).isTrue();
    }

    @Test
    void insertAndRetract_inSamePage_liveCountIsZero() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.retract(1L);
        storage.safepoint(0);        // page "0": [insert(1), retract(1)]

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness.get("0")[0]).isEqualTo(0L); // liveCount
    }

    @Test
    void retract_onLaterPageThanInsert_countedInRetractPageTotal() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);        // page "0": [insert(1)]
        storage.retract(1L);
        storage.safepoint(1);        // page "1": [retract(1)]

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness.get("1")[1]).isEqualTo(1L); // totalCount — retract occupies space
    }

    @Test
    void retract_onLaterPageThanInsert_decrementsLiveCountOnInsertPage() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);        // page "0": [insert(1)]
        storage.retract(1L);
        storage.safepoint(1);        // page "1": [retract(1)]

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness.get("0")[0]).isEqualTo(0L); // insert is no longer live
    }

    @Test
    void modifyRecord_inLivenessTracking_countsInTotalOnly() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.modify(1L, "Rule_MyRule_modify_0", new byte[0]);
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

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

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

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

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness.get("0")[0]).isEqualTo(1L); // liveCount
        assertThat(liveness.get("1")[0]).isEqualTo(1L); // liveCount
    }

    @Test
    void singlePage_singleInsert_isLive() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

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

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

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

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

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
    void stop_whenNeverStarted_isNoOp() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        CompactionCoordinator coordinator = new CompactionCoordinator(storage, Duration.ZERO);

        coordinator.stop(); // must not throw
    }
}

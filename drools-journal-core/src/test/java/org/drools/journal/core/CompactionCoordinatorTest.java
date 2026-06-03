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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionCoordinatorTest {

    @Test
    void emptyJournal_producesEmptyLivenessMap() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness).isEmpty();
    }

    @Test
    void pageWithAllInsertsLive_isNotSparse() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(CompactionCoordinator.isSparse(liveness.get("0"))).isFalse();
    }

    @Test
    void pageWithAllInsertsRetracted_isSparse() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.retract(1L);
        storage.safepoint(0);

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(CompactionCoordinator.isSparse(liveness.get("0"))).isTrue();
    }

    @Test
    void insertAndRetractInSamePage_liveCountIsZero() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.retract(1L);
        storage.safepoint(0);        // page "0": [insert(1), retract(1)]

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness.get("0")[0]).isEqualTo(0L); // liveCount
    }

    @Test
    void retractOnLaterPage_decrementsLiveCountOnInsertPage() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);        // page "0": [insert(1)]
        storage.retract(1L);
        storage.safepoint(1);        // page "1": [retract(1)]

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness.get("0")[0]).isEqualTo(0L); // insert is no longer live
    }

    @Test
    void twoPages_trackedSeparately() {
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
}

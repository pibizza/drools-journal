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

        CompactionCoordinator.compact(storage, Set.of("0"));

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
        storage.insert(2L, "b");
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
        storage.insert(1L, "live");
        storage.insert(2L, "dead");
        storage.safepoint(0);
        storage.retract(2L);
        storage.safepoint(1);

        CompactionCoordinator.compact(storage, Set.of("0", "1"));

        // Pm is a separate page in the raw journal (not inline between PREPARE and COMMIT).
        // Fact 1 (live) appears twice: once in P0, once in Pm.
        // Fact 2 (retracted) appears once in P0 only — not in Pm.
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

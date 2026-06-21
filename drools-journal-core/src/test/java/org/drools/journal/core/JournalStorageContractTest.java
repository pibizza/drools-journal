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

import java.util.ArrayList;
import java.util.List;

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.Payload;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract contract test for {@link JournalStorage} implementations.
 *
 * <p>Extend this class and implement {@link #createStorage()} and
 * {@link #appendTestRecord(JournalStorage, JournalRecord)} to verify that a
 * storage implementation honours the SPI contract. Chronicle and Aeron
 * implementations will extend this class in their own contract test subclasses.
 */
public abstract class JournalStorageContractTest {

    /** Returns a fresh, empty storage instance for each test. */
    protected abstract JournalStorage createStorage();

    /**
     * Appends a retract record to {@code storage} and returns its assigned position.
     * Implementations bridge their own retract mechanism here.
     */
    protected abstract long appendTestRecord(JournalStorage storage, JournalRecord record);

    /** Produces a distinct {@link RetractRecord} for use as test data. */
    protected final JournalRecord sampleRecord(final int index) {
        return new RetractRecord(index);
    }

    /** Produces a simple {@link Payload} for use in semantic write tests. */
    protected Payload samplePayload() {
        return new EmbedStrategy().store("test-fact", null);
    }

    // -------------------------------------------------------------------------
    // latestPosition
    // -------------------------------------------------------------------------

    @Test
    void latestPosition_emptyJournal_returnsMinusOne() {
        try (JournalStorage storage = createStorage()) {
            assertThat(storage.latestPosition()).isEqualTo(-1L);
        }
    }

    @Test
    void latestPosition_afterAppend_returnsLastAppendedPosition() {
        try (JournalStorage storage = createStorage()) {
            appendTestRecord(storage, sampleRecord(0));
            long lastPos = appendTestRecord(storage, sampleRecord(1));
            assertThat(storage.latestPosition()).isEqualTo(lastPos);
        }
    }

    // -------------------------------------------------------------------------
    // append — position monotonicity
    // -------------------------------------------------------------------------

    @Test
    void append_returnsMonotonicallyIncreasingPositions() {
        try (JournalStorage storage = createStorage()) {
            long pos0 = appendTestRecord(storage, sampleRecord(0));
            long pos1 = appendTestRecord(storage, sampleRecord(1));
            long pos2 = appendTestRecord(storage, sampleRecord(2));
            assertThat(pos0).isLessThan(pos1);
            assertThat(pos1).isLessThan(pos2);
        }
    }

    // -------------------------------------------------------------------------
    // scan — coverage and content fidelity
    // -------------------------------------------------------------------------

    @Test
    void scan_emptyJournal_returnsEmptyScanner() {
        try (JournalStorage storage = createStorage();
             JournalScanner scanner = storage.scan(0)) {
            assertThat(scanner.hasNext()).isFalse();
        }
    }

    @Test
    void scan_fromZero_returnsAllRecordsWithCorrectContent() {
        try (JournalStorage storage = createStorage()) {
            appendTestRecord(storage, sampleRecord(0));
            appendTestRecord(storage, sampleRecord(1));
            appendTestRecord(storage, sampleRecord(2));

            try (JournalScanner scanner = storage.scan(0)) {
                assertThat(drain(scanner))
                        .hasSize(3)
                        .containsExactly(sampleRecord(0), sampleRecord(1), sampleRecord(2));
            }
        }
    }

    @Test
    void scan_fromMidpoint_returnsRecordsFromThatPositionOnward() {
        try (JournalStorage storage = createStorage()) {
            appendTestRecord(storage, sampleRecord(0));
            long midPos = appendTestRecord(storage, sampleRecord(1));
            appendTestRecord(storage, sampleRecord(2));

            try (JournalScanner scanner = storage.scan(midPos)) {
                assertThat(drain(scanner))
                        .hasSize(2)
                        .containsExactly(sampleRecord(1), sampleRecord(2));
            }
        }
    }

    @Test
    void scan_fromLatestPosition_returnsSingleRecord() {
        try (JournalStorage storage = createStorage()) {
            appendTestRecord(storage, sampleRecord(0));
            long lastPos = appendTestRecord(storage, sampleRecord(1));

            try (JournalScanner scanner = storage.scan(lastPos)) {
                assertThat(drain(scanner))
                        .hasSize(1)
                        .containsExactly(sampleRecord(1));
            }
        }
    }

    @Test
    void scan_fromBeyondEnd_returnsEmptyScanner() {
        try (JournalStorage storage = createStorage()) {
            long lastPos = appendTestRecord(storage, sampleRecord(0));

            try (JournalScanner scanner = storage.scan(lastPos + 1000)) {
                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void scan_multipleIndependentScannersOnSameStorage_doNotInterfere() {
        try (JournalStorage storage = createStorage()) {
            appendTestRecord(storage, sampleRecord(0));
            appendTestRecord(storage, sampleRecord(1));
            appendTestRecord(storage, sampleRecord(2));

            try (JournalScanner s1 = storage.scan(0);
                 JournalScanner s2 = storage.scan(0)) {
                assertThat(drain(s1)).containsExactly(sampleRecord(0), sampleRecord(1), sampleRecord(2));
                assertThat(drain(s2)).containsExactly(sampleRecord(0), sampleRecord(1), sampleRecord(2));
            }
        }
    }

    // -------------------------------------------------------------------------
    // scanner — position semantics
    // -------------------------------------------------------------------------

    @Test
    void scannerPosition_beforeFirstNext_returnsStartPosition() {
        try (JournalStorage storage = createStorage()) {
            long startPos = appendTestRecord(storage, sampleRecord(0));

            try (JournalScanner scanner = storage.scan(startPos)) {
                assertThat(scanner.position()).isEqualTo(startPos);
            }
        }
    }

    @Test
    void scannerPosition_afterNext_returnsPositionOfLastReturnedRecord() {
        try (JournalStorage storage = createStorage()) {
            long pos0 = appendTestRecord(storage, sampleRecord(0));
            long pos1 = appendTestRecord(storage, sampleRecord(1));

            try (JournalScanner scanner = storage.scan(pos0)) {
                scanner.next();
                assertThat(scanner.position()).isEqualTo(pos0);
                scanner.next();
                assertThat(scanner.position()).isEqualTo(pos1);
            }
        }
    }

    // -------------------------------------------------------------------------
    // close — idempotency
    // -------------------------------------------------------------------------

    @Test
    void storageClose_isIdempotent() {
        JournalStorage storage = createStorage();
        storage.close();
        storage.close();
    }

    @Test
    void scannerClose_isIdempotent() {
        try (JournalStorage storage = createStorage()) {
            appendTestRecord(storage, sampleRecord(0));
            JournalScanner scanner = storage.scan(0);
            scanner.close();
            scanner.close();
        }
    }

    // -------------------------------------------------------------------------
    // Semantic write API — insert / retract / ruleMatch / compaction
    // -------------------------------------------------------------------------

    @Test
    void insert_nonLogical_producesInsertRecordInScan() {
        try (JournalStorage storage = createStorage()) {
            Payload payload = samplePayload();
            storage.insert(42L, payload, false, -1L);

            try (JournalScanner scanner = storage.scan(0)) {
                InsertRecord record = (InsertRecord) scanner.next();
                assertThat(record.factHandleId()).isEqualTo(42L);
                assertThat(record.logical()).isFalse();
                assertThat(record.justifyingRuleMatchId()).isEqualTo(-1L);
                assertThat(record.payload()).isEqualTo(payload);
            }
        }
    }

    @Test
    void insert_logical_producesLogicalInsertRecordInScan() {
        try (JournalStorage storage = createStorage()) {
            Payload payload = samplePayload();
            storage.insert(7L, payload, true, 99L);

            try (JournalScanner scanner = storage.scan(0)) {
                InsertRecord record = (InsertRecord) scanner.next();
                assertThat(record.factHandleId()).isEqualTo(7L);
                assertThat(record.logical()).isTrue();
                assertThat(record.justifyingRuleMatchId()).isEqualTo(99L);
            }
        }
    }

    @Test
    void retract_producesRetractRecordInScan() {
        try (JournalStorage storage = createStorage()) {
            storage.retract(55L);

            try (JournalScanner scanner = storage.scan(0)) {
                RetractRecord record = (RetractRecord) scanner.next();
                assertThat(record.factHandleId()).isEqualTo(55L);
            }
        }
    }

    @Test
    void ruleMatch_producesRuleMatchRecordInScan() {
        try (JournalStorage storage = createStorage()) {
            storage.ruleMatch(3L, "org.example", "myRule", new long[]{1L, 2L});

            try (JournalScanner scanner = storage.scan(0)) {
                RuleMatchRecord record = (RuleMatchRecord) scanner.next();
                assertThat(record.id()).isEqualTo(3L);
                assertThat(record.packageName()).isEqualTo("org.example");
                assertThat(record.ruleName()).isEqualTo("myRule");
                assertThat(record.factHandleIds()).containsExactly(1L, 2L);
            }
        }
    }

    @Test
    void compactionPrepare_producesCompactionPrepareRecordInScan() {
        try (JournalStorage storage = createStorage()) {
            storage.compactionPrepare("merged-1", "page-a", "page-b");

            try (JournalScanner scanner = storage.scan(0)) {
                CompactionPrepareRecord record = (CompactionPrepareRecord) scanner.next();
                assertThat(record.preparingPageId()).isEqualTo("merged-1");
                assertThat(record.replacedPageIds()).containsExactly("page-a", "page-b");
            }
        }
    }

    @Test
    void compactionCommit_producesCompactionCommitRecordInScan() {
        try (JournalStorage storage = createStorage()) {
            storage.compactionCommit("merged-1", "page-a", "page-b");

            try (JournalScanner scanner = storage.scan(0)) {
                CompactionCommitRecord record = (CompactionCommitRecord) scanner.next();
                assertThat(record.mergedPageId()).isEqualTo("merged-1");
                assertThat(record.replacedPageIds()).containsExactly("page-a", "page-b");
            }
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static List<JournalRecord> drain(final JournalScanner scanner) {
        List<JournalRecord> result = new ArrayList<>();
        scanner.forEachRemaining(result::add);
        return result;
    }
}

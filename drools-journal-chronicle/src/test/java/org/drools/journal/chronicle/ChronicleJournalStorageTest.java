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
package org.drools.journal.chronicle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ChronicleJournalStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void freshJournal_createsDirectoryLayout() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            assertThat(Path.of(path, "catalog")).isDirectory();
            assertThat(Path.of(path, "page-0")).isDirectory();
        }
    }

    @Test
    void insertAndSafepoint_thenScan_returnsRecords() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();

            List<JournalRecord> records = scanAll(storage);
            assertThat(records).hasSize(2);
            assertThat(records.get(0)).isInstanceOf(InsertRecord.class);
            assertThat(((InsertRecord) records.get(0)).factHandleId()).isEqualTo(1L);
            assertThat(records.get(1)).isInstanceOf(SafepointRecord.class);
        }
    }

    @Test
    void safepoint_rollsToNewPageDirectory() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            assertThat(Path.of(path, "page-0")).isDirectory();
            assertThat(Path.of(path, "page-1")).isDirectory();
            assertThat(Path.of(path, "page-2")).isDirectory();
        }
    }

    @Test
    void scanAcrossPages_reportsCorrectPageIds() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            try (JournalScanner scanner = storage.scan(0)) {
                assertThat(scanner.currentPageId()).isEqualTo("0");
                scanner.next(); // insert
                assertThat(scanner.currentPageId()).isEqualTo("0");
                scanner.next(); // safepoint
                assertThat(scanner.currentPageId()).isEqualTo("0");
                scanner.next(); // insert
                assertThat(scanner.currentPageId()).isEqualTo("1");
                scanner.next(); // safepoint
                assertThat(scanner.currentPageId()).isEqualTo("1");
                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void reopen_resumesFromLastState() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            List<JournalRecord> records = scanAll(storage);
            assertThat(records).hasSize(4);
            assertThat(((InsertRecord) records.get(0)).factHandleId()).isEqualTo(1L);
            assertThat(((InsertRecord) records.get(2)).factHandleId()).isEqualTo(2L);
        }
    }

    @Test
    void latestPosition_negativeOneWhenEmpty() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            assertThat(storage.latestPosition()).isEqualTo(-1L);
        }
    }

    @Test
    void latestPosition_positiveAfterWrite() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            assertThat(storage.latestPosition()).isGreaterThan(-1L);
        }
    }

    @Test
    void writeMergedPage_createsPageDirectory() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();

            List<JournalRecord> mergedRecords = List.of(
                    new InsertRecord(1L, false, -1L, new EmbeddedPayload(new byte[]{10}))
            );
            storage.writeMergedPage("m-1", mergedRecords);
            assertThat(Path.of(path, "page-m-1")).isDirectory();
        }
    }

    @Test
    void compaction_updatesScannedPageOrder() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            List<JournalRecord> mergedRecords = List.of(
                    new InsertRecord(1L, false, -1L, new EmbeddedPayload(new byte[]{10})),
                    new InsertRecord(2L, false, -1L, new EmbeddedPayload(new byte[]{20}))
            );
            storage.compactionPrepare("m-1", new String[]{"0", "1"});
            storage.writeMergedPage("m-1", mergedRecords);
            storage.compactionCommit("m-1", new String[]{"0", "1"});

            try (JournalScanner scanner = storage.scan(0)) {
                assertThat(scanner.currentPageId()).isEqualTo("m-1");
                assertThat(((InsertRecord) scanner.next()).factHandleId()).isEqualTo(1L);
                assertThat(((InsertRecord) scanner.next()).factHandleId()).isEqualTo(2L);
                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    private List<JournalRecord> scanAll(final ChronicleJournalStorage storage) {
        List<JournalRecord> records = new ArrayList<>();
        try (JournalScanner scanner = storage.scan(0)) {
            while (scanner.hasNext()) {
                records.add(scanner.next());
            }
        }
        return records;
    }
}

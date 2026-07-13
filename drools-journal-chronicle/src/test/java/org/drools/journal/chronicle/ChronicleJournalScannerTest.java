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

import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.SafepointRecord;
import org.drools.journal.chronicle.internal.ChronicleCatalogWriteOps;
import org.drools.journal.chronicle.internal.ChronicleDataWriteOps;
import org.drools.journal.chronicle.internal.PayloadCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MultiQueueScannerTest {

    @TempDir
    Path tempDir;

    private static final byte[] PAYLOAD = PayloadCodec.encode(new EmbeddedPayload(new byte[]{1}));

    @Test
    void scansTwoPagesInCatalogOrder() {
        Path root = tempDir.resolve("journal");

        try (SingleChronicleQueue catalog = openQueue(root.resolve("catalog"))) {
            ChronicleCatalogWriteOps catWriter = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            catWriter.pageCreated(0);
            catWriter.pageCreated(1);

            try (SingleChronicleQueue p0 = openQueue(root.resolve("page-0"))) {
                ChronicleDataWriteOps w = p0.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
                w.insert(1L, PAYLOAD);
                w.safepoint(0L, 1000L);
            }
            try (SingleChronicleQueue p1 = openQueue(root.resolve("page-1"))) {
                ChronicleDataWriteOps w = p1.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
                w.insert(2L, PAYLOAD);
                w.safepoint(1L, 2000L);
            }

            try (MultiQueueScanner scanner = MultiQueueScanner.create(root, catalog)) {
                assertThat(scanner.currentPageId()).isEqualTo("0");

                JournalRecord r1 = scanner.next();
                assertThat(r1).isInstanceOf(InsertRecord.class);
                assertThat(((InsertRecord) r1).factHandleId()).isEqualTo(1L);

                JournalRecord r2 = scanner.next();
                assertThat(r2).isInstanceOf(SafepointRecord.class);
                assertThat(scanner.currentPageId()).isEqualTo("0");

                JournalRecord r3 = scanner.next();
                assertThat(r3).isInstanceOf(InsertRecord.class);
                assertThat(((InsertRecord) r3).factHandleId()).isEqualTo(2L);

                JournalRecord r4 = scanner.next();
                assertThat(r4).isInstanceOf(SafepointRecord.class);

                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void skipsEmptyPageDirectory() {
        Path root = tempDir.resolve("journal");

        try (SingleChronicleQueue catalog = openQueue(root.resolve("catalog"))) {
            ChronicleCatalogWriteOps catWriter = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            catWriter.pageCreated(0);
            catWriter.pageCreated(1);

            try (SingleChronicleQueue p0 = openQueue(root.resolve("page-0"))) {
                // empty — no records
            }
            try (SingleChronicleQueue p1 = openQueue(root.resolve("page-1"))) {
                p1.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(1L, PAYLOAD);
            }

            try (MultiQueueScanner scanner = MultiQueueScanner.create(root, catalog)) {
                assertThat(scanner.hasNext()).isTrue();
                assertThat(scanner.currentPageId()).isEqualTo("1");
                InsertRecord ir = (InsertRecord) scanner.next();
                assertThat(ir.factHandleId()).isEqualTo(1L);
                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void compactedPages_readInMergedOrder() {
        Path root = tempDir.resolve("journal");

        try (SingleChronicleQueue catalog = openQueue(root.resolve("catalog"))) {
            ChronicleCatalogWriteOps catWriter = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            catWriter.pageCreated(0);
            catWriter.pageCreated(1);
            catWriter.pageCreated(2);
            catWriter.compactionCommit("m-1", "0", "1");

            // old pages still on disk (retirement deferred)
            try (SingleChronicleQueue p0 = openQueue(root.resolve("page-0"))) {
                p0.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(1L, PAYLOAD);
            }
            try (SingleChronicleQueue p1 = openQueue(root.resolve("page-1"))) {
                p1.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(2L, PAYLOAD);
            }

            // merged page and active page
            try (SingleChronicleQueue merged = openQueue(root.resolve("page-m-1"))) {
                merged.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(10L, PAYLOAD);
            }
            try (SingleChronicleQueue p2 = openQueue(root.resolve("page-2"))) {
                p2.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(20L, PAYLOAD);
            }

            try (MultiQueueScanner scanner = MultiQueueScanner.create(root, catalog)) {
                assertThat(scanner.currentPageId()).isEqualTo("m-1");
                assertThat(((InsertRecord) scanner.next()).factHandleId()).isEqualTo(10L);
                assertThat(scanner.currentPageId()).isEqualTo("m-1");

                assertThat(((InsertRecord) scanner.next()).factHandleId()).isEqualTo(20L);
                assertThat(scanner.currentPageId()).isEqualTo("2");

                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void emptyCatalog_scanHasNoRecords() {
        Path root = tempDir.resolve("journal");

        try (SingleChronicleQueue catalog = openQueue(root.resolve("catalog"))) {
            try (MultiQueueScanner scanner = MultiQueueScanner.create(root, catalog)) {
                assertThat(scanner.hasNext()).isFalse();
                assertThat(scanner.currentPageId()).isNull();
            }
        }
    }

    @Test
    void missingPageDirectory_skipped() {
        Path root = tempDir.resolve("journal");

        try (SingleChronicleQueue catalog = openQueue(root.resolve("catalog"))) {
            ChronicleCatalogWriteOps catWriter = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            catWriter.pageCreated(0);
            catWriter.pageCreated(1);

            // only create page-1, page-0 directory does not exist
            try (SingleChronicleQueue p1 = openQueue(root.resolve("page-1"))) {
                p1.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(1L, PAYLOAD);
            }

            try (MultiQueueScanner scanner = MultiQueueScanner.create(root, catalog)) {
                assertThat(scanner.hasNext()).isTrue();
                assertThat(scanner.currentPageId()).isEqualTo("1");
                assertThat(((InsertRecord) scanner.next()).factHandleId()).isEqualTo(1L);
                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    private static SingleChronicleQueue openQueue(final Path path) {
        return SingleChronicleQueueBuilder.binary(path).build();
    }
}

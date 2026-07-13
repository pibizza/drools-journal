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
package org.drools.journal.chronicle.internal;

import java.nio.file.Path;

import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void pagesOnly_returnsAllInOrder() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.pageCreated("2");

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("0", "1", "2");
            assertThat(index.highestPageCounter()).isEqualTo(2);
        }
    }

    @Test
    void compactionCommit_splicesReplacedPages() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.pageCreated("2");
            writer.compactionPrepare("m-1", "0", "1");
            writer.compactionCommit("m-1", "0", "1");

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("m-1", "2");
            assertThat(index.highestPageCounter()).isEqualTo(2);
        }
    }

    @Test
    void prepareWithoutCommit_leavesOriginalPages() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.compactionPrepare("m-1", "0", "1");

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("0", "1");
        }
    }

    @Test
    void twoRoundsOfCompaction_resolveCorrectly() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.pageCreated("2");
            writer.pageCreated("3");
            writer.compactionCommit("m-1", "0", "1");
            writer.compactionCommit("m-2", "m-1", "2");

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("m-2", "3");
        }
    }

    @Test
    void nonAdjacentMerge_splicesAtFirstReplacedPosition() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.pageCreated("2");
            writer.pageCreated("3");
            writer.compactionCommit("m-1", "0", "2");

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("m-1", "1", "3");
        }
    }

    @Test
    void pageCreatedAfterCompaction_appendsAtEnd() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.compactionCommit("m-1", "0", "1");
            writer.pageCreated("2");

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("m-1", "2");
            assertThat(index.highestPageCounter()).isEqualTo(2);
        }
    }

    @Test
    void singlePage_returnsSingletonList() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("0");
            assertThat(index.highestPageCounter()).isEqualTo(0);
        }
    }

    @Test
    void emptyCatalog_returnsEmptyList() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).isEmpty();
            assertThat(index.highestPageCounter()).isEqualTo(0);
        }
    }
}

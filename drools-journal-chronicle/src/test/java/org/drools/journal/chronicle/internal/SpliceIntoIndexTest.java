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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpliceIntoIndexTest {

    @Test
    void adjacentPages_mergedAtFirstPosition() {
        List<String> result = CatalogIndex.spliceIntoIndex(List.of("0", "1", "2"), "m-1", "0", "1");
        assertThat(result).containsExactly("m-1", "2");
    }

    @Test
    void nonAdjacentPages_mergedAtFirstReplacedPosition() {
        List<String> result = CatalogIndex.spliceIntoIndex(List.of("0", "1", "2", "3"), "m-1", "0", "2");
        assertThat(result).containsExactly("m-1", "1", "3");
    }

    @Test
    void replacedIdsInReverseOrder_mergedAtEarliestPosition() {
        List<String> result = CatalogIndex.spliceIntoIndex(List.of("0", "1", "2"), "m-1", "1", "0");
        assertThat(result).containsExactly("m-1", "2");
    }

    @Test
    void allPagesReplaced_resultIsSingleMergedPage() {
        List<String> result = CatalogIndex.spliceIntoIndex(List.of("0", "1", "2"), "m-1", "0", "1", "2");
        assertThat(result).containsExactly("m-1");
    }

    @Test
    void unknownReplacedId_ignored() {
        List<String> result = CatalogIndex.spliceIntoIndex(List.of("0", "1"), "m-1", "0", "99");
        assertThat(result).containsExactly("m-1", "1");
    }

    @Test
    void allReplacedIdsUnknown_listUnchanged() {
        List<String> result = CatalogIndex.spliceIntoIndex(List.of("0", "1"), "m-1", "98", "99");
        assertThat(result).containsExactly("0", "1");
    }

    @Test
    void singlePageReplaced_mergedAtSamePosition() {
        List<String> result = CatalogIndex.spliceIntoIndex(List.of("0", "1", "2"), "m-1", "1");
        assertThat(result).containsExactly("0", "m-1", "2");
    }
}

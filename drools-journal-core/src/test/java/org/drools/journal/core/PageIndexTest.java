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

import org.drools.journal.api.JournalScanner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageIndexTest {

    @Test
    void buildLivePageSet_noCompaction_returnsEmptyRetiredPages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);
        storage.insert(2L, "b");
        storage.safepoint(1);

        PageIndex.PageIndexStatus status;
        try (JournalScanner scanner = storage.scan(0)) {
            status = PageIndex.buildLivePageSet(scanner);
        }

        assertThat(status.livePages()).containsExactlyInAnyOrder("0", "1");
        assertThat(status.retiredPages()).isEmpty();
    }
}

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
package org.drools.journal.test;

import java.nio.file.Path;

import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.chronicle.ChronicleJournalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;

import static org.assertj.core.api.Assertions.assertThat;

class ChroniclePageRetirementIT {

    private static final String RULE = """
            package org.drools.journal.test
            rule "ProcessFact"
            when
                $i: Integer()
            then
            end
            """;

    @TempDir
    Path tempDir;

    @Test
    void retirePages_chronicle_deletesPageDirectories() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            assertThat(Path.of(path, "page-0")).isDirectory();
            assertThat(Path.of(path, "page-1")).isDirectory();

            storage.retirePages("0", "1");

            assertThat(Path.of(path, "page-0")).doesNotExist();
            assertThat(Path.of(path, "page-1")).doesNotExist();
        }
    }

    @Test
    void retirePages_chronicle_nonexistentPage_isIgnored() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();

            storage.retirePages("nonexistent");

            assertThat(Path.of(path, "page-0")).isDirectory();
        }
    }

    @Test
    void compactAndRetire_chronicle_restoreStillWorks() {
        String journalPath = tempDir.resolve("full-cycle").toString();

        // Build a journal with a sparse page: insert 2 facts, fire,
        // then retract one and fire again so page "0" becomes sparse.
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{42}));
            storage.insert(2L, new EmbeddedPayload(new byte[]{99}));
            storage.safepoint();                     // page "0": 2 inserts
            storage.retract(2L);
            storage.safepoint();                     // page "1": 1 retract

            // Compact page "0" and "1", seal, then retire
            storage.compactionPrepare("m-1", new String[]{"0", "1"});
            storage.writeMergedPage("m-1", java.util.List.of(
                    new org.drools.journal.api.InsertRecord(1L, false, -1L, new EmbeddedPayload(new byte[]{42}))));
            storage.compactionCommit("m-1", new String[]{"0", "1"});
            storage.safepoint();                     // seals the commit

            storage.retirePages("0", "1");
            assertThat(Path.of(journalPath, "page-0")).doesNotExist();
            assertThat(Path.of(journalPath, "page-1")).doesNotExist();
            assertThat(Path.of(journalPath, "page-m-1")).isDirectory();
        }

        // Reopen and scan — the merged page should be the only data source
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
            assertThat(storage.isEmpty()).isFalse();
            try (var scanner = storage.scan(0)) {
                assertThat(scanner.hasNext()).isTrue();
                var record = scanner.next();
                assertThat(record).isInstanceOf(org.drools.journal.api.InsertRecord.class);
                assertThat(((org.drools.journal.api.InsertRecord) record).factHandleId()).isEqualTo(1L);
            }
        }
    }
}

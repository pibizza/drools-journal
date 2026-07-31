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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.ModifyLambdaRegistry;
import org.drools.journal.api.Payload;
import org.drools.journal.chronicle.ChronicleJournalStorage;
import org.drools.journal.core.CompactionCoordinator;
import org.drools.journal.core.EmbedStrategy;
import org.drools.journal.core.RestoreEngine;
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
    void twoConcurrentCompactions_disjointPageSets_restoreShowsSurvivingFacts() throws Exception {
        EmbedStrategy embed = new EmbedStrategy();
        String journalPath = tempDir.resolve("concurrent-compaction").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
            // Pages 0+1: fact 1 survives, facts 2-4 die
            storage.insert(1L, embed.store(1, null));
            storage.insert(2L, embed.store(2, null));
            storage.insert(3L, embed.store(3, null));
            storage.insert(4L, embed.store(4, null));
            storage.safepoint();
            storage.retract(2L);
            storage.retract(3L);
            storage.retract(4L);
            storage.safepoint();

            // Pages 2+3: fact 5 survives, facts 6-8 die
            storage.insert(5L, embed.store(5, null));
            storage.insert(6L, embed.store(6, null));
            storage.insert(7L, embed.store(7, null));
            storage.insert(8L, embed.store(8, null));
            storage.safepoint();
            storage.retract(6L);
            storage.retract(7L);
            storage.retract(8L);
            storage.safepoint();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> c1 = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    CompactionCoordinator.onDemand(storage).compact(Set.of("0", "1"));
                    return null;
                });
                Future<?> c2 = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    CompactionCoordinator.onDemand(storage).compact(Set.of("2", "3"));
                    return null;
                });

                ready.await();
                go.countDown();
                c1.get();
                c2.get();
            } finally {
                pool.shutdown();
            }
        }

        // Reopen and verify restore sees only the two surviving facts
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
            RestoreEngine.ScanResult result =
                    new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();
            assertThat(result.survivingFacts()).hasSize(2);
            assertThat(result.survivingFacts()).containsKey(1L);
            assertThat(result.survivingFacts()).containsKey(5L);
        }
    }

    @Test
    void singleCompaction_chronicle_restoreShowsSurvivingFacts() throws Exception {
        EmbedStrategy embed = new EmbedStrategy();
        String journalPath = tempDir.resolve("single-compaction").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
            storage.insert(1L, embed.store(1, null));
            storage.insert(2L, embed.store(2, null));
            storage.insert(3L, embed.store(3, null));
            storage.insert(4L, embed.store(4, null));
            storage.safepoint();
            storage.retract(2L);
            storage.retract(3L);
            storage.retract(4L);
            storage.safepoint();

            CompactionCoordinator.onDemand(storage).compact(Set.of("0", "1"));
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
            RestoreEngine.ScanResult result =
                    new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();
            assertThat(result.survivingFacts()).hasSize(1);
            assertThat(result.survivingFacts()).containsKey(1L);
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

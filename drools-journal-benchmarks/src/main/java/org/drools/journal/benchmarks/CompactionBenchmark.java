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
package org.drools.journal.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import org.drools.journal.api.DurableSessionOption;
import org.drools.journal.chronicle.ChronicleJournalStorage;
import org.drools.journal.core.JournalledKieSession;
import org.kie.api.KieServices;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, batchSize = 1)
@Measurement(iterations = 5, batchSize = 1)
public class CompactionBenchmark extends AbstractJournalBenchmark {

    @Param({"100", "1000", "5000"})
    int factCount;

    private JournalledKieSession journalledSession;

    @Override
    protected DrlProvider drlProvider() {
        return DrlProvider.SIMPLE_INSERT;
    }

    @Override
    @Setup(Level.Iteration)
    public void openSession() throws IOException {
    }

    @Override
    @TearDown(Level.Iteration)
    public void closeSession() throws IOException {
    }

    @Setup(Level.Trial)
    public void populateJournal() throws IOException {
        buildKieBase();
        journalDir = Files.createTempDirectory("jmh-compaction-");
        storage = ChronicleJournalStorage.atPath(journalDir.toString());
        Environment env = KieServices.get().newEnvironment();
        env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
                .withJournalStorage(storage)
                .withCompactionInterval(Duration.ZERO));
        session = kbase.newKieSession(null, env);
        journalledSession = (JournalledKieSession) session;

        for (int i = 0; i < factCount; i++) {
            FactHandle handle = session.insert(i);
            session.fireAllRules();
            if (i % 2 == 0) {
                session.delete(handle);
                session.fireAllRules();
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (session != null) {
            session.dispose();
        }
        if (storage != null) {
            storage.close();
        }
        if (journalDir != null && Files.exists(journalDir)) {
            try (var walk = Files.walk(journalDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Benchmark
    public void compactJournal() {
        journalledSession.compactNow();
    }
}

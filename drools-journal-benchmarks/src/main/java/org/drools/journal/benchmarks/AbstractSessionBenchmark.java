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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import org.drools.journal.api.DurableSessionOption;
import org.drools.journal.chronicle.ChronicleJournalStorage;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED"
})
public abstract class AbstractSessionBenchmark {

    @Param({"PLAIN", "JOURNAL"})
    protected String mode;

    protected KieBase kieBase;
    protected KieSession kieSession;

    private ChronicleJournalStorage storage;
    private Path journalDir;

    protected void buildKieBase(final String drl) {
        kieBase = new KieHelper().addContent(drl, ResourceType.DRL).build();
    }

    @Setup(Level.Iteration)
    public void openSession() throws IOException {
        if ("JOURNAL".equals(mode)) {
            journalDir = Files.createTempDirectory("jmh-journal-");
            storage = ChronicleJournalStorage.atPath(journalDir.toString());
            Environment env = KieServices.get().newEnvironment();
            env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
                    .withJournalStorage(storage)
                    .withCompactionInterval(Duration.ZERO));
            kieSession = kieBase.newKieSession(null, env);
        } else {
            kieSession = kieBase.newKieSession();
        }
    }

    @TearDown(Level.Iteration)
    public void closeSession() throws IOException {
        if (kieSession != null) {
            kieSession.dispose();
            kieSession = null;
        }
        if (storage != null) {
            storage.close();
            storage = null;
        }
        if (journalDir != null) {
            deleteRecursively(journalDir);
            journalDir = null;
        }
    }

    private static void deleteRecursively(final Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }
}

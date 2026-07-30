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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.drools.journal.api.DurableSessionOption;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ModifyLambdaRegistry;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.chronicle.ChronicleJournalStorage;
import org.drools.journal.core.JournalDrlPrecompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import static org.assertj.core.api.Assertions.assertThat;

class ChronicleJournalledKieSessionIT {

    public static class Ticket implements java.io.Serializable {
        private String status;

        public Ticket() {}
        public Ticket(final String status) { this.status = status; }
        public String getStatus() { return status; }
        public void setStatus(final String status) { this.status = status; }
        @Override public String toString() { return status; }
    }

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

    private static Environment journalEnv(final JournalStorage storage) {
        Environment env = KieServices.get().newEnvironment();
        env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
                .withJournalStorage(storage)
                .withCompactionInterval(Duration.ZERO));
        return env;
    }

    private static Environment journalEnv(final JournalStorage storage,
                                          final ModifyLambdaRegistry registry) {
        Environment env = KieServices.get().newEnvironment();
        env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
                .withJournalStorage(storage)
                .withModifyLambdaRegistry(registry)
                .withCompactionInterval(Duration.ZERO));
        return env;
    }

    @Test
    void openFireDispose_thenReopen_restoresFact_andRuleDoesNotRefire() {
        final KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();
        final String journalPath = tempDir.resolve("chronicle-journal").toString();

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             KieSession session = kbase.newKieSession(null, journalEnv(storage))) {
            session.insert(42);
            final int fired = session.fireAllRules();
            assertThat(fired).isEqualTo(1);
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             KieSession session = kbase.newKieSession(null, journalEnv(storage))) {
            assertThat(session.getObjects()).singleElement().isEqualTo(42);
            final int fired = session.fireAllRules();
            assertThat(fired).isEqualTo(0);
        }
    }

    @Test
    void multiSession_insertsAccumulate_acrossRestarts() {
        final KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();
        final String journalPath = tempDir.resolve("multi-session").toString();

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             KieSession session = kbase.newKieSession(null, journalEnv(storage))) {
            session.insert(1);
            session.fireAllRules();
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             KieSession session = kbase.newKieSession(null, journalEnv(storage))) {
            assertThat(session.getObjects()).singleElement().isEqualTo(1);
            session.insert(2);
            session.fireAllRules();
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             KieSession session = kbase.newKieSession(null, journalEnv(storage))) {
            final java.util.Collection<Object> objects = (java.util.Collection<Object>) session.getObjects();
            assertThat(objects).containsExactlyInAnyOrder(1, 2);
        }
    }

    private static final String MODIFY_RULE = """
            package org.drools.journal.test;
            import org.drools.journal.test.ChronicleJournalledKieSessionIT.Ticket;

            rule "CloseTicket"
            when
                $t : Ticket(status == "open")
            then
                modify($t) {
                    setStatus("closed")
                }
            end
            """;

    @Test
    void modifyWithPrecompiler_chronicleBackend_writesModifyRecord() {
        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                MODIFY_RULE, registry, getClass().getClassLoader());
        KieBase kbase = new KieHelper().addContent(rewritten, ResourceType.DRL).build();
        String journalPath = tempDir.resolve("modify-write").toString();

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             KieSession session = kbase.newKieSession(null, journalEnv(storage, registry))) {
            session.insert(new Ticket("open"));
            session.fireAllRules();

            try (JournalScanner scanner = storage.scan(0)) {
                boolean found = false;
                while (scanner.hasNext()) {
                    if (scanner.next() instanceof ModifyRecord mr) {
                        assertThat(mr.lambdaClassRef()).isEqualTo("Rule_CloseTicket_modify_0");
                        found = true;
                    }
                }
                assertThat(found).isTrue();
            }
        }
    }

    @Test
    void modifyWithPrecompiler_chronicleBackend_restoresModifiedFact() {
        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                MODIFY_RULE, registry, getClass().getClassLoader());
        KieBase kbase = new KieHelper().addContent(rewritten, ResourceType.DRL).build();
        String journalPath = tempDir.resolve("modify-restore").toString();

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             KieSession session = kbase.newKieSession(null, journalEnv(storage, registry))) {
            session.insert(new Ticket("open"));
            session.fireAllRules();
        }

        ModifyLambdaRegistry freshRegistry = new ModifyLambdaRegistry();
        String freshRewritten = JournalDrlPrecompiler.rewrite(
                MODIFY_RULE, freshRegistry, getClass().getClassLoader());
        KieBase freshKbase = new KieHelper().addContent(freshRewritten, ResourceType.DRL).build();

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             KieSession session = freshKbase.newKieSession(null, journalEnv(storage, freshRegistry))) {
            Ticket restored = (Ticket) session.getObjects().iterator().next();
            assertThat(restored.getStatus()).isEqualTo("closed");
        }
    }

    @Test
    void insertAndFire_externalRefMode_restoresFactThroughLoader() {
        Map<String, Object> externalStore = new ConcurrentHashMap<>();
        KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();
        String journalPath = tempDir.resolve("external-ref").toString();

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
            Environment env = KieServices.get().newEnvironment();
            env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
                    .withJournalStorage(storage)
                    .withCompactionInterval(Duration.ZERO)
                    .withExternalRefStorage(
                            fact -> {
                                String key = "ext-" + fact;
                                externalStore.put(key, fact);
                                return key;
                            },
                            ref -> externalStore.get(ref.dbKey())));
            try (KieSession session = kbase.newKieSession(null, env)) {
                session.insert(42);
                session.fireAllRules();
            }
        }

        assertThat(externalStore).containsEntry("ext-42", 42);

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
            Environment env = KieServices.get().newEnvironment();
            env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
                    .withJournalStorage(storage)
                    .withCompactionInterval(Duration.ZERO)
                    .withExternalRefStorage(
                            fact -> "ext-" + fact,
                            ref -> externalStore.get(ref.dbKey())));
            try (KieSession session = kbase.newKieSession(null, env)) {
                assertThat(session.getObjects()).singleElement().isEqualTo(42);
                int fired = session.fireAllRules();
                assertThat(fired).isEqualTo(0);
            }
        }
    }
}

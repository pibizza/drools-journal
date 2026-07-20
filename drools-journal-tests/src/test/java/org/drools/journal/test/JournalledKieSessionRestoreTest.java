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

import java.time.Duration;

import org.drools.journal.api.DurableSessionOption;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.core.InMemoryJournalStorage;
import org.drools.journal.core.EmbedStrategy;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.internal.utils.KieHelper;

import static org.assertj.core.api.Assertions.assertThat;

class JournalledKieSessionRestoreTest {

    private static final String RULE = """
            package org.drools.journal.test
            rule "ProcessFact"
            when
                $i: Integer()
            then
            end
            """;

    private static final String LOGICAL_RULE = """
            package org.drools.journal.test
            rule "LogicalInserter"
            when
                $i: Integer()
            then
                drools.insertLogical("hello");
            end
            """;

    private static Environment journalEnv(final JournalStorage storage) {
        Environment env = KieServices.get().newEnvironment();
        env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
                .withJournalStorage(storage)
                .withCompactionInterval(Duration.ZERO));
        return env;
    }

    @Test
    void open_fromEmptyStorage_sessionIsEmpty() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (KieSession session = new KieHelper().addContent(RULE, ResourceType.DRL)
                .build().newKieSession(null, journalEnv(storage))) {
            assertThat(session.getObjects()).isEmpty();
        }
    }

    @Test
    void open_fromJournalWithSurvivingFact_factIsInWorkingMemory() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, new EmbedStrategy().store(42, null));
        storage.safepoint();

        try (KieSession session = new KieHelper().addContent(RULE, ResourceType.DRL)
                .build().newKieSession(null, journalEnv(storage))) {
            assertThat(session.getObjects()).singleElement().isEqualTo(42);
        }
    }

    @Test
    void open_fromJournalWithAlreadyFiredRule_ruleDoesNotFireAgain() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, new EmbedStrategy().store(42, null));
        storage.ruleMatch(1L, "org.drools.journal.test", "ProcessFact", new long[]{1L});
        storage.safepoint();

        try (KieSession session = new KieHelper().addContent(RULE, ResourceType.DRL)
                .build().newKieSession(null, journalEnv(storage))) {
            assertThat(session.fireAllRules()).isZero();
        }
    }

    @Test
    void open_fromJournalWithRetractedFact_sessionIsEmpty() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, new EmbedStrategy().store(42, null));
        storage.retract(1L);
        storage.safepoint();

        try (KieSession session = new KieHelper().addContent(RULE, ResourceType.DRL)
                .build().newKieSession(null, journalEnv(storage))) {
            assertThat(session.getObjects()).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // End-to-end restore: Session 1 runs → Session 2 restores from same storage
    // -------------------------------------------------------------------------

    @Test
    void endToEnd_insertAndFire_restoredSessionHasFactAndRuleDoesNotFireAgain() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();

        try (KieSession session1 = kbase.newKieSession(null, journalEnv(storage))) {
            session1.insert(42);
            session1.fireAllRules();
        }

        try (KieSession session2 = kbase.newKieSession(null, journalEnv(storage))) {
            assertThat(session2.getObjects()).singleElement().isEqualTo(42);
            assertThat(session2.fireAllRules()).isZero();
        }
    }

    @Test
    void endToEnd_insertRetractFire_restoredSessionIsEmpty() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();

        try (KieSession session1 = kbase.newKieSession(null, journalEnv(storage))) {
            FactHandle handle = session1.insert(42);
            session1.delete(handle);
            session1.fireAllRules();
        }

        try (KieSession session2 = kbase.newKieSession(null, journalEnv(storage))) {
            assertThat(session2.getObjects()).isEmpty();
            assertThat(session2.fireAllRules()).isZero();
        }
    }

    @Test
    void endToEnd_multipleSessions_eachResumesCorrectly() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();

        try (KieSession session1 = kbase.newKieSession(null, journalEnv(storage))) {
            session1.insert(1);
            session1.fireAllRules();
        }

        try (KieSession session2 = kbase.newKieSession(null, journalEnv(storage))) {
            assertThat(session2.getObjects()).singleElement().isEqualTo(1);
            session2.insert(2);
            session2.fireAllRules();
        }

        try (KieSession session3 = kbase.newKieSession(null, journalEnv(storage))) {
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> objects3 = (java.util.Collection<Object>) session3.getObjects();
            assertThat(objects3).containsExactlyInAnyOrder(1, 2);
            assertThat(session3.fireAllRules()).isZero();
        }
    }

    @Test
    void open_fromJournalWithLogicalFact_retractingSupportRemovesLogicalFact() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, new EmbedStrategy().store(42, null));
        storage.ruleMatch(1L, "org.drools.journal.test", "LogicalInserter", new long[]{1L});
        storage.insertLogical(2L, new EmbedStrategy().store("hello", null), 1L);
        storage.safepoint();

        try (KieSession session = new KieHelper().addContent(LOGICAL_RULE, ResourceType.DRL)
                .build().newKieSession(null, journalEnv(storage))) {
            Object integer = session.getObjects(o -> o instanceof Integer).iterator().next();
            session.delete(session.getFactHandle(integer));
            session.fireAllRules();
            assertThat(session.getObjects()).isEmpty();
        }
    }
}

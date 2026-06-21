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

import org.drools.journal.core.InMemoryJournalStorage;
import org.drools.journal.core.JournalledKieSession;
import org.drools.journal.core.JournalledSessionFactory;
import org.drools.journal.core.EmbedStrategy;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
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

    @Test
    void open_fromEmptyStorage_sessionIsEmpty() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            assertThat(session.getObjects()).isEmpty();
        }
    }

    @Test
    void open_fromJournalWithSurvivingFact_factIsInWorkingMemory() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, new EmbedStrategy().store(42, null), false, -1L);
        storage.safepoint();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            assertThat(session.getObjects()).singleElement().isEqualTo(42);
        }
    }

    @Test
    void open_fromJournalWithAlreadyFiredRule_ruleDoesNotFireAgain() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, new EmbedStrategy().store(42, null), false, -1L);
        storage.ruleMatch(1L, "org.drools.journal.test", "ProcessFact", new long[]{1L});
        storage.safepoint();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            assertThat(session.fireAllRules()).isZero();
        }
    }

    @Test
    void open_fromJournalWithRetractedFact_sessionIsEmpty() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, new EmbedStrategy().store(42, null), false, -1L);
        storage.retract(1L);
        storage.safepoint();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
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

        // Session 1: insert a fact and fire
        try (JournalledKieSession session1 = JournalledSessionFactory.open(kbase, storage)) {
            session1.insert(42);
            session1.fireAllRules();
        }

        // Session 2: open on the same storage — should restore working memory
        try (JournalledKieSession session2 = JournalledSessionFactory.open(kbase, storage)) {
            assertThat(session2.getObjects()).singleElement().isEqualTo(42);
            // Rule already fired for this fact — should not fire again
            assertThat(session2.fireAllRules()).isZero();
        }
    }

    @Test
    void endToEnd_insertRetractFire_restoredSessionIsEmpty() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();

        // Session 1: insert, retract, fire
        try (JournalledKieSession session1 = JournalledSessionFactory.open(kbase, storage)) {
            FactHandle handle = session1.insert(42);
            session1.delete(handle);
            session1.fireAllRules();
        }

        // Session 2: working memory should be empty
        try (JournalledKieSession session2 = JournalledSessionFactory.open(kbase, storage)) {
            assertThat(session2.getObjects()).isEmpty();
            assertThat(session2.fireAllRules()).isZero();
        }
    }

    @Test
    void endToEnd_multipleSessions_eachResumesCorrectly() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();

        // Session 1: insert fact 1, fire
        try (JournalledKieSession session1 = JournalledSessionFactory.open(kbase, storage)) {
            session1.insert(1);
            session1.fireAllRules();
        }

        // Session 2: restore, insert fact 2, fire
        try (JournalledKieSession session2 = JournalledSessionFactory.open(kbase, storage)) {
            assertThat(session2.getObjects()).singleElement().isEqualTo(1);
            session2.insert(2);
            session2.fireAllRules();
        }

        // Session 3: both facts present, neither rule fires again
        try (JournalledKieSession session3 = JournalledSessionFactory.open(kbase, storage)) {
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> objects3 = (java.util.Collection<Object>) session3.getObjects();
            assertThat(objects3).containsExactlyInAnyOrder(1, 2);
            assertThat(session3.fireAllRules()).isZero();
        }
    }

    @Test
    void open_fromJournalWithLogicalFact_retractingSupportRemovesLogicalFact() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, new EmbedStrategy().store(42, null), false, -1L);
        storage.ruleMatch(1L, "org.drools.journal.test", "LogicalInserter", new long[]{1L});
        storage.insert(2L, new EmbedStrategy().store("hello", null), true, 1L);
        storage.safepoint();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(LOGICAL_RULE, ResourceType.DRL).build(), storage)) {
            Object integer = session.getObjects(o -> o instanceof Integer).iterator().next();
            session.delete(session.getFactHandle(integer));
            session.fireAllRules();
            assertThat(session.getObjects()).isEmpty();
        }
    }
}

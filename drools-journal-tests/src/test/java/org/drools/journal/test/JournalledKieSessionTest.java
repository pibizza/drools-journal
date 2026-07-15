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

import java.io.Serializable;
import java.time.Duration;

import org.drools.journal.core.InMemoryJournalStorage;
import org.drools.journal.core.JournalDrlPrecompiler;
import org.drools.journal.core.JournalledKieSession;
import org.drools.journal.core.JournalledSessionFactory;
import org.drools.journal.core.ModifyLambdaRegistry;
import org.junit.jupiter.api.Test;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.internal.utils.KieHelper;

import static org.assertj.core.api.Assertions.assertThat;

class JournalledKieSessionTest {

    public static class Ticket implements Serializable {
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
    void insertAndFire_singleFact_producesInsertMatchAndSafepoint() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            session.insert(42);
            session.fireAllRules();
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(42)
                MATCH  id=1  pkg=org.drools.journal.test  rule=ProcessFact  facts=[1]
                SAFEPOINT  seq=0
                """);
    }

    @Test
    void retract_insertedFact_producesRetractRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            FactHandle handle = session.insert(42);
            session.delete(handle);
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(42)
                RETRACT  id=1
                """);
    }

    @Test
    void update_insertedFact_producesInsertSnapshotWithSameHandleId() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            FactHandle handle = session.insert(42);
            session.update(handle, 99);
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(42)
                INSERT  id=1  Integer(99)
                """);
    }

    @Test
    void fireAllRules_multipleMatches_matchIdsAreMonotonicallyIncreasing() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            session.insert(1);
            session.insert(2);
            session.fireAllRules();
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(1)
                INSERT  id=2  Integer(2)
                MATCH  id=1  pkg=org.drools.journal.test  rule=ProcessFact  facts=[1]
                MATCH  id=2  pkg=org.drools.journal.test  rule=ProcessFact  facts=[2]
                SAFEPOINT  seq=0
                """);
    }

    @Test
    void open_withDurationZero_disposeCompletesCleanly() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage,
                Duration.ZERO);
        session.dispose();

        boolean compactorThreadAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "drools-journal-compactor".equals(t.getName()));
        assertThat(compactorThreadAlive).isFalse();
    }

    @Test
    void insertLogical_duringRuleFiring_recordsLogicalFlagAndJustifyingMatchId() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(LOGICAL_RULE, ResourceType.DRL).build(), storage)) {
            session.insert(42);
            session.fireAllRules();
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(42)
                INSERT  id=2  logical  justifiedBy=1  String(hello)
                MATCH  id=1  pkg=org.drools.journal.test  rule=LogicalInserter  facts=[1]
                SAFEPOINT  seq=0
                """);
    }

    @Test
    void modifyWithPrecompiler_firesRule_writesModifyRecord() {
        String drl = """
                package org.drools.journal.test;
                import org.drools.journal.test.JournalledKieSessionTest.Ticket;

                rule "CloseTicket"
                when
                    $t : Ticket(status == "open")
                then
                    modify($t) {
                        setStatus("closed")
                    }
                end
                """;

        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                drl, registry, getClass().getClassLoader());

        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(rewritten, ResourceType.DRL).build(),
                storage, registry, Duration.ZERO)) {
            session.insert(new Ticket("open"));
            session.fireAllRules();
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Ticket(open)
                MODIFY  id=1  lambda=Rule_CloseTicket_modify_0
                MATCH  id=1  pkg=org.drools.journal.test  rule=CloseTicket  facts=[1]
                SAFEPOINT  seq=0
                """);
    }
}

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
import org.junit.jupiter.api.Test;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.internal.utils.KieHelper;

import static org.assertj.core.api.Assertions.assertThat;

class JournalledKieSessionTest {

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
    void insert_and_fire_produce_journal_records() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.create(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            session.insert(42);
            session.fireAllRules();
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(42)
                MATCH  id=1  rule=ProcessFact  facts=[1]
                """);
    }

    @Test
    void retract_produces_retract_record() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.create(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            final FactHandle handle = session.insert(42);
            session.delete(handle);
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(42)
                RETRACT  id=1
                """);
    }

    @Test
    void update_produces_insert_snapshot_with_same_handle_id() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.create(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            final FactHandle handle = session.insert(42);
            session.update(handle, 99);
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(42)
                INSERT  id=1  Integer(99)
                """);
    }

    @Test
    void insertLogical_records_logical_flag_and_justifying_match_id() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.create(
                new KieHelper().addContent(LOGICAL_RULE, ResourceType.DRL).build(), storage)) {
            session.insert(42);
            session.fireAllRules();
        }

        assertThat(storage).hasToString("""
                INSERT  id=1  Integer(42)
                INSERT  id=2  logical  justifiedBy=1  String(hello)
                MATCH  id=1  rule=LogicalInserter  facts=[1]
                """);
    }
}

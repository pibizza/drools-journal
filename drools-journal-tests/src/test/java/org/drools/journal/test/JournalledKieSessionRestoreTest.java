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

import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;
import org.drools.journal.core.InMemoryJournalStorage;
import org.drools.journal.core.JournalledKieSession;
import org.drools.journal.core.JournalledSessionFactory;
import org.drools.journal.core.JournalPayloadBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.io.ResourceType;
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

    @Test
    void open_fromEmptyStorage_sessionIsEmpty() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            assertThat(session.getObjects()).isEmpty();
        }
    }

    @Test
    void open_fromJournalWithSurvivingFact_factIsInWorkingMemory() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed(42)));
        storage.append(new SafepointRecord(1L, 0L));

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            assertThat(session.getObjects()).singleElement().isEqualTo(42);
        }
    }

    @Test
    void open_fromJournalWithAlreadyFiredRule_ruleDoesNotFireAgain() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed(42)));
        storage.append(new RuleMatchRecord(1L, "org.drools.journal.test", "ProcessFact", new long[]{1L}));
        storage.append(new SafepointRecord(1L, 0L));

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            assertThat(session.fireAllRules()).isZero();
        }
    }

    @Test
    void open_fromJournalWithRetractedFact_sessionIsEmpty() {
        final InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed(42)));
        storage.append(new RetractRecord(1L));
        storage.append(new SafepointRecord(1L, 0L));

        try (JournalledKieSession session = JournalledSessionFactory.open(
                new KieHelper().addContent(RULE, ResourceType.DRL).build(), storage)) {
            assertThat(session.getObjects()).isEmpty();
        }
    }
}

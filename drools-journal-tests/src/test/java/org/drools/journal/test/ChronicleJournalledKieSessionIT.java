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

import org.drools.journal.chronicle.ChronicleJournalStorage;
import org.drools.journal.core.JournalledKieSession;
import org.drools.journal.core.JournalledSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;

import static org.assertj.core.api.Assertions.assertThat;

class ChronicleJournalledKieSessionIT {

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
    void openFireDispose_thenReopen_restoresFact_andRuleDoesNotRefire() {
        final KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();
        final String journalPath = tempDir.resolve("chronicle-journal").toString();

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
            session.insert(42);
            final int fired = session.fireAllRules();
            assertThat(fired).isEqualTo(1);
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
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
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
            session.insert(1);
            session.fireAllRules();
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
            assertThat(session.getObjects()).singleElement().isEqualTo(1);
            session.insert(2);
            session.fireAllRules();
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
            final java.util.Collection<Object> objects = (java.util.Collection<Object>) session.getObjects();
            assertThat(objects).containsExactlyInAnyOrder(1, 2);
        }
    }
}

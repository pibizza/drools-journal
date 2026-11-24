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

import java.util.Collection;
import java.util.Set;

import org.drools.journal.api.DurabilityMode;
import org.drools.journal.api.DurableSessionOption;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.core.CompactionCoordinator;
import org.drools.journal.core.InMemoryJournalStorage;
import org.drools.journal.core.EmbedStrategy;
import org.drools.journal.core.JournalledKieSession;
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

    private static Environment snapshotEnv(final JournalStorage storage) {
        Environment env = KieServices.get().newEnvironment();
        env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
                .withJournalStorage(storage)
                .withMode(DurabilityMode.SNAPSHOT)
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
    void endToEnd_firedRuleSurvivesCompaction_restoredSessionDoesNotRefire() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();

        try (KieSession session1 = kbase.newKieSession(null, journalEnv(storage))) {
            session1.insert(42);
            session1.fireAllRules();   // page "0": insert(42) + RuleMatchRecord(ProcessFact,[42])
        }

        // Force-merge the sealed page. With the #72 fix the fired match survives the merge, so
        // the restored already-fired set still suppresses it.
        CompactionCoordinator.onDemand(storage).compact(Set.of("0"));

        try (KieSession session2 = kbase.newKieSession(null, journalEnv(storage))) {
            assertThat(session2.getObjects()).singleElement().isEqualTo(42);
            assertThat(session2.fireAllRules()).isZero(); // must not re-fire
        }
    }

    @Test
    void compactNow_snapshotModeSeveralSealedPages_restoresLiveFactsFromSingleMergedPage() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();

        try (KieSession session1 = kbase.newKieSession(null, snapshotEnv(storage))) {
            session1.insert(1);
            session1.fireAllRules();                 // page "0": insert(1) + match
            FactHandle handle2 = session1.insert(2);
            session1.fireAllRules();                 // page "1": insert(2) + match
            session1.insert(3);
            session1.fireAllRules();                 // page "2": insert(3) + match
            session1.delete(handle2);
            session1.fireAllRules();                 // page "3": retract(2)

            // SNAPSHOT policy: merge every sealed page, regardless of sparsity.
            ((JournalledKieSession) session1).compactNow();
        }

        // All sealed pages collapsed into a single merged snapshot page.
        assertThat(storage.indexStatus().livePageIds()).singleElement()
                .satisfies(id -> assertThat(id).startsWith("m-"));

        // Restore deliberately uses JOURNAL mode: the snapshot is an ordinary merged page.
        // SNAPSHOT-specific restore behaviour is #65.
        try (KieSession session2 = kbase.newKieSession(null, journalEnv(storage))) {
            @SuppressWarnings("unchecked")
            Collection<Object> objects = (Collection<Object>) session2.getObjects();
            assertThat(objects).containsExactlyInAnyOrder(1, 3);   // retracted fact 2 dropped
            assertThat(session2.fireAllRules()).isZero();          // live matches carried → no re-fire
        }
    }

    @Test
    void endToEnd_logicalFactLosesSupportThenCompacted_restoresEmpty() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        KieBase kbase = new KieHelper().addContent(LOGICAL_RULE, ResourceType.DRL).build();

        try (KieSession session1 = kbase.newKieSession(null, journalEnv(storage))) {
            FactHandle handle = session1.insert(42);
            session1.fireAllRules();   // page "0": insert(42) + match + insertLogical("hello")
            session1.delete(handle);
            session1.fireAllRules();   // page "1": retract(42) + TMS retract("hello")
        }

        // Retracting the non-logical support cascaded (via TMS) into retracting the logical
        // fact; both pages are now fully dead and get merged away. Restore must be empty.
        CompactionCoordinator.onDemand(storage).compact(Set.of("0", "1"));

        try (KieSession session2 = kbase.newKieSession(null, journalEnv(storage))) {
            assertThat(session2.getObjects()).isEmpty();
            assertThat(session2.fireAllRules()).isZero();
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

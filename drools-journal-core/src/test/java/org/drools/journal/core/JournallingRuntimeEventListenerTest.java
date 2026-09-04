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
package org.drools.journal.core;

import org.drools.core.common.DefaultFactHandle;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.RetractRecord;
import org.junit.jupiter.api.Test;
import org.kie.api.definition.rule.Rule;
import org.kie.api.event.rule.ObjectDeletedEvent;
import org.kie.api.event.rule.ObjectInsertedEvent;
import org.kie.api.event.rule.ObjectUpdatedEvent;
import org.kie.api.runtime.KieRuntime;
import org.kie.api.runtime.rule.FactHandle;

import static org.assertj.core.api.Assertions.assertThat;

class JournallingRuntimeEventListenerTest {

    @Test
    void objectInserted_withNoActiveActivation_appendsNonLogicalInsertRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        JournallingRuntimeEventListener listener = new JournallingRuntimeEventListener(
                storage, new EmbedStrategy());

        DefaultFactHandle handle = new DefaultFactHandle(42L, "hello");
        listener.objectInserted(insertedEvent(handle, "hello"));

        assertThat(storage.size()).isEqualTo(1);
        InsertRecord record = (InsertRecord) storage.currentPage().records.get(0);
        assertThat(record.factHandleId()).isEqualTo(42L);
        assertThat(record.logical()).isFalse();
        assertThat(record.justifyingRuleMatchId()).isEqualTo(-1L);
    }

    @Test
    void objectDeleted_appendsRetractRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        JournallingRuntimeEventListener listener = new JournallingRuntimeEventListener(
                storage, new EmbedStrategy());

        DefaultFactHandle handle = new DefaultFactHandle(42L, "hello");
        listener.objectDeleted(deletedEvent(handle, "hello"));

        assertThat(storage.size()).isEqualTo(1);
        RetractRecord record = (RetractRecord) storage.currentPage().records.get(0);
        assertThat(record.factHandleId()).isEqualTo(42L);
    }

    @Test
    void objectInserted_withActiveActivation_appendsLogicalInsertRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        JournallingRuntimeEventListener listener = new JournallingRuntimeEventListener(
                storage, new EmbedStrategy());

        listener.setCurrentActivationId(99L);
        DefaultFactHandle handle = new DefaultFactHandle(42L, "hello");
        listener.objectInserted(insertedEvent(handle, "hello"));

        InsertRecord record = (InsertRecord) storage.currentPage().records.get(0);
        assertThat(record.logical()).isTrue();
        assertThat(record.justifyingRuleMatchId()).isEqualTo(99L);
    }

    @Test
    void objectUpdated_appendsNonLogicalInsertRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        JournallingRuntimeEventListener listener = new JournallingRuntimeEventListener(
                storage, new EmbedStrategy());

        DefaultFactHandle handle = new DefaultFactHandle(42L, "updated");
        listener.objectUpdated(updatedEvent(handle, "updated", "old"));

        assertThat(storage.size()).isEqualTo(1);
        InsertRecord record = (InsertRecord) storage.currentPage().records.get(0);
        assertThat(record.factHandleId()).isEqualTo(42L);
        assertThat(record.logical()).isFalse();
        assertThat(record.justifyingRuleMatchId()).isEqualTo(-1L);
    }

    @Test
    void objectUpdated_withStagedModify_writesModifyRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        JournallingRuntimeEventListener listener = new JournallingRuntimeEventListener(
                storage, new EmbedStrategy());

        listener.stageModify("Rule_Test_modify_0", new Object[]{ 30, "Bob" });

        DefaultFactHandle handle = new DefaultFactHandle(42L, "updated");
        listener.objectUpdated(updatedEvent(handle, "updated", "old"));

        assertThat(storage.size()).isEqualTo(1);
        ModifyRecord record = (ModifyRecord) storage.currentPage().records.get(0);
        assertThat(record.factHandleId()).isEqualTo(42L);
        assertThat(record.lambdaClassRef()).isEqualTo("Rule_Test_modify_0");
    }

    @Test
    void objectUpdated_withoutStagedModify_writesInsertRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        JournallingRuntimeEventListener listener = new JournallingRuntimeEventListener(
                storage, new EmbedStrategy());

        DefaultFactHandle handle = new DefaultFactHandle(42L, "updated");
        listener.objectUpdated(updatedEvent(handle, "updated", "old"));

        assertThat(storage.size()).isEqualTo(1);
        InsertRecord record = (InsertRecord) storage.currentPage().records.get(0);
        assertThat(record.factHandleId()).isEqualTo(42L);
    }

    private static ObjectInsertedEvent insertedEvent(final FactHandle handle, final Object object) {
        return new ObjectInsertedEvent() {
            @Override public FactHandle getFactHandle() { return handle; }
            @Override public Object getObject() { return object; }
            @Override public Rule getRule() { return null; }
            @Override public KieRuntime getKieRuntime() { return null; }
        };
    }

    private static ObjectDeletedEvent deletedEvent(final FactHandle handle, final Object oldObject) {
        return new ObjectDeletedEvent() {
            @Override public FactHandle getFactHandle() { return handle; }
            @Override public Object getOldObject() { return oldObject; }
            @Override public Rule getRule() { return null; }
            @Override public KieRuntime getKieRuntime() { return null; }
        };
    }

    private static ObjectUpdatedEvent updatedEvent(final FactHandle handle, final Object object, final Object oldObject) {
        return new ObjectUpdatedEvent() {
            @Override public FactHandle getFactHandle() { return handle; }
            @Override public Object getObject() { return object; }
            @Override public Object getOldObject() { return oldObject; }
            @Override public Rule getRule() { return null; }
            @Override public KieRuntime getKieRuntime() { return null; }
        };
    }
}

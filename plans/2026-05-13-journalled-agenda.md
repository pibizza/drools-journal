# JournalledAgenda Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `JournalledAgenda` and `JournalledAgendaFactory` so that a `RuleMatchRecord` is appended after each rule activation, and the activation ID is available to `JournalledObjectStore` during the same RHS execution.

**Architecture:** `JournalledAgenda extends DefaultAgenda`. A private `AgendaEventListener` registered in the constructor intercepts `beforeMatchFired` / `afterMatchFired`. The store is reached at event time via the inherited `workingMemory` field: `workingMemory.getDefaultEntryPoint().getObjectStore()`, cast to `JournalledObjectStore`. No thread-local. `JournalledObjectStore` keeps its existing `setCurrentActivationId` / `clearCurrentActivationId` methods unchanged. `JournalledAgendaFactory` is a thin factory that holds a `JournalStorage` and creates `JournalledAgenda` instances.

**Tech Stack:** Java 17+, Drools internals (`DefaultAgenda`, `AgendaFactory`, `InternalMatch`, `InternalFactHandle`).

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `drools-journal-core/src/main/java/org/drools/journal/core/JournalledAgenda.java` | Extends `DefaultAgenda`; registers listener; appends `RuleMatchRecord`; sets/clears activation ID on store |
| Create | `drools-journal-core/src/main/java/org/drools/journal/core/JournalledAgendaFactory.java` | Implements `AgendaFactory`; holds `JournalStorage`; instantiates `JournalledAgenda` |

`JournalledObjectStore` is **not modified**. No test changes required.

---

## Task 1 — Implement `JournalledAgenda`

**Files:**
- Create: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledAgenda.java`

Reference: `ReliableAgenda` in `drools-reliability-core` for the constructor pattern. `DefaultAgenda` in `drools-kiesession` for the `workingMemory` field (declared `protected final` at line 98).

- [ ] **Step 1: Create the file**

```java
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

import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.impl.InternalRuleBase;
import org.drools.core.rule.accessor.FactHandleFactory;
import org.drools.core.rule.consequence.InternalMatch;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.kiesession.agenda.DefaultAgenda;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.AgendaEventListener;
import org.kie.api.event.rule.AgendaGroupPoppedEvent;
import org.kie.api.event.rule.AgendaGroupPushedEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.MatchCancelledEvent;
import org.kie.api.event.rule.MatchCreatedEvent;
import org.kie.api.event.rule.RuleFlowGroupActivatedEvent;
import org.kie.api.event.rule.RuleFlowGroupDeactivatedEvent;
import org.kie.api.runtime.rule.FactHandle;

import java.util.List;

// NOT thread-safe — Drools sessions fire on a single thread
public class JournalledAgenda extends DefaultAgenda {

    private final JournalStorage journal;
    private long nextActivationId = 0L;

    public JournalledAgenda(final InternalRuleBase kieBase,
                            final InternalWorkingMemory workingMemory,
                            final FactHandleFactory factHandleFactory,
                            final JournalStorage journal) {
        super(kieBase, workingMemory, factHandleFactory);
        this.journal = journal;
        workingMemory.getAgendaEventSupport().addEventListener(new JournallingListener());
    }

    private JournalledObjectStore store() {
        final Object store = workingMemory.getDefaultEntryPoint().getObjectStore();
        return store instanceof JournalledObjectStore ? (JournalledObjectStore) store : null;
    }

    private class JournallingListener implements AgendaEventListener {

        @Override
        public void beforeMatchFired(final BeforeMatchFiredEvent event) {
            final JournalledObjectStore store = store();
            if (store != null) {
                store.setCurrentActivationId(++nextActivationId);
            }
        }

        @Override
        public void afterMatchFired(final AfterMatchFiredEvent event) {
            final InternalMatch match = (InternalMatch) event.getMatch();
            final List<FactHandle> handles = match.getFactHandles(match.getTuple());
            final long[] ids = new long[handles.size()];
            for (int i = 0; i < handles.size(); i++) {
                ids[i] = ((InternalFactHandle) handles.get(i)).getId();
            }
            journal.append(new RuleMatchRecord(nextActivationId, match.getRule().getName(), ids));
            final JournalledObjectStore store = store();
            if (store != null) {
                store.clearCurrentActivationId();
            }
        }

        @Override public void matchCreated(final MatchCreatedEvent event) {}
        @Override public void matchCancelled(final MatchCancelledEvent event) {}
        @Override public void agendaGroupPopped(final AgendaGroupPoppedEvent event) {}
        @Override public void agendaGroupPushed(final AgendaGroupPushedEvent event) {}
        @Override public void beforeRuleFlowGroupActivated(final RuleFlowGroupActivatedEvent event) {}
        @Override public void afterRuleFlowGroupActivated(final RuleFlowGroupActivatedEvent event) {}
        @Override public void beforeRuleFlowGroupDeactivated(final RuleFlowGroupDeactivatedEvent event) {}
        @Override public void afterRuleFlowGroupDeactivated(final RuleFlowGroupDeactivatedEvent event) {}
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/pbizzarr/develop/kiegroups/drools/drools-journal
mvn compile -pl drools-journal-core -am -q
```

Expected: BUILD SUCCESS.

---

## Task 2 — Implement `JournalledAgendaFactory`

**Files:**
- Create: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledAgendaFactory.java`

Reference: `ReliableAgendaFactory` in `drools-reliability-core` for the factory pattern.

- [ ] **Step 1: Create the file**

```java
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

import org.drools.core.common.AgendaFactory;
import org.drools.core.common.InternalAgenda;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.impl.InternalRuleBase;
import org.drools.core.rule.accessor.FactHandleFactory;
import org.drools.journal.api.JournalStorage;

public class JournalledAgendaFactory implements AgendaFactory {

    private final JournalStorage journal;

    public JournalledAgendaFactory(final JournalStorage journal) {
        this.journal = journal;
    }

    @Override
    public InternalAgenda createAgenda(final InternalRuleBase kieBase,
                                       final InternalWorkingMemory workingMemory,
                                       final FactHandleFactory factHandleFactory) {
        return new JournalledAgenda(kieBase, workingMemory, factHandleFactory, journal);
    }
}
```

- [ ] **Step 2: Run all tests**

```bash
mvn test -pl drools-journal-core -q
```

Expected: all existing tests pass.

---

## Task 3 — Commit

- [ ] **Step 1: Commit**

```bash
cd /home/pbizzarr/develop/kiegroups/drools/drools-journal
git add drools-journal-core/src/main/java/org/drools/journal/core/JournalledAgenda.java \
        drools-journal-core/src/main/java/org/drools/journal/core/JournalledAgendaFactory.java
git commit -m "feat(core): add JournalledAgenda and JournalledAgendaFactory (Closes #9)"
```

# DurabilityMode API & Session Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a user-selectable `DurabilityMode` (`JOURNAL` default, `SNAPSHOT`) on `DurableSessionOption` and thread it to the `CompactionCoordinator`, with no behavioural change yet.

**Architecture:** Add an enum in `drools-journal-api`; add a builder method + accessor on `DurableSessionOption` defaulting to `JOURNAL`; give `CompactionCoordinator` a `DurabilityMode` field via a new 3-arg constructor (the existing 2-arg constructor delegates with `JOURNAL`); the SPI factory `JournalledRuntimeComponentFactory` passes `opt.getDurabilityMode()` when it builds the coordinator. The full-merge policy that *acts* on `SNAPSHOT` is a later issue (#64).

**Tech Stack:** Java 21, Maven (multi-module), JUnit 5, AssertJ.

**Spec:** `specs/2026-10-05-snapshot-mode-design.md` (Part A.4 — API surface; the mode becomes the selector the later strategy branches on).

**Issue:** #61 — SNAPSHOT durability mode — DurabilityMode API and session wiring (epic #38, branch `epic-snapshot-mode`).

## Global Constraints

- **Java 21**: `maven.compiler.release=21` in the parent POM. Builds fail on Java 17.
- **Build command**: always `mvn clean install` — never incremental. Focused test runs in this plan use `mvn -pl <module> -am clean test -Dtest=...` (still clean).
- **License header**: every new `.java` file starts with `Copyright (c) 2026 Drools Journal Authors` (Apache 2.0) — copy the header block verbatim from any existing source file (e.g. `DurableSessionOption.java` lines 1–15). Do **not** use the Apache Software Foundation boilerplate.
- **`final` usage**: `final` on fields and parameters only — never on local variables.
- **API module has no test tree**: `drools-journal-api/src/test` does not exist and this plan does not create one. All new tests live in `drools-journal-core/src/test` (core depends on api), matching current layout.
- **No behavioural change**: after this plan, a session with no mode set, or in `JOURNAL` mode, behaves exactly as today. `SNAPSHOT` selects the same cycle as `JOURNAL` until #64 lands.

## Review Focus

- **Unset mode** → must default to `DurabilityMode.JOURNAL`, never `null` (a null would NPE the coordinator branch in #64). Pinned in Task 1, Step 1.
- **`withMode(null)`** → must throw `IllegalArgumentException`, consistent with the other `with*` setters on `DurableSessionOption`. Pinned in Task 1, Step 1.
- **Existing 2-arg `CompactionCoordinator` callers** (`onDemand`, `CompactionCoordinatorTest`) → must keep compiling and default to `JOURNAL`. Pinned in Task 2, Step 1.

---

### Task 1: `DurabilityMode` enum + `DurableSessionOption` wiring

**Files:**
- Create: `drools-journal-api/src/main/java/org/drools/journal/api/DurabilityMode.java`
- Modify: `drools-journal-api/src/main/java/org/drools/journal/api/DurableSessionOption.java` (add field ~line 44, `withMode`/`getDurabilityMode` methods, extend `toString` ~line 139)
- Test: `drools-journal-core/src/test/java/org/drools/journal/core/DurabilityModeOptionTest.java` (new)

**Interfaces:**
- Consumes: nothing (new API).
- Produces:
  - `enum org.drools.journal.api.DurabilityMode { JOURNAL, SNAPSHOT }`
  - `DurableSessionOption.withMode(DurabilityMode mode) -> DurableSessionOption` (fluent; rejects null)
  - `DurableSessionOption.getDurabilityMode() -> DurabilityMode` (defaults to `JOURNAL`)

- [ ] **Step 1: Write the failing test**

Create `drools-journal-core/src/test/java/org/drools/journal/core/DurabilityModeOptionTest.java` (include the license header block):

```java
package org.drools.journal.core;

import org.drools.journal.api.DurabilityMode;
import org.drools.journal.api.DurableSessionOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurabilityModeOptionTest {

    @Test
    void getDurabilityMode_whenUnset_defaultsToJournal() {
        assertThat(DurableSessionOption.newSession().getDurabilityMode())
                .isEqualTo(DurabilityMode.JOURNAL);
    }

    @Test
    void withMode_snapshot_isReturnedByGetter() {
        DurableSessionOption opt = DurableSessionOption.newSession()
                .withMode(DurabilityMode.SNAPSHOT);
        assertThat(opt.getDurabilityMode()).isEqualTo(DurabilityMode.SNAPSHOT);
    }

    @Test
    void withMode_null_isRejected() {
        assertThatThrownBy(() -> DurableSessionOption.newSession().withMode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl drools-journal-core -am clean test -Dtest=DurabilityModeOptionTest`
Expected: COMPILE FAILURE — `DurabilityMode` and `withMode`/`getDurabilityMode` do not exist yet.

- [ ] **Step 3: Create the enum**

Create `drools-journal-api/src/main/java/org/drools/journal/api/DurabilityMode.java` (license header, then):

```java
package org.drools.journal.api;

/**
 * Session durability mode, selected via {@link DurableSessionOption#withMode}.
 *
 * <p>{@code JOURNAL_DELTA} (modify-as-lambda, Phase 5) is reserved and will be
 * added when that mode lands; it is intentionally not declared yet.
 */
public enum DurabilityMode {

    /** Full append-only log with compaction — the default, current behaviour. */
    JOURNAL,

    /**
     * STORES_ONLY-parity mode offering O(live-facts) restore. Semantically a
     * JOURNAL session whose compaction policy periodically merges all live pages
     * into one snapshot page (see issue #64).
     */
    SNAPSHOT;
}
```

- [ ] **Step 4: Add the field, builder, accessor, and toString to `DurableSessionOption`**

In `DurableSessionOption.java`, add the field alongside the others (after line 44):

```java
    private DurabilityMode durabilityMode = DurabilityMode.JOURNAL;
```

Add the accessor and fluent setter (place near `getModifyLambdaRegistry`/`withModifyLambdaRegistry`, before `toString`):

```java
    public DurabilityMode getDurabilityMode() {
        return durabilityMode;
    }

    public DurableSessionOption withMode(final DurabilityMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("DurabilityMode must not be null");
        }
        this.durabilityMode = mode;
        return this;
    }
```

Extend `toString()` to include the mode:

```java
    @Override
    public String toString() {
        return "DurableSessionOption(objectStorageMode=" + objectStorageMode +
                ", durabilityMode=" + durabilityMode +
                ", compactionInterval=" + compactionInterval +
                ", journalStorage=" + journalStorage + ")";
    }
```

`DurabilityMode` is in the same package, so no import is needed.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -pl drools-journal-core -am clean test -Dtest=DurabilityModeOptionTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add drools-journal-api/src/main/java/org/drools/journal/api/DurabilityMode.java \
        drools-journal-api/src/main/java/org/drools/journal/api/DurableSessionOption.java \
        drools-journal-core/src/test/java/org/drools/journal/core/DurabilityModeOptionTest.java
git commit -m "feat: add DurabilityMode and DurableSessionOption.withMode

Refs #61"
```

---

### Task 2: Thread `DurabilityMode` through `CompactionCoordinator` and the SPI factory

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java` (fields ~line 45–55, add 3-arg constructor, add `mode()` accessor)
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledRuntimeComponentFactory.java:101` (pass the mode)
- Test: `drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java` (add two tests)

**Interfaces:**
- Consumes: `DurabilityMode` and `DurableSessionOption.getDurabilityMode()` from Task 1.
- Produces:
  - `CompactionCoordinator(JournalStorage storage, Duration mergingInterval, DurabilityMode mode)` — new primary constructor
  - `CompactionCoordinator(JournalStorage storage, Duration mergingInterval)` — retained, delegates with `DurabilityMode.JOURNAL`
  - `CompactionCoordinator.mode() -> DurabilityMode` — package-private accessor (used by #64 and tests)

- [ ] **Step 1: Write the failing tests**

Add to `CompactionCoordinatorTest.java` (add `import org.drools.journal.api.DurabilityMode;`):

```java
    @Test
    void twoArgConstructor_defaultsToJournalMode() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        assertThat(CompactionCoordinator.onDemand(storage).mode())
                .isEqualTo(DurabilityMode.JOURNAL);
    }

    @Test
    void threeArgConstructor_retainsGivenMode() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        CompactionCoordinator coordinator =
                new CompactionCoordinator(storage, Duration.ZERO, DurabilityMode.SNAPSHOT);
        assertThat(coordinator.mode()).isEqualTo(DurabilityMode.SNAPSHOT);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl drools-journal-core -am clean test -Dtest=CompactionCoordinatorTest`
Expected: COMPILE FAILURE — `mode()` and the 3-arg constructor do not exist.

- [ ] **Step 3: Add the field, constructor, and accessor to `CompactionCoordinator`**

Add the import near the other api imports:

```java
import org.drools.journal.api.DurabilityMode;
```

Add the field next to the existing ones (after line 47 `retirementInterval`):

```java
    private final DurabilityMode mode;
```

Replace the existing 2-arg constructor (lines 51–55) with a delegating 2-arg plus a new 3-arg primary:

```java
    CompactionCoordinator(final JournalStorage storage, final Duration mergingInterval) {
        this(storage, mergingInterval, DurabilityMode.JOURNAL);
    }

    CompactionCoordinator(final JournalStorage storage, final Duration mergingInterval,
                          final DurabilityMode mode) {
        this.storage = storage;
        this.mergingInterval = mergingInterval;
        this.retirementInterval = Duration.ZERO;
        this.mode = mode;
    }
```

Add the accessor (place it just after the constructors / `onDemand`):

```java
    DurabilityMode mode() {
        return mode;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl drools-journal-core -am clean test -Dtest=CompactionCoordinatorTest`
Expected: PASS (all existing cases plus the two new ones).

- [ ] **Step 5: Wire the mode through the SPI factory**

In `JournalledRuntimeComponentFactory.java`, change the coordinator construction at line 101 from:

```java
        CompactionCoordinator coordinator = new CompactionCoordinator(storage, interval);
```

to:

```java
        CompactionCoordinator coordinator =
                new CompactionCoordinator(storage, interval, opt.getDurabilityMode());
```

No other change — `opt` is already in scope in `createJournalledSession`.

- [ ] **Step 6: Full build to verify nothing regressed**

Run: `mvn clean install`
Expected: BUILD SUCCESS. The default (`JOURNAL`) path is unchanged, so all existing unit tests and integration tests pass. (End-to-end exercise of the `SNAPSHOT` wiring arrives with the behavioural change in #64 and the parity ITs in #62.)

- [ ] **Step 7: Commit**

```bash
git add drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java \
        drools-journal-core/src/main/java/org/drools/journal/core/JournalledRuntimeComponentFactory.java \
        drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java
git commit -m "feat: thread DurabilityMode to CompactionCoordinator via SPI factory

Closes #61"
```

---

## Notes for the executor

- The design doc (Part B) describes the *future* full-merge behaviour; **do not implement any cycle-policy branch here** — this issue is purely the mode selector + plumbing. `runMergingCycle()` is untouched.
- `CompactionCoordinator` constructors are package-private by design; keep them package-private. The `mode()` accessor is package-private for the same reason (consumed by sibling classes/tests in the same package).
- There is deliberately no `JOURNAL_DELTA` enum constant yet — it is reserved in the enum javadoc only.

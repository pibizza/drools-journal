# JournalPosition and Incremental Liveness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `long` positions with `JournalPosition(pageId, offset)` across the SPI, enable Chronicle's `moveToIndex()` for mid-stream resume, and make compaction liveness scanning incremental.

**Architecture:** The change is a bottom-up SPI migration: new type in API, update InMemory (test infra), update Chronicle, update consumers (RestoreEngine, CompactionCoordinator), then add incremental liveness as a new capability on top.

**Tech Stack:** Java 21, Chronicle Queue 2026.4 (`ExcerptTailer.moveToIndex()`), JUnit 5, AssertJ

## Global Constraints

- Java 21 (`maven.compiler.release=21`)
- Always `mvn clean install` — never incremental builds
- License header: `Copyright (c) 2026 Drools Journal Authors` (Apache 2.0)
- No `final` on local variables
- Test naming: `action_context_expectedResult`
- Step-by-step implementation with review after each piece

---

### Task 1: JournalPosition record and SPI signature changes

**Files:**
- Create: `drools-journal-api/src/main/java/org/drools/journal/api/JournalPosition.java`
- Modify: `drools-journal-api/src/main/java/org/drools/journal/api/JournalStorage.java`
- Modify: `drools-journal-api/src/main/java/org/drools/journal/api/JournalScanner.java`

**Interfaces:**
- Consumes: nothing (foundation task)
- Produces: `JournalPosition(String pageId, long offset)` with `JournalPosition.START`. All `JournalStorage` write methods return `JournalPosition`. `scan(JournalPosition)` replaces `scan(long)`. `JournalScanner.position()` returns `JournalPosition`. `latestPosition()` returns `JournalPosition`.

- [ ] **Step 1: Create JournalPosition record**

```java
package org.drools.journal.api;

public record JournalPosition(String pageId, long offset) {
    public static final JournalPosition START = new JournalPosition(null, 0);
}
```

- [ ] **Step 2: Update JournalScanner interface**

Change `position()` return type from `long` to `JournalPosition`. Update the javadoc to reflect resume-point semantics:

```java
/**
 * Returns a position suitable for resuming scanning. Passing this value to
 * {@link JournalStorage#scan(JournalPosition)} will begin reading at the
 * record immediately following the last record returned by {@link #next()}.
 * If {@link #next()} has not yet been called, returns the starting position
 * of this scanner.
 */
JournalPosition position();
```

This changes the contract from "position of the last returned record" to "resume point." Chronicle's `tailer.index()` already returns the next-to-read index after a read, so this aligns with the underlying implementation. No existing caller uses `position()` for anything other than the synthetic counter, so the change is safe.

- [ ] **Step 3: Update JournalStorage interface**

Change all write method return types from `long` to `JournalPosition`:
- `JournalPosition insert(long factHandleId, Payload payload)`
- `JournalPosition insertLogical(long factHandleId, Payload payload, long justifyingRuleMatchId)`
- `JournalPosition retract(long factHandleId)`
- `JournalPosition modify(long factHandleId, String lambdaClassRef, byte[] params)`
- `JournalPosition ruleMatch(long id, String packageName, String ruleName, long[] factHandleIds)`
- `JournalPosition compactionPrepare(String preparingPageId, String[] replacedPageIds)`
- `JournalPosition compactionCommit(String mergedPageId, String[] replacedPageIds)`
- `JournalPosition latestPosition()` (was `long latestPosition()`)
- `JournalScanner scan(JournalPosition fromPosition)` (was `scan(long)`)

Update javadoc: `latestPosition()` returns `JournalPosition.START` when the journal is empty (replaces `-1`). `scan(JournalPosition.START)` scans from the beginning (replaces `scan(0)`).

- [ ] **Step 4: Verify the API module compiles in isolation**

Run: `mvn clean compile -pl drools-journal-api`

Expected: SUCCESS — the API module has no implementations, so the interface changes compile on their own. Downstream modules will fail until updated in later tasks.

- [ ] **Step 5: Commit**

```
feat: introduce JournalPosition record and update SPI signatures

Refs #<issue>
```

---

### Task 2: Migrate InMemoryJournalStorage and InMemoryJournalScanner

**Files:**
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalStorage.java`
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalScanner.java`

**Interfaces:**
- Consumes: `JournalPosition(String pageId, long offset)`, `JournalPosition.START`
- Produces: `InMemoryJournalStorage` and `InMemoryJournalScanner` implementing updated SPI. All write methods return `JournalPosition(currentPage.id, recordIndexWithinPage)`. `scan(JournalPosition)` supports mid-stream resume by page ID + offset. `latestPosition()` returns `JournalPosition(currentPage.id, currentPage.records.size() - 1)` or `JournalPosition.START` when empty.

- [ ] **Step 1: Update InMemoryJournalScanner**

Change constructor to accept `JournalPosition fromPosition` instead of `long fromPosition`. The scanner needs to track per-page offsets instead of a flat global index. Replace `long currentPosition` with page-aware tracking:

```java
InMemoryJournalScanner(List<JournalRecord> records,
                       List<String> pageIdsPerRecord,
                       List<Integer> pageOffsets,
                       JournalPosition fromPosition)
```

`position()` returns `new JournalPosition(currentPageId, offsetWithinCurrentPage)`.

`currentPageId` is read from `pageIdsPerRecord` at the current cursor position. `offsetWithinCurrentPage` is read from `pageOffsets` at the current cursor position (the page-local offset pre-computed by the storage when flattening).

The `fromPosition` logic: if `START`, start at index 0. Otherwise, find the first record whose pageId matches `fromPosition.pageId()` and whose page-offset equals `fromPosition.offset()`, and start there. If not found (page retired), fall back to index 0.

- [ ] **Step 2: Update InMemoryJournalStorage**

Change all write method return types from `long` to `JournalPosition`. Each `append()` returns `new JournalPosition(currentPage.id, currentPage.records.size() - 1)` — the page ID and the 0-based index of the just-appended record within that page.

Change `latestPosition()` to return `JournalPosition`:
```java
public synchronized JournalPosition latestPosition() {
    checkOpen();
    if (globalSize() == 0) {
        return JournalPosition.START;
    }
    return new JournalPosition(currentPage.id, currentPage.records.size() - 1);
}
```

Change `scan(long)` to `scan(JournalPosition)`. When flattening pages into the record list, also build a parallel `pageOffsets` list that tracks each record's 0-based offset within its page. Pass it to the scanner constructor.

- [ ] **Step 3: Verify the core test module compiles**

Run: `mvn clean compile -pl drools-journal-core -Dmaven.test.skip=true`

Expected: FAIL — production code in `drools-journal-core` (RestoreEngine, CompactionCoordinator, PageIndex) still uses `scan(0)` and `long` returns. This is expected. The test-only classes (InMemory*) should compile.

To verify just the test compilation: `mvn clean test-compile -pl drools-journal-core`

This will also fail because the test classes reference production code that hasn't been updated yet. That's expected — we'll fix it in Task 3.

- [ ] **Step 4: Commit**

```
refactor: migrate InMemoryJournalStorage to JournalPosition

Refs #<issue>
```

---

### Task 3: Migrate production consumers in drools-journal-core

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/RestoreEngine.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/PageIndex.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledKieSession.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournallingRuntimeEventListener.java`

**Interfaces:**
- Consumes: `JournalPosition.START`, `JournalStorage.scan(JournalPosition)`, `JournalScanner.position()` returning `JournalPosition`
- Produces: All production code compiles against the new SPI. No behavioral changes — this is a mechanical `0` → `JournalPosition.START` migration.

- [ ] **Step 1: Update RestoreEngine**

Replace `journal.scan(0)` with `journal.scan(JournalPosition.START)` on lines 65 and 79.

- [ ] **Step 2: Update CompactionCoordinator**

Replace `storage.scan(0)` with `storage.scan(JournalPosition.START)` on lines 89, 111, 123, and 200.

- [ ] **Step 3: Update PageIndex**

No direct `scan()` calls — `PageIndex.buildLivePageSet()` receives a `JournalScanner`, so no changes needed to PageIndex itself.

- [ ] **Step 4: Update JournallingRuntimeEventListener**

The listener calls `journal.insert(...)`, `journal.retract(...)`, etc. These now return `JournalPosition` instead of `long`. The listener ignores the return values (fire-and-forget writes), so no code changes are needed — the return type change is binary-compatible at the call site.

Verify this by reading the listener code: all calls are statement expressions (`journal.insert(...)` not `long pos = journal.insert(...)`).

- [ ] **Step 5: Update JournalledKieSession**

`journal.safepoint()` returns `void` — no change needed.

- [ ] **Step 6: Update test callers**

Replace all `storage.scan(0)` with `storage.scan(JournalPosition.START)` across test files:
- `JournalStorageContractTest.java` — all `scan(0)` calls
- `JournalPrinter.java` — line 47
- `RestoreEngineTest.java` — no direct `scan()` calls (uses `RestoreEngine.scan()`)
- `CompactionCorrectnessTest.java` — line 267
- `PageIndexTest.java` — lines 36, 55, 73, 99
- `SafepointRollbackTest.java` — no direct `scan()` calls
- `JournallingRuntimeEventListenerTest.java` — lines 44, 60, 74, 89, 107, 122

Also update `JournalStorageContractTest`:
- `latestPosition_emptyJournal_returnsMinusOne` — change assertion from `isEqualTo(-1L)` to `isEqualTo(JournalPosition.START)`
- `latestPosition_afterWrite_returnsLastWrittenPosition` — change `long lastPos` to `JournalPosition lastPos`
- `write_returnsMonotonicallyIncreasingPositions` — this test compares `long` positions with `isLessThan`. With `JournalPosition` there's no natural ordering. Change to verify that successive writes produce different positions (non-equal), or remove this test if position ordering is no longer part of the contract.

- [ ] **Step 7: Build the core module**

Run: `mvn clean install -pl drools-journal-api,drools-journal-core`

Expected: SUCCESS — all production and test code in `drools-journal-core` compiles and tests pass. The Chronicle module and integration tests will still fail (next tasks).

- [ ] **Step 8: Commit**

```
refactor: migrate core consumers to JournalPosition

Refs #<issue>
```

---

### Task 4: Migrate ChronicleJournalStorage and MultiQueueScanner

**Files:**
- Modify: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java`
- Modify: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/MultiQueueScanner.java`

**Interfaces:**
- Consumes: `JournalPosition(String pageId, long offset)`, `JournalPosition.START`, `ExcerptTailer.moveToIndex(long)`, `ExcerptTailer.index()`
- Produces: `ChronicleJournalStorage` write methods return `JournalPosition(currentPageId, lastIndexAppended)`. `scan(JournalPosition)` delegates to `MultiQueueScanner.create(rootDir, catalogQueue, fromPosition)`. `MultiQueueScanner.position()` returns `JournalPosition(currentPageId, tailer.index())`. `MultiQueueScanner` supports mid-stream resume via `moveToIndex()`.

- [ ] **Step 1: Update ChronicleJournalStorage write methods**

Each write method currently stores `lastWrittenPosition = activeAppender().lastIndexAppended()` and returns `lastWrittenPosition`. Change to:

```java
long rawPos = activeAppender().lastIndexAppended();
lastWrittenPosition = new JournalPosition(currentPageId, rawPos);
return lastWrittenPosition;
```

Change `lastWrittenPosition` field type from `long` to `JournalPosition`. Initialize to `JournalPosition.START` in `createFresh` (was `-1L`). In `reopenExisting`, set to `new JournalPosition(activePageId, queueLastIndex)` or `JournalPosition.START` if queue is empty.

Update `latestPosition()` to return `JournalPosition` (the field directly).

Update `scan(JournalPosition fromPosition)` to pass `fromPosition` through to `MultiQueueScanner.create()`.

- [ ] **Step 2: Update MultiQueueScanner — position() and create()**

Add `JournalPosition fromPosition` parameter to `create()`.

Replace the `long syntheticPosition` field with capturing the tailer's index. The scanner needs to hold a reference to the current tailer (not just the `MethodReader`). Add a field:

```java
private net.openhft.chronicle.queue.ExcerptTailer currentTailer;
```

Set it in `openNextNonEmptyQueue()` when opening a new queue. In `position()`:

```java
@Override
public JournalPosition position() {
    if (currentTailer == null) {
        return JournalPosition.START;
    }
    return new JournalPosition(currentPageId, currentTailer.index());
}
```

- [ ] **Step 3: Update MultiQueueScanner — mid-stream resume**

In `create()`, when `fromPosition != START`:
1. Build the live page list from the catalog (same as today)
2. Find the index of `fromPosition.pageId()` in the live pages list
3. If not found (page retired), fall back: set `currentPageListIndex = -1` and proceed as `START` (review this during implementation)
4. If found, set `currentPageListIndex` to that index minus 1 (so `openNextNonEmptyQueue` opens it next)
5. In `openNextNonEmptyQueue`, when opening the target page: instead of `tailer.toStart()`, call `tailer.moveToIndex(fromPosition.offset())`. Only apply `moveToIndex` for the first page opened after a non-START resume — subsequent pages start from `toStart()` as usual.

Add a field to track this:

```java
private JournalPosition resumePosition;
```

Set it in `create()`. In `openNextNonEmptyQueue`, after opening a queue, check if this is the resume page:

```java
if (resumePosition != null && pageId.equals(resumePosition.pageId())) {
    tailer.moveToIndex(resumePosition.offset());
    resumePosition = null; // consumed
} else {
    tailer.toStart();
}
```

- [ ] **Step 4: Update Chronicle test callers**

In `ChronicleJournalStorageTest.java`, `ChronicleJournalScannerTest.java` (class `MultiQueueScannerTest`), and `ChronicleStorageContractTest.java`: replace any `scan(0)` with `scan(JournalPosition.START)`.

- [ ] **Step 5: Build and test the Chronicle module**

Run: `mvn clean install -pl drools-journal-api,drools-journal-core,drools-journal-chronicle`

Expected: SUCCESS

- [ ] **Step 6: Commit**

```
feat: implement Chronicle scan(JournalPosition) via moveToIndex()

Refs #<issue>
```

---

### Task 5: Update integration tests and full build

**Files:**
- Modify: `drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleJournalledKieSessionIT.java`
- Modify: `drools-journal-tests/src/test/java/org/drools/journal/test/ChroniclePageRetirementIT.java`
- Modify: `drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleStorageContractTest.java`

**Interfaces:**
- Consumes: `JournalPosition.START`, `scan(JournalPosition)`
- Produces: All integration tests pass with the new position model.

- [ ] **Step 1: Update integration test scan calls**

Replace all `storage.scan(0)` and `scanner.scan(0)` calls with `scan(JournalPosition.START)` in:
- `ChronicleJournalledKieSessionIT.java` — line 156
- `ChroniclePageRetirementIT.java` — lines 142, 169, 204

Add the import `org.drools.journal.api.JournalPosition` to each file.

- [ ] **Step 2: Full build**

Run: `mvn clean install`

Expected: SUCCESS — all modules compile, all unit tests pass, all integration tests pass.

- [ ] **Step 3: Commit**

```
refactor: update integration tests for JournalPosition

Refs #<issue>
```

---

### Task 6: Add mid-stream resume contract tests

**Files:**
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/JournalStorageContractTest.java`

**Interfaces:**
- Consumes: `JournalPosition`, `JournalStorage.scan(JournalPosition)`, `JournalScanner.position()`
- Produces: New contract tests verifying that `scan(position)` resumes from the saved position, that the position round-trips correctly, and that a retired page falls back to full scan.

- [ ] **Step 1: Write test — scan from saved position resumes correctly**

```java
@Test
void scan_fromSavedPosition_resumesAtCorrectRecord() {
    try (JournalStorage storage = createStorage()) {
        storage.insert(1L, samplePayload());
        storage.safepoint();
        storage.insert(2L, samplePayload());
        storage.insert(3L, samplePayload());
        storage.safepoint();

        // Scan first page and capture position after the safepoint
        JournalPosition savedPosition;
        try (JournalScanner scanner = storage.scan(JournalPosition.START)) {
            scanner.next(); // insert(1)
            scanner.next(); // safepoint
            savedPosition = scanner.position();
        }

        // Resume from saved position — should see insert(2), insert(3), safepoint
        try (JournalScanner scanner = storage.scan(savedPosition)) {
            JournalRecord first = scanner.next();
            assertThat(first).isInstanceOf(InsertRecord.class);
            assertThat(((InsertRecord) first).factHandleId()).isEqualTo(2L);
        }
    }
}
```

- [ ] **Step 2: Write test — position round-trips through scan**

```java
@Test
void position_roundTrips_throughScan() {
    try (JournalStorage storage = createStorage()) {
        storage.insert(1L, samplePayload());
        storage.safepoint();
        storage.insert(2L, samplePayload());
        storage.safepoint();

        JournalPosition pos;
        try (JournalScanner s1 = storage.scan(JournalPosition.START)) {
            s1.next(); // insert(1)
            pos = s1.position();
        }

        try (JournalScanner s2 = storage.scan(pos)) {
            JournalRecord next = s2.next();
            assertThat(next).isInstanceOf(SafepointRecord.class);
        }
    }
}
```

- [ ] **Step 3: Run contract tests for both backends**

Run: `mvn clean install`

Expected: SUCCESS — both InMemory and Chronicle pass the new contract tests.

- [ ] **Step 4: Commit**

```
test: add mid-stream resume contract tests for JournalPosition

Refs #<issue>
```

---

### Task 7: Incremental liveness scanning in CompactionCoordinator

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java`
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java`

**Interfaces:**
- Consumes: `JournalPosition`, `JournalStorage.scan(JournalPosition)`, `JournalScanner.position()`
- Produces: `CompactionCoordinator` with accumulated liveness state between `runCycle()` calls. New fields: `lastScannedPosition`, `liveness`, `factToPage`. `scanLiveness(Set<String>)` accepts `JournalPosition` for incremental scanning. `onDemand()` stays stateless (fresh per call).

- [ ] **Step 1: Write failing test — incremental scan produces same result as full scan**

```java
@Test
void scanLiveness_incremental_producesSameResultAsFullScan() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    storage.insert(1L, "a");
    storage.safepoint(0);        // page "0"

    CompactionCoordinator coordinator = new CompactionCoordinator(storage, Duration.ZERO);

    // First cycle — full scan
    coordinator.runCycle();

    // Add more data
    storage.insert(2L, "b");
    storage.safepoint(1);        // page "1"

    // Second cycle — incremental scan
    coordinator.runCycle();

    // Compare with a fresh full scan
    Map<String, long[]> fullScan = CompactionCoordinator.onDemand(storage).scanLiveness();

    // Both should show pages "0" and "1" each with 1 live insert
    assertThat(fullScan).hasSize(2);
    assertThat(fullScan.get("0")[0]).isEqualTo(1L);
    assertThat(fullScan.get("1")[0]).isEqualTo(1L);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn clean test -pl drools-journal-core -Dtest=CompactionCoordinatorTest#scanLiveness_incremental_producesSameResultAsFullScan`

Expected: FAIL or compilation error — the coordinator does not yet have incremental state.

- [ ] **Step 3: Add incremental state fields to CompactionCoordinator**

Add instance fields that persist between `runCycle()` calls:

```java
private JournalPosition lastScannedPosition = JournalPosition.START;
private final Map<String, long[]> accumulatedLiveness = new HashMap<>();
private final Map<Long, String> accumulatedFactToPage = new HashMap<>();
```

- [ ] **Step 4: Modify scanLiveness(Set) to accept a start position and accumulated state**

Refactor `scanLiveness(Set<String> livePageIds)` to use the accumulated maps and scan from `lastScannedPosition`:

```java
Map<String, long[]> scanLiveness(Set<String> livePageIds) {
    try (JournalScanner scanner = storage.scan(lastScannedPosition)) {
        // ... same loop logic, but writing to accumulatedLiveness / accumulatedFactToPage
        // instead of local maps
        lastScannedPosition = scanner.position();
    }
    return accumulatedLiveness;
}
```

The existing loop body (page boundary detection, safepoint flushing, `flushPageLiveness`) stays the same — only the maps it writes to change from local to instance fields.

- [ ] **Step 5: Clean up retired pages from accumulated state**

In `runCycle()`, after building the page index and before the liveness scan, clean up retired pages:

```java
if (!pageStatus.retiredPages().isEmpty()) {
    storage.retirePages(pageStatus.retiredPages().toArray(new String[0]));
    for (String retired : pageStatus.retiredPages()) {
        accumulatedLiveness.remove(retired);
    }
}
```

- [ ] **Step 6: Keep onDemand() stateless**

`CompactionCoordinator.onDemand(storage)` creates a fresh instance. Its `scanLiveness()` starts from `JournalPosition.START` with empty maps — equivalent to a full scan. No changes needed to the factory method itself; the fresh instance's fields are already initialized to empty/START.

Verify that existing `onDemand` tests still pass — they create a fresh coordinator per call, so they always do a full scan.

- [ ] **Step 7: Run all CompactionCoordinator tests**

Run: `mvn clean test -pl drools-journal-core -Dtest=CompactionCoordinatorTest`

Expected: All tests PASS, including the new incremental test.

- [ ] **Step 8: Run full build**

Run: `mvn clean install`

Expected: SUCCESS

- [ ] **Step 9: Commit**

```
feat: incremental liveness scanning in CompactionCoordinator

Refs #<issue>
```

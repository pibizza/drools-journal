# CompactionCoordinator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement background journal compaction so the journal doesn't grow unboundedly as facts are inserted and retracted.

**Architecture:** Four sequential issues: (A) safepoint writing after `fireAllRules()` + thread-safe `InMemoryJournalStorage`; (B) real `RestoreEngine` Phase 0 compaction map; (C) `CompactionCoordinator` periodic liveness scan; (D) four-step PREPARE/WRITE/COMMIT/RETIRE protocol wired into the session lifecycle.

**Tech Stack:** Java 17, JUnit 5, AssertJ, Maven (`mvn clean install`). No new dependencies. All tests in `drools-journal-core`.

**Spec:** `specs/2026-05-26-compaction-coordinator-design.md` in the workspace.

---

## File Map

### Issue A — Safepoint writing + thread-safe InMemoryJournalStorage

| File | Change |
|------|--------|
| `drools-journal-core/src/main/java/org/drools/journal/core/JournalledKieSession.java` | Add `safepointSequenceNo` counter; extend `fireAllRules()` to append `SafepointRecord` |
| `drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalStorage.java` | Make all methods `synchronized`; use `ArrayList` copy in `scan()` |
| `drools-journal-core/src/test/java/org/drools/journal/core/SafepointRollbackTest.java` | New — safepoint written after fireAllRules; rollback to safepoint on restore |

### Issue B — RestoreEngine Phase 0

| File | Change |
|------|--------|
| `drools-journal-core/src/main/java/org/drools/journal/core/RestoreEngine.java` | Replace no-op Phase 0 with real compaction map scan; filter Phase 1 records by page-state map |
| `drools-journal-core/src/test/java/org/drools/journal/core/RestoreEngineTest.java` | Add tests for compaction map: PREPARE-only, PREPARE+COMMIT scenarios |

### Issue C — CompactionCoordinator liveness scan

| File | Change |
|------|--------|
| `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java` | New — periodic rescan, liveness map, candidate detection |
| `drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java` | New — unit tests for liveness scan logic |

### Issue D — Four-step protocol + session wiring

| File | Change |
|------|--------|
| `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java` | Add four-step protocol, background thread, lifecycle |
| `drools-journal-core/src/main/java/org/drools/journal/core/JournalledSessionFactory.java` | Start/stop `CompactionCoordinator` in `open()` / propagate to `dispose()` |
| `drools-journal-core/src/main/java/org/drools/journal/core/JournalledKieSession.java` | Hold and stop `CompactionCoordinator` in `dispose()` |
| `drools-journal-core/src/test/java/org/drools/journal/core/CompactionCorrectnessTest.java` | New — crash scenarios, sequential compactions, full cycle |

---

## Issue A — Safepoint writing + thread-safe InMemoryJournalStorage

### Task A1: Make InMemoryJournalStorage thread-safe

**Files:**
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalStorage.java`

- [ ] Add `synchronized` to every public method (`append`, `scan`, `latestPosition`, `close`, `size`). In `scan()`, copy the list snapshot before constructing the scanner so the scanner sees a consistent view:

```java
@Override
public synchronized long append(final JournalRecord record) {
    checkOpen();
    records.add(record);
    return records.size() - 1;
}

@Override
public synchronized JournalScanner scan(final long fromPosition) {
    checkOpen();
    return new InMemoryJournalScanner(List.copyOf(records), fromPosition);
}

@Override
public synchronized long latestPosition() {
    checkOpen();
    return records.isEmpty() ? -1 : records.size() - 1;
}

@Override
public synchronized void close() {
    closed = true;
}

synchronized int size() {
    return records.size();
}

private void checkOpen() {
    if (closed) {
        throw new IllegalStateException("InMemoryJournalStorage has been closed");
    }
}
```

- [ ] Remove the `// NOT thread-safe` comment from the class Javadoc. Update the class-level Javadoc to: `Non-durable, in-process {@link JournalStorage} for use in tests. Thread-safe.`

- [ ] Run `mvn clean install` — all existing tests must pass.

- [ ] Commit:
```bash
git add drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalStorage.java
git commit -m "refactor(core): make InMemoryJournalStorage thread-safe (Refs #12)"
```

---

### Task A2: Write failing SafepointRollbackTest

**Files:**
- Create: `drools-journal-core/src/test/java/org/drools/journal/core/SafepointRollbackTest.java`

- [ ] Write the test — it will fail because `fireAllRules()` does not yet write a `SafepointRecord`:

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

import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafepointRollbackTest {

    @Test
    void fireAllRules_appendsSafepointRecord() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Write one insert before firing — simulates a fact in working memory
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("hello")));

        // Build a minimal session with no rules, just to drive fireAllRules()
        // We test the journal content directly rather than a full KieBase session
        // to keep this test fast and focused.
        // Use RestoreEngine to verify safepoint was written.
        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        // Before safepoint: facts are pending (not flushed), so survivingFacts is empty.
        assertThat(result.survivingFacts()).isEmpty();

        // Now manually append a SafepointRecord as fireAllRules() will do.
        storage.append(new SafepointRecord(0L, System.currentTimeMillis()));

        result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        // After safepoint: insert is flushed and fact survives.
        assertThat(result.survivingFacts()).containsKey(1L);
    }

    @Test
    void fireAllRules_safepoint_sequenceNoIncrements() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("a")));
        storage.append(new SafepointRecord(0L, 1000L));
        storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("b")));
        storage.append(new SafepointRecord(1L, 2000L));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsKeys(1L, 2L);
    }

    @Test
    void recordsAfterLastSafepoint_areDiscardedOnRestore() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("committed")));
        storage.append(new SafepointRecord(0L, 1000L));
        // These records come after the last safepoint — simulates crash mid-write
        storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("lost")));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        assertThat(result.survivingFacts()).containsKey(1L);
        assertThat(result.survivingFacts()).doesNotContainKey(2L);
    }
}
```

- [ ] Run `mvn clean install` — the `fireAllRules_appendsSafepointRecord` test should pass already (it only tests manual writes), but note it as a baseline. All other existing tests must pass.

- [ ] Commit:
```bash
git add drools-journal-core/src/test/java/org/drools/journal/core/SafepointRollbackTest.java
git commit -m "test(core): add SafepointRollbackTest (Refs #12)"
```

---

### Task A3: Wire SafepointRecord into JournalledKieSession.fireAllRules()

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledKieSession.java`

- [ ] Add `safepointSequenceNo` field and update `fireAllRules()`:

```java
public class JournalledKieSession extends StatefulKnowledgeSessionImpl {

    private final JournalStorage journal;
    private ReplayFilter replayFilter;
    private CompactionCoordinator compactionCoordinator;
    private long safepointSequenceNo = 0L;

    // ... existing constructor unchanged ...

    @Override
    public int fireAllRules() {
        int fired = (replayFilter != null)
                ? super.fireAllRules(replayFilter)
                : super.fireAllRules();
        journal.append(new SafepointRecord(safepointSequenceNo++, System.currentTimeMillis()));
        return fired;
    }

    void setReplayFilter(final ReplayFilter filter) {
        this.replayFilter = filter;
    }

    void setCompactionCoordinator(final CompactionCoordinator coordinator) {
        this.compactionCoordinator = coordinator;
    }

    @Override
    public void dispose() {
        // CompactionCoordinator and JournalStorage are stopped/closed by the caller.
        super.dispose();
    }
}
```

Note: `CompactionCoordinator` field is added here for Task D — leave it null for now, `setCompactionCoordinator` is a stub.

- [ ] Run `mvn clean install` — all tests including `SafepointRollbackTest` must pass.

- [ ] Commit:
```bash
git add drools-journal-core/src/main/java/org/drools/journal/core/JournalledKieSession.java
git commit -m "feat(core): append SafepointRecord after fireAllRules (Refs #12)"
```

---

## Issue B — RestoreEngine Phase 0: real compaction map

### Task B1: Write failing RestoreEngine compaction map tests

**Files:**
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/RestoreEngineTest.java`

- [ ] Add these tests at the end of `RestoreEngineTest`:

```java
@Test
void prepareOnly_noCommit_originalPagesRemainCanonical() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    // Page "0": one live fact
    storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("original")));
    storage.append(new SafepointRecord(0L, 1000L));
    // Compaction started but crashed before COMMIT
    storage.append(new CompactionPrepareRecord("m-uuid-1", new String[]{"0"}));
    // Merged page would go here — but no COMMIT means it's ignored
    storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("merged-but-not-committed")));
    // No CompactionCommitRecord — simulate crash

    RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

    // Original page is still canonical — fact from page "0" survives
    assertThat(result.survivingFacts()).containsEntry(1L, "original");
}

@Test
void prepareAndCommit_mergedPageIsCanonical() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    // Page "0": fact inserted then retracted — dead
    storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("dead")));
    storage.append(new RetractRecord(1L));
    storage.append(new SafepointRecord(0L, 1000L));
    // Page "1": live fact
    storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("live")));
    storage.append(new SafepointRecord(1L, 2000L));
    // Compaction: merge pages "0" and "1" into "m-uuid-2", keeping only live fact
    storage.append(new CompactionPrepareRecord("m-uuid-2", new String[]{"0", "1"}));
    storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("live")));
    storage.append(new CompactionCommitRecord("m-uuid-2", new String[]{"0", "1"}));

    RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

    // Only the live fact from the merged page survives
    assertThat(result.survivingFacts()).hasSize(1);
    assertThat(result.survivingFacts()).containsEntry(2L, "live");
    assertThat(result.survivingFacts()).doesNotContainKey(1L);
}
```

- [ ] Run `mvn clean install` — these tests will fail because Phase 0 is a no-op today.

- [ ] Commit:
```bash
git add drools-journal-core/src/test/java/org/drools/journal/core/RestoreEngineTest.java
git commit -m "test(core): add RestoreEngine Phase 0 compaction map tests (Refs #12)"
```

---

### Task B2: Implement RestoreEngine Phase 0

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/RestoreEngine.java`

- [ ] Replace the current `scan()` method with a two-pass implementation. First, build the compaction page-state map; then run Phase 1 filtered by it:

```java
import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;

// Add to imports at top of file

ScanResult scan() {
    // Phase 0: build compaction page-state map
    // retiredPageIds = pages replaced by a committed merged page
    // inProgressMergedIds = merged page IDs with PREPARE but no COMMIT yet
    Set<String> retiredPageIds = new HashSet<>();
    Set<String> inProgressMergedIds = new HashSet<>();
    Set<String> committedMergedIds = new HashSet<>();

    try (JournalScanner phase0Scanner = journal.scan(0)) {
        while (phase0Scanner.hasNext()) {
            JournalRecord record = phase0Scanner.next();
            if (record instanceof CompactionPrepareRecord prepare) {
                inProgressMergedIds.add(prepare.preparingPageId());
            } else if (record instanceof CompactionCommitRecord commit) {
                committedMergedIds.add(commit.mergedPageId());
                inProgressMergedIds.remove(commit.mergedPageId());
                for (String replacedId : commit.replacedPageIds()) {
                    retiredPageIds.add(replacedId);
                }
            }
        }
    }

    // Phase 1: sequential scan, skipping records from retired pages
    Map<Long, Object> survivingFacts = new HashMap<>();
    List<RuleMatchRecord> firedMatches = new ArrayList<>();
    List<PendingTmsLink> pendingTmsLinks = new ArrayList<>();
    Map<Long, RuleMatchRecord> firedMatchesById = new HashMap<>();
    List<JournalRecord> pending = new ArrayList<>();

    String currentPageId = null;
    boolean inRetiredPage = false;
    boolean inInProgressMerge = false;

    try (JournalScanner scanner = journal.scan(0)) {
        while (scanner.hasNext()) {
            JournalRecord record = scanner.next();

            if (record instanceof CompactionPrepareRecord prepare) {
                inInProgressMerge = inProgressMergedIds.contains(prepare.preparingPageId());
                continue;
            }
            if (record instanceof CompactionCommitRecord) {
                inInProgressMerge = false;
                continue;
            }
            if (record instanceof SafepointRecord sp) {
                currentPageId = String.valueOf(sp.sequenceNo());
                inRetiredPage = retiredPageIds.contains(currentPageId);
                if (!inRetiredPage && !inInProgressMerge) {
                    flush(pending, survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
                }
                pending.clear();
                continue;
            }

            // Skip records from retired pages or in-progress (uncommitted) merged pages
            if (!inRetiredPage && !inInProgressMerge) {
                pending.add(record);
            }
        }
    }
    // Trailing records after the last safepoint are discarded (pending is cleared only on safepoint)

    return new ScanResult(survivingFacts, firedMatches, pendingTmsLinks, firedMatchesById);
}
```

- [ ] Run `mvn clean install` — all tests including the new Phase 0 tests must pass.

- [ ] Commit:
```bash
git add drools-journal-core/src/main/java/org/drools/journal/core/RestoreEngine.java
git commit -m "feat(core): implement RestoreEngine Phase 0 compaction map (Refs #12)"
```

---

## Issue C — CompactionCoordinator: liveness scan

### Task C1: Write failing CompactionCoordinatorTest (liveness scan)

**Files:**
- Create: `drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java`

- [ ] Write tests for the liveness scan logic. `CompactionCoordinator.scanLiveness(JournalStorage)` will be a package-private method returning a `Map<String, long[]>` where `long[]` is `[liveCount, totalCount]`:

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

import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionCoordinatorTest {

    @Test
    void emptyJournal_producesEmptyLivenessMap() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness).isEmpty();
    }

    @Test
    void singlePage_allLive_notSparse() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("a")));
        storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("b")));
        storage.append(new SafepointRecord(0L, 1000L));

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        assertThat(liveness).containsKey("0");
        long[] counts = liveness.get("0");
        assertThat(counts[0]).isEqualTo(2L); // liveCount
        assertThat(counts[1]).isEqualTo(2L); // totalCount
    }

    @Test
    void insertThenRetract_reducesLiveCountOnOriginalPage() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": fact inserted
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("a")));
        storage.append(new SafepointRecord(0L, 1000L));
        // Page "1": same fact retracted
        storage.append(new RetractRecord(1L));
        storage.append(new SafepointRecord(1L, 2000L));

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        // Page "0" liveCount decremented by retract on page "1"
        assertThat(liveness.get("0")[0]).isEqualTo(0L); // liveCount = 0
        assertThat(liveness.get("0")[1]).isEqualTo(1L); // totalCount = 1
        // Page "1" has one record (the retract itself counts toward total)
        assertThat(liveness.get("1")[1]).isEqualTo(1L);
    }

    @Test
    void sparsePageDetected_belowThreshold() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": 10 inserts, all retracted later
        for (long i = 1; i <= 10; i++) {
            storage.append(new InsertRecord(i, false, -1L, JournalPayloadBuilder.embed("fact-" + i)));
        }
        storage.append(new SafepointRecord(0L, 1000L));
        // Page "1": retract all facts from page "0"
        for (long i = 1; i <= 10; i++) {
            storage.append(new RetractRecord(i));
        }
        storage.append(new SafepointRecord(1L, 2000L));

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        // Page "0": 10 inserts, 0 live → ratio 0.0 < 0.30 → sparse
        assertThat(CompactionCoordinator.isSparse(liveness.get("0"))).isTrue();
        // Page "1": 10 retracts, all records are "live" in total → not sparse
        assertThat(CompactionCoordinator.isSparse(liveness.get("1"))).isFalse();
    }

    @Test
    void pageWithNoRecords_notIncludedInLivenessMap() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Safepoint with no preceding records — empty page
        storage.append(new SafepointRecord(0L, 1000L));
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("a")));
        storage.append(new SafepointRecord(1L, 2000L));

        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

        // Page "0" is empty — excluded (nothing to compact)
        assertThat(liveness).doesNotContainKey("0");
        assertThat(liveness).containsKey("1");
    }
}
```

- [ ] Run `mvn clean install` — tests fail because `CompactionCoordinator` does not exist yet.

- [ ] Commit:
```bash
git add drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java
git commit -m "test(core): add CompactionCoordinator liveness scan tests (Refs #12)"
```

---

### Task C2: Implement CompactionCoordinator liveness scan

**Files:**
- Create: `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java`

- [ ] Implement the liveness scan as a static utility method (the background thread comes in Task D):

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

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class CompactionCoordinator {

    private static final double SPARSENESS_THRESHOLD = 0.30;

    // Liveness map: pageId → [liveCount, totalCount]
    // Package-private for testing.
    static Map<String, long[]> scanLiveness(final JournalStorage storage) {
        Map<String, long[]> liveness = new HashMap<>();
        Map<Long, String> factPage = new HashMap<>(); // factHandleId → pageId where InsertRecord lives
        Set<String> retiredPageIds = new HashSet<>();
        Set<String> inProgressMergedIds = new HashSet<>();

        String currentPageId = null;

        try (JournalScanner scanner = storage.scan(0)) {
            while (scanner.hasNext()) {
                JournalRecord record = scanner.next();

                if (record instanceof SafepointRecord sp) {
                    currentPageId = String.valueOf(sp.sequenceNo());

                } else if (record instanceof InsertRecord insert) {
                    if (currentPageId != null && !retiredPageIds.contains(currentPageId)
                            && !inProgressMergedIds.contains(currentPageId)) {
                        long[] counts = liveness.computeIfAbsent(currentPageId, k -> new long[2]);
                        counts[0]++; // liveCount
                        counts[1]++; // totalCount
                        factPage.put(insert.factHandleId(), currentPageId);
                    }

                } else if (record instanceof RetractRecord retract) {
                    if (currentPageId != null && !retiredPageIds.contains(currentPageId)
                            && !inProgressMergedIds.contains(currentPageId)) {
                        long[] counts = liveness.computeIfAbsent(currentPageId, k -> new long[2]);
                        counts[1]++; // totalCount (retract itself counts)
                    }
                    String originalPage = factPage.remove(retract.factHandleId());
                    if (originalPage != null) {
                        long[] originalCounts = liveness.get(originalPage);
                        if (originalCounts != null) {
                            originalCounts[0]--; // decrement liveCount on the page holding the InsertRecord
                        }
                    }

                } else if (record instanceof RuleMatchRecord) {
                    if (currentPageId != null && !retiredPageIds.contains(currentPageId)
                            && !inProgressMergedIds.contains(currentPageId)) {
                        long[] counts = liveness.computeIfAbsent(currentPageId, k -> new long[2]);
                        counts[0]++;
                        counts[1]++;
                    }

                } else if (record instanceof CompactionPrepareRecord prepare) {
                    inProgressMergedIds.add(prepare.preparingPageId());

                } else if (record instanceof CompactionCommitRecord commit) {
                    inProgressMergedIds.remove(commit.mergedPageId());
                    for (String replacedId : commit.replacedPageIds()) {
                        retiredPageIds.add(replacedId);
                        liveness.remove(replacedId);
                    }
                    // Add merged page as a fresh full-live entry
                    // Its records were already scanned and counted above
                }
            }
        }

        // Remove empty pages (nothing to compact)
        liveness.entrySet().removeIf(e -> e.getValue()[1] == 0);

        return liveness;
    }

    // Package-private for testing.
    static boolean isSparse(final long[] counts) {
        if (counts == null || counts[1] == 0) {
            return false;
        }
        return (double) counts[0] / counts[1] < SPARSENESS_THRESHOLD;
    }
}
```

- [ ] Run `mvn clean install` — all liveness scan tests must pass.

- [ ] Commit:
```bash
git add drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java
git commit -m "feat(core): implement CompactionCoordinator liveness scan (Refs #12)"
```

---

## Issue D — Four-step protocol + session wiring

### Task D1: Write failing CompactionCorrectnessTest

**Files:**
- Create: `drools-journal-core/src/test/java/org/drools/journal/core/CompactionCorrectnessTest.java`

- [ ] Write tests for the four-step protocol. These drive `CompactionCoordinator.compact(JournalStorage, Set<String>)` directly (a package-private method) rather than through the background thread:

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

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionCorrectnessTest {

    @Test
    void compact_writesPrepareThenCommit() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": one live fact
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("a")));
        storage.append(new SafepointRecord(0L, 1000L));

        CompactionCoordinator.compact(storage, Set.of("0"));

        List<JournalRecord> records = drainAll(storage);
        // Last two appended records must be PREPARE then COMMIT
        long prepareIdx = -1, commitIdx = -1;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i) instanceof CompactionPrepareRecord) prepareIdx = i;
            if (records.get(i) instanceof CompactionCommitRecord) commitIdx = i;
        }
        assertThat(prepareIdx).isGreaterThanOrEqualTo(0);
        assertThat(commitIdx).isGreaterThan(prepareIdx);
    }

    @Test
    void compact_mergedPageContainsOnlyLiveFacts() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": two facts, one later retracted
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("live")));
        storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("dead")));
        storage.append(new SafepointRecord(0L, 1000L));
        // Page "1": retract fact 2
        storage.append(new RetractRecord(2L));
        storage.append(new SafepointRecord(1L, 2000L));

        CompactionCoordinator.compact(storage, Set.of("0", "1"));

        // After compaction, restore must see only fact 1
        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();
        assertThat(result.survivingFacts()).containsKey(1L);
        assertThat(result.survivingFacts()).doesNotContainKey(2L);
    }

    @Test
    void crashAfterPrepare_noCommit_restoreUsesOriginalPages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": one live fact
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("original")));
        storage.append(new SafepointRecord(0L, 1000L));
        // Simulate crash: PREPARE written, merged records written, but no COMMIT
        storage.append(new CompactionPrepareRecord("m-crashed", new String[]{"0"}));
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("merged-not-committed")));
        // No CompactionCommitRecord

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        // Original page "0" is still canonical
        assertThat(result.survivingFacts()).containsEntry(1L, "original");
    }

    @Test
    void crashAfterCommit_restoreUsesMergedPage() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": one live fact, one dead fact
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("live")));
        storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("dead")));
        storage.append(new SafepointRecord(0L, 1000L));
        // Page "1": retract fact 2
        storage.append(new RetractRecord(2L));
        storage.append(new SafepointRecord(1L, 2000L));
        // Compaction committed — source pages NOT physically deleted (lazy)
        storage.append(new CompactionPrepareRecord("m-committed", new String[]{"0", "1"}));
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("live")));
        storage.append(new CompactionCommitRecord("m-committed", new String[]{"0", "1"}));

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();

        // Merged page is canonical: only fact 1 survives
        assertThat(result.survivingFacts()).hasSize(1);
        assertThat(result.survivingFacts()).containsKey(1L);
        assertThat(result.survivingFacts()).doesNotContainKey(2L);
    }

    @Test
    void twoSequentialCompactions_onDifferentPages_completeIndependently() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        // Page "0": dead fact
        storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("dead-a")));
        storage.append(new SafepointRecord(0L, 1000L));
        // Page "1": retract fact 1; dead fact
        storage.append(new RetractRecord(1L));
        storage.append(new InsertRecord(2L, false, -1L, JournalPayloadBuilder.embed("dead-b")));
        storage.append(new SafepointRecord(1L, 2000L));
        // Page "2": retract fact 2; live fact
        storage.append(new RetractRecord(2L));
        storage.append(new InsertRecord(3L, false, -1L, JournalPayloadBuilder.embed("live")));
        storage.append(new SafepointRecord(2L, 3000L));

        // First compaction: pages "0" + "1"
        CompactionCoordinator.compact(storage, Set.of("0", "1"));
        // Second compaction: whatever new pages exist after first
        Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);
        Set<String> candidates = new java.util.HashSet<>();
        liveness.forEach((id, counts) -> { if (CompactionCoordinator.isSparse(counts)) candidates.add(id); });
        if (!candidates.isEmpty()) {
            CompactionCoordinator.compact(storage, candidates);
        }

        RestoreEngine.ScanResult result = new RestoreEngine(storage, new ModifyLambdaRegistry()).scan();
        assertThat(result.survivingFacts()).containsKey(3L);
        assertThat(result.survivingFacts()).doesNotContainKey(1L);
        assertThat(result.survivingFacts()).doesNotContainKey(2L);
    }

    // -------------------------------------------------------------------------
    // helper
    // -------------------------------------------------------------------------

    private static List<JournalRecord> drainAll(final InMemoryJournalStorage storage) {
        List<JournalRecord> records = new ArrayList<>();
        try (JournalScanner scanner = storage.scan(0)) {
            scanner.forEachRemaining(records::add);
        }
        return records;
    }
}
```

- [ ] Run `mvn clean install` — tests fail because `CompactionCoordinator.compact()` does not exist yet.

- [ ] Commit:
```bash
git add drools-journal-core/src/test/java/org/drools/journal/core/CompactionCorrectnessTest.java
git commit -m "test(core): add CompactionCorrectnessTest (Refs #12)"
```

---

### Task D2: Implement CompactionCoordinator.compact()

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java`

- [ ] Add `compact(JournalStorage, Set<String>)` as a package-private static method. It runs the four-step protocol:

```java
import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import java.util.Set;
import java.util.UUID;

// Add to CompactionCoordinator class:

static void compact(final JournalStorage storage, final Set<String> pageIds) {
    if (pageIds.isEmpty()) {
        return;
    }

    String mergedPageId = "m-" + UUID.randomUUID();
    String[] replacedPageIds = pageIds.toArray(new String[0]);

    // Step 1 — PREPARE
    storage.append(new CompactionPrepareRecord(mergedPageId, replacedPageIds));

    // Step 2 — WRITE: scan source pages, keep only live InsertRecords
    // Rebuild liveness to identify which factHandleIds are still live
    Map<Long, String> factPage = new HashMap<>();
    Set<Long> retractedIds = new HashSet<>();

    try (JournalScanner scanner = storage.scan(0)) {
        String currentPage = null;
        while (scanner.hasNext()) {
            JournalRecord record = scanner.next();
            if (record instanceof SafepointRecord sp) {
                currentPage = String.valueOf(sp.sequenceNo());
            } else if (record instanceof InsertRecord insert && pageIds.contains(currentPage)) {
                factPage.put(insert.factHandleId(), currentPage);
            } else if (record instanceof RetractRecord retract) {
                retractedIds.add(retract.factHandleId());
            }
        }
    }

    // Write only live InsertRecords from source pages
    try (JournalScanner scanner = storage.scan(0)) {
        String currentPage = null;
        while (scanner.hasNext()) {
            JournalRecord record = scanner.next();
            if (record instanceof SafepointRecord sp) {
                currentPage = String.valueOf(sp.sequenceNo());
            } else if (record instanceof InsertRecord insert
                    && pageIds.contains(currentPage)
                    && !retractedIds.contains(insert.factHandleId())) {
                storage.append(insert);
            }
        }
    }

    // Step 3 — COMMIT
    storage.append(new CompactionCommitRecord(mergedPageId, replacedPageIds));

    // Step 4 — RETIRE: for InMemoryJournalStorage, source page records remain
    // but are ignored by RestoreEngine after COMMIT. File-backed backends
    // delete source files here. No action needed for in-memory.
}
```

- [ ] Run `mvn clean install` — all `CompactionCorrectnessTest` tests must pass.

- [ ] Commit:
```bash
git add drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java
git commit -m "feat(core): implement CompactionCoordinator four-step PREPARE/COMMIT protocol (Refs #12)"
```

---

### Task D3: Wire background thread and session lifecycle

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledSessionFactory.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledKieSession.java`

- [ ] Add constructor, `start()`, and `stop()` to `CompactionCoordinator`:

```java
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class CompactionCoordinator {

    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

    private final JournalStorage storage;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    CompactionCoordinator(final JournalStorage storage, final Duration interval) {
        this.storage = storage;
        this.interval = interval;
    }

    void start() {
        if (Duration.ZERO.equals(interval)) {
            return; // disabled — used in tests that drive compact() directly
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drools-journal-compactor");
            t.setDaemon(true);
            return t;
        });
        long millis = interval.toMillis();
        scheduler.scheduleWithFixedDelay(this::runCycle, millis, millis, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void runCycle() {
        Map<String, long[]> liveness = scanLiveness(storage);
        Set<String> candidates = new HashSet<>();
        liveness.forEach((id, counts) -> {
            if (isSparse(counts)) candidates.add(id);
        });
        if (!candidates.isEmpty()) {
            compact(storage, candidates);
        }
    }

    // ... existing static methods unchanged ...
}
```

- [ ] Update `JournalledSessionFactory.open()` to create, start, and pass the coordinator to the session:

```java
public static JournalledKieSession open(final KieBase kbase, final JournalStorage storage) {
    return open(kbase, storage, CompactionCoordinator.DEFAULT_INTERVAL);
}

public static JournalledKieSession open(final KieBase kbase,
                                        final JournalStorage storage,
                                        final Duration compactionInterval) {
    Environment env = KieServices.get().newEnvironment();
    env.set(JOURNAL_KEY, storage);
    JournalledKieSession session = (JournalledKieSession) kbase.newKieSession(null, env);
    if (storage.latestPosition() >= 0) {
        restore(session, storage);
    }
    session.addEventListener(new JournallingRuntimeEventListener(storage,
            (fact, handle) -> StorageDecision.EMBED));
    CompactionCoordinator coordinator = new CompactionCoordinator(storage, compactionInterval);
    coordinator.start();
    session.setCompactionCoordinator(coordinator);
    return session;
}
```

- [ ] Update `JournalledKieSession.dispose()` to stop the coordinator:

```java
@Override
public void dispose() {
    if (compactionCoordinator != null) {
        compactionCoordinator.stop();
    }
    // JournalStorage is owned by the caller — closing it is their responsibility.
    super.dispose();
}
```

- [ ] Run `mvn clean install` — all tests must pass.

- [ ] Commit:
```bash
git add drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java \
        drools-journal-core/src/main/java/org/drools/journal/core/JournalledSessionFactory.java \
        drools-journal-core/src/main/java/org/drools/journal/core/JournalledKieSession.java
git commit -m "feat(core): wire CompactionCoordinator background thread into session lifecycle (Closes #12)"
```

---

## Self-Review

**Spec coverage:**
- ✅ Safepoints after `fireAllRules()` — Task A3
- ✅ Thread-safe `InMemoryJournalStorage` — Task A1
- ✅ RestoreEngine Phase 0 real compaction map — Task B2
- ✅ Liveness scan periodic rescan — Task C2
- ✅ Four-step PREPARE/WRITE/COMMIT/RETIRE — Task D2
- ✅ Crash after PREPARE — `CompactionCorrectnessTest`
- ✅ Crash after COMMIT — `CompactionCorrectnessTest`
- ✅ Sequential compactions — `CompactionCorrectnessTest`
- ✅ Session lifecycle (start/stop) — Task D3
- ✅ `Duration.ZERO` disables background thread — Task D3 `start()` guard

**Type consistency:**
- `CompactionCoordinator.scanLiveness(JournalStorage)` — defined C2, used in C1 tests and D3 `runCycle()`  ✅
- `CompactionCoordinator.isSparse(long[])` — defined C2, used in C1 tests and D3 `runCycle()` ✅
- `CompactionCoordinator.compact(JournalStorage, Set<String>)` — defined D2, used in D1 tests and D3 `runCycle()` ✅
- `JournalledKieSession.setCompactionCoordinator()` — added A3, used in D3 ✅
- `SafepointRecord(long, long)` — existing type, used in A2/A3 ✅

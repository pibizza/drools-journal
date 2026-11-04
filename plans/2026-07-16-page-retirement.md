# Page Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After sealed compaction, physically delete the source pages that were replaced by the merged page.

**Architecture:** Enrich `PageIndex.buildLivePageSet` to return a `PageIndexStatus` record carrying both `livePages` and `retiredPages`. Add `retirePages(String[])` to the `JournalStorage` SPI. Wire retirement into `CompactionCoordinator.runCycle()` by piggybacking on the existing `buildLivePageSet` scan — no additional journal traversal.

**Tech Stack:** Java 21, JUnit 5, AssertJ, Chronicle Queue (integration tests)

## Global Constraints

- Java 21 (`maven.compiler.release=21`)
- License header: `Copyright (c) 2026 Drools Journal Authors` (Apache 2.0)
- Always `mvn clean install` — never incremental
- Test naming: `action_context_expectedResult`
- IT class naming: `*IT` suffix (Maven Failsafe)
- `final` on fields and parameters only, not local variables
- Feature branch: `feat/34-page-retirement`

---

### Task 1: Introduce `PageIndexStatus` and update `buildLivePageSet`

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/PageIndex.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/RestoreEngine.java:62-64`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java:96-99`
- Test: `drools-journal-core/src/test/java/org/drools/journal/core/PageIndexTest.java` (create)

**Interfaces:**
- Consumes: `PageIndex.buildLivePageSet(JournalScanner)` (existing, will be modified)
- Produces: `PageIndex.PageIndexStatus(Set<String> livePages, Set<String> retiredPages)` — used by Tasks 4 and 5, and by `RestoreEngine` and `CompactionCoordinator` callers

- [ ] **Step 1: Write the failing test — no compaction yields empty retiredPages**

Create `drools-journal-core/src/test/java/org/drools/journal/core/PageIndexTest.java`:

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

import org.drools.journal.api.JournalScanner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageIndexTest {

    @Test
    void buildLivePageSet_noCompaction_returnsEmptyRetiredPages() {
        InMemoryJournalStorage storage = new InMemoryJournalStorage();
        storage.insert(1L, "a");
        storage.safepoint(0);
        storage.insert(2L, "b");
        storage.safepoint(1);

        PageIndex.PageIndexStatus status;
        try (JournalScanner scanner = storage.scan(0)) {
            status = PageIndex.buildLivePageSet(scanner);
        }

        assertThat(status.livePages()).containsExactlyInAnyOrder("0", "1");
        assertThat(status.retiredPages()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: compilation error — `PageIndexStatus` does not exist and `buildLivePageSet` returns `Set<String>`, not `PageIndexStatus`

- [ ] **Step 3: Introduce PageIndexStatus record and change buildLivePageSet return type**

In `PageIndex.java`, add the record and change `buildLivePageSet`:

```java
record PageIndexStatus(Set<String> livePages, Set<String> retiredPages) {}

static PageIndexStatus buildLivePageSet(final JournalScanner scanner) {
    final List<String> pageIndex = new ArrayList<>();
    final Map<String, String[]> pendingCommits = new LinkedHashMap<>();
    final List<String> currentIntervalPages = new ArrayList<>();
    final Set<String> retiredPages = new HashSet<>();
    String lastPageId = null;

    while (scanner.hasNext()) {
        final JournalRecord record = scanner.next();
        final String pageId = scanner.currentPageId();

        if (!pageId.equals(lastPageId)) {
            currentIntervalPages.add(pageId);
            lastPageId = pageId;
        }

        if (record instanceof CompactionCommitRecord commit) {
            pendingCommits.put(commit.mergedPageId(), commit.replacedPageIds());
        } else if (record instanceof SafepointRecord) {
            for (final Map.Entry<String, String[]> e : pendingCommits.entrySet()) {
                for (final String replaced : e.getValue()) {
                    retiredPages.add(replaced);
                }
                spliceIntoIndex(pageIndex, e.getKey(), e.getValue());
            }
            pendingCommits.clear();
            pageIndex.addAll(currentIntervalPages);
            currentIntervalPages.clear();
        }
    }

    return new PageIndexStatus(new HashSet<>(pageIndex), retiredPages);
}
```

Add `import java.util.HashSet;` to the imports if not already present.

- [ ] **Step 4: Update callers — RestoreEngine**

In `RestoreEngine.java`, change lines 62–64 from:

```java
final Set<String> livePageIds;
try (JournalScanner phase0 = journal.scan(0)) {
    livePageIds = PageIndex.buildLivePageSet(phase0);
}
```

to:

```java
final Set<String> livePageIds;
try (JournalScanner phase0 = journal.scan(0)) {
    livePageIds = PageIndex.buildLivePageSet(phase0).livePages();
}
```

- [ ] **Step 5: Update callers — CompactionCoordinator.scanLiveness**

In `CompactionCoordinator.java`, change lines 97–99 from:

```java
final Set<String> livePageIds;
try (JournalScanner phase0 = storage.scan(0)) {
    livePageIds = PageIndex.buildLivePageSet(phase0);
}
```

to:

```java
final Set<String> livePageIds;
try (JournalScanner phase0 = storage.scan(0)) {
    livePageIds = PageIndex.buildLivePageSet(phase0).livePages();
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: all tests pass, including the new `PageIndexTest`

- [ ] **Step 7: Commit**

```bash
git add drools-journal-core/src/main/java/org/drools/journal/core/PageIndex.java \
       drools-journal-core/src/main/java/org/drools/journal/core/RestoreEngine.java \
       drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java \
       drools-journal-core/src/test/java/org/drools/journal/core/PageIndexTest.java
git commit -m "feat: introduce PageIndexStatus with retiredPages in buildLivePageSet

Refs #34"
```

---

### Task 2: Test retiredPages tracking in PageIndex

**Files:**
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/PageIndexTest.java`

**Interfaces:**
- Consumes: `PageIndex.buildLivePageSet(JournalScanner)` returning `PageIndexStatus`

- [ ] **Step 1: Write test — sealed compaction produces retiredPages**

Add to `PageIndexTest.java`:

```java
@Test
void buildLivePageSet_sealedCompaction_returnsRetiredSourcePages() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    storage.insert(1L, "a");
    storage.insert(2L, "b");
    storage.safepoint(0);
    storage.retract(2L);
    storage.safepoint(1);

    CompactionCoordinator.compact(storage, java.util.Set.of("0", "1"));
    storage.safepoint(2);

    PageIndex.PageIndexStatus status;
    try (JournalScanner scanner = storage.scan(0)) {
        status = PageIndex.buildLivePageSet(scanner);
    }

    assertThat(status.retiredPages()).containsExactlyInAnyOrder("0", "1");
    assertThat(status.livePages()).noneMatch(id -> id.equals("0") || id.equals("1"));
}
```

Add `import java.util.Set;` if not already present.

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: PASS — the implementation from Task 1 already collects retired pages

- [ ] **Step 3: Write test — unsealed compaction produces no retiredPages**

Add to `PageIndexTest.java`:

```java
@Test
void buildLivePageSet_unsealedCompaction_returnsNoRetiredPages() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    storage.insert(1L, "a");
    storage.insert(2L, "b");
    storage.safepoint(0);
    storage.retract(2L);
    storage.safepoint(1);

    CompactionCoordinator.compact(storage, Set.of("0", "1"));
    // No safepoint — commit is not sealed

    PageIndex.PageIndexStatus status;
    try (JournalScanner scanner = storage.scan(0)) {
        status = PageIndex.buildLivePageSet(scanner);
    }

    assertThat(status.retiredPages()).isEmpty();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: PASS

- [ ] **Step 5: Write test — multiple sealed compactions accumulate retiredPages**

Add to `PageIndexTest.java`:

```java
@Test
void buildLivePageSet_twoSealedCompactions_accumulatesRetiredPages() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();

    // Round 1
    storage.insert(1L, "keep");
    storage.insert(2L, "drop");
    storage.safepoint(0);
    storage.retract(2L);
    storage.safepoint(1);
    CompactionCoordinator.compact(storage, Set.of("0", "1"));
    storage.safepoint(2);

    // Round 2
    storage.insert(3L, "keep");
    storage.insert(4L, "drop");
    storage.safepoint(3);
    storage.retract(4L);
    storage.safepoint(4);
    CompactionCoordinator.compact(storage, Set.of("3", "4"));
    storage.safepoint(5);

    PageIndex.PageIndexStatus status;
    try (JournalScanner scanner = storage.scan(0)) {
        status = PageIndex.buildLivePageSet(scanner);
    }

    assertThat(status.retiredPages()).containsExactlyInAnyOrder("0", "1", "3", "4");
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add drools-journal-core/src/test/java/org/drools/journal/core/PageIndexTest.java
git commit -m "test: verify retiredPages tracking in PageIndexStatus

Refs #34"
```

---

### Task 3: Add `retirePages` to `JournalStorage` SPI and InMemory implementation

**Files:**
- Modify: `drools-journal-api/src/main/java/org/drools/journal/api/JournalStorage.java`
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalStorage.java`
- Modify: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java` (stub)
- Test: `drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java`

**Interfaces:**
- Consumes: `InMemoryJournalStorage` internal `journal` list and `pageById` map
- Produces: `JournalStorage.retirePages(String[])` — used by Tasks 4, 5

- [ ] **Step 1: Write the failing test — retirePages removes pages from InMemory storage**

Add to `CompactionCoordinatorTest.java`:

```java
@Test
void retirePages_inMemory_removesSourcePagesFromJournal() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    storage.insert(1L, "a");
    storage.safepoint(0);           // page "0"
    storage.insert(2L, "b");
    storage.safepoint(1);           // page "1"

    assertThat(storage.currentPageNumber()).isEqualTo(2);

    storage.retirePages(new String[]{"0"});

    assertThat(storage.currentPageNumber()).isEqualTo(1);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: compilation error — `retirePages` does not exist on `JournalStorage` or `InMemoryJournalStorage`

- [ ] **Step 3: Add `retirePages` to `JournalStorage` SPI**

Add to `JournalStorage.java` in the semantic write API section, after the `safepoint()` method:

```java
/**
 * Deletes the physical storage for the given pages. Called only for pages
 * whose retirement is sealed (caller's responsibility). Idempotent — silently
 * ignores page IDs that do not exist.
 *
 * @param pageIds IDs of the pages to retire
 */
void retirePages(String[] pageIds);
```

- [ ] **Step 4: Implement in InMemoryJournalStorage**

Add to `InMemoryJournalStorage.java`:

```java
@Override
public synchronized void retirePages(final String[] pageIds) {
    checkOpen();
    Set<String> toRetire = Set.of(pageIds);
    journal.removeIf(page -> toRetire.contains(page.id));
    for (final String id : pageIds) {
        pageById.remove(id);
    }
}
```

Add `import java.util.Set;` if not already present.

- [ ] **Step 5: Add stub in ChronicleJournalStorage**

Add to `ChronicleJournalStorage.java`:

```java
@Override
public void retirePages(final String[] pageIds) {
    // TODO #34: implement Chronicle page directory deletion
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: PASS

- [ ] **Step 7: Write test — retirePages is idempotent**

Add to `CompactionCoordinatorTest.java`:

```java
@Test
void retirePages_inMemory_unknownPageId_isIgnored() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    storage.insert(1L, "a");
    storage.safepoint(0);

    storage.retirePages(new String[]{"nonexistent"});

    assertThat(storage.currentPageNumber()).isEqualTo(1);
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add drools-journal-api/src/main/java/org/drools/journal/api/JournalStorage.java \
       drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalStorage.java \
       drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java \
       drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java
git commit -m "feat: add retirePages to JournalStorage SPI with InMemory implementation

Refs #34"
```

---

### Task 4: Wire retirement into `CompactionCoordinator.runCycle`

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java`
- Test: `drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java`

**Interfaces:**
- Consumes: `PageIndex.buildLivePageSet(JournalScanner)` returning `PageIndexStatus`, `JournalStorage.retirePages(String[])`
- Produces: `CompactionCoordinator.runCycle()` now retires sealed source pages before liveness scanning

- [ ] **Step 1: Write the failing test — runCycle retires after sealed compaction**

Add to `CompactionCoordinatorTest.java`:

```java
@Test
void runCycle_afterSealedCompaction_retiresSourcePages() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    storage.insert(1L, "a");
    storage.insert(2L, "b");
    storage.insert(3L, "c");
    storage.insert(4L, "d");
    storage.safepoint(0);           // page "0": 4 inserts
    storage.retract(2L);
    storage.retract(3L);
    storage.retract(4L);
    storage.safepoint(1);           // page "1": 3 retracts

    CompactionCoordinator.compact(storage, Set.of("0", "1"));
    storage.safepoint(2);           // seals the COMMIT

    int pageCountBefore = storage.currentPageNumber();

    CompactionCoordinator coordinator = new CompactionCoordinator(storage, Duration.ZERO);
    coordinator.runCycleOnce();

    assertThat(storage.currentPageNumber()).isLessThan(pageCountBefore);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: compilation error — `runCycleOnce()` does not exist (currently `runCycle` is private)

- [ ] **Step 3: Extract retirement logic and expose runCycleOnce**

In `CompactionCoordinator.java`:

Change `runCycle` visibility and add the retirement step. Change the method from `private` to package-private and rename to `runCycleOnce` for testability (keep the scheduler calling the original name):

```java
private void runCycle() {
    runCycleOnce();
}

void runCycleOnce() {
    PageIndex.PageIndexStatus pageStatus;
    try (JournalScanner phase0 = storage.scan(0)) {
        pageStatus = PageIndex.buildLivePageSet(phase0);
    }

    if (!pageStatus.retiredPages().isEmpty()) {
        storage.retirePages(pageStatus.retiredPages().toArray(new String[0]));
    }

    Map<String, long[]> liveness = scanLiveness(storage, pageStatus.livePages());
    Set<String> candidates = new HashSet<>();
    liveness.forEach((id, counts) -> {
        if (isSparse(counts)) {
            candidates.add(id);
        }
    });
    if (!candidates.isEmpty()) {
        compact(storage, candidates);
    }
}
```

Refactor `scanLiveness` to accept the live page set instead of recomputing it:

```java
static Map<String, long[]> scanLiveness(final JournalStorage storage, final Set<String> livePageIds) {
    final Map<String, long[]> liveness = new HashMap<>();
    final Map<Long, String> factToPage = new HashMap<>();
    final List<JournalRecord> pending = new ArrayList<>();
    String lastPageId = null;

    try (JournalScanner scanner = storage.scan(0)) {
        while (scanner.hasNext()) {
            final JournalRecord record = scanner.next();
            final String pageId = scanner.currentPageId();

            if (!pageId.equals(lastPageId) && lastPageId != null) {
                if (livePageIds.contains(lastPageId)) {
                    flushPageLiveness(lastPageId, pending, liveness, factToPage);
                }
                pending.clear();
            }
            lastPageId = pageId;

            if (record instanceof SafepointRecord) {
                if (livePageIds.contains(pageId)) {
                    flushPageLiveness(pageId, pending, liveness, factToPage);
                }
                pending.clear();
            } else if (!(record instanceof CompactionPrepareRecord
                    || record instanceof CompactionCommitRecord)) {
                pending.add(record);
            }
        }
    }

    return liveness;
}
```

Keep a backward-compatible overload for the existing tests:

```java
static Map<String, long[]> scanLiveness(final JournalStorage storage) {
    final Set<String> livePageIds;
    try (JournalScanner phase0 = storage.scan(0)) {
        livePageIds = PageIndex.buildLivePageSet(phase0).livePages();
    }
    return scanLiveness(storage, livePageIds);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: all tests pass

- [ ] **Step 5: Write test — runCycle does NOT retire when commit is unsealed**

Add to `CompactionCoordinatorTest.java`:

```java
@Test
void runCycle_unsealedCompaction_doesNotRetireSourcePages() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    storage.insert(1L, "a");
    storage.insert(2L, "b");
    storage.safepoint(0);
    storage.retract(2L);
    storage.safepoint(1);

    CompactionCoordinator.compact(storage, Set.of("0", "1"));
    // No safepoint — commit is not sealed

    int pageCountBefore = storage.currentPageNumber();

    CompactionCoordinator coordinator = new CompactionCoordinator(storage, Duration.ZERO);
    coordinator.runCycleOnce();

    assertThat(storage.currentPageNumber()).isEqualTo(pageCountBefore);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn clean install -pl drools-journal-core -am 2>&1 | tail -30`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add drools-journal-core/src/main/java/org/drools/journal/core/CompactionCoordinator.java \
       drools-journal-core/src/test/java/org/drools/journal/core/CompactionCoordinatorTest.java
git commit -m "feat: wire page retirement into compaction cycle

Refs #34"
```

---

### Task 5: Implement Chronicle `retirePages` and integration tests

**Files:**
- Modify: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java`
- Test: `drools-journal-tests/src/test/java/org/drools/journal/test/ChroniclePageRetirementIT.java` (create)

**Interfaces:**
- Consumes: `JournalStorage.retirePages(String[])`, `ChronicleJournalStorage.atPath(String)`, `CompactionCoordinator.compact(JournalStorage, Set<String>)`
- Produces: `ChronicleJournalStorage.retirePages` physically deletes `page-{id}/` directories

- [ ] **Step 1: Write the failing integration test — retirePages deletes page directories**

Create `drools-journal-tests/src/test/java/org/drools/journal/test/ChroniclePageRetirementIT.java`:

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
package org.drools.journal.test;

import java.nio.file.Path;
import java.time.Duration;

import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.chronicle.ChronicleJournalStorage;
import org.drools.journal.core.JournalledKieSession;
import org.drools.journal.core.JournalledSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;

import static org.assertj.core.api.Assertions.assertThat;

class ChroniclePageRetirementIT {

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
    void retirePages_chronicle_deletesPageDirectories() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            assertThat(Path.of(path, "page-0")).isDirectory();
            assertThat(Path.of(path, "page-1")).isDirectory();

            storage.retirePages(new String[]{"0", "1"});

            assertThat(Path.of(path, "page-0")).doesNotExist();
            assertThat(Path.of(path, "page-1")).doesNotExist();
        }
    }

    @Test
    void retirePages_chronicle_nonexistentPage_isIgnored() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();

            storage.retirePages(new String[]{"nonexistent"});

            assertThat(Path.of(path, "page-0")).isDirectory();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn clean install 2>&1 | tail -30`
Expected: FAIL — `retirePages` is a stub that does nothing, so `page-0` and `page-1` still exist

- [ ] **Step 3: Implement Chronicle retirePages**

In `ChronicleJournalStorage.java`, replace the stub with:

```java
@Override
public void retirePages(final String[] pageIds) {
    for (final String pageId : pageIds) {
        Path pageDir = rootDir.resolve("page-" + pageId);
        if (java.nio.file.Files.exists(pageDir)) {
            deleteRecursively(pageDir);
        }
    }
}

private static void deleteRecursively(final Path dir) {
    try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(dir)) {
        walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        java.nio.file.Files.delete(p);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
    }
}
```

Add imports at the top of the file:

```java
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.stream.Stream;
```

Then simplify the method body to use the short names:

```java
@Override
public void retirePages(final String[] pageIds) {
    for (final String pageId : pageIds) {
        Path pageDir = rootDir.resolve("page-" + pageId);
        if (Files.exists(pageDir)) {
            deleteRecursively(pageDir);
        }
    }
}

private static void deleteRecursively(final Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn clean install 2>&1 | tail -30`
Expected: all tests pass

- [ ] **Step 5: Write integration test — full cycle with restore**

Add to `ChroniclePageRetirementIT.java`:

```java
@Test
void compactAndRetire_chronicle_restoreStillWorks() {
    KieBase kbase = new KieHelper().addContent(RULE, ResourceType.DRL).build();
    String journalPath = tempDir.resolve("full-cycle").toString();

    try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
         JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
        session.insert(42);
        session.insert(99);
        session.fireAllRules();
    }

    // Retire source pages
    try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath)) {
        storage.retirePages(new String[]{"0"});
        assertThat(Path.of(journalPath, "page-0")).doesNotExist();
    }

    // Restore from journal — should still work because the merged/surviving pages exist
    try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
         JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
        @SuppressWarnings("unchecked")
        java.util.Collection<Object> objects = (java.util.Collection<Object>) session.getObjects();
        assertThat(objects).containsExactlyInAnyOrder(42, 99);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn clean install 2>&1 | tail -30`
Expected: all tests pass

- [ ] **Step 7: Commit**

```bash
git add drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java \
       drools-journal-tests/src/test/java/org/drools/journal/test/ChroniclePageRetirementIT.java
git commit -m "feat: implement Chronicle retirePages with directory deletion

Refs #34"
```

---

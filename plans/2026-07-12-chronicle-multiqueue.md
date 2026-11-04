# Chronicle Multi-Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `ChronicleJournalStorage` from a single Chronicle Queue with embedded page IDs to a queue-per-page directory structure with a dedicated catalog queue.

**Architecture:** Each logical page maps to its own Chronicle Queue directory under a root path. A catalog queue tracks page lifecycle (`PageCreated`, `CompactionPrepareRecord`, `CompactionCommitRecord`). The scanner reads the catalog first to build the live page order, then chains through data queues sequentially. The `JournalStorage` SPI is unchanged — all changes are internal to `drools-journal-chronicle`.

**Tech Stack:** Java 21, Chronicle Queue 2026.4, JUnit 5, AssertJ

## Global Constraints

- **License header:** `Copyright (c) 2026 Drools Journal Authors` (Apache 2.0) on all Java files.
- **`final` usage:** Use `final` on fields and parameters only. Not on local variables.
- **No SPI changes:** `JournalStorage`, `JournalScanner`, and all `drools-journal-api` types are unchanged.
- **No changes** to `RestoreEngine`, `CompactionCoordinator`, or `InMemoryJournalStorage`.
- **Build command:** Always `mvn clean install` (never incremental).
- **JVM flags for Chronicle tests:** The `drools-journal-chronicle` module currently has no unit tests. Adding tests requires Surefire `argLine` configuration matching what `drools-journal-tests` already has.

## File Structure

**Create (new files):**
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleDataWriteOps.java` — Wire interface for data page queues (no pageId argument)
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleCatalogWriteOps.java` — Wire interface for the catalog queue
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleDataRecordHandler.java` — MethodReader handler for data queue records
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/CatalogIndex.java` — Reads the catalog queue and builds the live page list

**Rewrite (existing files):**
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java` — Single-queue → multi-queue directory manager
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalScanner.java` — Single-tailer → catalog-then-data-chain scanner

**Delete (replaced by the above):**
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleWriteOps.java`
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleRecordHandler.java`

**Unchanged:**
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/PayloadCodec.java`

**Test files (new):**
- `drools-journal-chronicle/src/test/java/org/drools/journal/chronicle/internal/ChronicleDataRecordHandlerTest.java`
- `drools-journal-chronicle/src/test/java/org/drools/journal/chronicle/internal/CatalogIndexTest.java`
- `drools-journal-chronicle/src/test/java/org/drools/journal/chronicle/ChronicleJournalScannerTest.java`
- `drools-journal-chronicle/src/test/java/org/drools/journal/chronicle/ChronicleJournalStorageTest.java`

---

### Task 1: Wire Interfaces and Data Record Handler

**Files:**
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleDataWriteOps.java`
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleCatalogWriteOps.java`
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleDataRecordHandler.java`
- Create: `drools-journal-chronicle/src/test/java/org/drools/journal/chronicle/internal/ChronicleDataRecordHandlerTest.java`
- Modify: `drools-journal-chronicle/pom.xml` (add Surefire argLine for Chronicle on Java 21)

**Interfaces:**
- Consumes: `PayloadCodec` (existing), API record types (`InsertRecord`, `RetractRecord`, etc.)
- Produces:
  - `ChronicleDataWriteOps` — used by storage (write) and scanner (read via MethodReader)
  - `ChronicleCatalogWriteOps` — used by storage (write) and CatalogIndex (read via MethodReader)
  - `ChronicleDataRecordHandler` — used by scanner to capture data records; field `public JournalRecord pending`

- [ ] **Step 1: Add Surefire argLine to chronicle module POM**

Add the `maven-surefire-plugin` configuration to `drools-journal-chronicle/pom.xml` inside a `<build><plugins>` block, matching the flags from `drools-journal-tests/pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <configuration>
        <argLine>
          --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED
          --add-exports=java.base/sun.nio.ch=ALL-UNNAMED
          --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED
          --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
          --add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED
          --add-opens=java.base/java.lang=ALL-UNNAMED
          --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
          --add-opens=java.base/java.io=ALL-UNNAMED
          --add-opens=java.base/java.util=ALL-UNNAMED
        </argLine>
      </configuration>
    </plugin>
  </plugins>
</build>
```

- [ ] **Step 2: Create `ChronicleDataWriteOps` interface**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle.internal;

public interface ChronicleDataWriteOps {
    void insert(long factHandleId, byte[] payload);
    void insertLogical(long factHandleId, byte[] payload, long justifyingRuleMatchId);
    void retract(long factHandleId);
    void modify(long factHandleId, String lambdaClassRef, byte[] parameters);
    void ruleMatch(long id, String packageName, String ruleName, long[] factHandleIds);
    void safepoint(long sequenceNo, long timestamp);
}
```

- [ ] **Step 3: Create `ChronicleCatalogWriteOps` interface**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle.internal;

public interface ChronicleCatalogWriteOps {
    void pageCreated(String pageId);
    void compactionPrepare(String preparingPageId, String[] replacedPageIds);
    void compactionCommit(String mergedPageId, String[] replacedPageIds);
}
```

- [ ] **Step 4: Create `ChronicleDataRecordHandler`**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle.internal;

import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

public final class ChronicleDataRecordHandler implements ChronicleDataWriteOps {

    public JournalRecord pending;

    @Override
    public void insert(final long factHandleId, final byte[] payload) {
        pending = new InsertRecord(factHandleId, false, -1L, PayloadCodec.decode(payload));
    }

    @Override
    public void insertLogical(final long factHandleId, final byte[] payload, final long justifyingRuleMatchId) {
        pending = new InsertRecord(factHandleId, true, justifyingRuleMatchId, PayloadCodec.decode(payload));
    }

    @Override
    public void retract(final long factHandleId) {
        pending = new RetractRecord(factHandleId);
    }

    @Override
    public void modify(final long factHandleId, final String lambdaClassRef, final byte[] parameters) {
        pending = new ModifyRecord(factHandleId, lambdaClassRef, parameters);
    }

    @Override
    public void ruleMatch(final long id, final String packageName, final String ruleName, final long[] factHandleIds) {
        pending = new RuleMatchRecord(id, packageName, ruleName, factHandleIds);
    }

    @Override
    public void safepoint(final long sequenceNo, final long timestamp) {
        pending = new SafepointRecord(sequenceNo, timestamp);
    }
}
```

- [ ] **Step 5: Write the failing test for `ChronicleDataRecordHandler`**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle.internal;

import java.nio.file.Path;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ChronicleDataRecordHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTrip_insert_capturesInsertRecord() {
        try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(tempDir.resolve("q")).build()) {
            ChronicleDataWriteOps writer = queue.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
            byte[] payload = PayloadCodec.encode(new EmbeddedPayload(new byte[]{1, 2, 3}));
            writer.insert(42L, payload);

            ChronicleDataRecordHandler handler = new ChronicleDataRecordHandler();
            MethodReader reader = queue.createTailer().methodReader(handler);
            assertThat(reader.readOne()).isTrue();
            assertThat(handler.pending).isInstanceOf(InsertRecord.class);
            InsertRecord ir = (InsertRecord) handler.pending;
            assertThat(ir.factHandleId()).isEqualTo(42L);
            assertThat(ir.logical()).isFalse();
        }
    }

    @Test
    void roundTrip_retract_capturesRetractRecord() {
        try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(tempDir.resolve("q")).build()) {
            ChronicleDataWriteOps writer = queue.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
            writer.retract(99L);

            ChronicleDataRecordHandler handler = new ChronicleDataRecordHandler();
            MethodReader reader = queue.createTailer().methodReader(handler);
            assertThat(reader.readOne()).isTrue();
            assertThat(handler.pending).isInstanceOf(RetractRecord.class);
            assertThat(((RetractRecord) handler.pending).factHandleId()).isEqualTo(99L);
        }
    }

    @Test
    void roundTrip_safepoint_capturesSafepointRecord() {
        try (SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(tempDir.resolve("q")).build()) {
            ChronicleDataWriteOps writer = queue.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
            writer.safepoint(7L, 1234567890L);

            ChronicleDataRecordHandler handler = new ChronicleDataRecordHandler();
            MethodReader reader = queue.createTailer().methodReader(handler);
            assertThat(reader.readOne()).isTrue();
            assertThat(handler.pending).isInstanceOf(SafepointRecord.class);
            SafepointRecord sr = (SafepointRecord) handler.pending;
            assertThat(sr.sequenceNo()).isEqualTo(7L);
            assertThat(sr.timestamp()).isEqualTo(1234567890L);
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn clean install -pl drools-journal-chronicle`

Expected: All 3 tests PASS. Old code (`ChronicleWriteOps`, `ChronicleRecordHandler`) still compiles — it is not deleted yet.

- [ ] **Step 7: Commit**

```
feat(chronicle): add multi-queue wire interfaces and data record handler

Refs #34
```

---

### Task 2: CatalogIndex — Catalog Replay Logic

**Files:**
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/CatalogIndex.java`
- Create: `drools-journal-chronicle/src/test/java/org/drools/journal/chronicle/internal/CatalogIndexTest.java`

**Interfaces:**
- Consumes: `ChronicleCatalogWriteOps` (Task 1), Chronicle Queue API
- Produces: `CatalogIndex` — used by scanner and storage construction; methods:
  - `static CatalogIndex build(SingleChronicleQueue catalogQueue)` — reads catalog, returns populated index
  - `List<String> livePages()` — ordered list of live page IDs
  - `int highestPageCounter()` — highest numeric page ID seen (for setting `pageIdCounter` on reopen)

- [ ] **Step 1: Write the failing test for `CatalogIndex`**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle.internal;

import java.nio.file.Path;

import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void pagesOnly_returnsAllInOrder() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.pageCreated("2");

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("0", "1", "2");
            assertThat(index.highestPageCounter()).isEqualTo(2);
        }
    }

    @Test
    void compactionCommit_splicesReplacedPages() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.pageCreated("2");
            writer.compactionPrepare("m-1", new String[]{"0", "1"});
            writer.compactionCommit("m-1", new String[]{"0", "1"});

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("m-1", "2");
            assertThat(index.highestPageCounter()).isEqualTo(2);
        }
    }

    @Test
    void prepareWithoutCommit_leavesOriginalPages() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.compactionPrepare("m-1", new String[]{"0", "1"});

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("0", "1");
        }
    }

    @Test
    void twoRoundsOfCompaction_resolveCorrectly() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps writer = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            writer.pageCreated("0");
            writer.pageCreated("1");
            writer.pageCreated("2");
            writer.pageCreated("3");
            writer.compactionCommit("m-1", new String[]{"0", "1"});
            writer.compactionCommit("m-2", new String[]{"m-1", "2"});

            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).containsExactly("m-2", "3");
        }
    }

    @Test
    void emptyCatalog_returnsEmptyList() {
        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(tempDir.resolve("catalog")).build()) {
            CatalogIndex index = CatalogIndex.build(catalog);
            assertThat(index.livePages()).isEmpty();
            assertThat(index.highestPageCounter()).isEqualTo(0);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn clean install -pl drools-journal-chronicle`

Expected: FAIL — `CatalogIndex` class does not exist.

- [ ] **Step 3: Implement `CatalogIndex`**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle.internal;

import java.util.ArrayList;
import java.util.List;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;

public final class CatalogIndex implements ChronicleCatalogWriteOps {

    private final List<String> pages = new ArrayList<>();
    private int highestPageCounter;

    @Override
    public void pageCreated(final String pageId) {
        pages.add(pageId);
        try {
            int n = Integer.parseInt(pageId);
            if (n > highestPageCounter) {
                highestPageCounter = n;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public void compactionPrepare(final String preparingPageId, final String[] replacedPageIds) {
    }

    @Override
    public void compactionCommit(final String mergedPageId, final String[] replacedPageIds) {
        spliceIntoIndex(pages, mergedPageId, replacedPageIds);
    }

    public List<String> livePages() {
        return List.copyOf(pages);
    }

    public int highestPageCounter() {
        return highestPageCounter;
    }

    public static CatalogIndex build(final SingleChronicleQueue catalogQueue) {
        CatalogIndex index = new CatalogIndex();
        ExcerptTailer tailer = catalogQueue.createTailer();
        tailer.toStart();
        MethodReader reader = tailer.methodReader(index);
        while (reader.readOne()) {
            // entries processed in-place via the handler methods above
        }
        return index;
    }

    static void spliceIntoIndex(final List<String> pageIndex, final String mergedId, final String[] replacedIds) {
        int splicePos = -1;
        for (String replaced : replacedIds) {
            int pos = pageIndex.indexOf(replaced);
            if (pos >= 0) {
                if (splicePos < 0 || pos < splicePos) {
                    splicePos = pos;
                }
                pageIndex.remove(pos);
                if (splicePos > pos) {
                    splicePos--;
                }
            }
        }
        if (splicePos >= 0) {
            pageIndex.add(splicePos, mergedId);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn clean install -pl drools-journal-chronicle`

Expected: All CatalogIndex tests PASS.

- [ ] **Step 5: Commit**

```
feat(chronicle): add CatalogIndex for catalog replay and page ordering

Refs #34
```

---

### Task 3: Multi-Queue Scanner

**Files:**
- Rewrite: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalScanner.java`
- Create: `drools-journal-chronicle/src/test/java/org/drools/journal/chronicle/ChronicleJournalScannerTest.java`

**Interfaces:**
- Consumes: `CatalogIndex` (Task 2), `ChronicleDataRecordHandler` (Task 1), `ChronicleDataWriteOps` (Task 1), `ChronicleCatalogWriteOps` (Task 1)
- Produces: `ChronicleJournalScanner` implementing `JournalScanner` — constructor: `ChronicleJournalScanner(Path rootDir, SingleChronicleQueue catalogQueue)`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle;

import java.nio.file.Path;

import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.SafepointRecord;
import org.drools.journal.chronicle.internal.ChronicleCatalogWriteOps;
import org.drools.journal.chronicle.internal.ChronicleDataWriteOps;
import org.drools.journal.chronicle.internal.PayloadCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ChronicleJournalScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scansTwoPagesInCatalogOrder() {
        Path root = tempDir.resolve("journal");
        root.toFile().mkdirs();
        byte[] payload = PayloadCodec.encode(new EmbeddedPayload(new byte[]{1}));

        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(root.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps catWriter = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            catWriter.pageCreated("0");
            catWriter.pageCreated("1");

            try (SingleChronicleQueue page0 = SingleChronicleQueueBuilder.binary(root.resolve("page-0")).build()) {
                ChronicleDataWriteOps w0 = page0.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
                w0.insert(1L, payload);
                w0.safepoint(0L, 1000L);
            }

            try (SingleChronicleQueue page1 = SingleChronicleQueueBuilder.binary(root.resolve("page-1")).build()) {
                ChronicleDataWriteOps w1 = page1.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
                w1.insert(2L, payload);
                w1.safepoint(1L, 2000L);
            }

            try (ChronicleJournalScanner scanner = new ChronicleJournalScanner(root, catalog)) {
                assertThat(scanner.hasNext()).isTrue();
                assertThat(scanner.currentPageId()).isEqualTo("0");
                JournalRecord r1 = scanner.next();
                assertThat(r1).isInstanceOf(InsertRecord.class);
                assertThat(((InsertRecord) r1).factHandleId()).isEqualTo(1L);
                assertThat(scanner.currentPageId()).isEqualTo("0");

                JournalRecord r2 = scanner.next();
                assertThat(r2).isInstanceOf(SafepointRecord.class);

                assertThat(scanner.currentPageId()).isEqualTo("1");
                JournalRecord r3 = scanner.next();
                assertThat(r3).isInstanceOf(InsertRecord.class);
                assertThat(((InsertRecord) r3).factHandleId()).isEqualTo(2L);

                JournalRecord r4 = scanner.next();
                assertThat(r4).isInstanceOf(SafepointRecord.class);

                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void skipsEmptyPage() {
        Path root = tempDir.resolve("journal");
        root.toFile().mkdirs();
        byte[] payload = PayloadCodec.encode(new EmbeddedPayload(new byte[]{1}));

        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(root.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps catWriter = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            catWriter.pageCreated("0");
            catWriter.pageCreated("1");

            try (SingleChronicleQueue page0 = SingleChronicleQueueBuilder.binary(root.resolve("page-0")).build()) {
                // empty — no records written
            }

            try (SingleChronicleQueue page1 = SingleChronicleQueueBuilder.binary(root.resolve("page-1")).build()) {
                ChronicleDataWriteOps w1 = page1.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
                w1.insert(1L, payload);
            }

            try (ChronicleJournalScanner scanner = new ChronicleJournalScanner(root, catalog)) {
                assertThat(scanner.hasNext()).isTrue();
                assertThat(scanner.currentPageId()).isEqualTo("1");
                JournalRecord r = scanner.next();
                assertThat(r).isInstanceOf(InsertRecord.class);
                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void compactedPages_readInMergedOrder() {
        Path root = tempDir.resolve("journal");
        root.toFile().mkdirs();
        byte[] payload = PayloadCodec.encode(new EmbeddedPayload(new byte[]{1}));

        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(root.resolve("catalog")).build()) {
            ChronicleCatalogWriteOps catWriter = catalog.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);
            catWriter.pageCreated("0");
            catWriter.pageCreated("1");
            catWriter.pageCreated("2");
            catWriter.compactionCommit("m-1", new String[]{"0", "1"});

            // Old pages still on disk (retirement deferred) but not in live list
            try (SingleChronicleQueue page0 = SingleChronicleQueueBuilder.binary(root.resolve("page-0")).build()) {
                page0.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(1L, payload);
            }
            try (SingleChronicleQueue page1 = SingleChronicleQueueBuilder.binary(root.resolve("page-1")).build()) {
                page1.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(2L, payload);
            }

            // Merged page and active page
            try (SingleChronicleQueue merged = SingleChronicleQueueBuilder.binary(root.resolve("page-m-1")).build()) {
                merged.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(10L, payload);
            }
            try (SingleChronicleQueue page2 = SingleChronicleQueueBuilder.binary(root.resolve("page-2")).build()) {
                page2.acquireAppender().methodWriter(ChronicleDataWriteOps.class).insert(20L, payload);
            }

            try (ChronicleJournalScanner scanner = new ChronicleJournalScanner(root, catalog)) {
                assertThat(scanner.currentPageId()).isEqualTo("m-1");
                InsertRecord r1 = (InsertRecord) scanner.next();
                assertThat(r1.factHandleId()).isEqualTo(10L);

                assertThat(scanner.currentPageId()).isEqualTo("2");
                InsertRecord r2 = (InsertRecord) scanner.next();
                assertThat(r2.factHandleId()).isEqualTo(20L);

                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void emptyCatalog_scanHasNoRecords() {
        Path root = tempDir.resolve("journal");
        root.toFile().mkdirs();

        try (SingleChronicleQueue catalog = SingleChronicleQueueBuilder.binary(root.resolve("catalog")).build()) {
            try (ChronicleJournalScanner scanner = new ChronicleJournalScanner(root, catalog)) {
                assertThat(scanner.hasNext()).isFalse();
                assertThat(scanner.currentPageId()).isNull();
            }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn clean install -pl drools-journal-chronicle`

Expected: FAIL — `ChronicleJournalScanner` constructor signature doesn't match.

- [ ] **Step 3: Rewrite `ChronicleJournalScanner`**

Replace the entire file content:

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle;

import java.nio.file.Path;
import java.util.List;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.chronicle.internal.CatalogIndex;
import org.drools.journal.chronicle.internal.ChronicleDataRecordHandler;

final class ChronicleJournalScanner implements JournalScanner {

    private final Path rootDir;
    private final List<String> livePages;
    private final ChronicleDataRecordHandler handler;
    private int currentPageListIndex;
    private String currentPageId;
    private SingleChronicleQueue currentQueue;
    private MethodReader currentReader;
    private JournalRecord buffered;
    private long syntheticPosition;
    private boolean closed;

    ChronicleJournalScanner(final Path rootDir, final SingleChronicleQueue catalogQueue) {
        this.rootDir = rootDir;
        CatalogIndex index = CatalogIndex.build(catalogQueue);
        this.livePages = index.livePages();
        this.handler = new ChronicleDataRecordHandler();
        this.currentPageListIndex = -1;
        advanceToNextQueue();
    }

    @Override
    public boolean hasNext() {
        return buffered != null;
    }

    @Override
    public JournalRecord next() {
        JournalRecord result = buffered;
        syntheticPosition++;
        advance();
        return result;
    }

    @Override
    public long position() {
        return syntheticPosition;
    }

    @Override
    public String currentPageId() {
        return currentPageId;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeCurrentQueue();
        }
    }

    private void advance() {
        handler.pending = null;
        if (currentReader != null && currentReader.readOne()) {
            buffered = handler.pending;
        } else {
            closeCurrentQueue();
            advanceToNextQueue();
        }
    }

    private void advanceToNextQueue() {
        buffered = null;
        while (++currentPageListIndex < livePages.size()) {
            currentPageId = livePages.get(currentPageListIndex);
            Path queuePath = rootDir.resolve("page-" + currentPageId);
            if (!queuePath.toFile().exists()) {
                continue;
            }
            currentQueue = SingleChronicleQueueBuilder.binary(queuePath).build();
            var tailer = currentQueue.createTailer();
            tailer.toStart();
            currentReader = tailer.methodReader(handler);
            handler.pending = null;
            if (currentReader.readOne()) {
                buffered = handler.pending;
                return;
            }
            closeCurrentQueue();
        }
        currentPageId = livePages.isEmpty() ? null : currentPageId;
    }

    private void closeCurrentQueue() {
        if (currentQueue != null) {
            currentQueue.close();
            currentQueue = null;
            currentReader = null;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn clean install -pl drools-journal-chronicle`

Expected: All scanner tests PASS.

- [ ] **Step 5: Commit**

```
feat(chronicle): rewrite scanner for multi-queue catalog-then-data chaining

Refs #34
```

---

### Task 4: Multi-Queue Storage

**Files:**
- Rewrite: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java`
- Delete: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleWriteOps.java`
- Delete: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleRecordHandler.java`
- Create: `drools-journal-chronicle/src/test/java/org/drools/journal/chronicle/ChronicleJournalStorageTest.java`

**Interfaces:**
- Consumes: `ChronicleDataWriteOps` (Task 1), `ChronicleCatalogWriteOps` (Task 1), `CatalogIndex` (Task 2), `ChronicleJournalScanner` (Task 3)
- Produces: `ChronicleJournalStorage` implementing `JournalStorage` — same public API as today, constructor: `ChronicleJournalStorage.atPath(String path)` and `atPath(String path, PageRollStrategy)`

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.SafepointRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ChronicleJournalStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void freshJournal_createsDirectoryLayout() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            assertThat(Path.of(path, "catalog")).isDirectory();
            assertThat(Path.of(path, "page-0")).isDirectory();
        }
    }

    @Test
    void insertAndSafepoint_thenScan_returnsRecords() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();

            List<JournalRecord> records = scanAll(storage);
            assertThat(records).hasSize(2);
            assertThat(records.get(0)).isInstanceOf(InsertRecord.class);
            assertThat(((InsertRecord) records.get(0)).factHandleId()).isEqualTo(1L);
            assertThat(records.get(1)).isInstanceOf(SafepointRecord.class);
        }
    }

    @Test
    void safepoint_rollsToNewPage() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            assertThat(Path.of(path, "page-0")).isDirectory();
            assertThat(Path.of(path, "page-1")).isDirectory();
            assertThat(Path.of(path, "page-2")).isDirectory();
        }
    }

    @Test
    void scanAcrossPages_reportsCorrectPageIds() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            try (JournalScanner scanner = storage.scan(0)) {
                assertThat(scanner.currentPageId()).isEqualTo("0");
                scanner.next(); // insert
                assertThat(scanner.currentPageId()).isEqualTo("0");
                scanner.next(); // safepoint
                assertThat(scanner.currentPageId()).isEqualTo("1");
                scanner.next(); // insert
                assertThat(scanner.currentPageId()).isEqualTo("1");
                scanner.next(); // safepoint
                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    @Test
    void reopen_resumesFromLastState() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
        }

        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            List<JournalRecord> records = scanAll(storage);
            assertThat(records).hasSize(4); // insert, safepoint, insert, safepoint
            assertThat(((InsertRecord) records.get(0)).factHandleId()).isEqualTo(1L);
            assertThat(((InsertRecord) records.get(2)).factHandleId()).isEqualTo(2L);
        }
    }

    @Test
    void latestPosition_negativeOneWhenEmpty() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            assertThat(storage.latestPosition()).isEqualTo(-1L);
        }
    }

    @Test
    void latestPosition_positiveAfterWrite() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            assertThat(storage.latestPosition()).isGreaterThan(-1L);
        }
    }

    @Test
    void writeMergedPage_createsPageDirectory() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();

            List<JournalRecord> mergedRecords = List.of(
                    new InsertRecord(1L, false, -1L, new EmbeddedPayload(new byte[]{10}))
            );
            storage.writeMergedPage("m-1", mergedRecords);
            assertThat(Path.of(path, "page-m-1")).isDirectory();
        }
    }

    @Test
    void compactionPrepareAndCommit_updatedCatalog() {
        String path = tempDir.resolve("journal").toString();
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(path)) {
            storage.insert(1L, new EmbeddedPayload(new byte[]{10}));
            storage.safepoint();
            storage.insert(2L, new EmbeddedPayload(new byte[]{20}));
            storage.safepoint();

            List<JournalRecord> mergedRecords = List.of(
                    new InsertRecord(1L, false, -1L, new EmbeddedPayload(new byte[]{10})),
                    new InsertRecord(2L, false, -1L, new EmbeddedPayload(new byte[]{20}))
            );
            storage.compactionPrepare("m-1", new String[]{"0", "1"});
            storage.writeMergedPage("m-1", mergedRecords);
            storage.compactionCommit("m-1", new String[]{"0", "1"});

            // Scan should now read merged page then active page (page-2)
            try (JournalScanner scanner = storage.scan(0)) {
                assertThat(scanner.currentPageId()).isEqualTo("m-1");
                InsertRecord r1 = (InsertRecord) scanner.next();
                assertThat(r1.factHandleId()).isEqualTo(1L);
                InsertRecord r2 = (InsertRecord) scanner.next();
                assertThat(r2.factHandleId()).isEqualTo(2L);
                assertThat(scanner.hasNext()).isFalse();
            }
        }
    }

    private List<JournalRecord> scanAll(final ChronicleJournalStorage storage) {
        List<JournalRecord> records = new ArrayList<>();
        try (JournalScanner scanner = storage.scan(0)) {
            while (scanner.hasNext()) {
                records.add(scanner.next());
            }
        }
        return records;
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn clean install -pl drools-journal-chronicle`

Expected: FAIL — `ChronicleJournalStorage` still has old single-queue constructor.

- [ ] **Step 3: Rewrite `ChronicleJournalStorage`**

Replace the entire file content:

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * (full Apache 2.0 header)
 */
package org.drools.journal.chronicle;

import java.nio.file.Path;
import java.util.List;

import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.api.JournalStorage;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.PageContext;
import org.drools.journal.api.PageRollStrategies;
import org.drools.journal.api.PageRollStrategy;
import org.drools.journal.api.Payload;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RollDecision;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;
import org.drools.journal.chronicle.internal.CatalogIndex;
import org.drools.journal.chronicle.internal.ChronicleDataWriteOps;
import org.drools.journal.chronicle.internal.ChronicleCatalogWriteOps;
import org.drools.journal.chronicle.internal.PayloadCodec;

public final class ChronicleJournalStorage implements JournalStorage {

    private final Path rootDir;
    private final SingleChronicleQueue catalogQueue;
    private final ChronicleCatalogWriteOps catalogWriter;
    private final PageRollStrategy rollStrategy;
    private SingleChronicleQueue activePageQueue;
    private ChronicleDataWriteOps sessionWriter;
    private int pageIdCounter;
    private String currentPageId;
    private long lastWrittenPosition;
    private long safepointSequenceNo;
    private long currentPageBytes;
    private long currentRecordCount;
    private boolean closed;

    private ChronicleJournalStorage(final Path rootDir, final PageRollStrategy rollStrategy) {
        this.rootDir = rootDir;
        this.rollStrategy = rollStrategy;
        this.lastWrittenPosition = -1L;

        this.catalogQueue = openQueue(rootDir.resolve("catalog"));
        this.catalogWriter = catalogQueue.acquireAppender().methodWriter(ChronicleCatalogWriteOps.class);

        CatalogIndex index = CatalogIndex.build(catalogQueue);
        List<String> livePages = index.livePages();

        if (livePages.isEmpty()) {
            this.pageIdCounter = 0;
            this.currentPageId = "0";
            this.activePageQueue = openQueue(pageDir("0"));
            this.sessionWriter = newDataWriter(activePageQueue);
            catalogWriter.pageCreated("0");
        } else {
            this.pageIdCounter = index.highestPageCounter();
            this.currentPageId = livePages.get(livePages.size() - 1);
            this.activePageQueue = openQueue(pageDir(currentPageId));
            this.sessionWriter = newDataWriter(activePageQueue);
            long queueLastIndex = activePageQueue.lastIndex();
            this.lastWrittenPosition = (queueLastIndex == Long.MIN_VALUE) ? -1L : queueLastIndex;
        }
    }

    public static ChronicleJournalStorage atPath(final String path) {
        return atPath(path, PageRollStrategies.safepointOnly());
    }

    public static ChronicleJournalStorage atPath(final String path, final PageRollStrategy rollStrategy) {
        return new ChronicleJournalStorage(Path.of(path), rollStrategy);
    }

    @Override
    public long insert(final long factHandleId, final Payload payload) {
        byte[] encoded = PayloadCodec.encode(payload);
        sessionWriter.insert(factHandleId, encoded);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new InsertRecord(factHandleId, false, -1L, payload), encoded.length + 8);
        return lastWrittenPosition;
    }

    @Override
    public long insertLogical(final long factHandleId, final Payload payload, final long justifyingRuleMatchId) {
        byte[] encoded = PayloadCodec.encode(payload);
        sessionWriter.insertLogical(factHandleId, encoded, justifyingRuleMatchId);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new InsertRecord(factHandleId, true, justifyingRuleMatchId, payload), encoded.length + 16);
        return lastWrittenPosition;
    }

    @Override
    public long retract(final long factHandleId) {
        sessionWriter.retract(factHandleId);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new RetractRecord(factHandleId), 8);
        return lastWrittenPosition;
    }

    @Override
    public long modify(final long factHandleId, final String lambdaClassRef, final byte[] params) {
        sessionWriter.modify(factHandleId, lambdaClassRef, params);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new ModifyRecord(factHandleId, lambdaClassRef, params), params.length + 8);
        return lastWrittenPosition;
    }

    @Override
    public long ruleMatch(final long id, final String packageName, final String ruleName, final long[] factHandleIds) {
        sessionWriter.ruleMatch(id, packageName, ruleName, factHandleIds);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        maybeRoll(new RuleMatchRecord(id, packageName, ruleName, factHandleIds), factHandleIds.length * 8 + 8);
        return lastWrittenPosition;
    }

    @Override
    public long compactionPrepare(final String preparingPageId, final String[] replacedPageIds) {
        catalogWriter.compactionPrepare(preparingPageId, replacedPageIds);
        return catalogQueue.acquireAppender().lastIndexAppended();
    }

    @Override
    public long compactionCommit(final String mergedPageId, final String[] replacedPageIds) {
        catalogWriter.compactionCommit(mergedPageId, replacedPageIds);
        return catalogQueue.acquireAppender().lastIndexAppended();
    }

    @Override
    public void safepoint() {
        long seqNo = safepointSequenceNo++;
        long ts = System.currentTimeMillis();
        sessionWriter.safepoint(seqNo, ts);
        lastWrittenPosition = activeAppender().lastIndexAppended();
        roll();
    }

    @Override
    public JournalScanner scan(final long fromPosition) {
        return new ChronicleJournalScanner(rootDir, catalogQueue);
    }

    @Override
    public long latestPosition() {
        return lastWrittenPosition;
    }

    @Override
    public void writeMergedPage(final String pageId, final List<JournalRecord> records) {
        try (SingleChronicleQueue mergedQueue = openQueue(pageDir(pageId))) {
            ChronicleDataWriteOps w = newDataWriter(mergedQueue);
            for (JournalRecord record : records) {
                writeRecord(w, record);
            }
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            activePageQueue.close();
            catalogQueue.close();
        }
    }

    private ExcerptAppender activeAppender() {
        return activePageQueue.acquireAppender();
    }

    private void maybeRoll(final JournalRecord record, final long estimatedBytes) {
        currentPageBytes += estimatedBytes;
        currentRecordCount++;
        PageContext ctx = new PageContextSnapshot(record, currentPageBytes, currentRecordCount);
        if (rollStrategy.decide(ctx) == RollDecision.ROLL) {
            roll();
        }
    }

    private void roll() {
        activePageQueue.close();
        currentPageId = String.valueOf(++pageIdCounter);
        currentPageBytes = 0;
        currentRecordCount = 0;
        activePageQueue = openQueue(pageDir(currentPageId));
        sessionWriter = newDataWriter(activePageQueue);
        catalogWriter.pageCreated(currentPageId);
    }

    private Path pageDir(final String pageId) {
        return rootDir.resolve("page-" + pageId);
    }

    private static SingleChronicleQueue openQueue(final Path path) {
        return SingleChronicleQueueBuilder.binary(path).build();
    }

    private static ChronicleDataWriteOps newDataWriter(final SingleChronicleQueue queue) {
        return queue.acquireAppender().methodWriter(ChronicleDataWriteOps.class);
    }

    private static void writeRecord(final ChronicleDataWriteOps w, final JournalRecord record) {
        switch (record) {
            case InsertRecord ir when !ir.logical() ->
                    w.insert(ir.factHandleId(), PayloadCodec.encode(ir.payload()));
            case InsertRecord ir ->
                    w.insertLogical(ir.factHandleId(), PayloadCodec.encode(ir.payload()), ir.justifyingRuleMatchId());
            case RetractRecord rr ->
                    w.retract(rr.factHandleId());
            case ModifyRecord mr ->
                    w.modify(mr.factHandleId(), mr.lambdaClassRef(), mr.parameters());
            case RuleMatchRecord rm ->
                    w.ruleMatch(rm.id(), rm.packageName(), rm.ruleName(), rm.factHandleIds());
            case SafepointRecord sr ->
                    w.safepoint(sr.sequenceNo(), sr.timestamp());
            case CompactionPrepareRecord ignored ->
                    throw new IllegalArgumentException("CompactionPrepareRecord should not appear in data pages");
            case CompactionCommitRecord ignored ->
                    throw new IllegalArgumentException("CompactionCommitRecord should not appear in data pages");
        }
    }

    private record PageContextSnapshot(
            JournalRecord lastRecord,
            long currentPageBytes,
            long currentRecordCount) implements PageContext {
    }
}
```

- [ ] **Step 4: Delete old wire interface and handler**

Delete these two files:
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleWriteOps.java`
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleRecordHandler.java`

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn clean install -pl drools-journal-chronicle`

Expected: All storage and scanner tests PASS. No compilation errors from deleted files.

- [ ] **Step 6: Commit**

```
feat(chronicle): rewrite storage for queue-per-page directory architecture

Refs #34
```

---

### Task 5: Integration Tests

**Files:**
- Existing: `drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleJournalledKieSessionIT.java`

**Interfaces:**
- Consumes: `ChronicleJournalStorage.atPath(String)` (Task 4), `JournalledSessionFactory.open(KieBase, JournalStorage, Duration)` (existing core API)

- [ ] **Step 1: Run existing integration tests without changes**

Run: `mvn clean install`

Expected: Both existing IT tests PASS (`openFireDispose_thenReopen_restoresFact_andRuleDoesNotRefire` and `multiSession_insertsAccumulate_acrossRestarts`). These exercise the full write/safepoint/reopen/restore cycle through the new multi-queue storage — if they pass, the architecture is working end-to-end.

- [ ] **Step 2: Verify directory layout in a manual check**

Add a temporary assertion or inspect test output to confirm that after the IT test the journal directory contains `catalog/`, `page-0/`, `page-1/` etc. rather than a flat queue. This is a sanity check, not a permanent test.

- [ ] **Step 3: Commit (if any test adjustments were needed)**

```
test(chronicle): verify multi-queue integration with existing ITs

Refs #34
```

# Chronicle Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `ChronicleJournalStorage` and `ChronicleJournalScanner` so that the Chronicle backend passes the full `JournalStorageContractTest` suite and an end-to-end restore smoke test.

**Architecture:** `ChronicleJournalStorage` writes records via Chronicle Queue's `MethodWriter` using an internal `ChronicleWriteOps` interface; each call embeds a `pageId` string as the first argument. `ChronicleJournalScanner` reads back via `MethodReader`, reconstructing `JournalRecord` instances via `ChronicleRecordHandler`. Logical page IDs are a counter string ("0", "1", …) tracked in the storage and embedded in every Chronicle entry — they are independent of Chronicle's file roll cycle.

**Tech stack:** Java 21, Chronicle Queue `5.27ea1` (OSS, Apache 2.0), JUnit 5, AssertJ, `@TempDir`.

## Global Constraints

- License header on all new Java files: `Copyright (c) 2026 Drools Journal Authors` (Apache 2.0). Never use the Apache Software Foundation boilerplate.
- Build command: always `mvn clean install` — never incremental. Stale compiled files cause confusing failures.
- No `final` on local variables; `final` on fields and parameters only.
- Test method names: `action_context_expectedResult` (three camelCase segments joined by underscores).
- `PageRollStrategy`, `PageRollStrategies`, `PageContext`, `RollDecision` are already in `drools-journal-api` — do **not** create new copies.
- `drools-journal-tests/pom.xml` already declares `drools-journal-chronicle` as a test dependency — do **not** add it again.
- `ChronicleJournalStorage` has no internal synchronization — external serialization is the caller's responsibility.
- `safepoint()` always forces a page roll, unconditionally. Other write methods consult `rollStrategy.decide()`.

---

## File Map

**Create:**
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleWriteOps.java`
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/PayloadCodec.java`
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleRecordHandler.java`
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalScanner.java`
- `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java`
- `drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleStorageContractTest.java`
- `drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleJournalledKieSessionIT.java`

**Modify:**
- `pom.xml` (parent): add `chronicle-queue.version` property + Chronicle entry in `<dependencyManagement>`
- `drools-journal-chronicle/pom.xml`: replace the Chronicle TODO with the real dependency

---

## Task 1: Add Chronicle Queue dependency

**Files:**
- Modify: `pom.xml` (parent)
- Modify: `drools-journal-chronicle/pom.xml`

**Interfaces:**
- Produces: `ChronicleJournalStorage` compiles with Chronicle classes on classpath

> **Note on version:** `5.27ea1` is the latest OSS 5.x on Maven Central (Feb 2025). Chronicle has since moved to a year-based scheme (`2026.4`), which is also OSS Apache 2.0. Verify at https://mvnrepository.com/artifact/net.openhft/chronicle-queue and substitute if a newer 5.x patch exists.

- [ ] **Step 1: Add version property and dependencyManagement entry to parent pom.xml**

In `pom.xml` at the top of `<properties>`:
```xml
<chronicle-queue.version>5.27ea1</chronicle-queue.version>
```

In `pom.xml` inside `<dependencyManagement><dependencies>`, after the existing internal module entries, replace:
```xml
      <!--
        TODO: add net.openhft:chronicle-queue dependency with version property
              once the Chronicle Queue OSS version is decided (Task 16)
      -->
```
with:
```xml
      <dependency>
        <groupId>net.openhft</groupId>
        <artifactId>chronicle-queue</artifactId>
        <version>${chronicle-queue.version}</version>
      </dependency>
```

- [ ] **Step 2: Activate the dependency in the Chronicle module pom.xml**

In `drools-journal-chronicle/pom.xml`, replace:
```xml
    <!-- TODO: add net.openhft:chronicle-queue once version is decided (Task 16) -->
```
with:
```xml
    <dependency>
      <groupId>net.openhft</groupId>
      <artifactId>chronicle-queue</artifactId>
    </dependency>
```

- [ ] **Step 3: Verify the build compiles**

```bash
mvn clean install -DskipTests
```
Expected: `BUILD SUCCESS` — all modules compile.

- [ ] **Step 4: Commit**

```bash
git add pom.xml drools-journal-chronicle/pom.xml
git commit -m "build: add chronicle-queue 5.27ea1 dependency

Refs #14"
```

---

## Task 2: Internal serialization classes

**Files:**
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleWriteOps.java`
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/PayloadCodec.java`
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/ChronicleRecordHandler.java`

**Interfaces:**
- Produces: `ChronicleWriteOps` (interface used by both storage writer and scanner reader), `PayloadCodec.encode(Payload)` → `byte[]`, `PayloadCodec.decode(byte[])` → `Payload`, `ChronicleRecordHandler` (implements `ChronicleWriteOps`, exposes `pending` and `currentPageId` after each dispatch)

- [ ] **Step 1: Create ChronicleWriteOps**

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
package org.drools.journal.chronicle.internal;

public interface ChronicleWriteOps {
    void insert(String pageId, long factHandleId, byte[] payload);
    void insertLogical(String pageId, long factHandleId, byte[] payload, long justifyingRuleMatchId);
    void retract(String pageId, long factHandleId);
    void modify(String pageId, long factHandleId, String lambdaClassRef, byte[] parameters);
    void ruleMatch(String pageId, long id, String packageName, String ruleName, long[] factHandleIds);
    void safepoint(String pageId, long sequenceNo, long timestamp);
    void compactionPrepare(String pageId, String preparingPageId, String[] replacedPageIds);
    void compactionCommit(String pageId, String mergedPageId, String[] replacedPageIds);
}
```

Note: `Payload` is encoded to `byte[]` via `PayloadCodec` before being passed here; Chronicle Wire serializes `byte[]` natively. `Payload` itself cannot be passed because `drools-journal-api` does not depend on Chronicle marshalling interfaces.

- [ ] **Step 2: Create PayloadCodec**

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
package org.drools.journal.chronicle.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.drools.journal.api.EmbeddedPayload;
import org.drools.journal.api.ExternalRef;
import org.drools.journal.api.Payload;

final class PayloadCodec {

    private static final byte TAG_EMBEDDED = 0;
    private static final byte TAG_EXTERNAL = 1;

    private PayloadCodec() {
    }

    static byte[] encode(final Payload payload) {
        if (payload instanceof EmbeddedPayload ep) {
            final byte[] bytes = ep.bytes();
            final ByteBuffer buf = ByteBuffer.allocate(1 + bytes.length);
            buf.put(TAG_EMBEDDED);
            buf.put(bytes);
            return buf.array();
        }
        if (payload instanceof ExternalRef er) {
            final byte[] typeBytes = er.typeName().getBytes(StandardCharsets.UTF_8);
            final byte[] keyBytes = er.dbKey().getBytes(StandardCharsets.UTF_8);
            final ByteBuffer buf = ByteBuffer.allocate(1 + 4 + typeBytes.length + 4 + keyBytes.length);
            buf.put(TAG_EXTERNAL);
            buf.putInt(typeBytes.length);
            buf.put(typeBytes);
            buf.putInt(keyBytes.length);
            buf.put(keyBytes);
            return buf.array();
        }
        throw new IllegalArgumentException("Unknown Payload type: " + payload.getClass());
    }

    static Payload decode(final byte[] data) {
        final ByteBuffer buf = ByteBuffer.wrap(data);
        final byte tag = buf.get();
        if (tag == TAG_EMBEDDED) {
            final byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return new EmbeddedPayload(bytes);
        }
        if (tag == TAG_EXTERNAL) {
            final int typeLen = buf.getInt();
            final byte[] typeBytes = new byte[typeLen];
            buf.get(typeBytes);
            final int keyLen = buf.getInt();
            final byte[] keyBytes = new byte[keyLen];
            buf.get(keyBytes);
            return new ExternalRef(
                    new String(typeBytes, StandardCharsets.UTF_8),
                    new String(keyBytes, StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("Unknown Payload tag: " + tag);
    }
}
```

- [ ] **Step 3: Create ChronicleRecordHandler**

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
package org.drools.journal.chronicle.internal;

import org.drools.journal.api.CompactionCommitRecord;
import org.drools.journal.api.CompactionPrepareRecord;
import org.drools.journal.api.InsertRecord;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.ModifyRecord;
import org.drools.journal.api.RetractRecord;
import org.drools.journal.api.RuleMatchRecord;
import org.drools.journal.api.SafepointRecord;

public final class ChronicleRecordHandler implements ChronicleWriteOps {

    public JournalRecord pending;
    public String currentPageId;

    @Override
    public void insert(final String pageId, final long factHandleId, final byte[] payload) {
        currentPageId = pageId;
        pending = new InsertRecord(factHandleId, false, -1L, PayloadCodec.decode(payload));
    }

    @Override
    public void insertLogical(final String pageId, final long factHandleId, final byte[] payload, final long justifyingRuleMatchId) {
        currentPageId = pageId;
        pending = new InsertRecord(factHandleId, true, justifyingRuleMatchId, PayloadCodec.decode(payload));
    }

    @Override
    public void retract(final String pageId, final long factHandleId) {
        currentPageId = pageId;
        pending = new RetractRecord(factHandleId);
    }

    @Override
    public void modify(final String pageId, final long factHandleId, final String lambdaClassRef, final byte[] parameters) {
        currentPageId = pageId;
        pending = new ModifyRecord(factHandleId, lambdaClassRef, parameters);
    }

    @Override
    public void ruleMatch(final String pageId, final long id, final String packageName, final String ruleName, final long[] factHandleIds) {
        currentPageId = pageId;
        pending = new RuleMatchRecord(id, packageName, ruleName, factHandleIds);
    }

    @Override
    public void safepoint(final String pageId, final long sequenceNo, final long timestamp) {
        currentPageId = pageId;
        pending = new SafepointRecord(sequenceNo, timestamp);
    }

    @Override
    public void compactionPrepare(final String pageId, final String preparingPageId, final String[] replacedPageIds) {
        currentPageId = pageId;
        pending = new CompactionPrepareRecord(preparingPageId, replacedPageIds);
    }

    @Override
    public void compactionCommit(final String pageId, final String mergedPageId, final String[] replacedPageIds) {
        currentPageId = pageId;
        pending = new CompactionCommitRecord(mergedPageId, replacedPageIds);
    }
}
```

- [ ] **Step 4: Compile**

```bash
mvn clean install -DskipTests -pl drools-journal-chronicle
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/internal/
git commit -m "feat(chronicle): add internal ChronicleWriteOps, PayloadCodec, ChronicleRecordHandler

Refs #14"
```

---

## Task 3: ChronicleJournalStorage + ChronicleJournalScanner + contract test

**Files:**
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalScanner.java`
- Create: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java`
- Create: `drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleStorageContractTest.java`

**Interfaces:**
- Consumes: `ChronicleWriteOps`, `PayloadCodec`, `ChronicleRecordHandler` (Task 2); `PageRollStrategy`, `PageRollStrategies`, `PageContext`, `RollDecision` (already in API)
- Produces: `ChronicleJournalStorage.atPath(String)`, `ChronicleJournalStorage.atPath(String, PageRollStrategy)`, all `JournalStorage` methods implemented

- [ ] **Step 1: Write the failing contract test**

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

import java.io.IOException;
import java.nio.file.Path;

import org.drools.journal.chronicle.ChronicleJournalStorage;
import org.drools.journal.core.JournalStorageContractTest;
import org.junit.jupiter.api.io.TempDir;

class ChronicleStorageContractTest extends JournalStorageContractTest {

    @TempDir
    Path tempDir;

    @Override
    protected ChronicleJournalStorage createStorage() {
        return ChronicleJournalStorage.atPath(tempDir.resolve("journal").toString());
    }
}
```

- [ ] **Step 2: Verify the test fails to compile (class not yet exists)**

```bash
mvn clean install -pl drools-journal-tests 2>&1 | grep "cannot find symbol\|BUILD FAILURE" | head -5
```
Expected: compilation error mentioning `ChronicleJournalStorage`.

- [ ] **Step 3: Create ChronicleJournalScanner**

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
package org.drools.journal.chronicle;

import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.MethodReader;
import org.drools.journal.api.JournalRecord;
import org.drools.journal.api.JournalScanner;
import org.drools.journal.chronicle.internal.ChronicleRecordHandler;

final class ChronicleJournalScanner implements JournalScanner {

    private final ExcerptTailer tailer;
    private final MethodReader reader;
    private final ChronicleRecordHandler handler;
    private long currentPosition;
    private JournalRecord buffered;
    private long bufferedPosition;
    private boolean closed;

    ChronicleJournalScanner(final ExcerptTailer tailer, final long fromPosition) {
        this.tailer = tailer;
        this.handler = new ChronicleRecordHandler();
        this.reader = tailer.methodReader(handler);
        if (fromPosition == 0) {
            tailer.toStart();
        } else {
            tailer.moveToIndex(fromPosition);
        }
        this.currentPosition = fromPosition;
        advance();
    }

    @Override
    public boolean hasNext() {
        return buffered != null;
    }

    @Override
    public JournalRecord next() {
        final JournalRecord result = buffered;
        currentPosition = bufferedPosition;
        advance();
        return result;
    }

    @Override
    public long position() {
        return currentPosition;
    }

    @Override
    public String currentPageId() {
        return handler.currentPageId;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            tailer.close();
        }
    }

    private void advance() {
        handler.pending = null;
        final long indexBeforeRead = tailer.index();
        if (reader.readOne()) {
            buffered = handler.pending;
            bufferedPosition = indexBeforeRead;
        } else {
            buffered = null;
        }
    }
}
```

- [ ] **Step 4: Create ChronicleJournalStorage**

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
package org.drools.journal.chronicle;

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
import org.drools.journal.chronicle.internal.ChronicleWriteOps;
import org.drools.journal.chronicle.internal.PayloadCodec;

public final class ChronicleJournalStorage implements JournalStorage {

    private final SingleChronicleQueue queue;
    private final ExcerptAppender appender;
    private final ChronicleWriteOps writer;
    private final PageRollStrategy rollStrategy;
    private int pageIdCounter;
    private String currentPageId;
    private long lastWrittenPosition;
    private long safepointSequenceNo;
    private long currentPageBytes;
    private long currentRecordCount;
    private boolean closed;

    private ChronicleJournalStorage(final SingleChronicleQueue queue, final PageRollStrategy rollStrategy) {
        this.queue = queue;
        this.appender = queue.acquireAppender();
        this.writer = appender.methodWriter(ChronicleWriteOps.class); // returns ChronicleWriteOps proxy
        this.rollStrategy = rollStrategy;
        this.pageIdCounter = 0;
        this.currentPageId = "0";
        this.lastWrittenPosition = -1L;
    }

    public static ChronicleJournalStorage atPath(final String path) {
        return atPath(path, PageRollStrategies.safepointOnly());
    }

    public static ChronicleJournalStorage atPath(final String path, final PageRollStrategy rollStrategy) {
        final SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(path).build();
        return new ChronicleJournalStorage(queue, rollStrategy);
    }

    @Override
    public long insert(final long factHandleId, final Payload payload) {
        final byte[] encoded = PayloadCodec.encode(payload);
        writer.insert(currentPageId, factHandleId, encoded);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new InsertRecord(factHandleId, false, -1L, payload), encoded.length + 8);
        return lastWrittenPosition;
    }

    @Override
    public long insertLogical(final long factHandleId, final Payload payload, final long justifyingRuleMatchId) {
        final byte[] encoded = PayloadCodec.encode(payload);
        writer.insertLogical(currentPageId, factHandleId, encoded, justifyingRuleMatchId);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new InsertRecord(factHandleId, true, justifyingRuleMatchId, payload), encoded.length + 16);
        return lastWrittenPosition;
    }

    @Override
    public long retract(final long factHandleId) {
        writer.retract(currentPageId, factHandleId);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new RetractRecord(factHandleId), 8);
        return lastWrittenPosition;
    }

    @Override
    public long modify(final long factHandleId, final String lambdaClassRef, final byte[] params) {
        writer.modify(currentPageId, factHandleId, lambdaClassRef, params);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new ModifyRecord(factHandleId, lambdaClassRef, params), params.length + 8);
        return lastWrittenPosition;
    }

    @Override
    public long ruleMatch(final long id, final String packageName, final String ruleName, final long[] factHandleIds) {
        writer.ruleMatch(currentPageId, id, packageName, ruleName, factHandleIds);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new RuleMatchRecord(id, packageName, ruleName, factHandleIds), factHandleIds.length * 8 + 8);
        return lastWrittenPosition;
    }

    @Override
    public long compactionPrepare(final String preparingPageId, final String[] replacedPageIds) {
        writer.compactionPrepare(currentPageId, preparingPageId, replacedPageIds);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new CompactionPrepareRecord(preparingPageId, replacedPageIds), 64);
        return lastWrittenPosition;
    }

    @Override
    public long compactionCommit(final String mergedPageId, final String[] replacedPageIds) {
        writer.compactionCommit(currentPageId, mergedPageId, replacedPageIds);
        lastWrittenPosition = appender.lastIndexAppended();
        maybeRoll(new CompactionCommitRecord(mergedPageId, replacedPageIds), 64);
        return lastWrittenPosition;
    }

    @Override
    public void safepoint() {
        final long seqNo = safepointSequenceNo++;
        final long ts = System.currentTimeMillis();
        writer.safepoint(currentPageId, seqNo, ts);
        lastWrittenPosition = appender.lastIndexAppended();
        roll();
    }

    @Override
    public JournalScanner scan(final long fromPosition) {
        return new ChronicleJournalScanner(queue.createTailer(), fromPosition);
    }

    @Override
    public long latestPosition() {
        return lastWrittenPosition;
    }

    @Override
    public void writeMergedPage(final String pageId, final List<JournalRecord> records) {
        for (final JournalRecord record : records) {
            writeRecord(pageId, record);
            lastWrittenPosition = appender.lastIndexAppended();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            queue.close();
        }
    }

    private void maybeRoll(final JournalRecord record, final long estimatedBytes) {
        currentPageBytes += estimatedBytes;
        currentRecordCount++;
        final PageContext ctx = new PageContextSnapshot(record, currentPageBytes, currentRecordCount);
        if (rollStrategy.decide(ctx) == RollDecision.ROLL) {
            roll();
        }
    }

    private void roll() {
        currentPageId = String.valueOf(++pageIdCounter);
        currentPageBytes = 0;
        currentRecordCount = 0;
    }

    private void writeRecord(final String pageId, final JournalRecord record) {
        switch (record) {
            case InsertRecord ir when !ir.logical() ->
                    writer.insert(pageId, ir.factHandleId(), PayloadCodec.encode(ir.payload()));
            case InsertRecord ir ->
                    writer.insertLogical(pageId, ir.factHandleId(), PayloadCodec.encode(ir.payload()), ir.justifyingRuleMatchId());
            case RetractRecord rr ->
                    writer.retract(pageId, rr.factHandleId());
            case ModifyRecord mr ->
                    writer.modify(pageId, mr.factHandleId(), mr.lambdaClassRef(), mr.parameters());
            case RuleMatchRecord rm ->
                    writer.ruleMatch(pageId, rm.id(), rm.packageName(), rm.ruleName(), rm.factHandleIds());
            case SafepointRecord sr ->
                    writer.safepoint(pageId, sr.sequenceNo(), sr.timestamp());
            case CompactionPrepareRecord cp ->
                    writer.compactionPrepare(pageId, cp.preparingPageId(), cp.replacedPageIds());
            case CompactionCommitRecord cc ->
                    writer.compactionCommit(pageId, cc.mergedPageId(), cc.replacedPageIds());
        }
    }

    private record PageContextSnapshot(
            JournalRecord lastRecord,
            long currentPageBytes,
            long currentRecordCount) implements PageContext {
    }
}
```

- [ ] **Step 5: Compile**

```bash
mvn clean install -DskipTests -pl drools-journal-chronicle,drools-journal-tests
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Run the contract test**

```bash
mvn clean install -pl drools-journal-chronicle,drools-journal-tests -Dtest=ChronicleStorageContractTest
```
Expected: all tests pass. If any fail, investigate:
- **Scan position semantics:** verify `tailer.index()` before vs after `readOne()` in `ChronicleJournalScanner.advance()`; if `index()` after read points to the next excerpt rather than the current one, the pattern is correct as written.
- **Empty journal latestPosition:** `lastWrittenPosition` starts at `-1L` so `latestPosition()` returns `-1` on fresh storage ✓.
- **`scan(fromBeyondEnd)`:** `tailer.moveToIndex()` on a non-existent index causes `readOne()` to return `false` → `hasNext()` is `false` ✓.
- **`currentPageId` before first `next()`:** `handler.currentPageId` is set during `advance()` in the constructor. If the scanner is created from a non-empty position, `advance()` reads the first record which sets `handler.currentPageId`. If the journal is empty, `handler.currentPageId` remains `null` (matches the contract: "Returns `null` if the journal is empty").

- [ ] **Step 7: Commit**

```bash
git add drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ \
        drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleStorageContractTest.java
git commit -m "feat(chronicle): implement ChronicleJournalStorage and ChronicleJournalScanner

All JournalStorageContractTest cases pass against Chronicle backend.

Refs #14"
```

---

## Task 4: End-to-end smoke test

**Files:**
- Create: `drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleJournalledKieSessionIT.java`

**Interfaces:**
- Consumes: `ChronicleJournalStorage.atPath(String)`, `JournalledSessionFactory.open(KieBase, JournalStorage, Duration)`, `JournalledKieSession`

- [ ] **Step 1: Write the failing e2e test**

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

        // Session 1: insert a fact and fire
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
            session.insert(42);
            final int fired = session.fireAllRules();
            assertThat(fired).isEqualTo(1);
        }

        // Session 2: reopen from same path — fact restored, rule does not refire
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

        // Session 1: insert fact 1 and fire
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
            session.insert(1);
            session.fireAllRules();
        }

        // Session 2: restore + insert fact 2 and fire
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
            assertThat(session.getObjects()).singleElement().isEqualTo(1);
            session.insert(2);
            session.fireAllRules();
        }

        // Session 3: restore — both facts present
        try (ChronicleJournalStorage storage = ChronicleJournalStorage.atPath(journalPath);
             JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)) {
            assertThat(session.getObjects()).hasSize(2).containsExactlyInAnyOrder(1, 2);
        }
    }
}
```

- [ ] **Step 2: Run the e2e test**

```bash
mvn clean install -pl drools-journal-chronicle,drools-journal-tests -Dtest=ChronicleJournalledKieSessionIT
```
Expected: both tests pass. Common failure modes:
- **Restore finds no facts:** verify `latestPosition()` is non-negative after `safepoint()` so `JournalledSessionFactory.open()` triggers the restore path.
- **Rule fires again on restore:** verify `SafepointRecord` is written by `fireAllRules()` and that the scanner correctly reports the `RuleMatchRecord` before the safepoint.
- **`ChronicleJournalStorage` not auto-closeable:** it implements `JournalStorage extends AutoCloseable` — the try-with-resources above should work.

- [ ] **Step 3: Run the full build**

```bash
mvn clean install
```
Expected: `BUILD SUCCESS` with all 77+ tests passing.

- [ ] **Step 4: Commit**

```bash
git add drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleJournalledKieSessionIT.java
git commit -m "test(chronicle): add end-to-end smoke test for Chronicle backend restore

Closes #14"
```

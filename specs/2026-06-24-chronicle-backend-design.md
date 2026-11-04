# Chronicle Backend Design

**Date:** 2026-06-24
**Issue:** #14 — Implement Chronicle Queue backend
**Status:** Approved

---

## Context

The `drools-journal-chronicle` module exists but is empty. This spec covers the implementation of `ChronicleJournalStorage` and `ChronicleJournalScanner` — a durable, append-only backend backed by Chronicle Queue 5.x (OSS, Apache 2.0).

The physical page ID foundation from PR #29 is the prerequisite: `JournalScanner.currentPageId()` maps to Chronicle roll cycles, and `SafepointRecord` always forces a page roll.

---

## Decisions

| Concern | Decision |
|---|---|
| Chronicle edition | 5.x OSS — no commercial dependency |
| Serialization | `MethodWriter`/`MethodReader` with internal `ChronicleWriteOps` interface |
| Page roll strategy | `PageRollStrategy` interface in `drools-journal-chronicle`; `SafepointOnlyRollStrategy` first impl |
| Thread safety | External serialization — no internal synchronization on storage |
| Factory | `ChronicleJournalStorage.atPath(String path)` |

---

## Module Structure

All new code lives in `drools-journal-chronicle`. No changes to `drools-journal-api` or `drools-journal-core`.

**`pom.xml` changes:**
- `drools-journal-chronicle/pom.xml`: add `net.openhft:chronicle-queue` dependency
- Parent `pom.xml`: add `chronicle-queue.version` property

**Already in `drools-journal-api` (no new code needed):**

| Class | Package | Note |
|---|---|---|
| `PageRollStrategy` | `org.drools.journal.api` | `RollDecision decide(PageContext)` |
| `PageRollStrategies` | `org.drools.journal.api` | `safepointOnly()`, `sizeThreshold()`, `countThreshold()` |
| `PageContext` | `org.drools.journal.api` | `lastRecord()`, `currentPageBytes()`, `currentRecordCount()` |
| `RollDecision` | `org.drools.journal.api` | `CONTINUE` / `ROLL` |

**Five new classes in `drools-journal-chronicle`:**

| Class | Package | Role |
|---|---|---|
| `ChronicleWriteOps` | `org.drools.journal.chronicle.internal` | Internal write-side interface for `MethodWriter` dispatch; all methods include a `pageId` first parameter |
| `PayloadCodec` | `org.drools.journal.chronicle.internal` | Encodes/decodes `Payload` to/from `byte[]` for Chronicle Wire serialization |
| `ChronicleRecordHandler` | `org.drools.journal.chronicle.internal` | Implements `ChronicleWriteOps`; builds `JournalRecord` instances from method dispatch |
| `ChronicleJournalStorage` | `org.drools.journal.chronicle` | Implements `JournalStorage` |
| `ChronicleJournalScanner` | `org.drools.journal.chronicle` | Implements `JournalScanner` |

---

## Write Path

`ChronicleJournalStorage` holds:
- `SingleChronicleQueue queue` — opened at the configured directory path
- `ExcerptAppender appender` — single appender; external serialization is the caller's responsibility
- `MethodWriter<ChronicleWriteOps> writer` — wraps the appender; encodes each call as a Chronicle excerpt
- `PageRollStrategy rollStrategy`
- `String currentPageId` — starts at `"0"`, advanced after any record where `rollStrategy.shouldRollAfter()` returns true

**Per write method:**
1. Delegate to the corresponding `MethodWriter` method, passing `currentPageId` as the first argument → Chronicle encodes to disk
2. Call `rollStrategy.shouldRollAfter(record)` — if `true`, increment `currentPageId` (e.g. `"0"` → `"1"`)

**`latestPosition()`** returns `appender.lastIndexAppended()` — Chronicle's 64-bit excerpt index. Returns `-1` if nothing has been written (matching the InMemory contract).

**`writeMergedPage(String pageId, List<JournalRecord> records)`** appends each record using the provided `pageId` directly (not `currentPageId`). The compaction coordinator owns and assigns merged page IDs; the storage just writes records with the given ID. `currentPageId` is not affected — the next normal write still uses the current logical page.

**`close()`** closes the queue. Chronicle handles double-close safely.

**Note on Chronicle file rolling:** Chronicle's own roll cycle (e.g. daily) is independent of our logical page boundaries. Chronicle files can roll on any schedule without affecting `currentPageId` tracking. We do not force Chronicle file rolls at safepoints — page boundaries are embedded in the stream data, not inferred from file structure.

---

## Read Path

**`scan(fromPosition)`** creates a `ChronicleJournalScanner` with a fresh tailer:
- `fromPosition == 0` → `tailer.toStart()`
- otherwise → `tailer.moveToIndex(fromPosition)`

`ChronicleJournalScanner` holds:
- `ExcerptTailer tailer`
- `MethodReader reader` — dispatches decoded entries to `ChronicleRecordHandler`
- `ChronicleRecordHandler handler` — internal class implementing `ChronicleWriteOps`; each dispatched method sets a `pending` record and stores the page ID from the method's first argument
- `long currentPosition` — excerpt index of the last returned record

**`hasNext()` / `next()`** drive the reader:
- `reader.readOne()` returns `true` when an entry is decoded
- `handler.pending` holds the built record
- `handler.currentPageId` holds the page ID passed by the storage when the entry was written
- `tailer.index()` → new `currentPosition`

**`position()`** returns `currentPosition`.

**`currentPageId()`** returns `handler.currentPageId` — set from the page ID embedded in each Chronicle entry at write time.

**`close()`** closes the tailer.

---

## PageRollStrategy

`PageRollStrategy` is already part of `drools-journal-api`:

```java
@FunctionalInterface
public interface PageRollStrategy {
    RollDecision decide(PageContext context);
}
```

`PageRollStrategies.safepointOnly()` always returns `CONTINUE` — it relies entirely on the core's unconditional safepoint-forced roll. `ChronicleJournalStorage` uses this as the default.

`ChronicleJournalStorage` calls `rollStrategy.decide(ctx)` after every write except `safepoint()`. After `safepoint()`, the page roll is unconditional regardless of strategy. "Roll" means advancing the logical page ID counter — it does not interact with Chronicle's own file roll cycle. `sizeThreshold()` and `countThreshold()` from `PageRollStrategies` can be plugged in without changes to `ChronicleJournalStorage`.

---

## Testing

Both test classes live in `drools-journal-tests`. That module gets `drools-journal-chronicle` added as a test dependency.

**`ChronicleStorageContractTest`** — extends `JournalStorageContractTest` (abstract, in `drools-journal-core`). Provides a `@TempDir`-based `ChronicleJournalStorage.atPath(tmpDir.toString())` as the storage under test. The full 47-case contract suite runs against the Chronicle backend with no additional test code.

**`ChronicleJournalledKieSessionIT`** — end-to-end smoke test:
1. `JournalledSessionFactory.open(kbase, storage)` → insert fact → `fireAllRules()` → `dispose()`
2. `JournalledSessionFactory.open(kbase, storage)` → verify fact restored, rule does not re-fire

Test method naming follows the project convention: `action_context_expectedResult`.

No unit tests inside `drools-journal-chronicle` — the contract test covers behaviour, and the internals are too coupled to Chronicle to test in isolation meaningfully.

---

## Out of Scope

- Size-based roll strategy (future, `PageRollStrategy` interface is ready for it)
- Aeron backend (#15)
- Chronicle Enterprise features (replication, encryption)

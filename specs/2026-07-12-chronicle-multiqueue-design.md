# Chronicle Multi-Queue Architecture

Date: 2026-07-12
Status: Draft
Implements: ADR-0002 (queue-per-page with catalog-based page ordering)

## Goal

Move `ChronicleJournalStorage` from a single Chronicle Queue with embedded
page IDs to a queue-per-page architecture with a dedicated catalog queue.
Each logical page maps to its own Chronicle Queue directory. A catalog queue
tracks page ordering. The `JournalStorage` SPI is unchanged — all changes
are internal to the `drools-journal-chronicle` module.

## Scope

- Chronicle-internal only. No SPI changes, no changes to `InMemoryJournalStorage`,
  `RestoreEngine`, or `CompactionCoordinator`.
- Page retirement (physical deletion of old page directories) is deferred to a
  follow-up.
- Mid-stream resume via `scan(fromPosition)` is not supported for multi-queue.
  `scan(0)` (full scan) is the only meaningful entry point. The position model
  (`latestPosition()`) uses the catalog queue's index for "has anything new been
  written?" checks.

## Directory Layout

The base path passed to `ChronicleJournalStorage.atPath(path)` becomes a root
directory:

```
journal-root/
  catalog/          <- Chronicle Queue for page lifecycle events
  page-0/           <- Chronicle Queue for page "0" data
  page-1/           <- Chronicle Queue for page "1" data
  page-m-<uuid>/    <- Chronicle Queue for merged page data
```

## Wire Interfaces

The current `ChronicleWriteOps` (which carries `pageId` as the first argument
on every method) is replaced by two separate interfaces.

### ChronicleDataWriteOps

Used by data page queues. No `pageId` — page identity is structural (which
queue directory).

```java
public interface ChronicleDataWriteOps {
    void insert(long factHandleId, byte[] payload);
    void insertLogical(long factHandleId, byte[] payload, long justifyingRuleMatchId);
    void retract(long factHandleId);
    void modify(long factHandleId, String lambdaClassRef, byte[] parameters);
    void ruleMatch(long id, String packageName, String ruleName, long[] factHandleIds);
    void safepoint(long sequenceNo, long timestamp);
}
```

### ChronicleCatalogWriteOps

Used by the catalog queue. Tracks page lifecycle and compaction metadata.

```java
public interface ChronicleCatalogWriteOps {
    void pageCreated(String pageId);
    void compactionPrepare(String preparingPageId, String[] replacedPageIds);
    void compactionCommit(String mergedPageId, String[] replacedPageIds);
}
```

Corresponding record handlers split similarly: `ChronicleDataRecordHandler`
and `ChronicleCatalogRecordHandler`.

## ChronicleJournalStorage Internal Structure

### Fields

- `Path rootDir` — base directory
- `SingleChronicleQueue catalogQueue` — opened at construction, lives for the
  storage's lifetime
- `ChronicleCatalogWriteOps catalogWriter` — cached writer for the catalog
- `SingleChronicleQueue activePageQueue` — current page's data queue, replaced
  on each roll
- `ChronicleDataWriteOps sessionWriter` — cached writer for active page,
  replaced on each roll
- `PageRollStrategy rollStrategy`, `int pageIdCounter`, `String currentPageId`,
  `long safepointSequenceNo`, `long currentPageBytes`, `long currentRecordCount`
  — same as today

### Construction

- **Fresh root:** create `catalog/` queue, create `page-0/` queue, write
  `PageCreated("0")` to catalog.
- **Existing root:** read catalog to discover state — replay `PageCreated` and
  `CompactionCommitRecord` entries to build the live page list (same logic as
  the scanner's catalog phase). The active page is the last entry in the live
  page list. Extract the numeric suffix to set `pageIdCounter`. Open the
  active page's queue.

### Page Roll

`roll()` increments `currentPageId`, creates `page-{id}/` queue, writes
`PageCreated` to catalog, replaces `activePageQueue` and `sessionWriter`.
Closes the previous page queue.

### Write Methods

`insert`, `retract`, `modify`, `ruleMatch`, `safepoint` — same as today but
use `sessionWriter` (a `ChronicleDataWriteOps`, no `pageId` argument).

### Compaction Methods

- `compactionPrepare` — writes `CompactionPrepareRecord` to `catalogWriter`
- `compactionCommit` — writes `CompactionCommitRecord` to `catalogWriter`
- `writeMergedPage` — creates a new `page-{mergedPageId}/` queue, opens a
  fresh `ChronicleDataWriteOps` writer, writes all records, closes the queue.
  No interaction with `activePageQueue`.

### Thread Safety

The session thread writes to `activePageQueue` via `sessionWriter`. The
compaction thread writes to its own merged page queue (created in
`writeMergedPage`) and to `catalogQueue` (via `catalogWriter`).

The only shared resource is `catalogQueue` — both threads write to it (session
writes `PageCreated` during roll, compaction writes PREPARE/COMMIT).
Chronicle Queue's internal write lock serializes appender access, so no
external synchronization is needed.

### Close

Closes `activePageQueue` and `catalogQueue`.

## Scanner Design

`ChronicleJournalScanner` performs a two-phase scan.

### Phase 1 — Catalog

Read the catalog queue from start to end:
- `PageCreated(pageId)` — append to ordered page list
- `CompactionPrepareRecord` — note intent (for orphan detection)
- `CompactionCommitRecord(mergedPageId, replacedPageIds)` — call
  `spliceIntoIndex`: replace `replacedPageIds` with `mergedPageId` at position
  of first replaced page

Result: an ordered `List<String>` of live page IDs.

### Phase 2 — Data

Open data queues one at a time in catalog order. For each queue, create a
tailer, read all records, close the tailer, move to the next. From the
caller's perspective: a flat `Iterator<JournalRecord>` stream.

`currentPageId()` returns the page ID from the catalog's page list — derived
from which queue is being read, not embedded in records.

### Performance Note

Sequential queue open/close is used rather than preloading all tailers.
Compaction keeps the live page count bounded, so the number of queues is
small. If profiling shows the open/close cycle is a bottleneck, switching to
preloaded tailers is a localized change inside the scanner with no API impact.

## Crash Analysis

### Ordering Rule

Always write data before metadata. Create the queue directory and write
records first, then write the catalog entry. If the catalog says something
exists, the data is guaranteed to be there.

### Normal Writes

| Crash point | State on disk | Recovery |
|---|---|---|
| Mid-record write to data queue | Partial append | Chronicle's atomic append makes partial writes invisible to readers. No impact. |
| After record written, before `lastWrittenPosition` updated | Record durable, position stale | Recovery always scans from 0. No impact. |

### Page Roll

Sequence: (1) write safepoint to current data queue, (2) create new page
directory and open queue, (3) write `PageCreated` to catalog.

| Crash point | State on disk | Recovery |
|---|---|---|
| After safepoint written, before new directory created | Last page has safepoint. No new page. | Session opens fresh, creates next page. No data lost. |
| After directory created, before `PageCreated` written | Orphan directory, no catalog entry | Scanner only follows catalog — orphan is invisible. Can clean up later. |
| After `PageCreated` written, before any records | Catalog references an empty page | Scanner opens it, finds no records, moves to next page. Harmless. |

### Compaction

Sequence: (1) write `CompactionPrepareRecord` to catalog, (2) create merged
page directory, (3) write records to merged page queue, (4) write
`CompactionCommitRecord` to catalog.

| Crash point | State on disk | Recovery |
|---|---|---|
| After `CompactionPrepareRecord`, before directory created | PREPARE in catalog, nothing else | No `CompactionCommitRecord` — merge incomplete. Original pages remain canonical. |
| After directory created, mid-write to merged page | PREPARE in catalog, partial merged page directory | Same — no `CompactionCommitRecord`. Orphan directory ignored/cleaned up. |
| After merged page fully written, before `CompactionCommitRecord` | PREPARE in catalog, complete merged page data, no commit | Same — no `CompactionCommitRecord`. Data exists but is not canonical. |
| After `CompactionCommitRecord` written | Merge complete and durable | Catalog shows merged page replaces old pages. Correct. |

### Construction (Fresh Journal)

| Crash point | State on disk | Recovery |
|---|---|---|
| After creating root dir, before catalog | Empty root directory | Treat as fresh start — create catalog and page-0. |
| After catalog created, before page-0 | Catalog exists but empty | Same — treat as fresh, create page-0, write `PageCreated("0")`. |
| After page-0 created, before `PageCreated("0")` | Orphan page-0 directory | Catalog is empty — fresh start. Orphan overwritten. |

### Sealing Is Unnecessary in Multi-Queue

In the single-queue model, `CompactionCommitRecord` must be followed by a
`SafepointRecord` in the same stream to be "sealed." In multi-queue, the
commit is in the catalog queue and the safepoint is in a data queue —
different streams, no ordering relationship.

Sealing is unnecessary because:

1. Compaction only targets already-sealed pages (past safepoints). The
   session's current open page is never a compaction target.
2. If we crash after `CompactionCommitRecord` but before the next safepoint,
   the session's open-tail records are discarded on recovery anyway — they're
   in the current page, unrelated to the merged pages.
3. The merged page data is complete (written before the catalog entry, per the
   ordering rule). The old pages still exist (retirement is deferred). The
   catalog entry is durable (Chronicle's atomic append).

`CompactionCommitRecord` in the catalog is self-sealing. The sealing concept
remains relevant only in `InMemoryJournalStorage` where data and metadata
share one stream.

### Open-Tail Handling

SafepointRecords remain in data queues. The last data queue may contain
records after the final safepoint — the "open tail." RestoreEngine's existing
Phase 1 logic discards these. In multi-queue, this applies to the last page
queue only.

## Impact on RestoreEngine and CompactionCoordinator

No changes needed.

**RestoreEngine:** Phase 0 scans via `storage.scan(0)` and sees the
pre-resolved page order from the multi-queue scanner. `CompactionPrepareRecord`
and `CompactionCommitRecord` are no longer in the data stream (they're in the
catalog, already processed by the scanner). Phase 0's splicing logic is never
triggered for Chronicle — the page index is already in correct merged order.
Phase 1 replays all pages normally. The code still works for
`InMemoryJournalStorage` where commits remain in the data stream.

**CompactionCoordinator:** `scanLiveness` scans via `storage.scan(0)` and
tracks page boundaries via `scanner.currentPageId()`, which changes when the
scanner moves to the next page queue. `compact` calls SPI methods
(`compactionPrepare`, `writeMergedPage`, `compactionCommit`) — the Chronicle
implementation redirects PREPARE and COMMIT to the catalog internally.

## Deferred Work

- **Page retirement** — physically delete old page directories after
  `CompactionCommitRecord` is written. Mechanically simple (`rm -rf` the
  directory) but requires wiring into `CompactionCoordinator`. Follow-up issue.
- **SPI evolution** — `JournalStorage` may gain page lifecycle methods when
  the Aeron backend needs them. Not needed now.
- **Orphan cleanup** — directories with no catalog entry can accumulate from
  crashes. Optional cleanup on construction (diff catalog entries against
  actual directories). Low priority — orphans waste disk but don't affect
  correctness.

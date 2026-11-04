# JournalPosition and Incremental Liveness Scanning

**Date:** 2026-08-07
**Status:** Draft
**Scope:** SPI position model, Chronicle random access, incremental compaction liveness

---

## Background

The position model in `JournalStorage` was designed under the assumption that
Chronicle Queue has no random access — only sequential forward scanning. This
assumption was wrong. Chronicle Queue provides `ExcerptTailer.moveToIndex(long)`
which performs O(1) lookup via a two-level sparse index tree, with a bounded
linear scan of at most `indexSpacing` entries (default 256).

This discovery invalidates several design decisions:

- `scan(fromPosition)` is dead code in Chronicle — the `fromPosition` parameter
  is accepted but ignored; every call site passes `0`.
- `MultiQueueScanner.position()` returns a synthetic counter that cannot be used
  as a resume point.
- `CompactionCoordinator.scanLiveness()` does a full journal rescan on every poll
  cycle (default every 60s), because incremental scanning was considered
  impractical without random access.
- The "mid-stream resume via cumulative record index" idea (IDEAS.md, 2026-07-13)
  proposed building a custom index — a workaround for something Chronicle already
  provides natively.

The queue-per-page architecture (ADR-0002) remains valid — it was driven by
deletion needs (Chronicle cannot delete individual records) and Aeron
compatibility, not by access patterns.

## Design

### 1. JournalPosition — replacing `long` positions

A new record type in `drools-journal-api`:

```java
public record JournalPosition(String pageId, long offset) {
    public static final JournalPosition START = new JournalPosition(null, 0);
}
```

- `pageId` — the logical page ID (e.g. `"3"`, `"m-abc123"`). `null` only in the
  `START` sentinel.
- `offset` — backend-specific offset within that page. For Chronicle, this is the
  64-bit composite index from `tailer.index()` / `lastIndexAppended()`. For
  InMemory, it is the record index within the page's record list.
- `START` — sentinel meaning "scan from the beginning". Replaces the current
  convention of passing `0`.

### 2. SPI changes to JournalStorage

All position-related signatures change from `long` to `JournalPosition`:

| Current signature | New signature |
|---|---|
| `long insert(long, Payload)` | `JournalPosition insert(long, Payload)` |
| `long insertLogical(long, Payload, long)` | `JournalPosition insertLogical(long, Payload, long)` |
| `long retract(long)` | `JournalPosition retract(long)` |
| `long modify(long, String, byte[])` | `JournalPosition modify(long, String, byte[])` |
| `long ruleMatch(long, String, String, long[])` | `JournalPosition ruleMatch(long, String, String, long[])` |
| `long compactionPrepare(String, String[])` | `JournalPosition compactionPrepare(String, String[])` |
| `long compactionCommit(String, String[])` | `JournalPosition compactionCommit(String, String[])` |
| `long latestPosition()` | `JournalPosition latestPosition()` |
| `JournalScanner scan(long)` | `JournalScanner scan(JournalPosition)` |

`JournalScanner` changes:

| Current signature | New signature |
|---|---|
| `long position()` | `JournalPosition position()` |

All current callers pass `scan(0)` — migration is mechanical: replace with
`scan(JournalPosition.START)`.

### 3. Chronicle scan(fromPosition) via moveToIndex()

`MultiQueueScanner.create()` gains a `JournalPosition fromPosition` parameter.

**When `fromPosition == START`:** same as today — read catalog, open first
non-empty queue, scan sequentially from the beginning.

**When `fromPosition` has a `pageId`:** read catalog to build the live page list,
find the page matching `fromPosition.pageId()`, open that page's queue, call
`tailer.moveToIndex(fromPosition.offset())` to jump directly to the target
entry, then continue sequentially from there through remaining pages.

**When the page in `fromPosition` no longer exists** (retired by compaction
between polls): fall back to `START` — a full rescan is the safe default. This
edge case needs review during implementation to determine if a smarter recovery
is warranted.

`MultiQueueScanner.position()` changes from the synthetic counter to:

```java
return new JournalPosition(currentPageId, tailer.index());
```

This returns a real Chronicle position that round-trips through `scan()`.

`ChronicleJournalStorage` write methods already capture
`lastIndexAppended()` — they now wrap it in
`new JournalPosition(currentPageId, lastIndexAppended)`.

### 4. Incremental liveness scanning in CompactionCoordinator

Today `scanLiveness()` does a full rescan from zero on every poll. With
`JournalPosition`, the coordinator remembers where it left off and scans only
new records.

**New fields on CompactionCoordinator:**

```java
private JournalPosition lastScannedPosition = JournalPosition.START;
private final Map<String, long[]> liveness = new HashMap<>();
private final Map<Long, String> factToPage = new HashMap<>();
```

**Modified `runCycle()` flow:**

1. **Phase 0 (page index)** — still a full catalog scan. Compaction can retire
   pages at any time and the page index must be rebuilt. This is cheap — it reads
   only the catalog queue, not data queues.

2. **Cleanup after compaction** — remove retired pages from the accumulated
   `liveness` map:
   ```java
   for (String retired : pageStatus.retiredPages()) {
       liveness.remove(retired);
   }
   ```
   The `factToPage` map does not need explicit cleanup — facts whose origin page
   was retired now live in a merged page whose `InsertRecord`s will be processed
   by the incremental scan, naturally overwriting stale entries.

3. **Incremental liveness scan** — `scan(lastScannedPosition)` instead of
   `scan(START)`. Process only new records. Update accumulated `liveness` and
   `factToPage` maps. After scanning, save `scanner.position()` as the new
   `lastScannedPosition`.

4. **Candidate selection and compaction** — unchanged.

**On-demand coordinator** (tests, `Duration.ZERO`) stays stateless — it creates
fresh state per call, always doing a full scan. The incremental state only
accumulates in the scheduled background coordinator.

### 5. InMemoryJournalStorage changes

The InMemory backend supports the same `JournalPosition` contract:

- `pageId` — the page's string ID (already tracked in `Page.id`)
- `offset` — the record index within the page's `records` list (0-based)

**`scan(JournalPosition fromPosition)`:**
- If `START` — flatten all pages from the beginning (same as today)
- Otherwise — find the page matching `fromPosition.pageId()`, skip to
  `fromPosition.offset()` within it, continue sequentially
- If the page is gone (retired) — fall back to `START`

**`latestPosition()`** returns
`new JournalPosition(currentPage.id, currentPage.records.size() - 1)`.

**`position()`** on `InMemoryJournalScanner` returns
`new JournalPosition(currentPageId, offsetWithinPage)`.

**Write methods** return `JournalPosition` with the current page ID and the
record's index within that page.

### 6. What this obsoletes

The following items in IDEAS.md become obsolete or resolved:

- **"Mid-stream resume via cumulative record index"** (2026-07-13) — obsolete.
  `moveToIndex()` solves it directly; no custom index needed.
- **D2 "scan(fromPosition) ignores fromPosition"** (2026-07-15) — fixed by this
  work.
- **C4 "Synthetic position counter in MultiQueueScanner.position()"**
  (2026-07-15) — replaced by `JournalPosition` returning real Chronicle indexes.

The mid-stream resume contract tests that were removed (blog 2026-07-13) can be
restored once `scan(fromPosition)` works for real in both backends.

## Implementation notes

Implementation must proceed one piece at a time with review after each step.
The SPI change touches every implementation and every caller — careful,
incremental migration avoids hidden breakage.

Items flagged for review during implementation:

- **Retired-page fallback:** when `fromPosition.pageId()` no longer exists, the
  scanner falls back to `START`. Evaluate whether a smarter recovery is needed
  (e.g. finding the nearest surviving page) or if full rescan is acceptable.
- **Liveness map consistency after compaction:** verify that `factToPage` entries
  for facts in retired pages are correctly overwritten when the merged page's
  `InsertRecord`s are processed by the incremental scan.
- **On-demand vs scheduled coordinator state:** confirm that on-demand callers
  (tests) get correct results with fresh state, and that no test relies on the
  accumulated maps.

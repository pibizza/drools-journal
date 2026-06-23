# 0001 — Decouple physical storage pages from safepoint boundaries

Date: 2026-06-23
Status: Accepted

## Context and Problem Statement

`JournalStorage` supports pluggable `PageRollStrategy` (safepoint-only,
size-threshold, count-threshold). The original compaction design used
`SafepointRecord.sequenceNo` as the page ID, which only works when every
physical page boundary coincides with a safepoint. With size-based rolling,
a page can close without a safepoint, leaving no ID to put in
`CompactionPrepareRecord` and making compaction impossible at physical page
granularity.

## Decision Drivers

* Chronicle and Aeron backends need size-based rolling to bound segment file
  size independently of session activity
* Compaction should operate on individual physical pages (Chronicle segments),
  not on coarser safepoint intervals
* No new `JournalRecord` types — the record hierarchy should not carry
  structural storage metadata
* `SafepointRecord` semantic must remain stable: consistency checkpoint that
  forces a physical page roll

## Considered Options

* **Option A** — Physical page IDs via `JournalScanner.currentPageId()`
* **Option B** — Restrict Chronicle to `safepointOnly()` and defer the problem
* **Option C** — Introduce `PageStartRecord` in the journal stream

## Decision Outcome

Chosen option: **Option A** — implementing `currentPageId()` on `JournalScanner`.

Each physical page is assigned an ID at creation time by the storage
implementation. `JournalScanner.currentPageId()` exposes that ID to all
readers. `CompactionCoordinator` and `RestoreEngine` use physical page IDs
throughout; `SafepointRecord.sequenceNo` becomes a pure consistency counter
with no page-ID role.

### Positive Consequences

* Compaction coordinator and restore engine operate at physical page
  granularity with no new record types
* Each storage backend assigns page IDs at creation time using its native
  scheme (sequential counter for InMemory, segment index for Chronicle)
* `SafepointRecord` stays a pure consistency marker; its `sequenceNo` is a
  safepoint counter, not a page ID
* The invariant "a physical page never crosses a safepoint boundary" is
  preserved (safepoint still forces a roll), so each page's records belong to
  exactly one safepoint interval

### Negative Consequences / Tradeoffs

* `JournalScanner` SPI gains a method (`currentPageId()`), requiring all
  implementations to track current page
* `InMemoryJournalStorage` must assign page IDs at page-creation time rather
  than at close time; the existing link between safepoint `sequenceNo` and page
  ID is removed
* `CompactionCoordinator` and `RestoreEngine` must switch from
  `SafepointRecord.sequenceNo`-keyed maps to physical page ID maps

## Pros and Cons of the Options

### Option A — Physical page IDs via `JournalScanner.currentPageId()`

* ✅ No new record types; record hierarchy unchanged
* ✅ Scanner already has position awareness; page ID is the same concept
  one level up
* ✅ Compaction granularity matches Chronicle's natural file boundary
* ✅ Works for both safepoint-triggered and size-triggered rolls uniformly
* ❌ Small SPI change; all scanner implementations must be updated

### Option B — Restrict Chronicle to `safepointOnly()`

* ✅ Zero design changes; all existing code works today
* ❌ Segment files grow unbounded between fireAllRules() calls
* ❌ Compaction is coarser than needed — whole safepoint intervals, not pages
* ❌ Defers the problem, blocking any size-based rolling use case permanently

### Option C — Introduce `PageStartRecord` in the journal stream

* ✅ Page boundaries self-describing in the record stream
* ❌ Expands the sealed `JournalRecord` hierarchy with a structural concern
* ❌ Every reader (restore engine, coordinator, scanners) must handle a new
  record type with no semantic domain meaning
* ❌ More invasive than exposing the same information via the scanner API

## Links

* GitHub issue [#18](https://github.com/pibizza/drools-journal/issues/18) —
  original problem statement
* `JournalScanner` — gains `currentPageId()` method
* `CompactionCoordinator.scanLiveness()` — switches to `currentPageId()`
* `RestoreEngine` Phase 0/1 — switches to physical page IDs

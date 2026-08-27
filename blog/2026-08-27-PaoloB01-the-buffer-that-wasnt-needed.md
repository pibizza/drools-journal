---
layout: post
title: "The Buffer That Wasn't Needed"
date: 2026-08-27
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [refactoring, restore, documentation]
---

## Still reading what we wrote

I was still in the documentation pass from yesterday, this time looking at `RestoreEngine`. The `flush` method caught my eye — five parameters, four of them accumulators passed on every call. That signature was hiding an object.

## Extracting the cursors

The same pattern showed up in two places. In `RestoreEngine`, the `flush` method's five parameters were hiding an accumulator object. In `PageIndex`, the `buildLivePageSet` method had its own cluster of mutable state — `pageIndex`, `retiredPages`, `pendingCommits`, `currentIntervalPages`, `lastPageId` — all local variables threaded through the loop.

I extracted both into cursor classes. `ScanCursor` owns the restore accumulators; `PageIndexCursor` owns the page index state. Both expose a `move` method, and both callers collapse to the same shape:

```java
while (scanner.hasNext()) {
    cursor.move(scanner.currentPageId(), scanner.next());
}
return cursor.getResult();
```

With the cursors in place, the duplicated page-scanning logic across `RestoreEngine`, `PageIndex`, and `CompactionCoordinator` became obvious — all three had the same boundary detection, safepoint handling, and buffer lifecycle.

## The question that deleted code

I brought Claude in to work through the logic. We looked at the page boundary check inside `move` — the first `if` that detects when the scanner crosses to a new physical page. Then the nested liveness check that decides whether to flush or discard. Then the safepoint check doing the same thing again.

I asked a basic question: why buffer at all?

The liveness set is computed upfront in phase 0 — by the time the replay loop runs, every page's status is already known. And safepoints seal pages — there are no records after a safepoint on the same physical page. So every record on a live page is covered by a safepoint and safe to process immediately.

The buffer-then-flush pattern existed because the original code made the liveness decision at flush time. Once the liveness filter moved to the caller — `scan()` now skips retired pages entirely before records reach the cursor — the buffer became redundant. Records go straight into the accumulators as they arrive.

The `ScanCursor.move()` method dropped from page-boundary detection, liveness checks, and buffered flush to a single record-type dispatch. The `pending` list, `lastPageId`, `currentPageIsNot()`, `moveCurrentPageTo()`, and `flush()` all disappeared.

## The scanner already knew

Then I looked at `MultiQueueScanner` — the Chronicle backend's scanner implementation. It builds a `CatalogIndex`, gets `livePages()`, and only iterates those pages. The production scanner already returns only live pages. The phase 0 liveness scan in `RestoreEngine` was doing the same work a second time.

`InMemoryJournalStorage.scan()` returns everything — live and retired pages alike. But it's a test artifact. The production contract is clear: scanners return live pages only.

## What's next

The logical next step is making `InMemoryJournalStorage.scan()` filter by liveness too — align the test implementation with the production contract. Once that's done, `RestoreEngine` can drop its phase 0 scan entirely, and the duplicated liveness logic across `PageIndex`, `CompactionCoordinator`, and `RestoreEngine` can be cleaned up.

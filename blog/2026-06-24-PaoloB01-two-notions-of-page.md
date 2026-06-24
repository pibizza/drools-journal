---
layout: post
title: "Two Notions of Page"
date: 2026-06-24
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [architecture, compaction, api-design]
---

## Two Notions of Page

The problem had a precise formulation. `PageRollStrategy` lets storage roll a
new physical page on size or record count, independently of safepoints. But
the compaction design used `SafepointRecord.sequenceNo` as the page ID — which
only works when every physical page boundary coincides with a safepoint. With
size-based rolling, a page closes without a safepoint, and there's nothing to
put in `CompactionPrepareRecord`.

Issue #18 had been on the list since the compaction coordinator work. Today I
picked it up.

I brought Claude in to lay out the options. We named the two notions: a storage
page (physical, bounded by size or count) and a compaction page (logical,
bounded by `fireAllRules()` cycles). The original design had kept them aligned
by restricting to `safepointOnly()`. The question was what to do when they
diverge.

## The invariant that made it tractable

We went back and forth. The design doc had three options: decouple, enforce
alignment, or defer. None felt right — enforcing alignment would require
synthetic safepoints mid-cycle, which breaks restore semantics.

Then I spotted the invariant: because `SafepointRecord` always forces a roll,
a physical page can never cross a safepoint boundary. It may end before the
next safepoint (size roll), but it always starts fresh after one. Each physical
page belongs to exactly one safepoint interval.

That changed the frame. The problem isn't about compaction page IDs. It's about
which physical files to delete when compaction commits — an internal storage
concern.

## currentPageId() — no new record type

I wanted compaction at the physical page level. Chronicle segments are the
natural unit; compacting whole safepoint intervals is coarser than needed.

Page boundaries had to be visible to the scanner. I rejected a new
`PageStartRecord` — the record hierarchy shouldn't carry structural storage
metadata. `JournalScanner` already exposes `position()`; `currentPageId()` is
the same concept one level up.

```java
String currentPageId();
```

One method. No new record types. `SafepointRecord.sequenceNo` becomes a pure
consistency counter with no role in page identity.

Claude flagged that this required `InMemoryJournalStorage` to assign page IDs
at creation time rather than at close time. Previously `page.id` was set from
the safepoint sequence number when the page sealed. With the new scheme, every
page gets a counter-based ID the moment it's created: `"0"`, `"1"`, `"2"`.
We also added `rollPage()` to simulate size-triggered rolling in tests.

## The test that revealed the real bug

The `CompactionCoordinator` changes were straightforward — swap
`SafepointRecord.sequenceNo` for `scanner.currentPageId()`, add page boundary
detection for size-triggered rolls.

The restore engine was more subtle. I wrote a test: two physical pages in one
safepoint interval, no compaction, both facts should be restored. It passed
immediately. That's because Phase 1 buffers all records between SafepointRecords
and flushes the whole buffer at once — without compaction, everything arrives
together regardless of page boundaries.

The real failure only surfaces when one physical page in a multi-page interval
is compacted while its sibling remains live. Phase 1 sees the SafepointRecord,
checks whether the sequenceNo is in `livePageIds`, finds it isn't (the merged
page replaced it), and discards the entire buffer — including records from the
still-live sibling.

We replaced the test with a compaction scenario: compact page `"0"` only, leave
page `"1"` live, expect both facts to survive restore. It failed. Then the fix:
Phase 0 now tracks all physical pages seen per safepoint interval and adds them
all to the page index when the interval closes. Phase 1 flushes at physical page
boundaries, not only at SafepointRecords.

PR #29 merged, 77 tests. The page design question deferred since the compaction
coordinator work is closed — Chronicle can proceed with size-based rolling.

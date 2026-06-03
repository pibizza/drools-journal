---
layout: post
title: "The Liveness Scan Finds Its Model"
date: 2026-06-03
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, tdd, liveness-scan]
---

Came back after a week. Issue C was waiting: `CompactionCoordinator.scanLiveness()`. I opened the plan. The implementation was wrong.

## What the Plan Had Missed

The plan set `currentPageId` when a `SafepointRecord` was encountered, then counted subsequent records as belonging to that page. Intuitive — see the safepoint, name the page.

But the tests in the same plan contradicted it. Page "0" was the records *before* `SafepointRecord(0)`, not after. The model in the prose and the model in the tests were inverted.

I decided to find out which was right by writing the tests one at a time.

## Slower, On Purpose

I told Claude upfront: show me the test first, I confirm, then show me the code. No file writes until I've read what's being written. The first test:

```java
@Test
void emptyJournal_producesEmptyLivenessMap() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);
    assertThat(liveness).isEmpty();
}
```

Minimal implementation: `return new HashMap<>()`. The second test pushed further — one insert, one safepoint, liveCount should be 1. Claude faked it:

```java
if (storage.latestPosition() >= 0) {
    liveness.put("0", new long[]{1L, 1L});
}
```

Exactly right for two tests. The next test would break the fake.

## Claude's Habit and the Catch

When the insert-then-retract test arrived, Claude proposed a full streaming scan immediately — fifteen lines for a test that had two records. I pushed back: too much for what the test required. Claude trimmed.

The trimmed version had an unnecessary `counts.clone()` before putting the array in the map. I asked why. Claude explained that reassigning `counts = new long[2]` leaves the map entry pointing at the old array anyway — the clone adds nothing. It also had an `if (counts[1] > 0)` guard that no test had driven. Removed.

Both catches happened before anything was written to disk.

## The Null That Broke the Streaming Model

The cross-page retract test exposed the real problem. Insert on page "0", retract on page "1", expect page "0" liveCount to drop to 0.

The streaming approach set `currentPage` at the safepoint — so records *before* the first safepoint were processed with `currentPage = null`. The insert stored `null` as its origin. The retract found nothing to decrement. Wrong.

The correct model: buffer records, process them when the safepoint fires. Then the page ID is known when needed.

```java
if (record instanceof SafepointRecord sp) {
    String pageId = String.valueOf(sp.sequenceNo());
    liveness.put(pageId, new long[2]);
    for (JournalRecord r : pending) {
        if (r instanceof InsertRecord insert) {
            liveness.get(pageId)[0]++;
            liveness.get(pageId)[1]++;
            factToPage.put(insert.factHandleId(), pageId);
        } else if (r instanceof RetractRecord retract) {
            liveness.get(pageId)[1]++;
            String origin = factToPage.remove(retract.factHandleId());
            if (origin != null) {
                liveness.get(origin)[0]--;
            }
        }
    }
    pending.clear();
}
```

Records before safepoint N belong to page N. The safepoint is the page boundary — not a cursor pointing forward.

## isSparse

With the liveness map correct, `isSparse()` was straightforward. A page is sparse when liveCount / totalCount falls below 30%:

```java
static boolean isSparse(final long[] counts) {
    return (double) counts[0] / counts[1] < 0.30;
}
```

One test for the sparse case, one for not sparse. The threshold is fixed for now.

A retract counts as live on the page where it sits. It's still doing work — pointing at a dead insert that hasn't been cleaned up. Compaction removes both together.

## Before the Compactor Runs

Issue C is committed. But `InMemoryJournalStorage` still has no page model — it's a flat list. A background compactor writing merged records into the same list as the session's safepoints would interleave them, breaking the restore logic. Real backends avoid this because the compactor writes to a separate file.

That's the next piece.

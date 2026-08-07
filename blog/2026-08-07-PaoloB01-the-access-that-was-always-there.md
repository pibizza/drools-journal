---
layout: post
title: "The Access That Was Always There"
date: 2026-08-07
type: correction
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [chronicle-queue, position-model, compaction]
---

## The assumption

Somewhere during the Chronicle backend design, I picked up the belief that Chronicle Queue has no random access — only sequential forward scanning. I don't know exactly where it came from. Maybe I read past `moveToIndex()` in the API and didn't register it. Maybe I inferred it from the append-only write model. Either way, that belief stuck, and we built around it.

The `scan(long fromPosition)` parameter in the SPI accepts a position but the Chronicle implementation ignores it. Every call site passes `0`. `MultiQueueScanner.position()` returns a synthetic counter — a useless incrementing `long` that can't be fed back to `scan()` to resume where you left off. CompactionCoordinator does a full journal rescan on every 60-second poll cycle because incremental scanning seemed impractical.

I asked Claude to check the Chronicle Queue documentation for a direct access method. It came back with `ExcerptTailer.moveToIndex(long)` — O(1) lookup via a two-level sparse index tree, bounded linear scan of at most 256 entries (the default `indexSpacing`). It was there the whole time.

## Five consequences of one wrong assumption

We traced the impact across the codebase. Five concrete things we built (or didn't build) because of it:

1. **`scan(fromPosition)` is dead code.** The Chronicle implementation accepts the parameter and ignores it. Every caller passes `0`.

2. **`MultiQueueScanner.position()` returns nothing useful.** A synthetic counter that increments with each `next()` call. Not a Chronicle index, not a resume point — just a number.

3. **CompactionCoordinator rescans everything every cycle.** `scanLiveness()` starts from record zero on every poll. At 60-second intervals, this re-reads the entire journal history every minute.

4. **The "mid-stream resume via cumulative record index" idea** in IDEAS.md (July 13) proposed building a custom index on top of Chronicle. A workaround for something Chronicle already provides natively.

5. **The contract tests for `scan(fromPosition)` were removed** (blog July 13) because they couldn't pass with the Chronicle backend. They can come back now.

## What changes: JournalPosition

The fix is a new position type that replaces `long` across the SPI:

```java
public record JournalPosition(String pageId, long offset) {
    public static final JournalPosition START =
        new JournalPosition(null, 0);
}
```

`pageId` identifies which queue-per-page directory holds the record. `offset` is the Chronicle 64-bit composite index — cycle plus sequence — from `tailer.index()`. Together they give you an address that round-trips through `scan()`: pass a `JournalPosition` to `scan()`, the scanner calls `moveToIndex(offset)` on the right page's tailer, and you're exactly where you left off.

Every write method changes its return type from `long` to `JournalPosition`. Every `scan(0)` becomes `scan(JournalPosition.START)`. `latestPosition()` returns `JournalPosition` instead of `long`. The migration is mechanical — every current call site passes `0` and ignores write return values.

## What changes: incremental liveness

With real positions, CompactionCoordinator can remember where it stopped and scan only new records next cycle. The `liveness` and `factToPage` maps become instance fields that accumulate between polls instead of being rebuilt from scratch:

```java
private JournalPosition lastScannedPosition = JournalPosition.START;
private final Map<String, long[]> liveness = new HashMap<>();
private final Map<Long, String> factToPage = new HashMap<>();
```

Each `runCycle()` calls `scan(lastScannedPosition)`, processes only new records, then saves `scanner.position()` as the new starting point. The page index (Phase 0) still does a full catalog scan — that's cheap, just the catalog queue — but the data scan becomes incremental.

One detail Claude flagged during self-review: `scanner.position()` needs "resume point" semantics. Chronicle's `tailer.index()` already returns the index of the *next* record to be read, not the last one returned. So `scan(scanner.position())` picks up exactly where the previous scan left off. The spec and plan both note this explicitly.

## What doesn't change

The queue-per-page architecture (ADR-0002) stays. It was driven by deletion needs — Chronicle can't delete individual records, so compaction retires whole page directories — and by Aeron compatibility. Access patterns were never the reason for the multi-queue design.

## Three items flagged for implementation review

We planned seven implementation tasks but I decided not to start coding this session. The design needs careful, step-by-step implementation with review after each piece. Three things to watch:

1. **Retired-page fallback.** When `fromPosition.pageId()` no longer exists (compaction retired it between polls), the scanner falls back to `START`. Full rescan is safe but maybe not the best answer — worth evaluating whether finding the nearest surviving page is better.

2. **Liveness map consistency after compaction.** The `factToPage` map has entries pointing at retired pages. They should be overwritten when the merged page's InsertRecords come through the incremental scan, but that needs verification.

3. **On-demand vs scheduled coordinator state.** The on-demand coordinator (tests, `Duration.ZERO`) stays stateless — fresh maps every call. The incremental state only accumulates in the scheduled background coordinator. Need to confirm no test depends on accumulated state.

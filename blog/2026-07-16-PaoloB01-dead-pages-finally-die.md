---
layout: post
title: "Dead Pages Finally Die"
date: 2026-07-16
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, page-retirement, adr, tdd]
---

## Dead Pages Finally Die

Page retirement — #34, "Compaction Step 4" — had been deferred from two
consecutive sessions. The compaction protocol does PREPARE, WRITE, COMMIT,
but never RETIRE: old page directories accumulate on disk forever. Correctness
isn't affected because `buildLivePageSet` already excludes them during restore,
but production disk usage grows without bound. Today I wanted to close that gap.

## The sealing question

The design conversation started simply: add `retirePages` to the SPI, call it
after compaction. But I asked Claude to walk me through `buildLivePageSet`
step by step, and that surfaced something worth documenting properly.

The interaction between `SafepointRecord` as a sealing mechanism,
`CompactionCommitRecord` as a tentative declaration, and the three levels of
"page" (logical safepoint interval, physical journal page, Chronicle `.cq4`
segment files) isn't written down anywhere. Blog entries mention sealing in
passing. DESIGN.md describes the protocol steps. But nobody explains *why* a
commit without a subsequent safepoint is meaningless, or what happens at each
crash point.

I decided to write ADR-0003 before continuing implementation. Ten-minute
detour while the concepts were fresh. The three-level page distinction —
logical, physical, Chronicle segment — was the part I wanted future readers
to find without re-deriving.

## Duplicated scan logic

While looking at `buildLivePageSet`, I noticed that `RestoreEngine.scan()` and
`CompactionCoordinator.scanLiveness()` share an identical structural pattern:
Phase 0 builds the live page set, Phase 1 re-scans the full journal with
safepoint buffering and page-boundary detection. The only difference is what
the flush callback does.

I logged this as an idea for later refactoring rather than fixing it now.
Duplicated traversal logic is dangerous — a bug fix to page-boundary detection
would need to be applied in both places.

## Piggybacking, not rescanning

My first design for retirement involved a separate scan to find sealed commits.
I caught myself — `buildLivePageSet` already knows which pages were replaced by
sealed compaction commits. The retired set falls out naturally from the
`spliceIntoIndex` calls it already makes. No third scan needed.

We introduced `PageIndexStatus(livePages, retiredPages)` as the return type.
I named it `PageIndexStatus` — it's the current state, not the result of an
operation. `runCycle` calls `buildLivePageSet` once, retires whatever is in
`retiredPages`, then proceeds with liveness scanning using `livePages`.

## TDD friction

The TDD cycle went well, with a few course corrections.

Claude tried to add three tests in a single edit. I stopped it — one test at
a time. The first proposed test for sealed compaction was also too complex:
two inserts, a retract, compaction of two pages, then sealing. I asked for
the simplest possible scenario. We settled on: one insert, one retract (making
the page 0% live), compact, seal. One page, one compaction, one safepoint.

The unsealed compaction test was subtler. The first version asserted that
`currentPageNumber` stayed constant after `runCycle`. It didn't — `runCycle`
saw the still-sparse source page and triggered a *new* compaction, adding a
merged page. The right assertion was on the live page set (page "0" should
still be present), not on raw page count.

## The coordinator API cleanup

While writing the `runCycle` test, the API inconsistency was obvious:
`compact` was a static method taking storage as a parameter, while `runCycle`
was an instance method using storage from the constructor. I suggested
cleaning this up as we went — duplicated calling conventions are confusing.

We made `compact` and `scanLiveness` instance methods and added
`CompactionCoordinator.onDemand(storage)` as a factory for test-friendly
coordinators with `Duration.ZERO`. Tests now read
`CompactionCoordinator.onDemand(storage).compact(Set.of("0"))` instead of the
awkward `CompactionCoordinator.compact(storage, Set.of("0"))`.

## Chronicle's own cleanup

For Chronicle directory deletion, Claude reached for `Files.walk` with
reverse-sorted deletion. I asked if Chronicle Queue had its own API for this.
A web search confirmed `IOTools.shallowDeleteDirWithFiles` from Chronicle
Core — designed for exactly this purpose. One line instead of twelve.

The `retirePages` SPI uses varargs — `retirePages("0", "1")` reads better
than `retirePages(new String[]{"0", "1"})`. I caught this during the first
test and asked for the change.

## Gap analysis status

With #34 closed, items 1–5 from the original spec-vs-implementation gap
analysis (#31) are all done: DESIGN.md updated, Chronicle thread safety
resolved structurally, Chronicle compaction tested, modify-as-lambda
precompiler shipped, page retirement implemented. The remaining gaps are
benchmarks (#37), concurrent compaction tests (#36), ExternalRefStrategy
(#35), and the Aeron backend (#15).

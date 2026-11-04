---
layout: post
title: "The Cycle That Never Retired"
date: 2026-09-10
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, catalog, retirement, code-review]
---

## Picking the issue apart

I opened issue #56 expecting a quick status check. Two of its claims were already stale: it said compaction records still landed in InMemory's data pages — they didn't, `catalog.records` had owned that since the last session — and it treated safepoint-sealing as an open decision that the catalog-first design had already made moot.

I asked Claude to audit the issue against the actual code before touching anything. It found the InMemory side further along than the issue admitted, but flagged a real gap: `CompactionCoordinator.runRetirementCycle()` still called `PageIndex.buildLivePageSet()`, which drives its cursor off `scan()` — and `scan()` never emits `CompactionCommitRecord` anymore. The method always returned an empty retired-page set. A test sitting right there confirmed it: `CompactionCoordinatorTest.runCycle_afterSealedCompaction_retiresSourcePages`, quietly `@Disabled`.

## Claude gets the catalog wrong, then right

Claude's first pass leaned on an idea that InMemory buffers compaction commits until a safepoint seals them, mirroring what it took to be a Chronicle divergence. I pushed back: compaction records live only in the catalog, replayed independently of the data-page safepoint sequence. The only invariant that matters is that compaction can only ever target pages already sealed into the catalog — `scan()` guarantees that structurally. Claude agreed; the "safepoint-sealing decision" in the issue turned out to be a non-issue neither backend needed solving.

## A different kind of review

Partway through, I told Claude to stop proposing implementations and just check what I write — I'd do the coding. I wrote `CompactionCoordinator`'s merge/retire split myself; Claude's job became running the builds and catching what I missed.

Two bugs came out of that immediately. Deduplicating `start()`/`stop()` into a shared `startThread`/`stopThread` pair, I'd inverted the `Duration.ZERO` guard — `scheduleWithFixedDelay(command, 0, 0, ...)` threw `IllegalArgumentException` for the on-demand case, and a real interval silently started nothing. The second was subtler: `startThread` took `ScheduledExecutorService executor` as a parameter and reassigned it locally. Java doesn't propagate that back to a field — `mergingExecutor`/`retirementExecutor` stayed null after `start()`, so `stop()` had nothing to shut down.

## The tombstone problem

Splitting retirement onto its own `IndexStatus`-driven cycle fixed the dead `PageIndex` path. Claude un-disabled the old test, pointed it at `runRetirementCycle()` instead of the merge cycle, and it passed. But retirement running twice doesn't. `InMemoryJournalStorage.retirePages()` removes a page from `journal` outright; the catalog still holds its `PageRecord` and `CompactionCommitRecord`. Replay it again and `InMemoryMultiQueueScanner.build()` throws `NullPointerException: Cannot read field "id" because "bufferedPage" is null`, trying to resolve a page that's already gone. Claude reproduced it with a throwaway test before I asked for it to be deleted.

The fix is an explicit retirement tombstone written into the catalog by `retirePages()` itself, so replay can skip a page instead of resolving it. Not built yet — noted in the journal and the issue for next time.

## Borrowing a worktree instead of a stash

Before committing, a full reactor build turned up two of `ChroniclePageRetirementIT`'s five tests failing — surviving facts missing after compact-then-reopen. I didn't want Claude stashing my uncommitted changes to check whether this predated today's work, so it spun up a throwaway `git worktree` at the last commit instead and ran the same test there: 5/5 passing at baseline. A real regression, not yet root-caused — I'll pick it up tomorrow.

Also fixed, unrelated to any of this: the workspace repo's GitHub default branch had been pointing at `epic-compaction-coordinator`, an epic closed back in June. Reset to `main`.

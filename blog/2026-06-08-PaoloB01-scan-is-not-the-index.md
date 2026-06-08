---
layout: post
title: "Scan Is Not the Index"
date: 2026-06-08
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, architecture, restore-engine]
---

The session started with a question, not a task. Before touching `compact()`, I wanted to understand why `writeMergedPage` needed to exist at all. The previous handover mentioned it without explaining the reasoning, and I wasn't going to build on a foundation I didn't understand.

## The Ordering Problem

The answer required thinking through what the journal actually is. It's a sequence of pages — some active, some retired, some merged. After compacting pages P0 and P1 into Pm, the live replay order should be `[Pm, P2]`, not `[P2, Pm]`. Pm conceptually replaces P0 and P1, which preceded P2. If you replay P2 first, its operations run against a working memory that hasn't seen Pm's facts yet.

The inline approach — writing merged records directly between PREPARE and COMMIT in the flat record stream — was fatally broken. Pm would land at the end of the stream, after P2. Correction happened after the fact, if at all.

The right model: Pm is written as a separate page, not yet in the live sequence. At COMMIT (sealed by a safepoint), it splices into the page index at the position of P0 — before P2, where it belongs.

## Where the Index Belongs

We built the wrong thing first. I had asked for a structural change to `InMemoryJournalStorage`, and Claude delivered it: a `List<Page> pageIndex` alongside the raw `journal`, maintained during `append()`, with `scan()` returning records in live-index order. The compaction COMMIT would splice Pm into position, pending commits would be sealed by the next safepoint. It was internally consistent.

But it was wrong for a simple reason: real storage doesn't maintain an in-memory index across crashes. When a file-backed journal restarts after a crash, there's no index — it has to be rebuilt from the raw pages. Putting index maintenance in `append()` only works when the process never stops.

The index isn't a storage concern. It belongs in `RestoreEngine`.

I made that point. Claude agreed and we reversed: `scan()` reverted to raw creation order (reads from `journal`, not `pageIndex`), Phase 0 came back to `RestoreEngine` — it scans the raw stream, builds `pageIndex` and `livePageIds`, hands them to Phase 1. Phase 1 replays, flushing live pages and discarding retired ones at each `SafepointRecord`.

`InMemoryJournalStorage` dropped to exactly what it should be: a sequential `journal`, a `pageById` lookup map, a `currentPage`, and nothing else. No `pendingCommits`. No `spliceIntoIndex`.

## compact() Phases 1 and 2

With the architecture settled, the first two steps of `compact()` were short work.

Phase 1 — PREPARE: generate a merged page ID, append a `CompactionPrepareRecord` referencing the source page IDs. Two tests, one implementation.

Phase 2 — WRITE: scan source pages to find live `InsertRecord`s, write them to a separate page via `writeMergedPage()`. We hit one non-obvious problem immediately: the buffering issue. Records appear in the raw stream *before* the `SafepointRecord` that closes their page. You can't attribute an `InsertRecord` to a page ID until the safepoint fires. The solution is a `pageBuffer`: accumulate records, flush them into `liveInserts` when the safepoint names the page.

A second problem appeared when verifying Phase 2: `Set.of(...)` throws `NullPointerException` on `contains(null)` — it doesn't return false, as a mutable set would. The `currentPageId` starts null, and the first `InsertRecord` arrived before any safepoint. A null guard fixed it.

68 tests pass. Phase 3 (COMMIT) and Phase 4 (RETIRE) are the remaining steps.

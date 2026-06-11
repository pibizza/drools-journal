---
layout: post
title: "Tests That Couldn't Happen"
date: 2026-06-11
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, testing, correctness]
---

Phase 3 of `compact()` was a one-liner. The fix itself took thirty seconds:
add `storage.append(new CompactionCommitRecord(mergedPageId, replacedPageIds))`
after `writeMergedPage()`. The work before it took longer.

## The Tests That Couldn't Happen

Before touching production code we looked at `CompactionCorrectnessTest`. Three
tests existed, all written in a previous session. All three called `compact()`
on pages that were 100% live — every inserted fact still in working memory,
nothing retracted. The sparseness threshold is 30%. A page with a live ratio
of 100% would never be selected by `isSparse()`.

I noticed this before writing anything new. The tests weren't wrong in the sense
of failing — they passed. But they were testing a scenario that can't occur in
production. The background thread would never call `compact()` on those pages.

We reworked all three. Every compaction test now starts with inserts, then
retracts enough facts to push the page below the threshold. A page with four
inserts and three retracts has a live ratio of 25% — that's sparse. That's what
`compact()` actually sees.

## One Line

With the tests honest, we added the failing test: insert, retract, compact.
Both pages sparse. PREPARE and COMMIT must both appear. It failed — `commitCount`
was 0. Then we added the line, and it passed.

## The Sealing Safepoint Trap

The end-to-end restore test caught something unexpected. After `compact()`, a
sealing safepoint is needed — the `CompactionCommitRecord` only becomes effective
when a `SafepointRecord` follows it in the raw stream. So the test called
`storage.safepoint()` to simulate the next `fireAllRules()`.

The test failed. `survivingFacts` had four entries instead of one.

The cause: `InMemoryJournalStorage.safepoint()` (no-argument form) uses an
internal counter that starts at 0. We'd already written safepoints 0 and 1 using
the test helper that takes an explicit sequence number — those calls don't update
the internal counter. So `safepoint()` appended `SafepointRecord(0)`, colliding
with the first page already in the journal. Phase 1 treated page "0" as live a
second time and flushed all four original inserts.

The fix: `storage.safepoint(2)` explicitly. When you mix manual sequence numbers
with the auto-counter, you need to track what you're doing.

## Four More Tests

With the end-to-end test green, we added three crash scenarios and a sequential
compaction test:

- **Crash after PREPARE**: orphaned PREPARE with no COMMIT leaves original pages
  canonical. If PREPARE alone retired source pages the surviving fact would
  disappear — it doesn't.
- **Crash after COMMIT, before sealing safepoint**: unsealed COMMIT leaves
  `pendingCommits` forever unspliced. Original pages stay canonical.
- **Two sequential compactions**: facts 1 and 5 survive two independent rounds.
  Each has its own PREPARE/COMMIT/safepoint sequence. `RestoreEngine` handles
  both without confusion.

73 tests pass. Task D3 — background thread and session lifecycle — is next.

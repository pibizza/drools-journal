---
layout: post
title: "The Branch That Came Home"
date: 2026-09-08
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [refactoring, scan, compaction, convergence]
---

## Eight commits in exile

The `refactor/code-revision` branch had been sitting 7 commits ahead of `main` — 4 refactors, 3 blog entries, and one uncommitted fix. No divergence the other way: `main` had nothing the branch didn't. A clean fast-forward waiting to happen.

I wanted to merge and get back to the normal workflow: issues, feature branches, PRs. But first we needed to know whether the code was actually sound.

## Two tests that said nothing

We ran `mvn clean install`. Two failures in `JournalledKieSessionTest`:

```
Expected: "INSERT  id=1  Integer(42)\nRETRACT  id=1\n"
but was: ""
```

Both tests inserted a fact and then deleted or updated it — without calling `fireAllRules()`. The journal came back completely empty. Not just missing the retract — the insert was gone too.

The first instinct was wrong. It looked like the journalling listener wasn't being wired to the session. We traced through `JournalledRuntimeComponentFactory` — the listener is added unconditionally at line 91, no conditional path. The wiring was fine.

## The scan that hid the open page

The real cause was in `InMemoryJournalStorage`. The catalog-driven refactor had changed `scan()` to return only sealed pages — pages that had been safepointed and registered in the catalog. Records written to the current open page (`currentPage`) were invisible to `scan()` until a safepoint flushed them into the `journal` list.

The two failing tests never called `fireAllRules()`, so no safepoint was written, so `currentPage` never moved into `journal`. The scanner returned nothing. The passing test called `fireAllRules()` — which triggers `safepoint()` — so its page got sealed.

The scan contract is correct. Restore and compaction must only operate on sealed pages — reading uncommitted records would be unsound. The problem was that `toString()` delegated to `scan()`, and `toString()` is a debugging tool, not a restore path.

## The fix: two lines of separation

I changed `JournalPrinter.print()` to take a `List<JournalRecord>` instead of a `JournalStorage`. Then `InMemoryJournalStorage.toString()` collects records from both the sealed pages and the open page, passing them directly to the printer. The scan contract stays clean; `toString()` shows everything.

All tests passed. We merged via PR #57 — fast-forward into `main`.

## What's still open

Merging brought us back to the normal workflow, but issue #56 is only half done. We checked the remaining task list against the code:

- `CompactionCoordinator` still calls `PageIndex.buildLivePageSet()` for retirement — the phase 0 scan that should be gone
- `ScanCursor` still handles `CompactionPrepareRecord` and `CompactionCommitRecord` — dead branches that will never fire
- `PageIndex` and `PageIndexCursor` still exist, kept alive only by the compaction coordinator
- Chronicle's `writeRecord()` has guard cases for compaction records that throw `IllegalArgumentException` — correct but pointing at unfinished separation

I want to separate retirement from compaction entirely. They're independent processes sharing a single `runCycle()` method for no good reason. Retirement just garbage-collects pages the catalog already marks as replaced. Compaction scans liveness and merges sparse pages. No data dependency between them. Splitting them makes each independently testable and eliminates the `PageIndex` dependency from compaction.

That will be the first proper issue-driven feature branch off `main`.

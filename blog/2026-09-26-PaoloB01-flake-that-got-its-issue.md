---
layout: post
title: "The Flake That Finally Got Its Own Issue"
date: 2026-09-26
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, chronicle, testing, flaky-tests]
---

## Picking #56 back up

Two weeks after the retirement fix, I came back to `refactor/56-two-tier-catalog-contract`
to close it out. The plan was small: confirm the last two open items were done, commit the
test cleanup already sitting in the working tree, open a PR, merge.

## Checking the work was actually finished

Before trusting the tracker I had Claude verify the two remaining production items against the
code. Both were done: `grep` for `CompactionPrepareRecord`/`CompactionCommitRecord` across
`drools-journal-core/src/main` came back empty, and `PageIndex`/`PageIndexCursor` no longer
exist anywhere in the tree — the only `PageIndex` hit was a `nextPageIndex` local in the
scanner. Good. #56's production checklist was genuinely complete.

## The "transient" failure, run in the open

Then `mvn clean install`. Red — two failures in `ChroniclePageRetirementIT`:

```
ChroniclePageRetirementIT.singleCompaction_chronicle_restoreShowsSurvivingFacts:170
  Expected size: 1 but was: 0 in: {}
ChroniclePageRetirementIT.twoConcurrentCompactions_disjointPageSets_...:143
  Expected size: 2 but was: 0 in: {}
```

Every handover I'd written called these "transient." Claude re-ran just that IT in isolation to
check — same 2/5, same empty `{}` restore. Deterministic on this run, not a one-off. After a
compaction, closing and reopening the Chronicle journal came back with zero surviving facts.

For a moment I treated that as a blocker for #56. It isn't. These tests have a genuine recurring
instability — they've failed intermittently across sessions — but the contract work #56 is about
lives in `drools-journal-core`, which builds green. The Chronicle failure is its own problem, and
it had been riding along inside #56's branch without ever being named.

## Giving the problem a name

So we opened #58 — "Review the Chronicle implementation." Not just "fix the flaky test," but the
larger suspicion underneath it: after the two-tier catalog contract landed, the Chronicle side
might be carrying more complexity than the contract now needs. The issue captures both — root-cause
the empty-restore-after-compaction, and decide whether these ITs get stabilised or rewritten.

That reframing is the whole point. A flaky test you keep excusing in handovers is invisible debt.
A flaky test with an issue number is work.

## Closing the loop

With the flake parked, the rest was quick. The staged test cleanup — dropping the misleading
"sealed"/"unsealed" naming and the safepoint-sealing assumptions, un-disabling the retirement
test, deleting `crashAfterCommit_beforeSafepoint_restoreUsesOriginalPages` (it asserted an
invariant that no longer exists) — committed as `test(compaction): drop sealed/unsealed naming`,
`Refs #56`.

Then PR #59, `Closes #56`, merged into `main` as a merge commit — no squash, the seven commits
tell the story better whole. Both project and workspace repos switched back to `main`, branches
deleted. #56 is done: the two-tier catalog+data contract is the explicit `JournalStorage` shape
now, `scan()` returns only live pages, and `PageIndex` is gone for good.

The Chronicle question is still open. But now it's #58, not a footnote.

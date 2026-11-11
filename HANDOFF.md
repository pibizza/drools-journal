# Session Handover — drools-journal

**Date:** 2026-11-11
**Branch:** `feat/64-snapshot-compaction-policy` (created off refreshed `epic-snapshot-mode`)
**Head:** `a7acf32` (branch point; no #64 commits yet)

---

## What Changed This Session

- **#61 DONE** — `DurabilityMode` API + session wiring. PR **#71 merged** into
  `epic-snapshot-mode`; **#61 closed manually** (merge was into the epic branch, not
  `main`, so `Closes #61` didn't auto-fire). Pure plumbing, no behavioural change.
- **Epic branch refreshed** — `epic-snapshot-mode` was 11 commits stale and missing
  the #66 fix; **rebased onto `main`** (its one unique commit was a duplicate blog
  entry already on main — nothing lost). Safety tag `backup/epic-snapshot-mode-prerebase`
  (`f24a6da`) kept; delete once epic is confirmed stable.
- **#64 planned** (not started) — task list agreed, branch created.
- **CLAUDE.md** — added the focused-test `-Dsurefire.failIfNoSpecifiedTests=false` nuance.

---

## Where to Start Next

**Implement #64 — full-merge SNAPSHOT compaction policy.** Strategy in spec
`specs/2026-10-05-snapshot-mode-design.md` Part B.2; AC in issue #64. Inline TDD.

Agreed task list:
1. **SNAPSHOT policy in `runMergingCycle`** — branch on `mode`: extract current
   contiguous-run logic to `runJournalCycle`, add `runSnapshotCycle` merging **all
   sealed live pages** into one via existing `compact(...)`. No-op on 0–1 pages.
2. **Retirement** of replaced pages (likely already covered by reusing
   `compact()` + `runRetirementCycle()`; pin with a test).
3. **Restore-from-snapshot IT** in `drools-journal-tests` (golden-output via `JournalPrinter`).
4. **Full build + DESIGN.md sync.**

**Key design insight (verified this session):** `scanLiveness()` only emits entries
for *sealed* pages (trailing open page discarded). So "all sealed live pages" =
`livePageIds()` filtered to those in the liveness map — the active append page is
excluded automatically (no `currentPageId()` needed), and the set is a contiguous
prefix so #66's modify-chain reordering concern doesn't apply. `compact()` is
reusable as-is.

PR #64 → `epic-snapshot-mode`. Epic order after: #65 → #62 → #63.

---

## Open Items / Notes

*Unchanged — retrieve with: `git show HEAD~1:HANDOFF.md`* (#55 catalogWriter
concurrency; #58 `ChroniclePageRetirementIT` known flake).

---

## References (locate on demand)

| What | Path |
|------|------|
| #64 strategy spec | `specs/2026-10-05-snapshot-mode-design.md` (Part B.2) |
| Coordinator (merge/retire) | `drools-journal-core/.../core/CompactionCoordinator.java` |
| Epic journal | `design/JOURNAL.md` |
| Primary design | `DESIGN.md` |

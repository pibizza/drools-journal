# Session Handover — drools-journal

**Date:** 2026-11-12
**Branch:** `feat/64-snapshot-compaction-policy` (rebased onto refreshed `main`)
**Head:** `3a20998` (handover doc) — #64 implementation not yet committed

---

## What Changed This Session

- **#72 found, fixed, merged.** While starting #64 we discovered `compact()`
  dropped **every** `RuleMatchRecord` — a latent base-compaction bug (not
  SNAPSHOT-specific). Matches for still-live facts are needed on restore for the
  already-fired set (no rule re-fire) and TMS justification. Fix: keep a match
  while all its referenced facts are live; drop once any is retracted. PR **#73
  merged to `main`**, `#72 closed`. **ADR 0004** records the decision. Verified
  both edges (3 core + 2 end-to-end tests), full `mvn clean install` green.
- **Branches rebased & clean.** `epic-snapshot-mode` (force-pushed) and
  `feat/64` both rebased onto `main` (now carries the #72 fix). All three
  branches in sync with origin.
- **#64 Task-1 is STASHED, not committed** — `stash@{0}` holds the
  `runMergingCycle` mode-branch (`runSnapshotCycle`/`runJournalCycle`) + 3
  SNAPSHOT tests. Left uncommitted on purpose (see open design question).
- **Blog correction** written (`2026-11-12-...-match-records-werent-overhead.md`)
  — corrects the 2026-10-05 claim that match records were "overhead".

---

## Where to Start Next

**Resume #64.** `git stash pop` to restore Task-1, then **before more code**
settle the open design question:

- **SNAPSHOT output shape.** Full-merge currently collapses all sealed pages into
  **one** merged page via `compact()`. For a large live set that's a single giant
  page. The desired shape is **multiple size-bounded pages**. This likely changes
  `compact()` (or a SNAPSHOT variant) to emit a *set* of merged pages — a real
  design decision. Brainstorm before implementing; Task-1's `runSnapshotCycle` may
  change as a result.

Prior task list (steps 1–4) from `git show HEAD~1:HANDOFF.md` still applies, but
step 1 is now partly done (stashed) and gated on the output-shape decision.
PR #64 → `epic-snapshot-mode`. Epic order after: #65 → #62 → #63.

---

## Open Items / Notes

*Unchanged — retrieve with: `git show HEAD~1:HANDOFF.md`* (#55 catalogWriter
concurrency; #58 `ChroniclePageRetirementIT` known flake).

---

## References (locate on demand)

| What | Path |
|------|------|
| #72 decision | `adr/0004-rule-match-liveness-in-compaction.md` |
| #64 strategy spec | `specs/2026-10-05-snapshot-mode-design.md` (Part B.2) |
| Compaction liveness | `DESIGN.md` → Compaction Protocol |
| Coordinator | `drools-journal-core/.../core/CompactionCoordinator.java` |
| Epic journal | `design/JOURNAL.md` |

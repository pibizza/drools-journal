# Session Handover — drools-journal

**Date:** 2026-11-24
**Branch:** `epic-snapshot-mode` (#77 merged at `f83ea13`; in sync with origin)

---

## What Changed This Session

- **#64 done.** SNAPSHOT = full-merge into a **single** page (decision: one large
  page for now). `runMergingCycle` → `runSnapshotCycle` / `runJournalCycle`.
  3 core tests + 1 end-to-end restore test (`compactNow()`), full build green.
  PR **#77 merged** into `epic-snapshot-mode`; #64 closed; epic #38 ticked (#61, #64).
- **Three issues filed** under epic #38, each needing discussion or its own step:
  - **#74**: split snapshot output into multiple size-bounded pages. **Atomicity
    of a multi-page commit must be designed first.**
  - **#75** (bug, existing): `InMemoryJournalStorage.buildIndex()` doesn't retire
    a merged page that is merged again, so both stay live and facts duplicate.
    SNAPSHOT hits it from cycle 2. Found by reading the code; **not yet proven by
    a test**. Chronicle `CatalogIndex.spliceIntoIndex` looks correct.
  - **#76**: physical retirement never runs in production (`retirementInterval`
    hard-coded `ZERO`, no caller of `runRetirementCycle`). Needs a policy
    discussion. Logical retirement at COMMIT works.
- Journal entry added to `design/JOURNAL.md`. Note its header still says
  `epic-compaction-coordinator` (stale from an earlier epic); fix at epic close.
- CLAUDE.md: new Work Tracking note (`Closes #N` doesn't close issues on
  epic-branch merges, so close them by hand). Blog entry written.

---

## Where to Start Next

**#75**: branch `fix/75-...` from `epic-snapshot-mode`. Start with the failing
test: two consecutive SNAPSHOT cycles in `CompactionCoordinatorTest`; `m1` must
be retired and each fact must appear once. Then make `buildIndex()` splice like
Chronicle, and cover the same scenario on Chronicle.

Epic order after that: #65 → #62 → #63. #74/#76 are discussion-first.

---

## Open Items / Notes

*Unchanged — `git show f9f71c7:HANDOFF.md`* (#55 catalogWriter concurrency;
#58 `ChroniclePageRetirementIT` flake). Local branch `feat/64-…` and its remote
are kept (not deleted).

---

## References (locate on demand)

| What | Path |
|------|------|
| SNAPSHOT spec | `specs/2026-10-05-snapshot-mode-design.md` (Part B.2) |
| Coordinator | `drools-journal-core/.../core/CompactionCoordinator.java` |
| InMemory index (#75) | `drools-journal-core/src/test/.../core/InMemoryJournalStorage.java` → `buildIndex()` |
| Chronicle index | `drools-journal-chronicle/.../internal/CatalogIndex.java` |
| Match liveness | `adr/0004-rule-match-liveness-in-compaction.md` |
| Epic journal | `design/JOURNAL.md` |
| Latest blog | `blog/2026-11-24-PaoloB01-one-page-three-issues.md` |

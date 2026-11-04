# Session Handover — drools-journal

**Date:** 2026-10-05
**Branch:** `epic-snapshot-mode` (both project and workspace; pushed to origin)
**Head:** workspace `3df1d5b`, project blog commit pushed on `epic-snapshot-mode`

---

## What Changed This Session

Restarted **epic #38** (three-mode durability replacing drools-reliability),
focused on the first deliverable: **SNAPSHOT mode**. All design + planning —
**no implementation code written**.

- **Design convergence:** SNAPSHOT is *not* a new record or restore path. A
  snapshot = an ordinary merged page containing all live facts. `SnapshotRecord`
  from #38's scope is **resolved by omission**. SNAPSHOT = a compaction *policy*
  that full-merges every sealed live page into one on the existing periodic
  interval. Restore is unchanged (`scan()` already returns only live pages).
- **Framing rule (Paolo's directive):** keep functionality separate from
  implementation strategy. Spec is split — **Part A = contract** (STORES_ONLY
  parity, O(live-facts) restore, safepoint consistency, `DurabilityMode` API);
  **Part B = initial strategy** (full-merge). Tests target Part A.
- **Cadence:** reuse the existing periodic compaction interval; no second
  trigger mechanism (revisit later if needed).
- **Artifacts:** spec `specs/2026-10-05-snapshot-mode-design.md`; plan
  `plans/2026-10-05-snapshot-durability-mode-api.md` (#61, 2 TDD tasks);
  `design/JOURNAL.md` + `.meta` scaffolded (issue 38, sha b0fbe5b).
- **GitHub:** #38 scope checklist nests new subitems. Created **#61** (API +
  wiring) → **#64** (full-merge policy) → **#65** (logical-facts-as-plain) →
  **#62** (parity ITs) → **#63** (restore benchmark). Dependency order as listed.
- **Blog:** `2026-10-05-...-snapshot-that-was-a-merge.md` (phase-update)
  committed + pushed (project repo).

---

## Where to Start Next

**Implement #61** per `plans/2026-10-05-snapshot-durability-mode-api.md` —
inline execution, TDD. Pure plumbing: `DurabilityMode` enum (JOURNAL,
SNAPSHOT; JOURNAL_DELTA reserved in javadoc only) + `DurableSessionOption
.withMode` + thread through `CompactionCoordinator` (new 3-arg ctor) and
`JournalledRuntimeComponentFactory.java:101`. No behavioural change until #64.
Then #64 → #65 → #62 → #63.

Stale-ref note: spec Part A.4 / B.2 mention `JournalledSessionFactory.open(...)`
— that class no longer exists; real wiring is `JournalledRuntimeComponentFactory`
reading `DurableSessionOption` from the `Environment`. The plan uses the correct class.

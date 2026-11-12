# 0004 — Rule-Match Liveness in Compaction

Date: 2026-11-12
Status: Accepted

## Context and Problem Statement

`CompactionCoordinator.compact()` rebuilt a merged page by carrying forward only
live facts' records (inserts + modify deltas) and dropping every
`RuleMatchRecord`. This is a correctness bug: a match whose referenced facts are
still live is needed on restore — it populates the already-fired set
(`ReplayFilter`) so a surviving fact's rule does not re-fire, and it provides the
justifying activation that TMS uses to rewire a live logical fact's dependency.
Dropping it causes rule re-firing (duplicate consequences) and orphaned logical
facts after any compaction.

## Decision Drivers

* Restore after compaction must reproduce working memory exactly — no rule
  re-fire, no broken TMS links.
* The rule must hold for all merge policies (JOURNAL sparse-run merges and the
  upcoming SNAPSHOT full merge), so it belongs in the shared `compact()`.
* Original compaction spec §7.4 already defines a liveness rule for
  `RuleMatchRecord`; the implementation had diverged from it.

## Considered Options

* **Option A** — Keep a `RuleMatchRecord` iff all its referenced facts are still
  live (drop once any is retracted).
* **Option B** — Literal §7.4: keep a match only if it justifies a live *logical*
  fact (TMS-only); drop all others.
* **Option C** — Keep every `RuleMatchRecord` unconditionally.

## Decision Outcome

Chosen option: **Option A**, because a match tuple can only re-form while all its
facts are live; once any referenced fact is retracted the match is dead weight.
This single rule covers both correctness needs — already-fired suppression and
TMS justification — and drops only genuinely dead matches.

### Positive Consequences

* No rule re-firing on restore after compaction; consequences are not
  re-executed.
* TMS justification for live logical facts survives the merge.
* One liveness rule shared by JOURNAL and SNAPSHOT compaction.

### Negative Consequences / Tradeoffs

* A merged page retains matches for still-live facts, so slightly less space is
  reclaimed per compaction than a facts-only merge — the cost of correct restore.
* A degenerate match referencing *no* facts has no liveness key and so is never
  reclaimed by this rule. Edge case, not the common path — matches tied to facts
  are reclaimed when those facts are retracted.

## Pros and Cons of the Options

### Option A — Keep match while all referenced facts live

* ✅ Preserves both already-fired suppression and TMS links
* ✅ Drops genuinely dead matches (space still reclaimed once facts are retracted)
* ❌ Retains more than a facts-only merge

### Option B — Literal §7.4 (TMS-only)

* ✅ Minimal records retained
* ❌ Still drops plain fired matches for live facts → rules re-fire on restore
* ❌ Fails the demonstrated non-TMS re-fire case

### Option C — Keep every match

* ✅ Simplest; trivially correct for restore
* ❌ Never reclaims match space, even once all a match's facts are retracted →
  journal bloat

## Links

* Issue #72, PR #73 (fix + tests)
* `DESIGN.md` — Compaction Protocol (rule-match liveness)
* Original compaction spec `specs/2026-05-26-compaction-coordinator-design.md` §7.4

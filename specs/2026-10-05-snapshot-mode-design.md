# SNAPSHOT Mode Design

**Epic:** #38 — Unified durability: three-mode journal replacing drools-reliability
**Epic branch:** `epic-snapshot-mode`
**Date:** 2026-10-05

---

## Problem

`drools-journal` today implements a single durability model: a full append-only
log (**JOURNAL mode**) with four-step compaction and a sequential-scan restore
whose cost is O(total records on live pages). Epic #38 sets out to make
`drools-journal` a drop-in replacement for `drools-reliability`, whose
**STORES_ONLY** strategy offers O(live-facts) restore by keeping only the current
fact set and re-evaluating rules on load.

This spec covers the first #38 deliverable: a **SNAPSHOT** durability mode that
matches STORES_ONLY semantics and restore speed, while keeping the journal's
sequential-I/O write path and safepoint-based crash consistency.

Out of scope for this epic (tracked as separate #38 subitems): CEP event support,
pseudo-clock persistence, globals persistence, `JOURNAL_DELTA` (modify-as-delta),
and the Aeron backend.

---

# Part A — SNAPSHOT mode: the contract

This is what the mode *is* and guarantees, independent of how it is built. These
statements are the stable surface that tests pin and users rely on. Part B
describes the initial implementation strategy, which may evolve without changing
anything here.

## A.1 What it is

A new durability mode, selected per session, that persists a durable session with
the same functional semantics as `drools-reliability` STORES_ONLY, on the journal
foundation. It coexists with the existing JOURNAL mode; JOURNAL remains the
default and is unchanged.

## A.2 Guarantees

- **Fast restore — O(live facts).** Restore cost scales with the number of live
  facts in the session, not with the total number of historical operations.
- **Crash consistency via safepoints.** On restart the session is restored to the
  state as of the last safepoint; records after it are discarded. No worse than
  JOURNAL mode in any crash scenario.
- **Sequential-I/O write path.** The normal write path is append-only, identical
  to JOURNAL mode — no random-access writes.

## A.3 Semantics (STORES_ONLY parity)

Supported: insert, retract, logical insert, and full-snapshot update (an update
re-persists the whole fact).

Explicitly *not* provided (matching STORES_ONLY):

- **No modify-as-delta.** Updates persist a full fact snapshot.
- **No TMS guarantee.** Surviving logical facts are restored as plain facts; truth
  maintenance links are not reconstructed.
- **No exact replay suppression across restore of arbitrarily old matches.** Rules
  must be re-fire-safe (deterministic, or idempotent side effects). Already-fired
  activations are suppressed only within the most recent consistency window.

These are the same trade-offs `drools-reliability` STORES_ONLY already makes, and
are the contract against which parity is tested.

## A.4 API surface

`drools-journal-api`:

```java
public enum DurabilityMode {
    JOURNAL,    // full append-only log (current behaviour) — default
    SNAPSHOT    // STORES_ONLY-parity mode; O(live-facts) restore
    // JOURNAL_DELTA reserved for Phase 5 (modify-as-lambda)
}
```

`DurableSessionOption` gains `withMode(DurabilityMode)`. When unset the mode is
`JOURNAL`, so every existing caller and test is unaffected. The mode is read by
`JournalledSessionFactory.open(...)` and drives the durability strategy for the
session.

---

# Part B — Implementation strategy (initial): full-merge compaction

This part is deliberately separable from Part A. It describes *how* we realize the
SNAPSHOT contract today, chosen because it reuses proven machinery at low cost. A
future strategy (e.g. threshold-paced snapshots, or a dedicated snapshot
representation) could replace this section without touching Part A.

## B.1 Realization

A SNAPSHOT is realized as an ordinary **merged page** that happens to contain all
live facts. The compaction machinery already produces, on every merge, a page
containing only live facts — the liveness accounting in `CompactionCoordinator`
treats `RuleMatchRecord`/`ModifyRecord` as collectable overhead and carries
forward only live `InsertRecord`s (plus a trailing `SafepointRecord`). A merged
page is therefore already a snapshot of live facts.

The only missing piece for O(live-facts) restore is a merge that covers **all**
live pages at once (today's policy merges only the subset of pages below a 30%
liveness threshold). SNAPSHOT mode is exactly that: a compaction *policy* that
merges every sealed live page into one, on the existing periodic cadence, and
retires the rest.

Two consequences of this strategy (not of the contract):

- **No `SnapshotRecord` record type is introduced.** The #38 scope item
  "`SnapshotRecord` type" is resolved by omission under this strategy — a snapshot
  is a merged page whose `replacedPageIds` are every prior live page. A different
  strategy might reintroduce a dedicated type; the contract does not require one.
- **No new restore code.** `JournalScanner.scan()` already yields only live pages
  (two-tier catalog+data contract, #56), so restore transparently sees the latest
  snapshot page followed by the post-snapshot tail.

## B.2 Compaction policy — full-merge in `CompactionCoordinator`

The coordinator keeps its periodic daemon and configurable interval. The per-cycle
behaviour branches on mode:

- **JOURNAL (unchanged):** `scanLiveness()` → select the candidate pages with
  `liveCount/totalCount < 0.30` → merge them via PREPARE/WRITE/COMMIT/RETIRE.
- **SNAPSHOT (new):** select **all sealed live pages** (every live page up to, but
  excluding, the active append page reported by `currentPageId()`), and merge them
  into a single merged page with the same four-step protocol:
  1. `compactionPrepare(Pm, replacedPageIds = [all sealed live pages])`
  2. `writeMergedPage(Pm, liveInserts + trailing SafepointRecord)` — identical
     construction to today's merge
  3. `compactionCommit(Pm, replacedPageIds)`
  4. retire the replaced pages after the commit is sealed (as in #34)

A cycle with zero or one sealed live page is a no-op. Excluding the active page
preserves the "writer never pauses" guarantee and avoids racing the appender.

Wiring: `JournalledSessionFactory.open(...)` passes the mode to the
`CompactionCoordinator`, which selects its cycle policy. No change to the session
write path, listener, agenda, or restore engine.

## B.3 Restore — unchanged

No code change. After a snapshot, `scan()` returns `[snapshot page Pm]` +
`[pages appended since Pm]`; `ScanCursor` builds `survivingFacts` from the merged
inserts and `firedMatches` from the post-snapshot `RuleMatchRecord`s. Restore cost
is O(live facts + records-since-last-snapshot), satisfying guarantee A.2.

## B.4 Logical facts

A surviving logical fact's `InsertRecord` carries `justifyingRuleMatchId`, but the
justifying `RuleMatchRecord` is dropped when its page is merged. Per the A.3
contract (no TMS), restore does not attempt TMS wiring for such facts — they are
re-inserted as plain facts and re-justified naturally by re-propagation if the
rule re-fires. `ScanCursor` currently always records a `PendingTmsLink` for logical
inserts; in SNAPSHOT mode these links are ignored during restore orchestration.
The exact seam is an implementation detail for the plan; the observable contract is
A.3's "no TMS".

## B.5 Why this strategy is not less reliable

The snapshot is an optimization layered on durability, not the durability
mechanism. Durability remains in the append + safepoint log, which is untouched.

- The full merge is written on the background `drools-journal-compactor` thread via
  PREPARE → WRITE → COMMIT → RETIRE. The session writer never pauses.
- A crash during the merge leaves PREPARE-only state: the half-written merged page
  is ignored and the source pages are still present (RETIRE is step 4, strictly
  after COMMIT). Zero data loss.
- Worst-case SNAPSHOT restore therefore equals JOURNAL restore — replay the log
  since the last committed snapshot. Never slower-to-recover than today.

---

## Testing

Tests target the Part A contract, so they survive a change of strategy.

- **Parity integration tests** (`drools-journal-tests`, `*IT`, Failsafe): mirror
  `drools-reliability` STORES_ONLY scenarios — insert/retract/fire → simulate
  restart → assert identical working memory and no duplicate in-window fires.
  Golden-output assertions via `JournalPrinter`
  (`assertThat(storage).hasToString(...)`).
- **Restore-cost test:** demonstrates O(live-facts) restore — restore reproduces
  working memory from a bounded live set regardless of total historical operations
  (strategy-level: after a snapshot, predecessors are retired).
- **Restore benchmark** (`drools-journal-benchmarks`): SNAPSHOT restore cost scales
  with live-fact count, independent of total record count; contrast with JOURNAL
  restore on the same workload.

## Decomposition (candidate #38 subitems)

Mirrors the contract/strategy split so behaviour and realization are separate
concerns:

*Contract:*
1. `DurabilityMode` API + `DurableSessionOption.withMode` + factory wiring (S)
2. SNAPSHOT parity integration tests vs STORES_ONLY (M)
3. SNAPSHOT restore benchmark (S)

*Initial strategy:*
4. Full-merge snapshot compaction policy in `CompactionCoordinator` + aggressive
   retirement (M)
5. SNAPSHOT-mode logical-fact handling on restore (S)

## Alternatives considered

- **`SnapshotRecord` embedding payloads (as #32 §5.1):** a single record holding
  all live payloads, or position references. Rejected for the initial strategy —
  embedding duplicates the merged-page mechanism at record granularity; position
  references require a new random-access `seek` SPI and are incompatible with
  retiring pre-snapshot pages (the references would dangle). Remains a possible
  future strategy under the same Part A contract.
- **Threshold-based snapshot cadence (live-page count / bytes):** bounds restore
  cost more directly, but adds a second trigger mechanism. Deferred — reuse the
  existing periodic interval for predictability; revisit if needed.
- **Separate `SnapshotWriter` subsystem:** duplicates PREPARE/COMMIT crash-safety
  and page-merge logic already in `CompactionCoordinator`. Rejected for YAGNI.

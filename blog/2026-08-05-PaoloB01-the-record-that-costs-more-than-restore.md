---
layout: post
title: "The Record That Costs More Than Restore"
date: 2026-08-05
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [snapshot-mode, brainstorming, design]
---

## A stale handover and a closed issue

I started by asking what's next. The handover from July 31 listed #33 (Chronicle thread safety) as an open blocker. Claude investigated and found it's already closed — PR #41 merged two commits fixing the main concern: per-thread data writers for concurrent session and compaction access.

But the investigation wasn't wasted. We found a residual gap: the `catalogWriter` field is still shared between the session thread (via `roll()` → `pageCreated()`) and the compaction background thread (via `compactionPrepare()`/`compactionCommit()`). The `methodWriter` proxy is bound to a specific `ExcerptAppender` instance, and appenders aren't thread-safe. Low probability of hitting it — catalog writes are infrequent — but technically unsafe. Filed as #55 so it doesn't get lost.

## Brainstorming SNAPSHOT mode

I wanted to focus on #38's SNAPSHOT mode — the streamlined journal that replaces drools-reliability STORES_ONLY. The #32 analysis proposed a `SnapshotRecord` containing `Map<Long, Long>` (factHandleId → journal position), with restore seeking to each position to read facts. Fast restore, O(live facts).

We looked at three approaches for the snapshot mechanism: the position-reference design from #32, a "snapshot as full compaction" approach that reuses the existing compaction infrastructure, and a hybrid with a new `SnapshotCommitRecord`. I leaned toward the compaction approach — zero new record types, zero SPI changes, existing tests cover the mechanism.

Then I raised the large-page concern. Compacting all live facts into a single merged page could produce a gigantic file. The fix is straightforward — roll across multiple pages using the existing `PageRollStrategy` — but it pushed us to question something deeper.

## What are we actually optimizing?

I asked: how heavy is it really to read one million records? Chronicle Queue reads at 80M+ records/sec. Even a million records at ~100 bytes is a fraction of a second. The journal scan (Phase 1) is cheap. The expensive part is Phase 1.5 — Rete re-propagation, rebuilding join nodes, evaluating rule conditions. Both SNAPSHOT and JOURNAL mode do Phase 1.5. If the scan takes 200ms and the Rete rebuild takes 20 seconds, SNAPSHOT saves 1%.

We were optimizing the wrong thing.

## The record that costs more than restore

Then the real insight landed. I pointed at the benchmarks: the fire-heavy overhead is dominated by `RuleMatchRecord` writes — one per individual rule activation, not per `fireAllRules()`. With 192 rules and 100 facts, a single fire cycle writes thousands of records. The benchmark showed Chronicle fire-only at 100 facts: 17.1ms vs 3.4ms plain — ~14ms of journal overhead, mostly RuleMatchRecord writes.

Why do we write RuleMatchRecords? To build the `ReplayFilter` on restore, so already-fired rules don't re-fire. But in SNAPSHOT mode, the premise is deterministic, idempotent rules. Same facts in, same rules fire, same results. The ReplayFilter is unnecessary.

Drop RuleMatchRecords entirely in SNAPSHOT mode. That's the real optimization — not restore speed, but **write-path cost**. The per-`fireAllRules()` overhead drops from O(activations) record writes to just one SafepointRecord.

## The claim that wasn't in the spec

Claude initially framed this as "something we're discovering now — it follows logically from the deterministic rules constraint but wasn't spelled out in either #38 or #32." I asked to verify that carefully. Claude re-read the #32 analysis and corrected itself: the spec **explicitly keeps** RuleMatchRecords in SNAPSHOT mode. Line 289: "On rule match: `RuleMatchRecord` — same as current journal (for replay filter)." The SNAPSHOT restore path in #32 builds a ReplayFilter from RuleMatchRecords after the snapshot.

So dropping RuleMatchRecords is a departure from the #32 design, not something implicit in it. A valid direction — but our new idea, not the spec's.

## Why dropping RuleMatchRecords works

We traced the protocol to verify the reasoning. At a safepoint boundary, `fireAllRules()` has just completed. All live facts have been evaluated through Rete. All matching activations have fired. The session is quiescent.

On crash, rollback discards everything after the last SafepointRecord. Facts inserted after the last `fireAllRules()` — the ones that never got processed — are gone. What remains is a fully-processed state.

On restore: re-insert surviving facts into a fresh Rete network. Rules re-evaluate and produce activations. But every activation corresponds to a rule that already fired. Suppress all with `match -> false` — exactly what drools-reliability STORES_ONLY does. No ReplayFilter needed, no RuleMatchRecords needed.

One detail confirmed along the way: re-inserted facts get **new** fact handles. The restore builds an `oldToNew` map (`Map<Long, FactHandle>`) to translate journal IDs to fresh session IDs.

## Where this stands

I'm not ready to commit to a design. The analysis is sound but I want to think about the code more before deciding. The key tension: the #32 spec was designed around restore-speed optimization with RuleMatchRecords preserved; what we're proposing is a write-path optimization that drops them. Different value proposition, different trade-offs. Pausing here to let the design settle.

---
layout: post
title: "Six benchmarks and a semicolon"
date: 2026-07-22
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [benchmarks, jmh, chronicle]
---

## From skeleton to suite

Yesterday was scaffolding — a Maven module with a shade plugin and a 26MB uber-jar containing nothing. Today we filled it. Nine tasks in the plan, nine tasks done: base class, six benchmarks, a full-suite smoke run, a merged PR.

The approach is straightforward. An `AbstractJournalBenchmark` carries the Chronicle `--add-exports`/`--add-opens` flags on `@Fork`, builds the `KieBase` once at trial level, and creates a fresh Chronicle storage directory per iteration. Each concrete benchmark overrides `drlProvider()` to select its DRL and adds one `@Benchmark` method. A `DrlProvider` enum holds canned DRL strings; a `StockItem` POJO gives the modify benchmark something to mutate.

## The numbers

All six benchmarks ran. Here's what we measured (1 fork, reduced iterations — directional, not publication-grade):

**Throughput benchmarks** (warm, steady-state):

| Benchmark | p50 | p99 | Throughput |
|-----------|-----|-----|-----------|
| Insert | 1.1µs | 4.1µs | 0.42 ops/µs |
| Insert+Retract | 1.0µs | 5.4µs | 0.32 ops/µs |
| Insert+Fire | 226µs | 546µs | 4K ops/s |
| Insert+Modify+Fire | 273µs | 440µs | 4K ops/s |

Insert at 1.1µs p50 is encouraging — that's the full path through `session.insert()`, serialisation, and Chronicle Queue append. The fire benchmarks are two orders of magnitude slower, but that's the Drools engine doing pattern matching, not the journal.

**Single-shot benchmarks** (cold):

| Benchmark | 100 | 1K | 10K | 100K |
|-----------|-----|-----|-----|------|
| Restore | 16.5ms | 48.8ms | 101ms | 387ms |

Restore scaling is sublinear. 1000x more facts takes only 23x longer — the fixed overhead of opening Chronicle queues and building the catalog index dominates at small sizes. At 100K facts, ~387ms is reasonable for a cold restore including full journal replay and Rete re-insertion.

Compaction clocked 109ms for 100 facts (half retracted to create sparse pages). That's the full cycle: catalog scan, liveness analysis, merged page write, and commit.

## The semicolon that broke the modify benchmark

The `ModifyBenchmark` was the one that didn't work on the first try. JMH reported a parse failure:

```
[ERR 107] Line 2:0 mismatched input 'package' expecting one of the following tokens:
'[package, unit, import, global, declare, function, rule, query]'.
```

The DRL was fine — the problem was in `JournalDrlPrecompiler.injectGlobalDeclaration()`. It looks for `package org.drools.journal.benchmarks;` (with semicolon) to find the insertion point. Our DRL text blocks had no semicolons after the package declaration. Without a match, the method prepends the global declaration *before* the package line, producing unparseable DRL.

The fix was one character per DRL string. The error message gives no hint of the cause — it complains about `package` being unexpected, which is the opposite of the real problem.

## compactNow() — keeping it tentatively

To benchmark compaction, we needed to trigger `CompactionCoordinator.runCycle()` from outside `drools-journal-core`. The coordinator is package-private, so we added a `compactNow()` method on `JournalledKieSession` — the session is public and already holds a reference to the coordinator. It's a one-liner delegation.

I'm not convinced this belongs in the public API long-term. It exists because the benchmark needs it, and that's not the best reason for an API surface. We kept it for now with the understanding that it might get removed along with the compaction benchmark if the design doesn't justify it.

## Closing #37

PR #51 merged to main. Issue #37 is closed. The acceptance criteria — JMH suite in a dedicated module, covering write path and restore path with Chronicle, results reproducible via `java -jar` — are all met. The compaction benchmark is a bonus beyond the original scope.

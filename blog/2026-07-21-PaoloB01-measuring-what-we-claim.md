---
layout: post
title: "Measuring what we claim"
date: 2026-07-21
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [benchmarks, jmh, chronicle]
---

## The claims nobody checked

The spec cites sub-microsecond latency and 80M+ records/sec for Chronicle Queue. I wanted to believe those numbers — Chronicle's reputation is built on them — but we had zero benchmarks validating that our journal paths actually achieve anything close. Issue #37 had been sitting open since the gap analysis, and it was time.

Before writing anything, I wanted to see what the upstream drools-reliability project had done. I pointed Claude at `apache/incubator-kie-benchmarks` — specifically the `drools-benchmarks-reliability` module. It's a proper JMH suite with six benchmarks covering insert-only, fire-only, insert+fire, complex facts, fire-and-alarm scenarios, and a failover/restore test. Each parameterises across five storage backends: no durability (baseline), Infinispan embedded, Infinispan remote, Infinispan remote with protostream, and H2MVStore.

## What we learned from the upstream suite

The upstream benchmarks gave us a clear template, but also revealed gaps we should fill:

- **No real restore benchmark.** `InsertFailoverFireBenchmark` simulates a crash and calls `restoreSession()`, but only measures session ID retrieval — not the full replay cost that matters for journal-based durability.
- **No compaction benchmark.** Compaction doesn't exist in drools-reliability, so naturally there's nothing to measure.
- **No scaling dimension.** Fact counts are fixed parameters, not swept across a range to show how cost grows with journal size.

Our situation is simpler in one way — Chronicle-only, no Infinispan — but harder in another: we need to measure replay and compaction, which are journal-specific concerns that the key-value store approach doesn't have.

## The module skeleton

We created `drools-journal-benchmarks` — a new Maven module that produces a shaded uber-jar via `maven-shade-plugin`. The JMH dependencies were already managed in `drools-build-parent` at version 1.21, but scoped as `test`. For a standalone benchmark jar, they need `compile` scope, so the module overrides that explicitly.

The Chronicle Queue `--add-exports` and `--add-opens` JVM flags will go on the `@Fork` annotation in the abstract base class rather than in a surefire config — JMH forks its own JVMs, so the flags need to travel with the benchmark code.

Build works, 26MB uber-jar produced. That's all we did today — the scaffolding.

## What's next

Five benchmarks to write, following the upstream decomposition but adapted for journal semantics:

1. **InsertOnly** — journal write throughput, `useDurability` true/false for baseline
2. **FireOnly** — fire overhead with journalling
3. **InsertAndFire** — combined
4. **Restore** — replay time vs journal size (the one upstream lacks)
5. **Compaction** — compaction overhead measurement

Each will parameterise fact count for scaling. The abstract base class and DRL providers come first — Task 2 in the plan.

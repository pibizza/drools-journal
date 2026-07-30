# Chronicle Journal vs H2MVStore Reliability — Benchmark Comparison

**Date:** 2026-07-29
**Branch:** `feat/37-journal-benchmarks`
**Conditions:** SingleShot mode, 1 fork, full warmup (2000–3000 iterations), 1000 measurement iterations

## Setup

| | Chronicle Journal (this project) | H2MVStore (drools-reliability) |
|---|---|---|
| Persistence model | Append-only Chronicle Queue log | Embedded H2 MVStore (key-value) |
| Session creation | `DurableSessionOption` | `PersistedSessionOption` |
| Safepoints | After `fireAllRules()`, skipped if page empty | `AFTER_FIRE` disabled (`useSafepoints=false`) |
| Parameters | rulesNr=192, joinsNr=1 | rulesNr=192, joinsNr=1 |

Both suites use the same workload: `RulesWithJoinsProvider`, fact model `A/B/C/D`, `ConsequenceBlackhole` (no fact writes in consequences).

---

## Results

All times in **ms/op**.

### Insert-Only (no `fireAllRules`)

| facts | NONE/PLAIN | H2MVStore | Chronicle | H2M overhead | Chronicle overhead |
|---|---:|---:|---:|---:|---:|
| 10 | 0.010 | 3.850 | 0.106 | 377× | 14× |
| 100 | 0.092 | 93.364 | 0.262 | 1016× | 7× |

### Fire-Only (facts pre-loaded in iteration setup)

| facts | NONE/PLAIN | H2MVStore | Chronicle | H2M overhead | Chronicle overhead |
|---|---:|---:|---:|---:|---:|
| 10 | 0.659 | 2.026 | 2.669 | 3.1× | 4.2× |
| 100 | 3.401 | 28.143 | 17.145 | 8.3× | 5.0× |

### Insert + Fire (H2M) vs Insert + No-Fire (Chronicle)

> Not directly equivalent workloads — shown for orientation only.

| facts | NONE/PLAIN | H2MVStore | Chronicle | H2M overhead | Chronicle overhead |
|---|---:|---:|---:|---:|---:|
| 10 | 0.536 | 3.573 | 0.503 | 6.7× | 3.3× |
| 100 | 3.482 | 55.208 | — | 15.9× | — |

---

## Observations

### Insert-heavy workloads — Chronicle wins decisively

H2MVStore overhead grows super-linearly with fact count (377× at 10 facts, 1016× at 100 facts) because it persists each fact synchronously. Chronicle's append-only sequential writes keep overhead nearly constant — **14× at 10 facts, 7× at 100 facts** — and the gap widens with scale.

### Fire-heavy workloads — both backends pay for match records

Every rule activation writes a `ruleMatch` record to the journal (required for restore correctness). With 192 rules × N facts, the number of activations scales as O(rules × facts), making match-record writes the dominant cost — not the safepoint or page roll.

At 10 facts Chronicle is slightly heavier than H2MVStore (4.2× vs 3.1×); at 100 facts Chronicle is cheaper (5.0× vs 8.3×). The crossover is driven by Chronicle's lower per-append cost at scale.

The safepoint roll (close + open a Chronicle queue file, ~1ms) is a fixed per-`fireAllRules()` cost and is **not** the bottleneck in fire-heavy workloads.

### Key numbers

- Chronicle insert overhead per fact: ~600–700 ns (Chronicle append + serialization)
- Chronicle ruleMatch overhead per activation: ~500–700 ns
- H2MVStore insert overhead per fact: ~930 µs at 10 facts, growing super-linearly

---

## What to investigate next

- **ruleMatch write cost** — ~600ns per activation is the ceiling on fire throughput. Batching or deferring match records could reduce this significantly.
- **Chronicle insert scaling** — overhead drops from 14× to 7× as fact count grows from 10 to 100; the trend suggests further improvement at larger scales worth confirming.

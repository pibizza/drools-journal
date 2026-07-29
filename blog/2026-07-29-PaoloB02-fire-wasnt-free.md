---
layout: post
title: "The Fire Wasn't Free"
date: 2026-07-29
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
---

The session started with housekeeping. Claude's morning brief flagged nine
deleted files in the project repo — the old synthetic benchmark classes
(`AbstractJournalBenchmark`, `CompactionBenchmark`, `DrlProvider`, and six more)
that a refactoring commit had replaced but not removed from the index.
They were physically deleted but not staged. Two commands: `git rm` on the lot,
a cleanup commit, done.

Then I turned to the upstream reliability benchmarks.

## A different persistence model, same questions

The `incubator-kie-benchmarks` reliability package tests `drools-reliability` —
Infinispan and H2MVStore backends. Different SPI from ours, but the workload
structure is the same: unprotected vs. persisted, insert-only, fire-only,
insert+fire. The repo was already cloned and compiled. I asked Claude to run
`FireOnlyBenchmark`, `InsertOnlyBenchmark`, and `InsertAndFireBenchmark` with
`mode=NONE,H2MVSTORE` at full iteration counts — 2000–3000 warmup, 1000
measurement, SingleShot mode.

That run finished in minutes. Ours would have taken much longer. Our
`AbstractSessionBenchmark` was using `Mode.AverageTime` — each iteration runs
for about a second, not one invocation. We changed it: `Mode.SingleShot`, output
in milliseconds, warmup and measurement annotations updated to match the
reliability package. A rebuild, a rerun, and the numbers came back fast.

## The fire-only overhead that didn't add up

At 1000 SingleShot measurements the error margins tightened. Most numbers were
clean. `FireOnlyWithJoinsBenchmark` at 100 facts showed 17ms in JOURNAL mode
versus 3.4ms in PLAIN — 5× overhead. Upstream H2MVStore was 8.3× at the same
fact count.

The safepoint fix from last session guards `roll()` with
`if (currentRecordCount == 0) return`. Facts are pre-loaded in iteration setup,
so `currentRecordCount > 0` when `fireAllRules()` fires. The safepoint still
rolls. But one `roll()` call costs ~1ms. Not 14ms.

We started in `JournalledKieSession.fireAllRules()`:

```java
@Override
public int fireAllRules() {
    int fired = (replayFilter != null)
            ? super.fireAllRules(replayFilter)
            : super.fireAllRules();
    journal.safepoint();
    return fired;
}
```

One safepoint call, after everything fires. Not per rule, not per match.
Clean. Then Claude read `JournallingRuntimeEventListener`:

```java
@Override
public void afterMatchFired(final AfterMatchFiredEvent event) {
    List<? extends FactHandle> handles = event.getMatch().getFactHandles();
    long[] ids = handles.stream()
            .mapToLong(h -> ((InternalFactHandle) h).getId())
            .toArray();
    journal.ruleMatch(currentActivationId,
            event.getMatch().getRule().getPackageName(),
            event.getMatch().getRule().getName(), ids);
}
```

Every rule activation writes a `ruleMatch` record. 192 rules, 100 B facts:
19,200 journal writes per `fireAllRules()` call. The overhead isn't the
safepoint. It's the match records.

## What ruleMatch actually costs

We confirmed in `ChronicleJournalStorage`: `ruleMatch` appends to the active
page but does not increment `currentRecordCount`. So 19,200 match records land
in the journal without touching the roll condition. The roll fires once, from
the inserts. The match writes are pure append overhead.

At 10 facts (1920 activations), fire overhead is ~2ms. At 100 facts
(19,200 activations), ~14ms. Linear scaling with activation count. Rough
per-write cost: ~700ns — one Chronicle method-writer call, serialise a `long[]`
and a rule name, acquire the write lock, append.

## The comparison

Both suites at full counts:

| Workload        | NONE/PLAIN | H2MVStore overhead | Chronicle overhead |
|-----------------|:----------:|:------------------:|:-----------------:|
| Insert-only 10  | 0.010 ms   | 377×               | 14×               |
| Insert-only 100 | 0.092 ms   | 1016×              | 7×                |
| Fire-only 10    | 0.659 ms   | 3.1×               | 4.2×              |
| Fire-only 100   | 3.401 ms   | 8.3×               | 5.0×              |

Chronicle is 26–145× faster than H2MVStore on pure inserts. On fire-heavy
workloads both pay for match records — different formats, similar bottleneck.
Chronicle's per-write cost is slightly higher at small scales, slightly lower
at large. The overhead picture is clear: for write-heavy sessions Chronicle
wins decisively; for fire-heavy sessions the match-record cost is the number
to watch if throughput matters.

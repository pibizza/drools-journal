---
layout: post
title: "The Restore Completes"
date: 2026-05-26
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [restore, tms, tdd, refactor]
---

The last session ended at Phase 1 — surviving facts in a map, safepoint
buffering working. This session, we closed issue #11 entirely.

## One Thing at a Time

We started where TDD wants you to start: with the simplest possible failing
test. Not the full restore — just "open from a storage with one surviving
fact, assert it's in working memory." We built the storage manually with
`InsertRecord` plus `SafepointRecord`, called `JournalledSessionFactory.open()`,
checked `session.getObjects()`.

It failed. `open()` always returned a fresh session. Good. That's the right
failure.

Making it green was straightforward: scan the journal, insert surviving facts.
Twelve lines in `JournalledSessionFactory` and the test passed.

## The Method That Did Too Much

The first version of Phase 1.5 put everything into a `RestoreEngine.restore()`
method. It scanned, inserted facts, built the `oldToNew` ID translation map,
constructed `ReplayFilter`, returned it. I stopped and looked at it.

`RestoreEngine` is a scanner. It should scan. Building the filter and inserting
facts into a session is the factory's job. The return type being `ReplayFilter`
was the tell — a scanner returning a filter it happened to build along the way
is not a scanner anymore.

We refactored. `RestoreEngine.scan()` stays exactly what it was: returns a
`ScanResult` with the data. `JournalledSessionFactory` orchestrates: scan,
insert non-logical facts, translate IDs, construct filter, fire, wire TMS, set
filter, install listener. That sequence lived in one large method until I pushed
for extraction — `restore()`, `insertNonLogicalFacts()`, `buildReplayFilter()`,
`wireTms()`. `open()` is now six lines.

## The ID Translation That Didn't Need to Be Complex

When facts re-insert into a fresh session they get new handle IDs. The
`RuleMatchRecord`s in the journal have old IDs. `ReplayFilter.accept()` checks
live matches — which have new IDs — against the fired set.

The initial instinct was to pass a `newToOld` reverse map into `ReplayFilter`
and translate during `accept()`. But the factory already has `oldToNew` at the
point it constructs the filter. Translate the records *before* constructing the
filter. `ReplayFilter` receives records with new IDs and never needs to know
about old ones. It stays exactly as it was.

## The NPE That Pointed the Way

Phase 2: TMS wiring. We built the journal with a logical insert, restored the
session, deleted the supporting fact, fired rules, asserted working memory
empty. It threw.

```
NullPointerException: Cannot invoke
"TruthMaintenanceSystemEqualityKey.getBeliefSet()" because
"InternalFactHandle.getEqualityKey()" is null
```

We had called `session.insert("hello")` for the logical fact during restore.
That creates a plain handle — no `EqualityKey`, no belief set. Then
`readLogicalDependency()` expected all that to already exist.

Claude read the Drools protobuf marshaller, which sets up the equality key
manually before calling `readLogicalDependency()`. Then it found
`TruthMaintenanceSystem.insertPositive(object, internalMatch)`. That one
call handles everything: enables TMS on the type config, creates the handle,
sets the JUSTIFIED equality key, registers with TMS, propagates into Rete. The
protobuf marshaller's twelve-step setup collapses to one.

We needed the `InternalMatch` for the justifying activation. That came from
`ReplayFilter.matchCache` — a `Map<MatchKey, Match>` populated in `accept()`
when the filter suppresses an already-fired match. Call `fireAllRules(filter)`
internally during restore, drain the agenda, cache the suppressed matches, then
call `insertPositive()` for each logical fact using the cached match.

The test went green.

## Closing the Last Criterion

Issue #11 had five acceptance criteria. Four were met after Phase 2. The fifth:
unresolved `lambdaClassRef` throws `JournalSchemaEvolutionException`.

One test, one `else if` in `flush()`:

```java
} else if (record instanceof ModifyRecord modify) {
    if (lambdaRegistry.lookup(modify.lambdaClassRef()) == null) {
        throw new JournalSchemaEvolutionException(modify.lambdaClassRef());
    }
}
```

The actual lambda application (mutating the surviving fact with deserialized
parameters) waits for the compiler rewrite in Task 15. The exception path
works now. Criterion met. Issue closed.

## The `final` That Wasn't Earning Its Keep

We'd been writing `final` on every local variable throughout the codebase.
I questioned it. For fields — yes, `final` communicates something real. For
parameters — reasonable. For local variables inside a ten-line method — it's
noise. Modern IDEs catch reassignment. The JIT doesn't use the hint. The scope
is too small for the signal to matter.

We stripped it from local variables across the entire codebase in one commit.
Forty-four tests stayed green. The code is easier to read.

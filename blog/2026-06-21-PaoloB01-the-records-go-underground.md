---
layout: post
title: "The Records Go Underground"
date: 2026-06-21
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [refactoring, api-design, architecture]
---

## The Records Go Underground

Issue #19 had been on the list since the compaction coordinator work. Adding
`safepoint()` at the time was the minimal fix; the bigger question — should
the write API be semantic at all? — got parked to avoid scope creep. Today
I picked it up.

The complaint was `append(JournalRecord)`. Every caller —
`JournallingRuntimeEventListener`, `CompactionCoordinator` — had to construct
record objects: `new InsertRecord(...)`, `new CompactionPrepareRecord(...)`.
The callers knew the record types. They knew the internal serialisation model.
That's the wrong coupling direction.

I brought Claude in, and we discussed what the write side should look like
instead. The answer was straightforward: typed methods that say what happened,
not what record to construct.

```java
// before — caller constructs the record
journal.append(new InsertRecord(handle.getId(), logical, currentActivationId,
        strategy.store(event.getObject(), handle)));

// after — caller says what happened
journal.insert(handle.getId(), payload);
journal.insertLogical(handle.getId(), payload, currentActivationId);
```

I noticed early that `safepoint()` already fit this model — it had always been
a semantic method, never a record constructor. That confirmed the direction.

## The split that removed a sentinel

The original proposal had a single `insert(long, Payload, boolean, long)`. I
recognise that boolean-plus-sentinel pattern immediately: `false, -1L` as
arguments at every non-logical call site is noise. It encodes two distinct
cases where neither slot means anything without the other.

I asked whether `insert` and `insertLogical` should be separate methods. The
answer was obvious once the question was asked. The listener's conditional
became explicit:

```java
if (currentActivationId >= 0) {
    journal.insertLogical(handle.getId(), payload, currentActivationId);
} else {
    journal.insert(handle.getId(), payload);
}
```

The branch is now on the concept, not a boolean parameter. `objectUpdated`
simply calls `insert()` — no flags, no sentinels.

## Arrays over varargs at the SPI boundary

Consistency surfaced another question: `ruleMatch` took `long[]`, but
`compactionPrepare` and `compactionCommit` had landed with `String...` varargs.
No principled reason — the varargs had started as a way to avoid a method
signature conflict with old package-private test helpers, and outlived its
justification once those helpers were cleaned up.

Varargs is a call-site convenience for literal arguments. Every real caller of
these SPI methods already has an array in hand, built from a stream or a
`Set.toArray()`. I pushed for `String[]` throughout, and we made the change.

## The RED step that wouldn't compile

The TDD cycle here had an unusual shape. The failing tests didn't fail at
runtime — they failed to compile. Adding calls to `storage.insert(42L, payload)`
through a `JournalStorage`-typed variable produced immediate compilation errors
because the methods didn't exist on the interface yet.

We added six contract tests to `JournalStorageContractTest`, one per semantic
write method, each verifying the method produces the right record type on scan.
The existing position and scan tests had used an abstract `appendTestRecord`
method that every implementation had to override. Once the SPI was the only
write mechanism, I noticed `appendTestRecord` was pointless: every override
would just call `retract()`. We inlined it.

86 tests after. 80 before.

## What the callers no longer know

`JournallingRuntimeEventListener` doesn't import a single record type.
`CompactionCoordinator` dropped `CompactionPrepareRecord` and
`CompactionCommitRecord`. The record hierarchy is now entirely a read-side
concern — the deserialisation model for `scan()` and `RestoreEngine`.

Chronicle and Aeron backends, when they arrive, implement the semantic SPI
methods using whatever storage format they choose. No caller knows or cares
what's underneath.

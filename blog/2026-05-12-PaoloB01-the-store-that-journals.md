---
layout: post
title: "The Store That Journals"
date: 2026-05-12
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [tdd, design, drools-internals]
---

Before writing any code today I wanted to understand how `ReliableNamedEntryPoint`
actually works. It's the pattern the plan names as the template for
`JournalledNamedEntryPoint`, and I'd never read it closely.

It's 49 lines. It overrides exactly one method: `createObjectStore()`. That's it.
All the insert, retract, and update behaviour happens in the custom `ObjectStore`
subclass it returns — `FullReliableObjectStore` or `SimpleReliableObjectStore`
depending on configuration. The entry point itself stays thin.

That raised an immediate question. `NamedEntryPoint.update()` only calls
`objectStore.updateHandle()` when the object reference changes or equality
behaviour is enabled. DRL `modify` blocks mutate in-place — same reference.
So how does `FullReliableObjectStore` ever see the mutation?

It doesn't, at runtime. It relies on `safepoint()`. At safepoint time, the
reliable store re-serialises the current state of every stored object —
capturing whatever mutations happened since the last safepoint. The guarantee
is "restore to the last safepoint", not "restore every individual operation."

That's a fundamentally different model from what we're building. drools-journal
is an append-only operation log — every mutation needs a record at the time it
happens. The safepoint workaround doesn't apply. For updates, we need
`NamedEntryPoint.beforeUpdate()`, which fires unconditionally on every call
regardless of whether the reference changed. The `ObjectStore` approach covers
insert and retract. For update, we need the hook.

So the structure became: `JournalledObjectStore` extending `IdentityObjectStore`
(following `FullReliableObjectStore`), intercepting `addHandle()` and
`removeHandle()`. `JournalledNamedEntryPoint` wires it in via `createObjectStore()`
and overrides `beforeUpdate()` for the update path. Thin entry point, logic in the store.

Before writing any of that, we had to fix a circular dependency. `InMemoryJournalStorage`
was living in `drools-journal-tests/src/main/java`, and I'd added `drools-journal-tests`
as a test dependency of `drools-journal-core`. That made Maven refuse to build — core
depends on tests, tests depend on core. The fix was to move `InMemoryJournalStorage`
and the SPI contract tests into `drools-journal-core/src/test/java`. The tests module
becomes what it should have been from the start: a place for integration tests, not
shared test utilities.

While doing that, `JournalStorage.append(ByteBuffer)` was staring at me. The scanner
already returns typed `JournalRecord` objects. The storage accepting raw bytes was
asymmetric and put serialisation responsibility in the wrong place — callers
shouldn't need to know about wire formats. We replaced it with `append(JournalRecord)`.
Each backend implementation owns its own serialisation. The SPI boundary now speaks
the same type on both sides.

Then the TDD loop, deliberately slow.

I asked Claude to make one test compile and fail. It came back with a full
`JournalledObjectStore` implementation including `EXTERNAL_REF` handling,
`CurrentActivation` thread-local, and serialisation logic — for a test that
only checked whether one `InsertRecord` had been appended. I pulled it back.
The minimum to compile is just the class and the constructor.

From there: write one test, see it fail, add only the code that makes it pass.
The insert test needed `addHandle()` with serialisation. The retract test needed
`removeHandle()` with a `RetractRecord`. When the `EXTERNAL_REF` test landed,
Claude added a full `buildPayload()` helper with a switch expression covering both
cases. Again I pulled it back — the test didn't need `EXTERNAL_REF` code yet,
just an `if`.

The thread-local question surfaced when thinking about logical inserts. I'd
sketched `CurrentActivation` as a static class holding a `ThreadLocal<Long>`.
I stopped and asked who actually sets it. `JournalledAgenda`. And `JournalledAgenda`
has a direct reference to the session, through which it can reach
`JournalledObjectStore`. No call stack to thread through. No thread-local needed.
A plain `long` field, default `-1`, set and cleared by the agenda around each firing.
The assumption it relies on: Drools sessions are single-threaded. That went into
IDEAS.md as a design note — the assumption is load-bearing and needs to be visible.

The `dbKey` for `EXTERNAL_REF` is still a placeholder. The test doesn't assert on
it, because `JournalledObjectStore` can't compute the right key — it's Chronicle
index, Aeron position, or whatever the backend decides. That design gap is in IDEAS.md
too: `ObjectStorageStrategy` probably needs to return a `Payload` directly rather
than a `StorageDecision`, so the strategy owns both the decision and the execution.
Not decided yet.

Three tests green: insert EMBED, insert EXTERNAL_REF (type only), retract.
`JournalledNamedEntryPoint` is next.

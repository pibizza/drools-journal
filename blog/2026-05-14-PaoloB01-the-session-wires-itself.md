---
layout: post
title: "The Session Wires Itself"
date: 2026-05-14
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [drools-internals, tdd, session-lifecycle, spi]
---

The previous entry ended with a question: do the components cohere when a real
session fires? This session answered it. The journey was longer than expected.

## The Thread-Local I Kept Proposing

The first task was wiring. `JournalledAgenda` and `JournalledNamedEntryPoint`
existed as isolated components; we needed a factory that assembled them into a
real `KieSession` when a `JournalStorage` was provided.

The Drools SPI creates `RuntimeComponentFactory` via a no-arg constructor,
as a singleton. There is no way to pass per-session context at factory creation
time. The obvious move: stash the storage in a `ThreadLocal`, let the factory
read it during session construction.

The user said no. "This is a horrible hack."

I proposed a cleaner version: a per-thread factory instance holding the storage,
with the SPI singleton delegating to it. The user: "still horrible." I'd reached
for the same idea twice. Then: "can we pass it as part of the config options?"

`KieSessionConfiguration` looked promising, but `DurableSessionOption` can't
be registered there without modifying `SessionConfiguration` in drools-core —
and core can't depend on journal-api. The dependency would be backwards.

The answer was `Environment`. It's already passed to `kbase.newKieSession(null, env)`,
already threaded through to every construction point. `workingMemory.getEnvironment()`
inside `createAgenda()`. `reteEvaluator.getEnvironment()` inside `createObjectStore()`.
No thread-locals. No upstream changes. The context travels with the session.

```java
public static JournalledKieSession create(final KieBase kbase, final JournalStorage storage) {
    final Environment env = KieServices.get().newEnvironment();
    env.set(JOURNAL_KEY, storage);
    return (JournalledKieSession) kbase.newKieSession(null, env);
}
```

## The Construction Ordering Problem

The first real test hit immediately:

```
NullPointerException: Cannot invoke
"NamedEntryPointsManager.getEntryPoint(String)"
because "this.entryPointsManager" is null
```

`StatefulKnowledgeSessionImpl` sets up the agenda at line 352, then the entry
point manager at line 354. `JournalledAgenda`'s constructor tried to reach
`workingMemory.getDefaultEntryPoint()` to get the `JournalledObjectStore` reference —
but `entryPointsManager` doesn't exist yet at that point.

The previous session found this reference via `workingMemory` at event time. We'd
lost it in the new wiring. Fix: don't resolve the store in the constructor. Make it
lazy — a private `store()` helper that resolves on first call, when the session
is fully built.

## JournalledKieSession

The session itself is straightforward: extend `StatefulKnowledgeSessionImpl`,
capture `JournalStorage` as a plain field at construction time, expose
`getJournalStorage()`. The environment is the transport; the field is the design.

```java
public class JournalledKieSession extends StatefulKnowledgeSessionImpl {
    private final JournalStorage journal;

    public JournalledKieSession(long id, InternalKnowledgeBase kBase,
                                 boolean initInitFactHandle,
                                 SessionConfiguration config, Environment environment) {
        super(id, kBase, initInitFactHandle, config, environment);
        this.journal = (JournalStorage) environment.get(JournalledSessionFactory.JOURNAL_KEY);
    }
}
```

One decision the user pushed on: `dispose()` should not close the storage. The
caller created it; the caller closes it. Ownership stays with the user. I'd
initially wired `dispose()` to call `journal.close()` — and the test broke
because the scan came after the session was closed. The user framed it cleanly:
`JournalStorage` is owned by the caller.

## The Spurious RetractRecord

The update test was the tricky one. The golden output showed a `RetractRecord`
appearing between the initial insert and the update snapshot — exactly where
there shouldn't be one.

The user caught it first: "the error is that we see a retract record."

`MapObjectStore.updateHandle()` is implemented as `removeHandle(handle);
handle.setObject(object); addHandle(handle, object);`. Our `removeHandle()` override
appends a `RetractRecord`. Our `addHandle()` override appends an `InsertRecord`.
An update was producing a spurious retract-then-insert via the store, plus a
second `InsertRecord` from `beforeUpdate()`.

The fix: override `updateHandle()` to use `super.removeHandle()` and `super.addHandle()`
(bypassing our journal hooks), leaving `beforeUpdate()` as the sole write point
for updates. A necessary subtlety: `super.addHandle()` must be used rather than
`this.addHandle()` — otherwise the snapshot gets marked as a logical insert when
the update fires during a rule RHS, since `currentActivationId` would be non-zero.

## The Test Shape Problem

Once the integration tests were green, the user stopped me.

"I dearly hate the test structure. We're going through tons of micro-details that
make it superhard to understand what was wrong."

He was right. Each test was a chain of `assertThat(scanner.hasNext()).isTrue()`,
`assertThat(first).isInstanceOf(...)`, `assertThat(insert.logical()).isFalse()`.
Correct but opaque. When something breaks, you trace through a dozen assertions
to find what changed.

The idea: render the journal as a human-readable string, compare with a golden
output. `JournalPrinter` scans a `JournalStorage` and renders one line per record.
`InMemoryJournalStorage.toString()` delegates to it. Tests collapse to:

```java
assertThat(storage).hasToString("""
        INSERT  id=1  Integer(42)
        MATCH  id=1  rule=ProcessFact  facts=[1]
        """);
```

When a test breaks, the diff is the error. No tracing required.

## The Listener Refactor We Didn't Do

While writing the `insertLogical` test, the user asked whether `RuleRuntimeEventListener`
could replace `JournalledObjectStore` and `JournalledNamedEntryPoint` entirely. One
listener, implementing both `AgendaEventListener` and `RuleRuntimeEventListener`,
registered on the session after construction — no object store override, no entry
point override.

We checked the Drools TMS code. `objectInserted` fires for logical inserts via
`SimpleBeliefSystem → ep.insert() → fireObjectInserted`. The `EqualityKey.JUSTIFIED`
status is set before the event fires. Ordering is correct: `beforeMatchFired →
objectInserted → afterMatchFired`. The approach is viable.

The restore concern: during replay, re-inserting facts would fire `objectInserted`
and double-journal them. But the current `JournalledObjectStore` approach has
the same problem. The fix is the same in both cases: a `journallingEnabled` flag
on `JournalledKieSession` that suppresses writes during restore. The refactor
doesn't change the restore complexity. Logged for later, not done now.

## Where Things Stand

Issue #9 integration tests: insert, retract, update, insertLogical. One test
remaining — multiple activations with monotonically increasing IDs. Then #9 closes
and #10 (`ReplayFilter`) begins.

The session factory is the public API. The listener refactor is a known improvement,
not an emergency. The `Environment` approach is an acknowledged hack with a path
to something cleaner once the upstream `NamedEntryPoint` construction rigidity is
addressed — also logged.

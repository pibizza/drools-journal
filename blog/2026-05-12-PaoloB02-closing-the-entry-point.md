---
layout: post
title: "Closing the Entry Point"
date: 2026-05-12
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [tdd, drools-internals, refactoring]
---

The previous entry ended with `JournalledNamedEntryPoint` still unwritten. This
one closes it.

Before touching production code, I wanted to clean up the tests. We'd been using
Mockito to stub `InternalFactHandle` — mocking `getId()`, `getObject()`, the usual
noise. I went looking for a real alternative and found `DefaultFactHandle(long id,
Object object)`. Right there in the Drools source, the constructor carries this
comment: *"this is only used by tests, left as legacy as so many test rely on it."*

That's as explicit an invitation as you get. We dropped Mockito from the pom entirely
and replaced every mock with `new DefaultFactHandle(42L, "hello")`. The tests got
shorter and more honest — they now exercise the real handle, not a stub.

Then the TDD loop.

First RED: a test that expected `JournalledNamedEntryPoint` to exist. It didn't.
The compiler said `cannot find symbol: class JournalledNamedEntryPoint`. Correct
failure. We wrote the class with a `beforeUpdate()` override that appends an
`InsertRecord`, ran the test, it went green.

At that point I moved to the next test. The user stopped me: *"we are green, so
next is refactor."* Right. I'd been looking at the `buildPayload()` method sitting
identically in both `JournalledObjectStore` and `JournalledNamedEntryPoint`, and
I'd been planning to extract it — just not immediately. The discipline named the
moment. We pulled the logic into `JournalPayloadBuilder`, a package-private final
class with a single static method.

The refactoring paid off faster than expected. When I wrote the EXTERNAL_REF test
for `JournalledNamedEntryPoint`, it passed immediately. No new code needed —
`JournalPayloadBuilder` already covered that path. The test is still useful as a
regression guard, but it wasn't RED. I flagged it; we kept the test and moved on.

`createObjectStore()` ran into a wall. The method is `protected` on `NamedEntryPoint`,
which lives in `org.drools.kiesession.entrypoints`. Our test class is in
`org.drools.journal.core`. Protected in Java means same-package or subclass — it
doesn't mean same-package of the subclass. We couldn't call it from the test.
The override is one line: `return new JournalledObjectStore(journal, strategy)`. We
implemented it without a dedicated unit test and noted that integration coverage
will pick it up when the full session is wired.

Logical inserts were the last piece. The `InsertRecord` carries a `logical` flag
and a `justifyingRuleMatchId`. Until now both were hardcoded: `false` and `-1`.
The design (already in IDEAS.md) calls for a plain `long currentActivationId = -1L`
field on `JournalledObjectStore`. When positive, an insert is logical and carries
that ID. `JournalledAgenda` will set and clear it around each rule firing — no
thread-local, relying on the single-threaded session model.

We TDD'd it straight: one RED test setting `currentActivationId` to `99L` before
calling `addHandle()`, asserting `logical=true` and `justifyingRuleMatchId=99`. We
added `setCurrentActivationId()` and `clearCurrentActivationId()`, wired the flag
into `addHandle()`. Green.

Issue #8 closed. `JournalledAgenda` is next.

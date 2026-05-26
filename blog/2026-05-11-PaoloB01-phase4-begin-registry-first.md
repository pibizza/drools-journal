---
layout: post
title: "Phase 4 begins: registry first, one test at a time"
date: 2026-05-11
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [tdd, drools-journal-core, epic, issue-workflow]
---

Phase 3 closed last session. This one was about getting Phase 4
ready to actually build — and then building the first piece.

The setup takes longer than you'd expect. Before writing a line of
`drools-journal-core`, I needed an epic branch in both repos, a
GitHub issue for the epic, a `design/JOURNAL.md` in the workspace,
six child issues with proper acceptance criteria, and an active issue
declared for the session. That's not overhead — that's the workflow.
I brought Claude in and we worked through `epic-start` and
`issue-workflow` in sequence.

The epic is `epic-journal-aware-session`. I pushed back once on
Claude's first suggestion (`epic-journaled-session-implementation` —
accurate but verbose). We settled on something that names what the
code does rather than what the phase is called.

The six child issues cover Tasks 9–14: `ModifyLambdaRegistry`,
`JournalledNamedEntryPoint`, `JournalledAgenda`, `ReplayFilter`,
`RestoreEngine`, and `CompactionCoordinator`. Each has a context
section, acceptance criteria, and notes — including pitfall pointers
like "study `ReliableNamedEntryPoint` in drools-reliability-core
before Task 10." Writing those notes forces you to think through
dependencies before you start.

Task 9 was first. `ModifyLambda`, `ModifyLambdaRegistry`,
`JournalSchemaEvolutionException`. A session-scoped registry that
maps string lambda class references to their lambda implementations,
throwing on a missing lookup.

I held Claude to strict TDD. One test. Watch it fail. Write minimal
code to pass. Then the next test.

The first test was the simplest: register a lambda, look it up, get
the same instance back.

```java
@Test
void lookupReturnsRegisteredLambda() {
    final ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
    final ModifyLambda lambda = (fact, params) -> {};

    registry.register("Rule_MyRule_modify_0", lambda);

    assertThat(registry.lookup("Rule_MyRule_modify_0")).isSameAs(lambda);
}
```

Red: `cannot find symbol`. Green: `ModifyLambda` as a functional
interface, `ModifyLambdaRegistry` backed by a `HashMap`, `lookup`
returning `lambdas.get(ref)`. That's all.

The second test introduced the exception path — an unknown ref should
throw `JournalSchemaEvolutionException` with the ref in the message.
That needed new code: the exception class and a null check in `lookup`.
Red, then green.

Two more tests followed — overwrite semantics and multiple independent
lookups. Both passed without any code changes; `HashMap` handles them
for free. The question was whether to write them at all. I asked Claude
and got a useful answer: those tests document expected behavior even
when no new code is needed. For a registry that RestoreEngine will
depend on, that's worth having on record.

Four tests, all green. Then Claude had written the wrong license
header on every file. The project uses
`Copyright (c) 2026 Drools Journal Authors`. Claude had used the
Apache Software Foundation boilerplate — the kind that comes out of
Maven archetypes by default. I caught it. We fixed all four files,
confirmed the tests still passed, and committed.

`drools-journal-core` had no source directories before this session.
It does now.

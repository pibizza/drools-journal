---
layout: post
title: "The Dead Pages and the API Question"
date: 2026-07-14
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, reliability, api-design, tdd]
---

## The Dead Pages and the API Question

Two threads today. One was a bug that had been sitting in IDEAS.md since last
session — `scanLiveness` blindly scanning retired pages. The other was a
larger question I'd been circling: how should drools-journal's API look if
it's going to replace drools-reliability?

## What drools-reliability actually does

I wanted to understand the surface before proposing changes. I had Claude dig
through the reliability module's internals. The findings were clarifying.

drools-reliability's API is just `kbase.newKieSession(conf, null)`. The user
passes a normal KieBase and a `KieSessionConfiguration` with
`PersistedSessionOption`. No special factory. The `RuntimeComponentFactory`
SPI does the interception — same mechanism drools-journal already uses.

The interesting part: reliability handles `modify` as a full-object snapshot.
When a fact is updated, the entire object is re-serialized into storage. No
delta, no lambda capture, no DRL rewriting. On restore, it re-inserts
everything from scratch.

drools-journal already does the same thing — `objectUpdated` in the event
listener writes a full `InsertRecord`. The `ModifyRecord` with lambda replay
was an aspiration, not a requirement.

## The KieBase question

I'd been thinking about DRL rewriting as the path to delta-based modify
capture. But the investigation killed that approach for in-pipeline hooks:
the Drools compilation pipeline has no plugin mechanism. Compilation phases
are hardcoded in `KnowledgeBuilderImpl`. And `KieBase` doesn't retain DRL
source — it's consumed during compilation and discarded.

But that led to a simpler idea: a standalone `JournalDrlPrecompiler` that
transforms DRL text before it reaches `KieHelper.build()`. Opt-in, no
upstream changes, the journal still works without it. If the user skips the
precompiler, snapshots handle modify. If they use it, deltas.

We updated #17 to reflect this — it's no longer blocked on upstream.

## The API gap

Comparing the two APIs side by side, the gap is small:

drools-reliability: `kbase.newKieSession(conf, null)` with
`PersistedSessionOption` on the config.

drools-journal: `JournalledSessionFactory.open(kbase, storage)` — a custom
factory that smuggles the storage via `Environment`.

`DurableSessionOption` already exists in the API module. The refactor is:
move the initialization logic from `JournalledSessionFactory.open()` into
`JournalledRuntimeComponentFactory.createStatefulSession()`, switch
activation from environment check to config check, eliminate the custom
factory. Created #44 to track it.

## The dead pages fix

Then we shifted to the bug. `scanLiveness` computed liveness for every page
in the raw stream, including pages retired by sealed compactions. Retired
pages had 0% liveness and got perpetually flagged as sparse candidates.

The fix was straightforward TDD. First test:

```java
@Test
void scanLiveness_afterSealedCompaction_excludesRetiredPages() {
    // ... insert 4 facts, retract 3, compact, seal ...
    Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

    assertThat(liveness).doesNotContainKey("0");
    assertThat(liveness).doesNotContainKey("1");
}
```

It failed showing `{"0"=[0, 4], "1"=[0, 3], "m-..."=[1, 1]}` — the retired
pages right there in the map with zero liveness.

The fix: replay the compaction protocol (Phase 0) before computing liveness
to build the set of live pages. Same logic `RestoreEngine` already had. We
added three more tests — merged page present, two sequential compactions,
unsealed compaction leaves source pages canonical.

Then the refactor: `RestoreEngine` and `CompactionCoordinator` were both
doing the same Phase 0 replay. We extracted `PageIndex` as a shared utility.
A second refactor narrowed the signature from `JournalStorage` to
`JournalScanner` — the method only needs to iterate records, not hold a
reference to the whole storage.

PR #46 merged. #45 closed.

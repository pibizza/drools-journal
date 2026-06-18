---
layout: post
title: "Five Classes, One Listener"
date: 2026-06-18
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [refactoring, architecture, cleanup]
---

## Five Classes, One Listener

The session started with a backlog review. I wanted drools-journal's open issues on the kiegroup project board — visibility had been the complaint, and keeping everything in a local `IDEAS.md` nobody else could see wasn't cutting it. I pulled Claude in to help, and we spent the first part of the session wiring up the GitHub dashboard.

Getting the issues onto the board took more negotiation than I expected. The kiegroup board runs a daily automation that enforces field requirements, and issues from `pibizza/drools-journal` sitting in a kiegroup project are oddities — you can't assign them the same issue types as native kiegroup issues. We ended up adding all twelve open drools-journal issues as sub-issues of my in-progress epic (#6683), set to Backlog with estimates, so the Σ fields could aggregate the full remaining workload. Not perfectly clean, but the epic now has a realistic picture of what's left: 5.1 weeks across seven items.

There was a quieter problem we found along the way: `epic-compaction-coordinator` had never been merged to `main`. The issue was closed, the branch retained, the merge skipped. I merged it — a fast-forward with no drama — and established a workflow for the rest of the session: discuss the change, implement in a worktree, open a PR, review it there, then merge. Shorter cycles, more visible to collaborators.

## The first refactor: symmetric storage

Issue #22 was about `ObjectStorageStrategy`. The original design split a single responsibility across two classes: the strategy returned a `StorageDecision` enum, and `JournalPayloadBuilder` performed the actual serialization. The problem wasn't the split per se — it was that the caller had to know about both halves. Any future implementation (an external store, a custom codec) would need to implement the strategy *and* know how `JournalPayloadBuilder` worked.

We collapsed it:

```java
// before — two classes, one concern
StorageDecision decide(Object fact, FactHandle handle);  // strategy
static Payload build(Object, InternalFactHandle, ObjectStorageStrategy);  // builder

// after — one interface, symmetric
Payload store(Object fact, FactHandle handle);
Object load(Payload payload);
```

`StorageDecision` and `JournalPayloadBuilder` deleted. I reviewed the PR and caught something: `EmbedStrategy` and `ExternalRefStrategy` had landed in the `api` module. The `api` module defines the SPI — interfaces, sealed records, data types. Concrete implementations with I/O dependencies belong in `core`. Claude moved them in a follow-up commit. The import errors after the package move were a reminder that `sed`-based package renaming doesn't carry the necessary imports along for the ride.

## The second refactor: five classes become one

Issue #20 was the bigger one. The codebase had journalling spread across five infrastructure classes: `JournalledObjectStore`, `JournalledNamedEntryPoint`, `JournalledEntryPointFactory`, `JournalledAgenda`, and `JournalledAgendaFactory`. The proposal was to consolidate everything into `JournallingRuntimeEventListener`, which already handled inserts, updates, and deletes. The question was whether the other classes were doing anything real.

`JournalledObjectStore` had one override: `updateHandle()`. It called `super.removeHandle()` and `super.addHandle()` directly to prevent spurious `objectInserted`/`objectDeleted` events during `session.update()`. Claude checked the Drools `NamedEntryPoint` source. The answer was clear: `NamedEntryPoint.update()` fires exactly one event — `objectUpdated`. The object store's `updateHandle()` fires none. The override had been written to solve a problem that didn't exist with the listener approach.

With that confirmed, the whole chain fell. No store override meant no custom entry point; no custom entry point meant no entry point factory. `JournalledAgenda` was in the same position — its journalling work was done by an inner `AgendaEventListener`, not by overriding `DefaultAgenda` methods. We moved `beforeMatchFired` and `afterMatchFired` into `JournallingRuntimeEventListener` and registered it as both `RuleRuntimeEventListener` and `AgendaEventListener`. `JournalledAgenda` and `JournalledAgendaFactory` went with the rest.

Five classes, 349 lines deleted. The golden-output test confirmed `session.update()` still produces exactly one `InsertRecord`.

## Closing out the stale items

Three issues closed without code. Issue #21 (replace `ThreadLocal<JournalStorage>`) was already resolved — the Environment-based approach had been the implementation all along, written that way from the start. Issue #24 (upstream `NamedEntryPoint` construction rigidity) became irrelevant the moment we deleted `JournalledNamedEntryPoint` — no subclass, no problem. Issue #23 (test naming convention) we did properly: fourteen test methods renamed to `action_context_expectedResult` across two test classes and committed as a standalone PR.

Three PRs merged, five issues closed. The remaining open items are the three backend epics and two design questions — the work that actually requires new code.

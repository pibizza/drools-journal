---
layout: post
title: "The Proxy That Recorded Calls"
date: 2026-08-28
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [chronicle, architecture, testing]
---

## Starting from the gap

The previous session ended with a clear statement: `InMemoryJournalStorage.scan()` returns everything — live and retired pages alike — while `MultiQueueScanner` in Chronicle returns only live pages. The production contract and the test implementation disagree.

I wanted to fix that by giving InMemory the same two-tier catalog+data structure that Chronicle already has. Catalog tracks page lifecycle events — page created, compaction prepare, compaction commit. Data pages hold the actual records. The scanner reads the catalog first to determine which pages are live, then chains only those data pages.

The approach seemed clear. Then I tried to understand how Chronicle actually writes to the catalog, and the ground shifted.

## The method that wasn't a method

I brought Claude in to trace the catalog write path. I couldn't find where catalog events were being written — no explicit serialization calls, no record builders, nothing that looked like "create a catalog entry and write it."

Claude walked through the `methodWriter` mechanism. Chronicle Queue doesn't serialize objects. It serializes method *calls*. A proxy implements an interface — `ChronicleCatalogWriteOps` with methods like `pageCreated(int)`, `compactionPrepare(String, String...)`, `compactionCommit(String, String...)`. When you call `catalogWriter.pageCreated(pageId)`, the proxy captures the method name, the argument types, and the argument values, then appends the entire invocation as a binary record.

On the replay side, `MethodReader` does the reverse. It deserializes the method call and invokes the matching method on a handler object — in this case, `CatalogIndex`, which implements the same interface.

I'd been looking for the serialization logic because I assumed the catalog stored data. It stores behaviour. The "catalog records" are replayed method calls, not written-and-read objects.

## Prototyping the mirror

With the mechanism understood, I started sketching the InMemory equivalent. A `Page catalog` field on `InMemoryJournalStorage`, a `PageRecord` type implementing `JournalRecord`, catalog writes on every safepoint roll and compaction event. I extracted the `Page` inner class to its own file while I was in there. I unsealed `JournalRecord` — the `sealed` permits list would need updating every time a new catalog record type appears, and there was no practical reason to keep it sealed.

Then I tried building an `InMemoryMultiQueueScanner` to chain live pages in order. It broke at the first page boundary — `hasNext()` returned false before the crossing logic could fire. I fixed that, hit the next issue, fixed that, and then stopped. The scanner was replicating Chronicle's multi-queue chaining for a list I already had in memory. Flattening live page records into a single list gives the same result with none of the machinery.

None of this code was committed. It was prototype work — enough to confirm the shape of the changes and surface the real questions.

## The contract question

With the prototyping out of the way, the real question surfaced: what breaks when `scan()` returns only live pages?

Claude and I traced all the `scan(0)` call sites. `RestoreEngine` does a phase 0 scan to compute liveness, then a phase 1 scan filtered by the live set. `CompactionCoordinator` does its own phase 0 scan. Both are doing work that `MultiQueueScanner` already does — but `InMemoryJournalStorage.scan()` returns everything, so they can't skip it.

The catch: `CompactionCoordinator.runCycle()` needs the retired page list from phase 0 for `retirePages()`. If `scan()` only returns live pages, where do retired pages come from? The catalog knows — it tracks compaction commits, which name the replaced pages. But surfacing that information requires the catalog to be more than write-only.

I wrote a full impact assessment: which tests break, which methods simplify, which code can be deleted. 18 tests in `CompactionCoordinatorTest` mostly unaffected. 4 tests in `PageIndexTest` need rewriting or removal. 11 tests in `RestoreEngineTest` survive, though the crash-recovery ones depend on safepoint sealing semantics. `JournalPrinter` loses visibility into compaction records — a diagnostic trade-off.

The assessment went into issue #56. The prototype code went into the bin. The next session starts clean — same branch, empty working tree, the issue as the guide.

---
layout: post
title: "The Scan That Saw Too Much"
date: 2026-09-04
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, concurrency, scanner, catalog]
---

## Two implementations, one lie

I'd been carrying a quiet assumption: `scan()` means the same thing in both implementations. It doesn't. Chronicle's `MultiQueueScanner` reads the catalog to find live pages, opens only those page queues, and never sees compaction records in the data stream. InMemory's scanner copies everything into a flat list — all pages, all records, retired or not — and hands the mess to callers.

The core engine was compensating. `RestoreEngine` ran a phase 0 pre-scan to compute liveness. `CompactionCoordinator` did the same. `ScanCursor` had no-op branches for `CompactionPrepareRecord` and `CompactionCommitRecord` — records that only appear because InMemory puts them in the data stream. Chronicle never emits them there. The entire filtering apparatus existed to fix one implementation's leaky abstraction.

## The concurrency question I couldn't dodge

I wanted to promote Chronicle's model — catalog-driven `scan()` returning only live pages — to the `JournalStorage` contract. Clean, simple. Then I thought about concurrent compaction.

If compaction mutates the catalog while a scan is in progress, the scanner could see a half-committed state. A `compactionCommit` lands mid-scan. A merged page exists but hasn't been committed yet. The catalog changes under the scanner's feet.

The current code survives this by accident. InMemory snapshots the flat record list at scan creation time — immutable copy, safe from concurrent mutation. But it's not safe by design. There's no protocol.

I started asking: what does a scanner actually represent? A snapshot at time T? A live view? The current code has no answer. `JournalScanner` is just a forward cursor that reads until it runs out. "Runs out" is nondeterministic in a concurrent system.

## Simplify first, then complicate

I chose to strip the problem down. No concurrent compactions — one compaction thread at a time. No page retirement for now. Just get the basic protocol right between the session thread (writing records) and the compaction thread (scanning them).

We discussed two approaches for snapshot consistency. The first: read the catalog first to determine live pages, then read data pages. Even if the session thread writes records between the two reads, the inconsistency is bounded — you might see a few extra trailing records, but never phantom pages or missing pages.

The second: a global `AtomicLong` counter stamping every record — catalog and data alike. At scan time, read the counter, then only process records with a stamp up to that value. Exact snapshot across both structures. More precise, at the cost of every record carrying a sequence number.

I haven't decided yet. Both work for the simplified case. The counter approach is correct by construction; the catalog-first approach relies on an argument about trailing records being harmless. I'll come back to this.

## The catalog gets real

I started with the structural work: giving InMemory a catalog that mirrors Chronicle's. A `Page` named `catalog` accumulates three kinds of records: `PageRecord` (page sealed), `CompactionPrepareRecord`, and `CompactionCommitRecord`. Data pages get only data records. The separation is clean.

I unsealed `JournalRecord` — it was a sealed interface with a fixed permits list. `PageRecord` needed to implement it, and adding a new permitted subtype to a sealed hierarchy felt like the wrong direction when the hierarchy is actively evolving.

Then I built `InMemoryMultiQueueScanner` — a scanner that reads the catalog first to determine live pages, then iterates those pages in order. Same structure as Chronicle's `MultiQueueScanner` but over in-memory lists. Getting the `next()`/`hasNext()` mechanics right took a few iterations — the classic off-by-one territory where the record index needs to advance before the page boundary check, not after.

## Safepoints are the only page boundary

The existing code supported two kinds of page rolls: safepoint rolls (the page is committed, sealed) and size-triggered rolls (the page is full, start a new one, nothing committed). Size-triggered rolls came from Chronicle's physical storage concerns — file sizes matter on disk. But at the logical level they introduce a concept that complicates everything: a page that exists but isn't committed.

I removed support for size-triggered rolling. Safepoints are the only page boundary. A `PageRecord` in the catalog means the page is sealed. If it's not in the catalog, the scanner doesn't see it. One mechanism, one meaning.

This killed several tests that used `rollPage()` and simplified the page-boundary handling in both `CompactionCoordinator` and `RestoreEngine`.

## Commit takes effect immediately

The old model required a safepoint to "seal" a compaction commit. `PageIndexCursor` buffered commits and only applied them when it saw a `SafepointRecord`. This was an artifact of mixing compaction records into the data stream — the cursor needed safepoint boundaries to know when a compaction was truly committed.

With the catalog-driven model, a `CompactionCommitRecord` in the catalog takes effect immediately. This matches Chronicle's `CatalogIndex.compactionCommit()` which also applies instantly. The test `commitWithoutSafepoint_originalPageRemainsCanonical` flipped to `commitWithoutSafepoint_newPageBecomesCanonical` — once the commit is written, the merged page is canonical.

## What's left

`RestoreEngine` is now clean — no phase 0, no live-page filtering, no compaction-record handling. `CompactionCoordinator.scanLiveness()` dropped its pre-filtering too. `ScanCursor` still has the old branches but they're effectively dead code.

Two Chronicle integration tests need rechecking — the safepoint-sealing semantics changed, and I need to verify that Chronicle's `pageCreated` (creation-time registration) versus InMemory's `PageRecord` (sealing-time registration) don't create a behavioral divergence. That's the next session's first task.

The bigger open question: does the snapshot protocol (catalog-first vs atomic counter) matter for the simplified single-compaction case, or only when we reintroduce concurrency? I think it only matters for concurrency — but I want to verify that assumption before moving on.

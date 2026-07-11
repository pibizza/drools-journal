---
layout: post
title: "The Queue That Couldn't Forget"
date: 2026-07-11
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [chronicle, aeron, compaction, architecture]
---

## The Queue That Couldn't Forget

With #33 and #39 merged, the next issue on the board was #34 — page retirement
after compaction COMMIT. Straightforward in theory: once a compaction is sealed,
delete the source pages. But I started asking the obvious question: what does
"delete a page" actually mean in Chronicle Queue?

The answer: nothing. You can't.

## What Chronicle doesn't do

Chronicle Queue is append-only at a granularity finer than file. There's no
`delete(index)`, no `truncate()`, `clear()` throws
`UnsupportedOperationException`. The OSS edition offers exactly one deletion
mechanism: remove an entire `.cq4` roll-cycle file from the filesystem. And
roll cycles are time-based — `DAILY`, `HOURLY` — with no API to force a roll
on demand.

The current `ChronicleJournalStorage` puts everything in a single queue. Page
IDs are just strings stamped into every record as the first argument to each
`ChronicleWriteOps` method. Retired pages don't go away — `RestoreEngine`
skips them at read time. The queue grows without bound.

## The temptation to simplify

Claude researched Chronicle's APIs and came back with a clear picture: no
per-record deletion, but you can manage whole queue directories. Two approaches
emerged.

**Snapshot-and-swap:** pause the session, scan the journal, write live state
into a fresh queue, delete the old one, resume. This would eliminate the entire
compaction subsystem — no PREPARE/COMMIT protocol, no liveness scanning, no
page index in `RestoreEngine`. Dramatically simpler.

**Queue-per-page:** each logical page gets its own Chronicle Queue directory.
Retirement means deleting a directory. The existing compaction protocol stays.

I was initially drawn to snapshot-and-swap. The compaction protocol is the most
complex part of the codebase — `scanLiveness()`, `spliceIntoIndex()`, the
sealed/unsealed commit semantics. Replacing all of it with a single checkpoint
operation felt right.

Then I thought about Aeron.

## Aeron changes the calculus

Aeron Archive is the distributed backend. It's the reason we have it as a
second backend option — not for single-node durability, but for remote
subscribers replaying recordings across machines.

Snapshot-and-swap assumes you can pause the world, replace the stream, and tell
all consumers "start reading from the new recording now." In a distributed Aeron
deployment with remote subscribers, that's a coordination problem. You can't
atomically pause all readers across the network.

Claude had initially recommended snapshot-and-swap confidently. When I pushed
back on the distributed angle, the analysis flipped. Aeron Archive natively
supports multiple independent recordings with `purgeRecording()` for selective
deletion — recording-per-page maps directly to Aeron's model.

The existing PREPARE/COMMIT protocol, which I was ready to throw away, turns out
to be inherently distributed-safe. The merged data exists before COMMIT makes it
canonical. Old pages remain readable until explicitly retired. No coordination
needed.

## The catalog queue

Queue-per-page solves deletion but creates a new problem: how does the scanner
know which queues to read and in what order? With a single queue, the page
ordering was derived from the stream itself — `CompactionCommitRecord`s told
`RestoreEngine` which pages were retired and where merged pages slot in.

I didn't want a manifest file or some exotic metadata format. We settled on a
catalog queue — a separate queue (same mechanism, nothing new) that tracks page
lifecycle:

- `PageCreated(pageId)` — appended before writing to a new page
- `PageMerged(mergedPageId, replacedPageIds[])` — appended when compaction
  completes

The scanner reads the catalog first to build the live page index, then opens
data queues in that order. The same `spliceIntoIndex` logic applies — `PageMerged`
replaces the old page IDs with the merged page at the position of the first
replaced page.

For Aeron, the catalog recording is what remote subscribers follow to discover
the current page set.

## What stays, what waits

The PREPARE/COMMIT protocol stays as-is. Claude suggested that PREPARE might
be unnecessary — orphaned merged pages (written but never committed) are
detectable by comparing directories against catalog entries. The sealing
safepoint might also be unnecessary since metadata and data are now separate
streams. Both observations might be right, but I'd rather keep the proven
protocol and simplify later once we actually implement the Aeron backend and
understand the distributed edge cases.

Recorded as [ADR-0002](adr/0002-queue-per-page-with-catalog-ordering.md). The
next step is planning the implementation changes to move
`ChronicleJournalStorage` from single-queue to multi-queue.

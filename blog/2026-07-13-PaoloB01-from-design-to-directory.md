---
layout: post
title: "From Design to Directory"
date: 2026-07-13
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [chronicle, multi-queue, compaction, architecture]
---

## From Design to Directory

The ADR was written. The catalog queue concept was settled. The crash analysis
was done. This session was about turning ADR-0002 into working code — moving
`ChronicleJournalStorage` from a single Chronicle Queue with embedded page IDs
to a directory of queues, one per page.

## The experiment that didn't work

I wanted to try subagent-driven development — dispatch a fresh agent per task,
review between tasks, fully autonomous execution. The idea was speed: five
tasks, no human-in-the-loop between them.

Two tasks in, I stopped it. The process was cumbersome, slow, full of blind
mechanical approvals, and produced no visible result. I couldn't follow what
was happening or develop understanding of the code. Claude was doing work, but
I wasn't learning anything.

We reset the branch, deleted the commits, and started fresh — one piece at a
time, conversational, with me reviewing every class before it was committed.
The pace was slower on paper but faster in practice, because every piece got
the scrutiny it needed the first time through.

## Five pieces, bottom up

The implementation followed the natural dependency order:

**Wire interfaces** — `ChronicleDataWriteOps` (data queues, no `pageId`
argument) and `ChronicleCatalogWriteOps` (catalog queue: `pageCreated`,
`compactionPrepare`, `compactionCommit`). Plus `ChronicleDataRecordHandler`
for the read side. I pushed back on the public field pattern from the old
`ChronicleRecordHandler` — a proper getter and `reset()` method instead.

**CatalogIndex** — reads the catalog queue and builds the ordered page list.
The key algorithm is `spliceIntoIndex`: when a `CompactionCommitRecord`
arrives, replace the retired pages with the merged page at the position of
the first retired page. I suggested scanning the page list from the bottom
instead of tracking splice positions with index adjustments:

```java
int r = replacedIds.length - 1;
for (int i = result.size() - 1; i >= 0 && r >= 0; i--) {
    if (result.get(i).equals(replacedIds[r])) {
        if (r == 0) {
            result.set(i, mergedId);
        } else {
            result.remove(i);
        }
        r--;
    }
}
```

Remove from the bottom, replace the head. Single pass, no adjustment logic.

**MultiQueueScanner** — reads the catalog via `CatalogIndex`, then chains
through data queues one at a time. When one queue is exhausted, close it,
open the next. I insisted on a factory method (`create`) with a constructor
that takes only simple pre-computed values.

**ChronicleJournalStorage** — the directory manager. Construction splits
cleanly into `createFresh` and `reopenExisting`. `roll()` closes the old page
queue, creates a new directory, writes `PageCreated` to the catalog. Compaction
methods redirect to the catalog queue. `writeMergedPage` creates its own
directory — no shared writer with the session thread.

**Integration** — where the real bugs surfaced.

## Two bugs the unit tests missed

The contract tests and integration tests caught problems that the
module-level tests couldn't:

**The page ID timing bug.** After `next()` returns the last record of a page,
`advance()` moves to the next queue. `currentPageId()` was already reporting
the next page — but `RestoreEngine` checks it between `next()` calls and
expects it to reflect the *last returned* record. The fix was the
`bufferedPageId` pattern: separate the look-ahead page from the current page,
update `currentPageId` inside `next()`.

**The empty active page.** On reopen, the active page is the last entry in
the catalog — which is often empty (safepoint called `roll()`, creating a new
page with no records). `latestPosition()` returned -1 for this empty queue,
and `JournalledSessionFactory` interpreted that as "nothing to restore." The
session reopened with zero facts.

The fix wasn't a hack. I pointed out that `latestPosition() >= 0` was the
wrong test entirely — the real question is "does this journal have data?" We
added `isEmpty()` to the `JournalStorage` SPI. For Chronicle, `reopenExisting`
returns `false` by definition — if we're reopening, data exists.

## What `pageCreated(int)` taught about types

Claude had `pageCreated(String pageId)` in the catalog interface, with an
`Integer.parseInt` / `catch NumberFormatException` in the handler. I asked:
why String if we always pass an int and a non-int would be an error? The
answer was obvious — use `int`. The `try/catch` disappeared, the handler got
simpler, and the type now enforces the contract instead of validating it at
runtime.

## Seven contract tests removed

The `JournalStorageContractTest` had tests for mid-stream resume
(`scan(fromPosition)` with non-zero position) and compaction records appearing
in the data scan. Neither is supported by any real backend — Chronicle puts
compaction metadata in the catalog, and nobody calls `scan` with a non-zero
position. Rather than disabling them for Chronicle only (which would have
required making parent test methods `protected` across packages), we removed
them from the contract entirely. If no real implementation supports a
behaviour, it doesn't belong in the contract.

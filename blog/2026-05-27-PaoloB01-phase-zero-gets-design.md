---
layout: post
title: "Phase Zero Gets a Design"
date: 2026-05-27
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, testing, api-design]
---

Issue B started with Claude about to dispatch a fleet of subagents —
one per task, spec loaded, ready to go. I stopped it. "Tell me what
you want to do first."

That change of pace turned out to matter.

## The Test API We Were Missing

Before writing any compaction test, I noticed the existing tests were
full of noise:

```java
storage.append(new InsertRecord(1L, false, -1L, JournalPayloadBuilder.embed("hello")));
storage.append(new SafepointRecord(1L, 0L));
```

`InMemoryJournalStorage` is our own test class. We could just add helpers.
I suggested `storage.insertRecord(2L, "live")` as a direction. Claude and I
worked out the full shape: `insert`, `logicalInsert`, `retract`, `safepoint`,
`ruleMatch`, `modify`, `compactionPrepare`, `compactionCommit`. Two insert
variants because `InsertRecord` has `logical` and `justifyingRuleMatchId` —
fields that are always meaningful together, never one without the other.

The same test after:

```java
storage.insert(1L, "hello");
storage.safepoint(1L);
```

We added the helpers, migrated `RestoreEngineTest` and `SafepointRollbackTest`,
and committed before writing a single new test.

## One Step at a Time

The plan's B1 tests covered the compaction protocol in one big scenario —
multiple pages, multiple safepoints, a retired page, all at once. I wanted
one test at a time. Start with PREPARE only, then add the insert, then add
COMMIT, then add the post-COMMIT safepoint.

Claude generated the first test. It had two inserts, three safepoints,
a retract, and a re-insert in the merged content. I pushed back: "this
is far too complicated." The scenario was trying to prove too many things
simultaneously.

We stripped it down. One insert, one safepoint, one compaction prepare.
Then we added each piece as a separate test. That approach is what caught
the bug in the plan.

## The Crash the Plan Had Missed

After adding COMMIT to the sequence, the question was: what if there's
no safepoint after? The plan described Phase 0 retiring source pages as
soon as a `CompactionCommitRecord` appeared.

I traced through the crash case: process dies after writing COMMIT,
before the session's next `fireAllRules()`. COMMIT is in the journal.
Phase 0 retires page "0". The merged inserts have no safepoint — they're
trailing-pending and get discarded. The original page is retired. The fact
is gone.

That's wrong. The fact had a safepoint. Compaction shouldn't make durable
data disappear.

The fix: a page is retired only when its COMMIT is followed by a safepoint.
The safepoint is the durability boundary — not the COMMIT. An unsealed
COMMIT is treated the same as no COMMIT: original pages remain canonical.

We wrote the missing test first:

```java
@Test
void commitWithoutSafepoint_originalPageRemainsCanonical() {
    storage.insert(1L, "fact");
    storage.safepoint(0L);
    storage.compactionPrepare("m-1", "0");
    storage.insert(1L, "fact");
    storage.compactionCommit("m-1", "0");
    // no safepoint — crash simulation

    assertThat(result.survivingFacts()).containsEntry(1L, "fact");
}
```

Four protocol tests in total, each adding one element. Then we implemented.

## Phase Zero, Correctly

Two passes. Phase 0 tracks commits that haven't been sealed yet. When a
`CompactionCommitRecord` appears, it goes into `unsealedCommits`. A safepoint
seals whatever is in there — source pages join `retiredPageIds`, merge ID
joins `sealedMergeIds`. No following safepoint, no sealing.

Phase 1 replays in order, skipping retired pages. When it hits a
`CompactionPrepareRecord` for a *sealed* merge, it resets `inRetiredPage = false`
so the merged inserts land in pending. The sealing safepoint then flushes them.

The plan's Phase 1 had a subtle bug: `inRetiredPage` would stay `true` through
the merged records, discarding exactly the content that was supposed to replace
the retired page. A single complex test would have passed by accident. The
incremental approach made it visible.

## Pages Are Physical Things

While discussing the implementation, a deeper issue surfaced. In a file-backed
backend, the compactor works on old sealed page files. The session writes to the
current file. Physical separation — they can't interfere.

`InMemoryJournalStorage` is one flat list. A concurrent `fireAllRules()` could
interleave its records with the compactor's merged inserts. I pushed on this:
if pages are physical files in production, does the in-memory storage miss a
fundamental design detail?

Yes. It has no page model. Safepoints are logical markers in a flat list, not
file boundaries. Concurrent compaction isn't testable with it.

And then: what happens when a page fills up? `PageRollStrategy` and `PageContext`
already exist in the API — `currentPageBytes()`, `currentRecordCount()`. But
size-based rolling breaks the compaction design. Page ID = safepoint sequence
number only works when every page boundary is a safepoint. Size-based rolling
creates boundaries without safepoints, and the compaction protocol has no page
ID to write.

Both issues go into the idea log. They surface when the file-backed backends
are built.

## Safepoint on the Interface

One concrete fix came out of the discussion. `JournalledKieSession` was
constructing `SafepointRecord` directly — owning the sequence counter, importing
the record type. The compactor will need to write safepoints too. Two threads,
one counter, no fun.

`safepoint()` now lives on the `JournalStorage` interface. The storage owns the
counter. The session calls `journal.safepoint()`. Clean.

The broader write-API refactor — `insert`, `retract`, all of it — is an idea
for later. It's the right direction, but the wrong moment.

Issue B is done. Issue C — liveness scan — is next.

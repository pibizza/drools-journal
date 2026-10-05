---
layout: post
title: "The snapshot that was just a merge"
date: 2026-10-05
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [design, durability, compaction, reliability]
---

Epic #38 has been sitting open for a while: make drools-journal a drop-in
replacement for drools-reliability, with three durability modes instead of
one. I came back to it to start the first real piece — SNAPSHOT mode, the
one that matches reliability's STORES_ONLY strategy: O(live-facts) restore
instead of replaying the whole log.

The original #38 scope named a `SnapshotRecord` type. A record that holds the
full live fact set, written periodically, so restore reads one record instead
of scanning everything. I started designing it and immediately disliked it.

## The record that kept getting bigger

A single record holding every live payload is just a page wearing a costume.
It gets large, it takes time to write, and a large slow write is exactly the
thing a durability mechanism should avoid — more time in flight means more
exposure to a crash mid-write. The #32 notes had an alternative: store
*position references* instead of payloads. But references into pages you then
want to retire are dangling references. You can't snapshot-then-retire if the
snapshot points back at what you retired.

Then the obvious thing landed. We already produce, on every compaction, a
merged page containing only live facts. The liveness accounting in
`CompactionCoordinator` already drops `RuleMatchRecord`s and `ModifyRecord`s
as collectable overhead and carries forward only the live `InsertRecord`s. A
merged page *is* a snapshot of live facts.

So SNAPSHOT isn't a new record. It isn't even a new restore path. It's a
compaction *policy*: instead of merging the pages below a 30% liveness
threshold, merge all live pages into one. The `SnapshotRecord` from the epic
scope disappears — resolved by omission.

## Where Claude got nervous

When I said "drop the match history on merge," Claude pushed back — wouldn't
restore break if the `RuleMatchRecord`s are gone? It's a fair reflex, but it
had the dependency backwards. A merged page has to bring forward everything it
needs to stand alone, or compaction would already be broken today. It is. The
match records are overhead STORES_ONLY never promised to keep — no TMS, rules
must be re-fire-safe. Dropping them is the whole point, not a regression.

That exchange is why I wanted the next decision on paper.

## Keeping the "what" apart from the "how"

The thing I care most about from this session isn't the merge trick. It's a
framing rule I made explicit to Claude: separate the functionality from the
implementation strategy. We are delivering a *durability mode* — a contract.
The fact that we can build it cheaply by reusing the merge machinery is a
strategy, and strategies get replaced.

So the spec is now in two parts. Part A is the contract: STORES_ONLY-parity
semantics, O(live-facts) restore, safepoint crash consistency, a
`DurabilityMode` selector. Part B is the initial strategy: full-merge
compaction, no new record, no new restore code. Tests target Part A, so they
survive a change of strategy. If the full-merge approach ever stops paying
off, Part B gets rewritten and nothing in Part A moves.

The API surface is small enough to show. The enum reserves the future mode in
a javadoc comment rather than declaring it — a constant you can't use yet is a
trap:

    public enum DurabilityMode {
        JOURNAL,    // full append-only log (current behaviour) — default
        SNAPSHOT    // STORES_ONLY-parity; O(live-facts) restore
        // JOURNAL_DELTA reserved for Phase 5 (modify-as-lambda)
    }

## What's planned

I broke the work into five issues along the same seam: contract first
(#61 the DurabilityMode API and wiring, #62 parity ITs, #63 the restore
benchmark), then the strategy (#64 full-merge policy, #65 logical-facts-as-
plain on restore). #61 has a full plan written — two TDD tasks, pure
plumbing, no behavioural change until #64 lands. On cadence I decided to
leave it alone: reuse the existing periodic compaction interval rather than
invent a second trigger. Predictable beats clever, and I can separate the
mechanism later if the numbers ask for it.

Next time in here, #61 gets implemented.

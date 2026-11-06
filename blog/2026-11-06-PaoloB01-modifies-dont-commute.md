---
layout: post
title: "Modifies don't commute"
date: 2026-11-06
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, journal, bug-fix]
---

The bug was already written down. Issue #66: compaction drops modify deltas
for still-live facts, so a fact reverts to its originally-inserted value on
restore. Two defects described, a reproduction test attached. This session was
meant to be a straightforward fix. It wasn't — not because the fix was hard,
but because the obvious fix was wrong.

We'd never caught the bug in the first place because the coverage had a blind
spot: every modify test disables compaction, every compaction test uses only
inserts and retracts. The one combination that corrupts — deltas plus an active
compactor — had zero tests.

## The test that was the bug

There was even a unit test asserting the broken behaviour was correct:
`modifyRecord_inLivenessTracking_countsInTotalOnly`. It checked that a modify
counts toward a page's total but never its live count, with a comment spelling
it out: "modify does not contribute". The bug, written down as a contract,
passing happily.

The compactor scores each page `live / total` and merges anything under 0.30.
A page holding only modify deltas for live facts scored `0/N` — so it looked
like the *best* thing to compact away. The deltas were being treated as dead
weight.

The scoring fix was small. A modify is the current state of a live fact, so it
counts as live, like the insert does:

```java
} else if (r instanceof ModifyRecord modify) {
    liveness.get(pageId)[0]++; // live
    liveness.get(pageId)[1]++; // total
    factToPages.computeIfAbsent(modify.factHandleId(), k -> new ArrayList<>())
               .add(pageId);
}
```

The one-page `factToPage` map became a one-to-many `factToPages`, so a retract
reverses a fact's live count on every page it touched — its insert and all its
modifies. Symmetric to how inserts already worked.

## The scenario that broke the next fix

That left the merge itself, which rebuilt pages from live inserts only and
dropped the modifies. Claude proposed carrying them forward into the merged
page, in order. Clean enough. I thought we were done — then I asked where a
merged page actually goes.

Page 1 inserts R1. Page 2 modifies it. Page 3 modifies it again. Compact pages
1 and 3 — not page 2, which sits between them and is still live. Where does the
merged page land? A page is one slot in the journal. Put it at page 1's slot and
the second modify replays before the first. Put it at page 3's slot and a modify
replays before R1 even exists. There is no correct position.

That is the whole lesson in one line: **inserts commute, modifies don't.**
Inserts of different facts can be replayed in any order, which is exactly why
the original design could merge arbitrary non-adjacent sparse pages. Modifies
are ordered deltas against one fact's state — merging across a live gap reorders
the chain.

Claude's next offer was to materialise instead: apply the lambdas and collapse
R1 to a single fresh insert. I killed that too — page 2 is still live. We can't
compute R1's current state without its modify, and we can't consume that modify
while leaving the page in place.

## Accept the boring constraint

So, bug-fix mode: merge only *contiguous* runs of pages. A contiguous run is one
unbroken span — nothing outside it interleaves — which makes carrying modifies
forward provably safe. The selection used to drop every sparse page into a single
`compact()` call regardless of adjacency. Now it walks pages in journal order,
groups consecutive sparse pages, and merges only runs of two or more. A lone
sparse page with no sparse neighbour waits.

The smarter version is real and tempting: reorder independent events to make
sparse pages adjacent, or split a page into its pinned facts and its compactable
ones — a kind of stable sort over facts. That's logged in IDEAS.md for a day when
the goal is better compaction, not a correct one.

Three commits, four new tests, #66 closed. The fix that shipped is the one that's
easy to defend: contiguous-only, carry the deltas, drop nothing that's alive.

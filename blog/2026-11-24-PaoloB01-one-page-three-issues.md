---
layout: post
title: "One page, and three things I didn't fix"
date: 2026-11-24
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, snapshot, retirement, testing, scope]
---

Last entry ended on the giant-page worry: SNAPSHOT merges every sealed page,
so a large live set becomes one very large page. I settled it today by not
settling it. One big page for now. Splitting the output into size-bounded
pages is #74, and it waits until we've worked out how to make a multi-page
commit atomic. That part isn't obvious, and I didn't want it hiding inside #64.

## The policy is eight lines

With the output shape fixed, #64 was mostly done already. The code had been
sitting in a stash since the last session:

    void runSnapshotCycle() {
        final Set<String> sealedPages = new LinkedHashSet<>(scanLiveness().keySet());
        if (sealedPages.size() >= 2) {
            compact(sealedPages);
        }
    }

`scanLiveness()` only reports sealed pages, so the active append page is
excluded without anyone asking. Sealed pages form a contiguous prefix, so the
merge can't reorder a modify chain. That's the lesson from #66, and it holds
here for free. JOURNAL keeps its sparse-run policy behind `runJournalCycle()`.

Claude and I added an end-to-end test that goes through the public API:
a SNAPSHOT session inserts across four pages, retracts one fact, calls
`compactNow()`, and closes. The journal is down to a single `m-` page. A fresh
session restores the two live facts, and `fireAllRules()` returns zero, because
the match records survive the merge now (#72).

## "Retired" meant less than I thought

#64's title says "aggressive retirement", so I asked Claude to check what
retirement actually does on the SNAPSHOT path. The report came back in two
halves.

Logical retirement works. `compactionCommit` writes the commit record, the
index drops the replaced pages, and scanners never see them again.

Physical retirement never happens. Not in SNAPSHOT, not in JOURNAL:

    this.retirementInterval = Duration.ZERO;

A zero interval means the retirement thread never starts, and nothing else in
production calls `runRetirementCycle()`. Only tests do. Replaced page
directories stay on disk forever.

Claude offered to wire it into #64: call `runRetirementCycle()` right after
`compact()`, add a test, done. I said no. When to delete, whether a reader can
still be scanning a page you're deleting, and what to do about a retired-page
list that only ever grows are policy questions, not a one-liner. That's #76.

## The test double that drifted

The same check turned up something worse, by reading the code rather than
running it. SNAPSHOT's second cycle always merges the previous merged page
together with the new ones. Claude traced that through
`InMemoryJournalStorage.buildIndex()`:

    P0 P1 P2 Commit(m1, [0,1,2])   → live [m1]; buffer cleared
    P3 P4    Commit(m2, [m1,3,4])  → only 3, 4 in buffer; m1 never matched
                                   → live [m1, m2]

Every fact in `m1` would appear twice. Chronicle's `CatalogIndex` splices
against the full live list and gets it right. The in-memory version rebuilt
the same index with a shortcut that only works if nothing is ever merged twice.
No test merges twice, so nobody noticed.

Claude wanted to write the failing test straight away. I wanted it filed.
It's an existing bug, it's not proven yet, and #64's tests only run one cycle.
That's #75, and it's the next thing I'll pick up, because everything multi-cycle
in SNAPSHOT depends on it.

The wider lesson: a fake storage that reimplements index logic will drift from
the real one, and the drift lives exactly where your tests don't go. Running
the same scenario against both backends is cheap insurance.

## Closes doesn't close

Small one. The commit said `Closes #64` and the PR merged cleanly. #64 stayed
open. GitHub only auto-closes issues on merges into the default branch, and
this PR went into `epic-snapshot-mode`. It's documented behaviour, and it's
the kind of thing I'll forget every epic, so it's in CLAUDE.md now.

#64 is closed by hand. One issue went out today and three came in, which feels
backwards, but each of the three is a real question rather than something I
swept into a PR that was supposed to be about eight lines.

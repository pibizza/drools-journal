---
layout: post
title: "The salt that faked a flake"
date: 2026-10-02
type: correction
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [chronicle, compaction, testing, jvm]
---

Last time I gave the Chronicle flake an issue and called it transient. That
was wrong, and this entry is the correction. `ChroniclePageRetirementIT`
wasn't flaky. It was fully deterministic — just not within a single JVM run.

I opened #58 as "stabilise the retirement ITs." When I came back to it, I
re-read my own handover and didn't like it: it told the next session to
root-cause and stabilise, as if the diagnosis were already done. The real
item was smaller and more honest — review the Chronicle code against the new
two-tier catalog contract, and *understand* the bug before touching it. So I
told Claude to slow down: branch first, read the contract, then talk.

## The empty restore

The shape of the failure never changed. Compact two pages, close, reopen —
and `RestoreEngine` comes back with nothing. On reopen, Chronicle rebuilds
the live page list from the catalog and chains only those data queues. So an
empty restore means the live list came back wrong.

The culprit was `spliceIntoIndex` — the function that, on replay, folds a
compaction commit into the live page list. It walked `livePages` from the
tail and matched the replaced ids from the tail, assuming both were in the
same order. Feed it `livePages = ["0","1","2"]`, merged `"m"`:

- replaced `["0","1"]` → `["m","2"]` ✅
- replaced `["1","0"]` → `["1","2"]` ❌ — the merged page vanishes, and the
  stale all-retracts page stays live

## Why it only "sometimes" failed

Claude traced where that order came from. `CompactionCoordinator.compact()`
takes a `Set<String>` and flattens it with `toArray`. The test calls
`compact(Set.of("0","1"))`. And `Set.of(...)` picks its iteration order from
a random salt chosen once per JVM start — stable within a run, different
between runs. So one `mvn` invocation got `["0","1"]` and passed; the next
got `["1","0"]` and lost a page. A deterministic bug wearing a flake's
costume.

That's the lesson I want to keep: "transient" is a hypothesis, not a
diagnosis. When a test fails *sometimes*, suspect per-run randomization —
hashing salt, `Set.of`, parallel streams — before you blame timing.

## The fix, and the bug in the fix

My first instinct was to sort the array. Claude pushed back, and it was
right: inside `compact()` the set is only ever used as membership
(`pageIds.contains(...)`). Order shouldn't matter anywhere. The defect was
that `spliceIntoIndex` *invented* an order requirement.

So I rewrote it myself — membership set, one pass, drop the replaced ids,
put the merged id in the first slot. Then Claude caught what I'd missed: I
was removing from the list by index while incrementing the index, so a run
of three consecutive replaced pages skipped one. Concrete failing input:
`["0","1","2","3"]`, replace `{"0","1","2"}` → I produced `["m","2","3"]`
instead of `["m","3"]`.

The clean version builds a fresh list instead of mutating in place:

    for (final String pageId : pageIndex) {
        if (idsToReplace.contains(pageId)) {
            if (!mergePageAlreadyInserted) {
                result.add(mergedId);
                mergePageAlreadyInserted = true;
            }
        } else {
            result.add(pageId);
        }
    }

Order-independent, and correct for non-adjacent and consecutive pages. The
existing `allPagesReplaced` test already covered the three-page case — it
went red on my version and green on this one. I ran the IT three times in
separate JVMs, different salts each time, all green.

#58 was really two things — a review and a bug — and the review half found
the bug the review-before had only predicted. There's a loose thread left:
`compactionPrepare` is a no-op on replay, and I still find Chronicle a bit
fuzzy. That's for the next time I'm in here.

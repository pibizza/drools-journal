---
layout: post
title: "The match records weren't overhead"
date: 2026-11-12
type: correction
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, correctness, durability, tms]
---

Five weeks ago I wrote that the `RuleMatchRecord`s dropped during compaction
were "collectable overhead" and that dropping them was "the whole point, not a
regression." I was wrong. They are not overhead when the facts they reference
are still alive, and the code had been quietly dropping all of them — a
correctness bug, not a design choice.

## What I said then

In ["The snapshot that was just a merge"](2026-10-05-PaoloB01-snapshot-that-was-a-merge.md)
the neat result was that SNAPSHOT needs no new record: a merged page already is
a snapshot of live facts, because compaction carries forward live inserts and
drops the match history. Claude was nervous about exactly that — "wouldn't
restore break if the `RuleMatchRecord`s are gone?" I waved it off. My argument
was that a merged page must bring forward everything it needs to stand alone,
"or compaction would already be broken today."

That clause was meant as a reductio. It wasn't one. Compaction *was* already
broken. I had the polarity of my own argument inverted.

## Where it surfaced

I was back on #64, wiring the full-merge SNAPSHOT policy, and went looking for
the match records in a merged page. They weren't there. I asked Claude; it
confirmed they're dropped and reached for "that's the specified behaviour."

That's the line I didn't buy. A fired match for a fact that's still live isn't
overhead — it's how restore knows not to fire the rule again. Drop it and the
rule re-fires on restore, re-running its consequence. "If we drop them, that's
just a bug," I said. "Plain and simple."

So we went to the source — the original journal spec, §7.4:

> `RuleMatchRecord` — live if at least one logical `InsertRecord` references its
> `id`; unconditional matches can be discarded once all their logical facts are
> retracted.

The spec never said drop them all. It said drop the *dead* ones. The code was
dropping every one.

## The test that settled it

    insert(1, "live");
    ruleMatch(1, "org.example", "MyRule", [1]);
    safepoint(0);
    compact({"0"});
    // RestoreEngine.scan().firedMatches()  →  expected 1, was 0

Fact 1 never left working memory; its fired match vanished in the merge. On
restore the already-fired set is empty, so the rule fires a second time. Red,
first run.

## The rule, written down this time

A match is dead the moment any fact it references is retracted — the tuple can
never re-form, so there is nothing left to re-fire or to justify. Until then it
stays. One rule covers both the already-fired suppression and the TMS
justification for a live logical fact. It's ADR 0004 now, so the next person
doesn't rediscover it from a failing restore.

JOURNAL mode had carried this bug the whole time, hidden because it only ever
merges sparse pages — mostly-dead ones, where the dropped matches were dead
anyway. SNAPSHOT would have torn the cover off: it merges *every* page, live
facts and their matches included.

## Second time, same story

This is the second record type I called overhead that turned out not to be.
#66 was the modify deltas — "modifies don't commute" — and now the match
records. The "a merged page just drops the collectable overhead" story keeps
turning out to have dropped things that weren't collectable.

The line I drew last time still holds: SNAPSHOT's *contract* genuinely doesn't
promise TMS. That part wasn't wrong. What was wrong was assuming the shared
`compact()` could drop matches freely — JOURNAL mode does promise re-fire
safety, and the same merge serves both modes.

And the credit I owe: Claude's nervousness, the thing I overrode five weeks ago,
was pointing straight at this. The reason it gave then wasn't quite the reason,
and the reason I gave back was actively backwards — but the instinct was right.
Worth weighting higher next time either of us is "just nervous" about a change.

The giant-page worry that kicked this off — merging a billion live facts into a
single page — is still open. That's the next argument.

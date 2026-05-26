---
layout: post
title: "From Restore to Compact"
date: 2026-05-26
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, epic, design, tdd, safepoint]
---

The restore is done. Time to close one epic and open the next.

## Closing the Books on Session Runtime

Epic-journal-aware-session ended today — formally. Issue #6 closed, ten blog
entries promoted to the project repo, the design journal merged into `DESIGN.md`,
two specs posted to GitHub. That last part is mostly ceremony but it matters.
The spec for `RestoreEngine` took three sessions to finalise and two more to
implement. Worth having it attached to the issue that drove it.

`CompactionCoordinator` — the one open item — didn't fit. I'd underestimated
it at the start and the closer we got to it the more it looked like its own
thing. So we split it out and started a new epic: `epic-compaction-coordinator`.

## What is a Page, Exactly?

The first design question for compaction was also the most fundamental.
The spec talked about "pages" and "page IDs," but the `JournalStorage` SPI
had no page concept at all. Just positions — monotonically increasing longs.

We had three options: add an explicit `roll()` to the SPI, expose backend-native
page IDs, or treat safepoints as page boundaries implicitly. I kept coming back
to the third one. The question that broke it open was simple: when do we actually
write a `SafepointRecord`?

The answer was embarrassing. Nobody was writing them. The type existed, `RestoreEngine`
handled them, the Javadoc described them — but no production code called
`storage.append(new SafepointRecord(...))` anywhere.

Once I answered "after every `fireAllRules()`," everything else followed. Safepoints
go at natural quiescent points. `JournalledKieSession` already overrides `fireAllRules()`.
A safepoint always forces a page roll by contract. So: page N is the records
between `SafepointRecord(N-1)` and `SafepointRecord(N)`, and the page ID is just
`String.valueOf(sequenceNo)`. No SPI changes. Pages fall out of the mechanism for free.

## Periodic Rescan Wins by Not Being Complex

The next question was how `CompactionCoordinator` tracks which pages are sparse.
The original plan said: intercept every append, maintain `pageId → (liveCount, totalCount)`
incrementally. A decorator over `JournalStorage`. Plausible but fragile.

I suggested the alternative: periodic full rescan. On each poll cycle, scan the
journal from scratch, rebuild the liveness map, compact candidates. No write-path
interception. No auxiliary state to keep consistent under concurrent writes.

The cost is O(journal size) per poll. For moderate journals this is fine, and
the poll interval is configurable. If it ever becomes a bottleneck we can optimise
then — but that's not today's problem.

## InMemoryJournalStorage Grows Up

`InMemoryJournalStorage` was explicitly marked not thread-safe. That was fine
when the session was single-threaded. With a background compactor reading
concurrently, it needed to be fixed.

I could have worked around it — a special test executor, a same-thread mode for
the coordinator. But constraining the production design to accommodate a test
stub is backwards. We made all methods `synchronized` and switched `scan()` to
return a `List.copyOf()` snapshot. Straightforward fix, done once.

## The Stub That Shouldn't Have Been There

Issue A also wired `SafepointRecord` into `fireAllRules()`. One counter field,
one append call, four lines of change. Clean and obvious.

What wasn't clean was the first version, which also added a `compactionCoordinator`
field and `setCompactionCoordinator()` method to `JournalledKieSession` — stubs
for Task D that had no business being in Task A. I caught it before committing
and we stripped it out. `CompactionCoordinator` doesn't exist yet as a class.
Adding references to it "to be ready" is exactly the kind of forward-looking
complexity that makes code harder to reason about. The field goes in when the
coordinator goes in.

## The End-to-End Test We Hadn't Written

The safepoint wiring made something possible that wasn't before: a true end-to-end
restore test through `JournalledSessionFactory`. Not a manually constructed journal —
an actual session running, firing rules, disposing, then a second session opening
on the same storage.

We added three tests to `JournalledKieSessionRestoreTest`. Session 1 inserts
and fires. Session 2 opens, checks working memory, confirms the rule doesn't fire
again. Session 3 (in the chained test) finds both facts from both prior sessions
and fires nothing. All pass.

One wrinkle: `KieSession.getObjects()` returns `Collection<? extends Object>`.
AssertJ's `containsExactlyInAnyOrder` fails on it — even with explicit
`Integer.valueOf()` boxing, even with `containsExactlyInAnyOrderElementsOf`.
The fix was a cast: `@SuppressWarnings("unchecked")` to `Collection<Object>`
first. Annoying but isolated.

Issue A is done. Issue B — real `RestoreEngine` Phase 0 — is next.

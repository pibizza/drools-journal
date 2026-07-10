---
layout: post
title: "Three Records the Spec Forgot"
date: 2026-06-04
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, tdd, page-model]
---

The liveness scan from last session felt complete. Three record types handled,
tests green. Then I started wondering about logical inserts.

If fact B is logically inserted by A, and A is retracted, does the liveness scan
need special handling for B? I brought the question to Claude. The answer came
quickly: when A is retracted, the Drools TMS automatically retracts B — which
means a `RetractRecord(B)` lands in the journal. The normal liveness logic picks
that up. The `logical` flag on `InsertRecord` is irrelevant to compaction.

## Three Records the Spec Forgot

Good. But checking the `InsertRecord` definition opened a wider question: what
else were we not handling?

The spec table listed four record types for the liveness scan. The implementation
handled three. `RuleMatchRecord` was missing — it should increment `totalCount`
only, never `liveCount`. It occupies space on a page but represents no working
memory state. We TDDed the fix: one test (`totalCount` was 1, expected 2), one
failing run, two lines of implementation.

Then `ModifyRecord` — in the API, absent from both the spec table and the
implementation. Same treatment as `RuleMatchRecord`: `totalCount++` only. Same
TDD cycle.

That left a subtler issue: `RetractRecord`. The spec said only
`liveCount[factPage[id]]--`. The implementation also did
`totalCount[currentPageId]++`. And a note from a previous session said "a retract
is counted as live on the page where it appears" — which would mean `liveCount++`
too. Three sources, three different answers.

The implementation is right. A retract occupies space on its page (`totalCount++`)
but is not working memory state — no `liveCount` credit. The spec was incomplete,
the older session note was imprecise. We updated the spec table, added an explicit
test for the `totalCount++` on the retract's own page, and moved on.

## The Page Model, Step by Step

The next question was `InMemoryJournalStorage`. Currently a flat list — fine for
session recording, but the compaction protocol needs to write a merged page
without interleaving with the session's ongoing writes.

Claude's first instinct was to design the full solution: named pages in a
`Map<String, Page>`, a `writeMergedPage()` API, a scanner that transparently
inlines merged records on COMMIT. I kept redirecting. We're just restructuring
the storage internals. One step at a time.

We walked through the four-step protocol in the spec line by line. PREPARE goes
to the current page — plain `append()`. COMMIT is a pointer, not a merge
operation — also plain `append()`. The merged page is assembled separately and
stored outside the session page list. The intelligence is in RestoreEngine's
Phase 0, which must do a first pass collecting all COMMIT records before Phase 1
replays anything. Without Phase 0, a single-pass restore sees COMMIT after the
retired pages and has already replayed the wrong records.

The ordering concern I raised — does the merged page need to appear at the source
pages' position in the list? — turns out not to matter. Compaction keeps only
facts that are live at scan time. Any subsequent session records that retract
source-page facts are retracting facts already excluded from the merged page.
They become harmless no-ops on replay.

## One Test, Then the Refactor

With that understood, we reduced the task to its minimum: give
`InMemoryJournalStorage` a concept of pages. The test:

```java
@Test
void safepointRollsCurrentPage() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    assertThat(storage.currentPageNumber()).isEqualTo(0);
    storage.insert(1L, "a");
    storage.safepoint(0);
    assertThat(storage.currentPageNumber()).isEqualTo(1);
}
```

Compilation failure — `currentPageNumber()` doesn't exist. The implementation:
flat `List<JournalRecord>` became `List<Page>`, each `Page` holding its own
record list. `currentPage` is a live reference to the last page in the list.
`append()` adds to `currentPage`; a `SafepointRecord` triggers a new page and
updates the pointer. `scan()` flattens all pages — 11 existing tests don't
notice.

The compaction protocol now has a foundation to build on.

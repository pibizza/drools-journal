---
layout: post
title: "The Page That Forgot It Was Retired"
date: 2026-09-11
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, catalog, retirement, testing]
---

## What I was trying to finish

Back on `refactor/56-two-tier-catalog-contract` after the branch-home entry, I wanted to know what was actually left on issue #56 before treating it as a normal feature branch. There was already an uncommitted diff sitting there — `IndexStatus` moved from `Set<String>` to `List<String>`, `CatalogStatus` deleted, `InMemoryJournalStorage.buildIndex()` rewritten to be the single source for both `scan()` and `indexStatus()`.

## What I believed going in

I assumed that unification was basically the whole job. Claude reviewed the diff and agreed it was structurally right, but flagged one line: `buildIndex()` still resolved each catalog page id against the physical `journal` list while doing pure bookkeeping. That looked survivable. It wasn't.

## A test written backwards

I asked Claude to expose the double-retirement bug as a test. It came back with one asserting the crash — `assertThatThrownBy(...).isInstanceOf(NullPointerException.class)`. Wrong test: we didn't want proof the code throws, we wanted proof it shouldn't. Claude rewrote it as `assertThatCode(coordinator::runRetirementCycle).doesNotThrowAnyException()`.

## Quieter is not the same as fixed

I added the obvious guard — skip the page id if it's not in the journal anymore — and the test went green. Claude traced through it anyway and found the guard just moved the bug: the merged page's "mark me live" logic only fired when it found its retired predecessor still buffered. Skip the predecessor, and the merged page vanishes from `livePageIds()` with nothing logging it. A crash tells you something is wrong; this didn't.

Claude then proposed a tombstone record, written to the catalog before the physical page delete, to keep the catalog from "lying" mid-crash. Wrong order — write the record first and every retirement cycle has to re-check whether the physical delete actually landed. Delete first, record second, and the record only ever states something that already happened.

## Catalog only, journal is just storage

I wanted a plain-text example, not a document and not a test. Claude tried both anyway before I said what I actually meant — page records and journal contents, nothing else. Laid out as text, the bug was obvious: replay the catalog twice against a journal that's lost a page in between, and the merged page disappears on the second pass.

That made the real fix cheap. The catalog already remembers every retirement forever via `CompactionCommitRecord` — no tombstone needed. `buildIndex()` just had no business resolving ids to `Page` objects while classifying them; it only ever read `.id` back off something it already had as a string. Buffer ids, not pages, and the journal never enters the decision at all.

## Green, twice, and a correction from GitHub

I stripped the guard and the now-dead `pageIdToPage` map, and strengthened the test to assert `livePageIds()`/`retiredPageIds()` stay identical across two retirement cycles, not just "doesn't throw." `mvn clean install` came back clean except two pre-existing `ChroniclePageRetirementIT` failures — transient, for now. Committed as `fix(compaction): retirement stays catalog-only`, pushed.

I also folded `PageIndexTest` into `CompactionCorrectnessTest` — `PageIndex` itself has been dead for a while, and the four tests already exercised `livePageIds()`/`retiredPageIds()` directly.

Checking #56's task list against the actual code found a stale tracker more than stale code — `ScanCursor`, `JournalPrinter`, and `PageIndex` cleanup were already done, just never checked off. Genuinely open: a disabled test still calling `runMergingCycle()` instead of `runRetirementCycle()`, four files still carrying "sealed"/"unsealed" naming from an assumption already ruled out, and the Chronicle regression.

Claude also told me #56 had no parent epic, based on an empty `trackedInIssues` query. I could see the link on GitHub directly. Claude had queried the wrong relationship — that field covers old checklist-based tracking, not the `parent` field GitHub uses for native sub-issues — and re-querying the right one showed #56 correctly linked to `apache/incubator-kie#6683` all along.

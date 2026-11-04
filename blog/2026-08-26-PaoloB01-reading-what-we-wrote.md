---
layout: post
title: "Reading What We Wrote"
date: 2026-08-26
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [documentation, code-review, restore]
---

## The code got ahead of me

I realised I couldn't explain `JournalledRuntimeComponentFactory` to someone else. Not the broad strokes — those were clear — but the specific ordering decisions, the guard clauses, the reason a global called "journal" gets wired conditionally. The code worked, but I'd lost the thread of *why* each piece was there.

This matters because the whole workflow depends on my ability to review what Claude generates. If I can't defend the code, I'm just trusting it blindly — and that's exactly what the collaborative process is supposed to prevent.

## Starting with the factory

I picked `JournalledRuntimeComponentFactory.createJournalledSession()` as the first target. It's the entry point for session creation — everything flows from here.

First change was structural: an if-inversion to flatten the session-pool guard into an early return. The original nested the entire session creation inside `if (fromPool || kbase.getSessionPool() == null)` with an else branch. Claude initially misread the inverted condition as a bug — the `fromPool` semantics are confusing. The name suggests "this session comes from a pool" but the logic says the opposite: `fromPool == true` means "we're already inside the pool path, don't recurse."

Then I added comments marking the phases. Minimal — one line per phase, just enough to show what's happening without drowning the code:

```java
// the session is restored at startup
if (!storage.isEmpty()) {
    restore(session, storage, opt.getModifyLambdaRegistry(), strategy);
}

// the listener generates the events that are stored in the journal
// so it has to be added AFTER the session is restored
JournallingRuntimeEventListener listener = ...
```

The listener-after-restore ordering is the kind of thing that's obvious once stated but invisible without the comment.

## The journal global mystery

One piece I couldn't explain: `hasJournalGlobal(kbase)`. We traced it through git history to commit `489c24b` — the DRL precompiler wiring. The precompiler rewrites `modify` blocks to inject `journal.stageModify()` calls, enabling delta-based `ModifyRecord` capture instead of full object snapshots. The global check is necessary because Drools requires globals to be declared in DRL before `setGlobal()` can be called — otherwise it throws.

I asked whether we could just add the global programmatically and skip the check entirely. Claude found `InternalKnowledgePackage.addGlobal(String, Type)` in Drools internals — so yes, it's possible. But I chose to leave it as-is. The current code works, and modifying KieBase packages at creation time feels more invasive than warranted right now.

## The restore flow

The `restore()` method was the hardest to read. I knew the broad sequence but not the mechanics. Claude walked me through the detail:

1. **Scan** the journal → surviving facts, fired matches, pending TMS links
2. **Insert non-logical facts** → rebuild working memory, build old-to-new ID map
3. **Build replay filter** → translate match records to use new fact handle IDs
4. **Fire all rules** → not actually to fire anything. The filter blocks every match. The real purpose is forcing Drools to create live `Match` objects internally, which the filter caches
5. **Wire TMS** → link each logical fact to its justifying `Match` object from the cache
6. **Install filter permanently** → prevents duplicate firings after restore

Step 4 was the key insight I was missing. `fireAllRules` isn't about firing — it's about generating objects that `wireTms` needs.

I extracted named methods to make the flow readable: `extractRecordsFromJournal`, `regenerateMatchObjects`, `getTms`. The restore method now reads as a sequence of named steps instead of a wall of inline code.

## Two things to fix later

The `ReplayFilter` is doing double duty — it's both an `AgendaFilter` (permanent role) and a match cache (one-time restore role). These should be separate objects. Also, its constructor takes `Collection<RuleMatchRecord>` only to immediately discard everything except the key fields. We construct fake records just to decompose them one line later. Both are logged in IDEAS.md.

## Flaky tests

During the build we hit intermittent failures in `ChroniclePageRetirementIT` — two compaction tests returned empty `survivingFacts()` after close and reopen. Clean pass on retry with no code changes. Likely a Chronicle Queue flush timing issue. Also logged in IDEAS.md for investigation.

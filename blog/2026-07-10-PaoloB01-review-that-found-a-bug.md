---
layout: post
title: "The Review That Found a Bug"
date: 2026-07-10
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [review, compaction, bugs, housekeeping]
---

## The Review That Found a Bug

Mark Proctor opened two analysis issues on the project. Issue #31 maps every
section of the original design spec against the implemented codebase — 10
improvements over spec, 9 gaps. Issue #32 compares drools-reliability and
drools-journal head-to-head, proposing a unified three-mode system (SNAPSHOT /
JOURNAL / JOURNAL_DELTA) that would replace drools-reliability entirely.

I expected a housekeeping session: read the analyses, sync the repos, file the
missing issues, move on. That's mostly what happened. But the second document
raised a question about modify handling that led us somewhere unexpected.

## The sync debt

The workspace and project repos had drifted apart. The project had 20 blog
entries; the workspace had 15 — different 15. Nine entries existed only in the
project, three only in the workspace. ADR-0001 had never been copied to the
workspace. The `design/JOURNAL.md` had never been copied to the project.

The routing rules in CLAUDE.md already say blog, adr, and design go to the
project. We just hadn't been enforcing it consistently. We copied everything
both ways, rebuilt INDEX.md with all 23 entries in chronological order, and
committed to both repos.

## Five issues that were known but invisible

Issue #31 lists 9 gaps. Mapping them against the issue tracker: 3 were already
tracked (#17 modify compiler, #15 Aeron, #16 integration tests). The other 6
were documented in blog entries or HANDOFF.md but never filed as issues.

We created five:

- **#33** — Chronicle thread safety (Critical — blocks real compaction)
- **#34** — Compaction Step 4: page retirement (Medium — disk accumulates)
- **#35** — ExternalRefStrategy.load() (Low — throws UnsupportedOperationException)
- **#36** — Concurrent compaction tests (Medium — spec-promised, untested)
- **#37** — Performance benchmarks (Medium — claims unverified)

The sixth gap — DESIGN.md showing Phase 6 as Pending — I almost fixed inline,
but Chronicle isn't actually complete until the thread safety issue is resolved.
The status is more accurate than #31 gives it credit for.

## The modify insight

Issue #32 proposes that drools-journal should eventually support a JOURNAL_DELTA
mode where DRL `modify` blocks emit small delta records instead of full object
snapshots. That's Phase 5 — the compiler rewrite, the biggest remaining work
item.

But I realised we don't actually need the compiler rewrite for journal
efficiency. When a fact is updated, the journal writes a new InsertRecord with
the full object. Compaction should collapse these — keep only the latest
InsertRecord per factHandleId, discard the rest. The intermediate states are
dead weight.

That reframes Phase 5. The compiler rewrite becomes a write-path optimisation
(less I/O per modify), not a prerequisite for compact journals or fast restore.
For most workloads where Chronicle's append speed already makes full-object
writes cheap, it drops from "core innovation" to "nice-to-have."

## The liveness scan doesn't know about superseded inserts

That line of thinking led to a question: does the compaction liveness scan
actually treat a re-insert as a death event for the old insert? Claude traced
through `CompactionCoordinator.scanLiveness()` and found that it doesn't.

The `flushPageLiveness` method tracks which page holds each fact via
`factToPage.put(insert.factHandleId(), pageId)`. When the same factHandleId is
inserted again in a later page, the map updates — but nobody decrements the
live count on the old page. The old InsertRecord is superseded, effectively
dead, but the liveness scan still counts it as alive.

```java
// Current code — overwrites mapping, forgets to decrement
if (r instanceof InsertRecord insert) {
    liveness.get(pageId)[0]++;
    liveness.get(pageId)[1]++;
    factToPage.put(insert.factHandleId(), pageId);  // old page not decremented
}
```

We wrote a reproduction test to confirm:

```java
storage.insert(1L, "original");
storage.safepoint(0);              // page "0": [insert(1)]
storage.insert(1L, "updated");     // re-insert same factHandleId
storage.safepoint(1);              // page "1": [insert(1)]

Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);
// page "0" reports live=1 — should be 0
```

Page 0 shows `live=1` when it should show `0`. The fix is one line — check if
`factToPage` already contains the handle, and if so, decrement the old page:

```java
String origin = factToPage.put(insert.factHandleId(), pageId);
if (origin != null) {
    liveness.get(origin)[0]--;
}
```

Filed as #39. The `compact()` method itself is fine — `liveInserts.put()` on a
LinkedHashMap correctly keeps only the latest InsertRecord per handle. The bug
is in the liveness *scan* that decides which pages are sparse enough to compact.
Pages full of stale re-inserts look healthy when they're mostly dead.

No existing test covers this because none of the compaction tests re-insert the
same factHandleId across pages. The scenario only arises with object updates —
which, until today, we hadn't thought about in the context of compaction
liveness.

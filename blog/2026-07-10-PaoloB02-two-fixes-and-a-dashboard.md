---
layout: post
title: "Two Fixes and a Dashboard"
date: 2026-07-10
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, chronicle, thread-safety, project-management]
---

## Two Fixes and a Dashboard

The previous session filed #39 — a liveness scan bug where re-inserting the same
factHandleId across pages leaves the old page looking alive. This session started
with project housekeeping and ended with both #39 and #33 closed.

## Wiring up the project board

The issues created in the last session existed in the repo but weren't visible on
the kiegroup project board. We added #33–#39 as sub-issues of the upstream epic
`apache/incubator-kie-drools#6683`, then pulled #31 and #32 back out — analysis
documents, not work items.

Priority and size came from issue #31's "Recommended Next Steps" table. I pushed
back on the initial estimates — #38 (unified durability) was pegged at 6 weeks,
which assumed building a new system rather than doing less than what the journal
already does. SNAPSHOT mode is simpler than JOURNAL mode: no ModifyRecord
tracking, no compaction, just "deserialize the latest snapshot." We settled on
1.5 weeks.

The full board: 3.5 weeks of estimated work across 7 issues.

## Fixing #39 — the one-line fix

Classic TDD cycle. The test reproduces the exact scenario from the issue:

```java
storage.insert(1L, "original");
storage.safepoint(0);              // page "0": [insert(1)]
storage.insert(1L, "updated");
storage.safepoint(1);              // page "1": [insert(1)]

Map<String, long[]> liveness = CompactionCoordinator.scanLiveness(storage);

assertThat(liveness.get("0")[0]).isEqualTo(0L); // old insert is dead
assertThat(liveness.get("1")[0]).isEqualTo(1L); // new insert is live
```

The fix uses the return value of `Map.put()` — it already gives us the previous
value:

```java
String previousPage = factToPage.put(insert.factHandleId(), pageId);
if (previousPage != null) {
    liveness.get(previousPage)[0]--;
}
```

PR #40, merged.

## The restore question that led somewhere

Before starting #33, I asked whether the restore engine scans the journal twice.
It does — phase 0 builds the compaction page index, phase 1 replays records. But
if phase 0 already sees every InsertRecord, it could also build a
`Map<Long, String>` of factHandleId to canonical pageId. Phase 1 would then skip
non-canonical inserts entirely — one deserialization per fact instead of N.

I parked it. Optimizing restore is separate work. But it reframes how we think
about the two-pass design: the first pass is a cheap opportunity to pre-compute
what the second pass should ignore.

## Fixing #33 — Chronicle thread safety

The issue sounded heavy: "thread safety for concurrent compaction." Claude dug
into Chronicle Queue's current source on GitHub and confirmed that
`acquireAppender()` is backed by a `CleaningThreadLocal<ExcerptAppender>` — each
thread gets its own cached appender. Chronicle's internal write lock serializes
queue access. No external synchronization needed.

The real question was about `methodWriter()`. It creates a dynamically-compiled
proxy via Chronicle Wire — and it creates a *new* one every call. Calling it per
record in a `writeMergedPage` loop would mean N proxy creations for N records.

I wanted a clean separation: the session thread uses a cached writer stored as a
field (`sessionWriter`), created once at construction. The compaction thread
creates a fresh writer per operation and passes it through the call chain. One
proxy per compaction operation, not per record.

The internal API reflects this. Public methods like `insert()` and `safepoint()`
use `sessionWriter` directly. Internal methods like `writeRecord()` take a writer
as a parameter so callers control the lifecycle. `writeMergedPage` creates one
writer, passes it to every `writeRecord` call in the loop.

```java
private final ChronicleWriteOps sessionWriter;  // cached, session thread

@Override
public void writeMergedPage(final String pageId, final List<JournalRecord> records) {
    final ChronicleWriteOps w = newWriter();     // fresh, compaction thread
    for (final JournalRecord record : records) {
        writeRecord(w, pageId, record);
        lastWrittenPosition = appender().lastIndexAppended();
    }
}
```

A side benefit: we removed `maybeRoll()` from `compactionPrepare` and
`compactionCommit`. In the original single-threaded code this was harmless, but
with two threads the compaction thread would have mutated the session thread's
page-tracking state. Silent race condition prevented.

PR #41, merged.

## Two gotchas for the record

Both Chronicle Queue findings went into IDEAS.md under `# Gotchas`:

1. `acquireAppender()` is thread-local — don't add external synchronization
2. `methodWriter()` creates a new proxy per call — cache it yourself

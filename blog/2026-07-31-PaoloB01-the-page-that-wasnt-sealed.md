---
layout: post
title: "The Page That Wasn't Sealed"
date: 2026-07-31
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
---

Issue #36 had been sitting there since the analysis sweep three weeks ago: "Test
concurrent compaction on distinct page pairs." The spec promises it. The sequential
test proves two rounds work back-to-back. But nobody had run two `compact()` calls
in parallel on disjoint page sets to see if they actually survive restore.

I expected a test-only session. Write the test, watch it pass, close the issue.

## The in-memory test wrote itself

Two sets of four pages, each with one surviving fact and three retracted. Two threads,
a `CountDownLatch` to synchronise the start, `CompactionCoordinator.onDemand(storage).compact()`
on disjoint page sets. Seal with a safepoint, restore, assert two surviving facts.

It passed immediately. `InMemoryJournalStorage` is fully `synchronized` — there's no
real concurrency to stress. The interesting test is Chronicle, where the I/O and
appender threading actually matter.

But `CompactionCoordinator` and `RestoreEngine` were both package-private. The IT
module couldn't call them. I made both public — `CompactionCoordinator.onDemand()`,
`compact()`, `RestoreEngine` and its `ScanResult`. These are legitimate APIs for
on-demand compaction; the visibility was just an oversight from when everything
lived in one module.

## Chronicle: zero facts

The Chronicle test followed the same shape — `EmbedStrategy.store()` for proper
payloads, close and reopen the storage, restore through `RestoreEngine`. First run:
deserialization error. Raw `EmbeddedPayload(new byte[]{1})` isn't a valid
Java-serialized object. Switched to `EmbedStrategy.store()`. Second run:

```
Expected size: 2 but was: 0 in: {}
```

Zero surviving facts. Not a concurrency issue — the single-compaction variant
failed the same way.

Claude traced the problem through two layers. The merged page produced by
`compact()` contained only `InsertRecord`s — no trailing `SafepointRecord`.
`RestoreEngine.scan()` buffers records and flushes them at safepoints or page
boundaries. Trailing records after the last safepoint are discarded as incomplete.
With the in-memory backend, merged page records happen to flush at the physical
page boundary when the scanner transitions to the next page. With Chronicle,
if the merged page is the last one scanned, its records are silently dropped.

The second layer was subtler. Chronicle puts `CompactionPrepareRecord` and
`CompactionCommitRecord` in the catalog queue — not in data pages. The
`MultiQueueScanner` only reads data pages. So `PageIndex.buildLivePageSet()`,
which RestoreEngine uses to determine which pages are canonical, never sees the
compaction metadata. It works with the in-memory backend because PREPARE and
COMMIT go inline on the current data page. Two backends, two completely different
compaction metadata paths, and nobody had tested the Chronicle path through
`RestoreEngine` before.

## The fix: every page gets a safepoint

A page without a safepoint is unsealed. That's by design — `RestoreEngine`
discards trailing unsealed records as crash-incomplete data. Merged pages should
follow the same rule as session pages.

```java
List<JournalRecord> mergedRecords = new ArrayList<>(liveInserts.values());
mergedRecords.add(new SafepointRecord(-1, 0L));
storage.writeMergedPage(mergedPageId, mergedRecords);
```

The `-1` sequence number marks it as synthetic — not part of the session's
safepoint counter. With this, merged pages are self-contained regardless of
where PREPARE/COMMIT records live. The Chronicle and in-memory paths converge.

All 166 tests pass. PR #54 merged, #36 closed.

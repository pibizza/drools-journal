# 0002 — Queue-per-page architecture with catalog-based page ordering

Date: 2026-07-11
Status: Accepted

## Context and Problem Statement

The current `ChronicleJournalStorage` uses a single Chronicle Queue with logical page IDs embedded as a field in every record. Compaction writes merged pages and logically retires old pages, but retired records remain in the queue permanently — there is no physical space reclamation. Chronicle Queue (OSS) has no API to delete individual records. The upcoming Aeron Archive backend (#15) is a distributed platform with remote subscribers, requiring an architecture that works without pause-the-world coordination.

## Decision Drivers

* Aeron Archive is the distributed backend — remote subscribers must read the journal without coordinated pauses
* Chronicle Queue (OSS) cannot delete individual records; the only deletion granularity is an entire queue directory
* Both backends need physical space reclamation after compaction
* The architecture should be shared across backends to avoid maintaining two fundamentally different compaction models
* Aeron Archive natively supports multiple independent recordings with `purgeRecording()` for selective deletion

## Considered Options

* **Option A** — Queue/recording-per-page with a catalog queue for page ordering
* **Option B** — Snapshot-and-swap: periodically rewrite the entire journal into a fresh queue, replacing the compaction protocol entirely
* **Option C** — Implicit directory naming to derive page ordering

## Decision Outcome

Chosen option: **Option A** — each logical page maps to its own Chronicle Queue directory (or Aeron recording). A dedicated catalog queue maintains page ordering. The existing PREPARE/COMMIT compaction protocol is retained, extended with a physical retirement step after COMMIT is sealed.

### Catalog queue

A separate queue (or Aeron recording) tracks page lifecycle events:

* **PageCreated(pageId)** — appended when a new page starts, before any data is written to it
* **PageMerged(mergedPageId, replacedPageIds[])** — appended when compaction completes (serves the COMMIT role)

The scanner reads the catalog first to build the live page index:

* `PageCreated` → append page to end of ordered list
* `PageMerged` → replace `replacedPageIds` with `mergedPageId` at the position of the first replaced page (same logic as today's `spliceIntoIndex`)

Data queues contain only domain records (insert, retract, modify, ruleMatch, safepoint). Compaction metadata (PREPARE, COMMIT) is written to the catalog queue, not to data queues.

For Aeron, the catalog recording is what remote subscribers follow to discover the current page set.

### Compaction protocol

The existing PREPARE/COMMIT protocol is retained without simplification:

1. **PREPARE** — write to catalog: compaction in progress for `replacedPageIds`
2. **WRITE** — create a new data queue for `mergedPageId`, write live InsertRecords
3. **COMMIT** — write to catalog: `mergedPageId` replaces `replacedPageIds`
4. **SEAL** — a SafepointRecord after COMMIT makes the merge effective
5. **RETIRE** — physically delete the old page queues/recordings

The protocol may be simplifiable once the Aeron backend is implemented and distributed edge cases are understood. Until then, the proven crash-safety semantics are preserved as-is.

### Positive Consequences

* Both backends share one compaction architecture — recording-per-page for Aeron, queue-directory-per-page for Chronicle
* Physical space reclamation becomes a first-class operation: delete directory (Chronicle) or `purgeRecording()` (Aeron)
* No concurrent writers on the same queue/recording — session writes to the current page, compaction writes to a new merged page. Eliminates the thread-safety complexity addressed in #33
* Incremental compaction — only sparse pages are rewritten
* The catalog queue uses the same queue mechanism as data pages — no exotic new concepts
* Remote Aeron subscribers follow the catalog recording to discover page changes

### Negative Consequences / Tradeoffs

* `ChronicleJournalStorage` becomes a multi-queue directory manager
* `ChronicleJournalScanner` must read across multiple queue directories in catalog order
* The position model (`long` index within one queue) needs rethinking to span multiple queues
* More open file handles and memory-mapped regions (one Chronicle Queue per live page)
* `JournalStorage` SPI gains page lifecycle methods and a catalog concept

## Pros and Cons of the Options

### Option A — Queue/recording-per-page with catalog queue

* ✅ Works for both backends with the same logical architecture
* ✅ Distributed-safe — no pause-the-world coordination needed
* ✅ Catalog uses the same queue mechanism as data — nothing exotic
* ✅ One writer per queue/recording — concurrency solved structurally
* ✅ Aeron's `purgeRecording()` maps directly to page retirement
* ❌ Multi-queue management adds complexity to Chronicle storage
* ❌ Cross-queue position model is more complex

### Option B — Snapshot-and-swap (checkpoint)

* ✅ Dramatically simpler — eliminates CompactionCoordinator, PREPARE/COMMIT, page index logic
* ✅ Single queue, simple position model
* ❌ Requires pause-the-world — incompatible with distributed Aeron subscribers
* ❌ Would require a fundamentally different architecture for Aeron

### Option C — Implicit directory naming for page ordering

* ✅ No separate metadata queue needed
* ❌ Cannot express merged page placement from name alone — when pages 2, 3, 4 merge into m-1, the name doesn't encode that m-1 replaces position 2
* ❌ Fragile after multiple rounds of compaction (merged pages being merged again)

## Links

* [ADR-0001](0001-decouple-storage-pages-from-safepoints.md) — physical page IDs via `JournalScanner.currentPageId()` (foundation for this decision)
* GitHub issue [#34](https://github.com/pibizza/drools-journal/issues/34) — page retirement after COMMIT
* GitHub issue [#33](https://github.com/pibizza/drools-journal/issues/33) — Chronicle thread safety (eliminated by one-writer-per-queue)
* GitHub issue [#15](https://github.com/pibizza/drools-journal/issues/15) — Aeron Archive backend epic

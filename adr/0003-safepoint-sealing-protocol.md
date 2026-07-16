# 0003 — Safepoint sealing protocol for compaction and crash recovery

Date: 2026-07-16
Status: Proposed

## Context and Problem Statement

The journal uses a four-step compaction protocol (PREPARE, WRITE, COMMIT, RETIRE) to merge sparse pages. A `CompactionCommitRecord` declares that a merged page replaces its source pages, but a crash between writing the commit and the next consistency checkpoint would leave the commit in an ambiguous state. The system needs a well-defined mechanism to determine when a compaction commit becomes effective — i.e., when it is safe to treat the merged page as canonical and to physically delete the source pages.

This decision is not about whether to compact (ADR-0002 covers the architecture), but about the specific protocol that makes compaction crash-safe: the role of `SafepointRecord` as a sealing mechanism, how it interacts with the page lifecycle, and what guarantees it provides.

## Decision Drivers

* Crash at any point in the compaction protocol must not lose data or corrupt restore
* The protocol must work identically for InMemory (testing) and Chronicle (production) backends
* No additional record types or metadata — the existing `SafepointRecord` and `CompactionCommitRecord` must be sufficient
* `SafepointRecord` already serves as the consistency checkpoint for the session's working memory; reusing it for compaction sealing avoids a second fencing mechanism

## Considered Options

* **Option A** — `SafepointRecord` as the sealing mechanism for compaction commits
* **Option B** — Explicit `CompactionSealRecord` written by the compactor after verifying the merged page
* **Option C** — Treat `CompactionCommitRecord` as immediately effective (no sealing delay)

## Decision Outcome

Chosen option: **Option A** — a `CompactionCommitRecord` becomes effective only when a subsequent `SafepointRecord` appears in the journal stream.

### Three levels of "page"

1. **Logical page (safepoint interval)** — the set of records between two consecutive `SafepointRecord`s. This is the consistency and durability boundary: records after the last safepoint are discarded on restore. A logical page may span one or more physical pages.

2. **Physical page (journal page)** — the unit of compaction. Bounded by either a safepoint or a `PageRollStrategy` threshold (size, count). Each physical page is assigned a stable ID at creation time (ADR-0001). The key invariant is one-directional: a physical page never crosses a safepoint boundary, but a safepoint interval can contain multiple physical pages (when size-based rolling splits it).
   - **InMemory:** a `Page` object in `List<Page> journal`. A safepoint or `rollPage()` closes the current page and opens a new one. Page IDs are sequential integers (`"0"`, `"1"`, `"2"`, ...).
   - **Chronicle:** a separate Chronicle Queue directory (`page-0/`, `page-1/`, ..., `page-m-{uuid}/` for merged pages). Created by `openQueue(pageDir(rootDir, pageId))`. A safepoint or roll-strategy trigger closes `activePageQueue` and opens a new directory.

3. **Chronicle segment files** — `.cq4` files within a single queue directory, managed by Chronicle Queue's own time-based roll cycle (daily by default, not configured by drools-journal). A single physical page directory can contain multiple `.cq4` files if it stays open across roll-cycle boundaries. This level is invisible to the journal layer — `MultiQueueScanner` reads all segments within a queue directory as a single stream.

Compaction operates on physical pages (level 2). Sealing operates on logical pages (level 1). Chronicle's internal segmentation (level 3) is transparent.

### Safepoints

`JournalledKieSession.fireAllRules()` appends a `SafepointRecord(sequenceNo, timestamp)` after all rules have fired and working memory is consistent. This is the sole safepoint write point. Writing a safepoint always forces a physical page roll — the current page is closed and a new page is opened. Records after the last safepoint are discarded on restore (they represent incomplete work).

### The catalog

In Chronicle, page lifecycle events are stored in a dedicated `catalog/` queue, separate from data pages. The catalog records three event types via `ChronicleCatalogWriteOps`:

- `pageCreated(int pageId)` — emitted when a new data page is opened
- `compactionPrepare(String preparingPageId, String... replacedPageIds)` — emitted at PREPARE
- `compactionCommit(String mergedPageId, String... replacedPageIds)` — emitted at COMMIT

`CatalogIndex.build(catalogQueue)` replays the catalog to derive `livePages()` — the ordered list of page IDs that should be scanned. `compactionCommit` calls `spliceIntoIndex`, which replaces `replacedPageIds` with `mergedPageId` at the position of the first replaced page.

In InMemory storage, there is no separate catalog — `CompactionPrepareRecord` and `CompactionCommitRecord` are appended inline in the data stream alongside domain records. `PageIndex.buildLivePageSet` scans the full stream and processes these records to build the same logical page index.

### The page index

`PageIndex.buildLivePageSet` (used by both `RestoreEngine` and `CompactionCoordinator`) scans the journal and builds the canonical page ordering. It maintains:

- `pageIndex` — the ordered list of live page IDs (the output)
- `pendingCommits` — `CompactionCommitRecord`s seen but not yet sealed
- `currentIntervalPages` — page IDs seen since the last safepoint

When a `CompactionCommitRecord` is encountered, it is parked in `pendingCommits`. When a `SafepointRecord` is encountered, two things happen atomically: (1) all `pendingCommits` are applied via `spliceIntoIndex`, replacing source pages with merged pages, and (2) `currentIntervalPages` are appended to `pageIndex`. Both collections are then cleared. This means a commit only takes effect when sealed by a safepoint.

### Sealing

A `CompactionCommitRecord` is "sealed" when a `SafepointRecord` appears after it in the journal stream. Until sealed:

- The commit is tentative — a crash discards it (trailing records after the last safepoint are dropped)
- Source pages remain canonical — restore uses them, not the merged page
- The merged page exists on disk but is not in the live page index

After sealing:

- The merged page is canonical — it replaces the source pages in the page index
- Source pages are retired — they are excluded from the live page set
- Source pages can be physically deleted (RETIRE step) without affecting correctness
- Even if physical deletion is interrupted, restore works correctly because `buildLivePageSet` excludes the retired pages based on the sealed commit

### The compaction protocol with sealing

```
Step 1 — PREPARE     CompactionPrepareRecord written (catalog in Chronicle, inline in InMemory)
                     Merged page ID and source page IDs recorded.
                     Source pages remain canonical.

Step 2 — WRITE       Merged page created. Live InsertRecords from source pages
                     are written to the new page. Source pages still canonical.

Step 3 — COMMIT      CompactionCommitRecord written. Still not effective — no
                     safepoint has sealed it yet.

         [time passes — session continues normal operation]

Step 4 — SEAL        Next fireAllRules() appends SafepointRecord.
                     buildLivePageSet now processes the pending commit:
                     merged page enters the page index, source pages are removed.

Step 5 — RETIRE      Source page directories/queues can be physically deleted.
                     Idempotent — deletion of already-absent pages is a no-op.
```

### Crash scenarios

| Crash point | State on restart | Restore behavior |
|---|---|---|
| After PREPARE, before WRITE | `CompactionPrepareRecord` in stream, no merged page, no commit | PREPARE ignored — source pages are canonical |
| After WRITE, before COMMIT | Merged page exists on disk but no `CompactionCommitRecord` | Merged page is orphaned (not in any index). Source pages are canonical |
| After COMMIT, before SEAL | `CompactionCommitRecord` exists but no subsequent `SafepointRecord` | Commit is unsealed — trailing records after last safepoint are discarded. Source pages remain canonical |
| After SEAL, before RETIRE | Commit is sealed, source pages still on disk | `buildLivePageSet` excludes source pages. Restore uses merged page. Source pages are dead weight on disk |
| During RETIRE | Some source pages deleted, others still on disk | Same as above — `buildLivePageSet` is authoritative, not the filesystem |

### Positive Consequences

* Single fencing mechanism — `SafepointRecord` serves both as the working memory consistency checkpoint and as the compaction commit seal
* Crash safety is structural — no two-phase commit, no write-ahead log, no filesystem rename tricks
* The protocol is backend-agnostic — InMemory and Chronicle implement the same logic with different physical representations
* Retirement is fully decoupled from correctness — it can happen immediately, lazily, or never, without affecting restore

### Negative Consequences / Tradeoffs

* Compaction commits are delayed by one safepoint — a merge is not effective until the next `fireAllRules()` call
* If the session never fires again after a compaction cycle, the commit remains unsealed indefinitely (until the session is disposed and reopened, at which point the trailing unsealed commit is discarded)
* Two full journal scans are required for restore and for each compaction cycle — one for `buildLivePageSet` (Phase 0), one for the actual replay/liveness computation (Phase 1)

## Pros and Cons of the Options

### Option A — SafepointRecord as sealing mechanism

* ✅ Reuses existing record type — no new records in the sealed hierarchy
* ✅ Crash safety falls out naturally from the existing trailing-record discard rule
* ✅ Backend-agnostic — same protocol for InMemory and Chronicle
* ✅ Retirement safety is a corollary, not an additional mechanism
* ❌ One-safepoint delay before compaction takes effect
* ❌ Requires understanding two roles of `SafepointRecord` (consistency checkpoint + compaction seal)

### Option B — Explicit CompactionSealRecord

* ✅ Makes sealing an explicit, self-documenting event
* ✅ Seal could happen independently of session activity
* ❌ New record type in the sealed hierarchy for a purely structural concern
* ❌ Every reader (restore, coordinator, scanner) must handle the new type
* ❌ Must define its own crash-safety semantics (when is the seal itself durable?)

### Option C — Immediate commit effectiveness

* ✅ Simpler — no sealing delay, fewer states to reason about
* ❌ A crash between COMMIT and the next durable flush loses the source pages if retirement has started
* ❌ Requires the COMMIT itself to be durable before retirement, which demands backend-specific fsync guarantees

## Links

* [ADR-0001](0001-decouple-storage-pages-from-safepoints.md) — physical page IDs decoupled from safepoint sequence numbers
* [ADR-0002](0002-queue-per-page-with-catalog-ordering.md) — queue-per-page architecture with catalog-based page ordering
* GitHub issue [#34](https://github.com/pibizza/drools-journal/issues/34) — page retirement after sealed COMMIT
* `PageIndex.buildLivePageSet` — implements the sealing logic (pending commits applied on safepoint)
* `RestoreEngine.scan()` — Phase 0 builds live page set, Phase 1 replays with safepoint buffering
* `CompactionCoordinator.compact()` — implements PREPARE/WRITE/COMMIT steps
* `JournalledKieSession.fireAllRules()` — sole safepoint write point

# CompactionCoordinator — Design Spec

**Epic:** epic-compaction-coordinator  
**Issue:** #12  
**Date:** 2026-05-26

---

## Problem

The journal grows unboundedly as facts are inserted and retracted. A fact inserted on page N and retracted on page M leaves a dead `InsertRecord` on page N that can never be cleaned up without compaction. Without compaction, restore time and storage cost grow with session lifetime regardless of how small the live working memory actually is.

---

## Approach

Periodic background compaction using a four-step atomic PREPARE/COMMIT protocol. The session writer never pauses. Crash safety is provided by the journal itself — the compaction protocol writes its coordination records (`CompactionPrepareRecord`, `CompactionCommitRecord`) as ordinary journal appends, which `RestoreEngine` Phase 0 already handles.

---

## Page Concept

A **page** is the sequence of records from one `SafepointRecord` (exclusive) to the next `SafepointRecord` (inclusive). Page ID = `String.valueOf(SafepointRecord.sequenceNo)`.

This is implicit — no `JournalStorage` SPI changes are needed. Page boundaries fall out naturally from safepoint writes.

**Safepoint trigger:** `JournalledKieSession.fireAllRules()` appends a `SafepointRecord` after firing completes. The session holds a `long safepointSequenceNo` counter (starts at 0, auto-increments on each write):

```java
@Override
public int fireAllRules() {
    int fired = super.fireAllRules(replayFilter);
    storage.append(new SafepointRecord(safepointSequenceNo++, System.currentTimeMillis()));
    return fired;
}
```

Each `fireAllRules()` call produces exactly one page. Pages are uniform and meaningful — one rule-firing cycle worth of mutations.

---

## Thread Safety

`InMemoryJournalStorage` is made thread-safe (synchronized methods) so the background compactor can read from it concurrently with session writes. This is a correctness fix, not a test workaround — production storage backends (Chronicle, Aeron) are already concurrent-safe.

---

## Liveness Scan

`CompactionCoordinator` runs a full journal scan on its background thread at a configurable interval (`compactionIntervalMs`, default 60 000 ms). No write-path interception — purely a periodic read.

The scan builds two structures:

- `Map<String, long[]> liveness` — `pageId → [liveCount, totalCount]`
- `Map<Long, String> factPage` — `factHandleId → pageId` (which page holds its `InsertRecord`)

Scan logic per record type:

| Record | Action |
|--------|--------|
| `SafepointRecord(seq)` | Set `currentPageId = String.valueOf(seq)`; initialise liveness entry |
| `InsertRecord(id)` | `totalCount[currentPageId]++`; `liveCount[currentPageId]++`; `factPage[id] = currentPageId` |
| `RetractRecord(id)` | `totalCount[currentPageId]++`; `liveCount[factPage[id]]--` |
| `RuleMatchRecord` | `totalCount[currentPageId]++` |
| `ModifyRecord` | `totalCount[currentPageId]++` |
| `CompactionPrepareRecord` / `CompactionCommitRecord` | Update compaction page-state map (same logic as RestoreEngine Phase 0) |

After the scan, pages where `liveCount / totalCount < 0.30` are compaction candidates. Pages involved in a PREPARE-only (no matching COMMIT) are skipped — they represent a prior crashed compaction run and their originals are still canonical.

---

## Four-Step PREPARE/COMMIT Protocol

For each group of sparse pages identified by the liveness scan:

```
Step 1 — PREPARE:  mergedPageId = "m-" + UUID.randomUUID()
                   storage.append(CompactionPrepareRecord(mergedPageId, replacedPageIds[]))

Step 2 — WRITE:    scan each source page's records
                   keep only InsertRecords whose factHandleId is still live (not retracted)
                   append each live record to storage

Step 3 — COMMIT:   storage.append(CompactionCommitRecord(mergedPageId, replacedPageIds[]))

Step 4 — RETIRE:   mark source pages as retired in coordinator's local set
                   file-backed storage: delete source page files
                   InMemoryJournalStorage: no-op (records remain but are ignored post-COMMIT)
```

The merged page content lands inline in the main journal between PREPARE and COMMIT — no separate file or side channel. `RestoreEngine` Phase 0 handles crash recovery:
- PREPARE written, no COMMIT → merged page is ignored; original pages remain canonical
- COMMIT written → merged page is canonical regardless of whether source pages were deleted

Multiple compactions within one poll cycle run sequentially on distinct page groups — simpler than concurrent within-cycle compaction and sufficient for the expected workload.

---

## RestoreEngine Phase 0

The current Phase 0 is a no-op. It must be replaced with a real scan that builds the page-state map before Phase 1 runs. The scan logic is the same as the compaction scan's PREPARE/COMMIT handling:

- `CompactionPrepareRecord(Pm, [P1, P2])` → mark Pm as in-progress
- `CompactionCommitRecord(Pm, [P1, P2])` → mark Pm as committed; P1, P2 as retired
- Phase 1 skips records whose position falls within the position range of a retired page (between its PREPARE and COMMIT positions)

This ensures that on restore after compaction, only live records from the correct (merged or original) pages are replayed.

---

## Session Lifecycle

`CompactionCoordinator` is constructed by `JournalledSessionFactory.open()` immediately after session creation and started on its background thread. `JournalledKieSession.dispose()` stops the coordinator (with a short drain timeout, e.g. 5 s) before closing storage.

Configuration via `DurableSessionOption` builder (new parameter): `withCompactionInterval(Duration)`. Default: 60 seconds. Passing `Duration.ZERO` disables background compaction entirely (useful in tests that drive compaction manually).

---

## Issue Decomposition

| Issue | Title | Depends on |
|-------|-------|-----------|
| A | Safepoint writing + thread-safe `InMemoryJournalStorage` | — |
| B | RestoreEngine Phase 0 — real compaction map | A |
| C | `CompactionCoordinator` — liveness scan | B |
| D | `CompactionCoordinator` — four-step protocol + session wiring | C |

Issue #12 is the parent. Implementation order: A → B → C → D.

---

## Acceptance Criteria

- [ ] `fireAllRules()` appends `SafepointRecord` after every invocation
- [ ] `InMemoryJournalStorage` is thread-safe
- [ ] `RestoreEngine` Phase 0 builds a real compaction page-state map
- [ ] `CompactionCoordinator` periodic rescan correctly identifies sparse pages
- [ ] Four-step protocol writes `CompactionPrepareRecord` then `CompactionCommitRecord` in correct order
- [ ] Crash after PREPARE but before COMMIT: restore uses original pages
- [ ] Crash after COMMIT: restore uses merged page regardless of source page state
- [ ] Sequential compactions within one poll cycle complete without interference
- [ ] `CompactionCoordinator` lifecycle tied to session open/dispose
- [ ] `CompactionCorrectnessTest` covers all crash scenarios above

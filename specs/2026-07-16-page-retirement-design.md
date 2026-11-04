# Page Retirement Design

**Issue:** #34 — Compaction Step 4: retire source pages after COMMIT
**Date:** 2026-07-16

---

## Problem

The compaction protocol implements PREPARE, WRITE, and COMMIT but not RETIRE.
After a `CompactionCommitRecord` is sealed (a subsequent `SafepointRecord` has
appeared), the source pages it replaced remain on disk indefinitely. Disk usage
grows without bound in long-running sessions.

Correctness is unaffected — `PageIndex.buildLivePageSet` already excludes
retired pages during restore — but the physical storage is never reclaimed.

## Approach

Retire source pages at the top of each compaction cycle in
`CompactionCoordinator.runCycle()`, piggybacking on the
`PageIndex.buildLivePageSet` scan that `scanLiveness` already performs. No
additional scan is needed.

### Alternatives considered

- **Retire inside safepoint flow:** tighter coupling, adds I/O to the
  synchronous write path.
- **Retire immediately after compactionCommit:** unsafe — the commit isn't
  sealed until the next safepoint. A crash between commit and safepoint would
  leave the merged page non-canonical but the source pages deleted.
- **Separate periodic process:** viable for the future (retirement is
  monotonic), but adds scheduling complexity with no benefit today.

## Design

### 1. API change — `JournalStorage.retirePages`

Add to `JournalStorage`:

```java
void retirePages(String[] pageIds);
```

Contract:
- Deletes the physical storage for each page ID.
- Idempotent — silently ignores page IDs that don't exist.
- Called only for pages whose retirement is sealed (caller's responsibility).

### 2. `PageIndexStatus` return type

Replace the `Set<String>` return type of `PageIndex.buildLivePageSet` with:

```java
record PageIndexStatus(Set<String> livePages, Set<String> retiredPages) {}
```

`retiredPages` collects the page IDs removed by `spliceIntoIndex` during sealed
compaction commit processing — these fall out naturally from the existing logic.

### 3. InMemory implementation

`InMemoryJournalStorage.retirePages(String[] pageIds)`:
- Build a `Set` from the page IDs for O(1) lookup.
- Remove matching entries from the `journal` list and `pageById` map.
- Skip unknown page IDs (idempotent).
- `synchronized` like all other methods.

### 4. Chronicle implementation

`ChronicleJournalStorage.retirePages(String[] pageIds)`:
- For each page ID, resolve `rootDir.resolve("page-" + pageId)`.
- Delete the directory recursively (`Files.walk`).
- Skip non-existent directories (idempotent).

No queue handles need closing — retired pages are never the active page, and the
scanner opens/closes queues on the fly.

### 5. Retirement trigger

In `CompactionCoordinator.runCycle()`:
1. `buildLivePageSet` returns `PageIndexStatus` (already happens inside
   `scanLiveness`).
2. Call `storage.retirePages(status.retiredPages())` before proceeding with
   liveness scanning.

### 6. Crash safety

The sealed `CompactionCommitRecord` is the source of truth. If a crash occurs
mid-deletion:
- Remaining source page directories are orphaned disk space.
- Restore works correctly — `buildLivePageSet` excludes them.
- The next compaction cycle retires them again (idempotent).

No additional crash-safety mechanism is needed.

## Testing strategy

TDD, red/green/refactor, one test at a time.

### Unit tests (`drools-journal-core`)

1. `buildLivePageSet` returns `PageIndexStatus` with correct `livePages` and
   empty `retiredPages` when no compaction has occurred.
2. `buildLivePageSet` returns correct `retiredPages` after a sealed compaction
   commit.
3. Unsealed commit (no safepoint after) produces no retired pages.
4. Multiple sealed compactions accumulate retired pages.
5. `InMemoryJournalStorage.retirePages` removes pages from journal and pageById.
6. `InMemoryJournalStorage.retirePages` is idempotent — unknown page IDs are
   ignored.
7. `CompactionCoordinator.runCycle` retires source pages after sealed compaction.

### Integration tests (`drools-journal-tests`, `*IT` suffix)

8. `ChronicleJournalStorage.retirePages` deletes `page-{id}/` directories from
   disk.
9. Full cycle: compact + safepoint + next cycle retires, restore still works.

## Future work

- Extract the common two-pass scan skeleton shared by `RestoreEngine.scan()` and
  `CompactionCoordinator.scanLiveness()` into a reusable harness (logged in
  IDEAS.md 2026-07-16).
- Move retirement to a separate periodic process if decoupling from the
  compaction cycle becomes beneficial.

# Design Journal — epic-compaction-coordinator

### 2026-07-13 · §Storage Backends

Queue-per-page architecture implemented for Chronicle (#42). Each logical
page maps to its own Chronicle Queue directory (`page-{id}/`); a catalog
queue (`catalog/`) tracks page ordering via `pageCreated(int)` and
compaction metadata (`CompactionPrepareRecord`, `CompactionCommitRecord`).
The scanner reads the catalog first to build the live page list via
`CatalogIndex`, then chains through data queues sequentially.

Implementation confirmed the crash analysis from ADR-0002: catalog entries
are self-sealing — no safepoint correlation needed across streams. The
PREPARE/COMMIT protocol works unchanged; compaction metadata is simply
redirected from the data stream to the catalog.

SPI change: added `JournalStorage.isEmpty()`. The previous
`latestPosition() >= 0` check was incorrect for multi-queue — when the
active page queue is empty on reopen (safepoint triggered a roll), it
returned -1 even though earlier pages contain data.

Contract tests for mid-stream resume (`scan(fromPosition)` with non-zero
position) and compaction-records-in-scan were removed — no real backend
supports these. Mid-stream resume via cumulative record index is parked
as an idea for future implementation if needed.

### 2026-07-11 · §Storage Backends

Architecture decision: move from single-queue-with-embedded-page-IDs to
queue-per-page (Chronicle) / recording-per-page (Aeron). Each logical page
maps to its own Chronicle Queue directory or Aeron recording. A dedicated
catalog queue tracks page ordering via `PageCreated` and `PageMerged` entries;
the scanner reads the catalog first to build the live page index, then reads
data queues in that order. Physical page retirement becomes a first-class
operation: delete directory (Chronicle) or `purgeRecording()` (Aeron).

Snapshot-and-swap (rewrite entire journal into a fresh queue periodically) was
considered and rejected: it requires pause-the-world coordination incompatible
with Aeron's distributed model where remote subscribers replay recordings.

The existing PREPARE/COMMIT compaction protocol is retained — it is inherently
distributed-safe and maps to both backends. Simplification may follow once the
Aeron backend validates the design. See ADR-0002.

### 2026-07-05 · §Storage Backends

`ChronicleJournalStorage` implemented. Logical page IDs are embedded as the
first argument of every `ChronicleWriteOps` method call and stored verbatim in
the Chronicle Wire payload — they are entirely independent of Chronicle's own
file roll cycle (`RollCycle`). Chronicle is used as a flat append log; our page
concept is purely a data-layer construct. The scanner reads page IDs back via
`ChronicleRecordHandler`, which implements `ChronicleWriteOps` and captures the
`pageId` argument on each dispatch.

`ChronicleJournalStorage` carries no internal synchronization — by design,
identical to the plan's spec. This creates a known concurrency gap: the
compaction coordinator background thread and the session write thread both write
to the same `ExcerptAppender`, which Chronicle documents as not thread-safe.
External coordination (e.g. a `ReentrantLock` shared between session writes and
the compactor's write phase) is required before the compaction path is wired
up for the Chronicle backend. Logged as a follow-up; not blocking Phase 6 close
since `CompactionCoordinator` is not yet integrated with `ChronicleJournalStorage`.

### 2026-07-01 · §Storage Backends

`chronicle-queue 2026.4` (Apache 2.0) added as the OSS dependency for the
Chronicle backend module. Chronicle migrated from the `5.27ea*` early-access
series to a year-based release scheme during 2025; `2026.4` is the current
stable release and retains the same `MethodWriter`/`MethodReader` API surface
the backend plan was designed against. Version is pinned centrally in the
parent `dependencyManagement`; the `drools-journal-chronicle` module activates
it without a version override. This is the opening step of Phase 6.

### 2026-06-17 · §Compaction Protocol (CompactionCoordinator)

`CompactionCoordinator` promoted from static utility to lifecycle-managed class.
Constructor takes `(JournalStorage, Duration)`; `Duration.ZERO` disables the
background thread — the safe state for tests that drive `compact()` directly.
`start()` creates a single daemon thread named `drools-journal-compactor`;
`stop()` shuts it down with a 5-second grace period before forcing shutdown.

Session wiring: `JournalledSessionFactory.open()` gains a `Duration` overload;
the existing one-arg overload delegates with `DEFAULT_INTERVAL = 60s`. The
coordinator is started at open time and stopped in
`JournalledKieSession.dispose()` — callers are not involved in the
coordinator's lifecycle.

Task D3 complete. Issue #12 (CompactionCoordinator) fully implemented.

### 2026-05-26 · §Compaction Protocol (CompactionCoordinator)

Safepoint trigger settled: `JournalledKieSession.fireAllRules()` appends a
`SafepointRecord(sequenceNo++, currentTimeMillis())` after firing completes.
This is the sole safepoint write point — no timer, no caller-driven API.
Working memory is fully consistent at that point and it's already the flush
trigger RestoreEngine relies on.

Page concept settled: a page is the sequence of records between two consecutive
`SafepointRecord`s. Page ID = `String.valueOf(SafepointRecord.sequenceNo)`.
No `JournalStorage` SPI changes required — page boundaries fall out of the
safepoint mechanism for free.

Liveness tracking strategy: periodic full journal rescan (not incremental
decorator interception). `CompactionCoordinator` rescans on its background
thread at a configurable interval (default 60 s). No write-path interception;
the coordinator stays stateless between polls.

`InMemoryJournalStorage` made thread-safe (all methods `synchronized`;
`scan()` returns `List.copyOf()` snapshot) to support concurrent background
reads by the compactor.

Issue A (safepoint + thread-safety) complete. End-to-end restore tests added
to `JournalledKieSessionRestoreTest` — Session 1 inserts/fires, Session 2
opens on same storage and verifies working memory state and replay suppression.


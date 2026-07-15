# drools-journal — Design Document

Upstream tracking issue: https://github.com/apache/incubator-kie-drools/issues/6682

## Purpose

`drools-journal` provides write-optimised, append-only session durability for Drools,
replacing the full-object-serialisation model of `drools-reliability`. Every session
mutation is captured as a small, typed `JournalRecord`; restore replays those records
in a single sequential pass with no random access and no separate index file.

---

## Module Structure

```
drools-journal/
  drools-journal-api/          ← JournalStorage SPI, record types, session config, strategy SPIs
  drools-journal-core/         ← session hooks, restore engine, compaction coordinator
  drools-journal-chronicle/    ← Chronicle Queue OSS implementation (single-host default)
  drools-journal-aeron/        ← Aeron Archive OSS implementation (distributed, SBE encoded)
  drools-journal-tests/        ← InMemoryJournalStorage + abstract SPI contract tests
```

Dependency chain: `core` → `api`; `chronicle` → `core`; `aeron` → `core`;
`tests` → all others (test scope).

---

## Record Hierarchy

```
JournalRecord (sealed interface)
├── InsertRecord            — fact insert (logical or non-logical); payload is EmbeddedPayload | ExternalRef
├── RetractRecord           — fact retracted by factHandleId
├── ModifyRecord            — targeted property update via lambdaClassRef + params (compiler rewrite)
├── RuleMatchRecord         — rule activation fired; carries session-scoped auto-incrementing id, packageName + ruleName
├── SafepointRecord         — consistent checkpoint; always forces a page roll
├── CompactionPrepareRecord — begins four-step compaction protocol
└── CompactionCommitRecord  — commits merged page; source pages become safe to delete

Payload (sealed interface, used by InsertRecord)
├── EmbeddedPayload  — serialised bytes inline
└── ExternalRef      — typeName + dbKey pointing to external store
```

---

## SPI Contracts

### JournalStorage / JournalScanner

The write side is semantic — callers describe what happened, not how it is stored.
`JournalRecord` types are an internal concern of each implementation.

```java
interface JournalStorage {
    // Semantic write API
    long insert(long factHandleId, Payload payload);
    long insertLogical(long factHandleId, Payload payload, long justifyingRuleMatchId);
    long retract(long factHandleId);
    long modify(long factHandleId, String lambdaClassRef, byte[] params);
    long ruleMatch(long id, String packageName, String ruleName, long[] factHandleIds);
    long compactionPrepare(String preparingPageId, String[] replacedPageIds);
    long compactionCommit(String mergedPageId, String[] replacedPageIds);
    void safepoint();                       // seals a page; sequence number is auto-managed

    // Read API
    JournalScanner scan(long fromPosition); // sequential scan for restore; exposes currentPageId()
    long latestPosition();
    void writeMergedPage(String pageId, List<JournalRecord> records);
    void close();                           // idempotent
}
```

### PageRollStrategy

Decides when to roll to a new physical page. Called after every append. Built-in
factories in `PageRollStrategies`: `SafepointOnly`, `SizeThreshold(n)`,
`CountThreshold(n)`, `Composite(strategies...)`. `SafepointRecord` always forces ROLL
regardless of strategy (enforced in core, not in the SPI).

### ObjectStorageStrategy

Decides per-fact whether to embed bytes inline (`EMBED`) or write an `ExternalRef`
(`EXTERNAL_REF`). Session-level default via `ObjectStorageMode` enum; overridable
per-object via `ObjectStorageStrategy` SPI.

### DurableSessionOption

`KieSessionConfiguration` option with fluent builder:

```java
DurableSessionOption.newSession()
    .withObjectStorage(ObjectStorageMode.EMBED)
    .withPageRollStrategy(PageRollStrategies.safepointOnly())
    .withJournalStorage(ChronicleJournalStorage.atPath("/var/drools/journal"))
```

---

## Session Lifecycle Hooks (core)

### Session wiring — JournalledRuntimeComponentFactory

`JournalledRuntimeComponentFactory` (SPI-registered, priority 1) intercepts
`createStatefulSession()`, `getAgendaFactory()`, and `getEntryPointFactory()`. When
`JournalStorage` is present in the `Environment`, it produces a `JournalledKieSession`
(extends `StatefulKnowledgeSessionImpl`) and wires in `JournalledAgendaFactory` and
`JournalledEntryPointFactory`. Context is passed via `Environment` under a fixed key —
avoiding KieSessionConfiguration changes that would create a wrong-direction dependency
(core → journal-api).

Public entry point: `JournalledSessionFactory.open(kbase, storage)` — the same call
works whether the journal is empty (fresh session) or contains prior state (restore).
A `Duration` overload controls the compaction interval (`Duration.ZERO` disables it).
`JournalStorage` lifecycle is owned by the caller; `dispose()` does not close it.

### JournalledNamedEntryPoint

Extends `NamedEntryPoint`. Overrides `createObjectStore()` to return a
`JournalledObjectStore`, and `updateHandle()` to prevent spurious `objectInserted` /
`objectDeleted` events during update (uses `super.removeHandle()` + `super.addHandle()`
so that `JournallingRuntimeEventListener` remains the sole write point for updates).

### JournalledObjectStore

Extends `IdentityObjectStore`. Overrides `addHandle()` and `removeHandle()` — these
events feed `JournallingRuntimeEventListener` via the `RuleRuntimeEventListener` path.

### JournallingRuntimeEventListener

Implements `RuleRuntimeEventListener`. Installed on the session after construction by
`JournalledSessionFactory`; during restore it is simply not installed, allowing Rete
re-propagation without journal side-effects. Handles `objectInserted` → `InsertRecord`,
`objectDeleted` → `RetractRecord`, `objectUpdated` → full `InsertRecord` snapshot.
Manages `currentActivationId` (set/cleared around each rule firing) for logical inserts.
Serialisation delegated to `JournalPayloadBuilder`, which also provides
`deserialize(Payload) → Object` for restore.

### JournalledAgenda

Extends `DefaultAgenda`. After each rule firing, appends `RuleMatchRecord` with an
auto-incrementing session-scoped `long` ID (`packageName` + `ruleName` + `factHandleIds`).
Resolves the `JournallingRuntimeEventListener` lazily on first event and calls
`setCurrentActivationId()` / `clearCurrentActivationId()` around each activation.

### ModifyLambdaRegistry

Session-scoped registry of `ModifyLambda` (`void apply(Object fact, Object[] params)`).
Populated at KieBase build time. `lookup()` throws `JournalSchemaEvolutionException`
on unresolved `lambdaClassRef`.

---

## Modify-as-Delta via DRL Precompiler (Phase 5)

`JournalDrlPrecompiler` transforms DRL text before `KieHelper.build()`, rewriting
`modify` blocks to emit `ModifyRecord`s instead of full object snapshots. Opt-in —
if the user skips the precompiler, the full-snapshot fallback works unchanged.

```java
ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
String rewritten = JournalDrlPrecompiler.rewrite(originalDrl, registry, classLoader);
KieBase kbase = new KieHelper().addContent(rewritten, ResourceType.DRL).build();
JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, registry, Duration.ZERO);
```

The precompiler parses the DRL via `DrlParser` (rule envelope) and `MvelParser`
(consequence body AST). For each `ModifyStatement`, it:
1. Extracts setter names and argument expressions from the AST
2. Creates a `MethodHandleModifyLambda` and registers it with a deterministic name
   (`Rule_{ruleName}_modify_{index}`)
3. Inserts a `journal.stageModify(...)` call before the modify block in the AST
4. Serializes the modified AST back via `PrintUtil.printNode()`

The `JournallingRuntimeEventListener` is set as a DRL global (`journal`).
`stageModify()` stages the lambda ref + params; `objectUpdated()` consumes
the staged data and writes a `ModifyRecord` instead of a full `InsertRecord`.

On restore, `RestoreEngine` looks up the lambda via `ModifyLambdaRegistry` and
applies it to the surviving fact. Crash recovery: rerun the precompiler on the
same DRL — deterministic naming ensures identical registry population.

---

## Storage Backends

### Chronicle Queue (drools-journal-chronicle)

One Chronicle Queue instance per session directory. `ExcerptAppender` for writes,
`ExcerptTailer` for reads. Chronicle roll cycle maps to physical page boundaries;
`PageRollStrategy` controls when rolls are triggered. Records serialised via
`BytesMarshallable`. Multiple readers can scan concurrently.

| Metric | Value |
|--------|-------|
| Write latency | Sub-µs to 40 µs p99 |
| Throughput | 80M+ records/sec |
| Cross-machine | No (commercial only) |
| Best for | Single host, lowest latency |

### Aeron Archive (drools-journal-aeron)

Embedded `MediaDriver` in the writer JVM. `append()` publishes to a live Aeron
recording; `scan(fromPosition)` replays via Archive client by recording ID.
Records encoded/decoded with SBE (schema at `src/main/resources/sbe/journal-records.xml`
covering all 7 record types).

| Metric | Value |
|--------|-------|
| Write latency | ~29 µs round-trip |
| Throughput | 20M+ records/sec |
| Cross-machine | Yes — native UDP |
| Best for | Multi-host cluster |

### Contract Testing

All `JournalStorage` implementations are verified by extending
`JournalStorageContractTest` (in `drools-journal-tests`). The abstract class
covers position monotonicity, scan coverage, content fidelity, and close
idempotency. Chronicle and Aeron will add `ChronicleStorageContractTest` and
`AeronStorageContractTest` respectively in Phases 6–7.

---

## Restore Protocol (RestoreEngine)

Sequential single-pass scan — no random access, no separate index file:

| Phase | Action |
|-------|--------|
| 0 | Scan for `CompactionPrepareRecord` / `CompactionCommitRecord`; build page-state map: PREPARE-only → use original pages; PREPARE+COMMIT → use merged page |
| 1 | Sequential scan with safepoint buffering: records accumulated in a pending buffer, flushed only on `SafepointRecord`; trailing records after the last safepoint are discarded. Applies `InsertRecord`, `RetractRecord`, `ModifyRecord` (registry lookup; throws `JournalSchemaEvolutionException` on unresolved `lambdaClassRef`; lambda application deferred to Phase 5), `RuleMatchRecord` (builds `firedMatches` indexed by ID), `SafepointRecord` |
| 1.5 | Bulk Rete re-propagation of surviving facts; `JournalledSessionFactory` inserts facts, builds `Map<Long, FactHandle> oldToNew`, translates `RuleMatchRecord` handle IDs to new session IDs, constructs `ReplayFilter` |
| 2 | TMS wiring: logical facts restored via `TruthMaintenanceSystem.insertPositive()` (establishes JUSTIFIED `EqualityKey`); `ReplayFilter.matchCache` captures live `InternalMatch` objects for TMS links |
| 3 | Install `ReplayFilter` (suppresses already-fired `(packageName, ruleName, long[] factHandleIds)` tuples) on agenda via `JournalledKieSession.fireAllRules()` override |

`RestoreEngine` is a pure data collector: `scan()` returns `ScanResult(survivingFacts, firedMatches)`.
All orchestration lives in `JournalledSessionFactory.open()`, decomposed into
`restore()`, `insertNonLogicalFacts()`, `buildReplayFilter()`, `wireTms()`.

`ReplayFilter` is keyed on `(packageName, ruleName, long[] factHandleIds)` via a private
`MatchKey` record using `Arrays.equals` / `Arrays.hashCode` for correct array equality.

**Rollback:** records after the last `SafepointRecord` on the final page are discarded
on incomplete-page crash. Since safepoints always force a page roll, worst-case data
loss is one page since the last safepoint.

---

## Compaction Protocol (CompactionCoordinator)

**Safepoint trigger:** `JournalledKieSession.fireAllRules()` appends
`SafepointRecord(sequenceNo++, currentTimeMillis())` after firing completes —
working memory is fully consistent at that point. This is the sole safepoint
write point.

**Page definition:** a physical storage unit bounded by either a safepoint or
a `PageRollStrategy` threshold (size, count). Each page is assigned a stable
ID by the storage implementation at **creation time** — not derived from
`SafepointRecord.sequenceNo`. `SafepointRecord` always forces a page roll, so
a physical page never crosses a safepoint boundary; all records on a page
belong to exactly one safepoint interval. `JournalScanner.currentPageId()`
exposes the current physical page ID to all readers (coordinator, restore
engine). `SafepointRecord.sequenceNo` is a pure consistency counter, not a
page ID. See ADR-0001.

**Liveness tracking:** `CompactionCoordinator` performs a periodic full journal
rescan (not write-path interception) on a daemon background thread
(`drools-journal-compactor`) at a configurable interval (default 60 s).
The coordinator is stateless between polls. Tracks `pageId → (liveCount,
totalCount)`. When `liveCount / totalCount < 0.30`, the page becomes a
compaction candidate.

**Session lifecycle:** `JournalledSessionFactory.open(kbase, storage, Duration)`
creates and starts the coordinator; the one-arg overload delegates with
`DEFAULT_INTERVAL = 60s`. `Duration.ZERO` disables the background thread — the
safe state for tests that drive `compact()` directly. `JournalledKieSession
.dispose()` stops the coordinator (5-second grace period, then forced shutdown).
`JournalStorage` lifecycle remains the caller's responsibility.

Four-step atomic protocol — writer never pauses:

```
Step 1 — PREPARE:  append CompactionPrepareRecord { Pm_id, replacedPageIds: [P1, P2] }
Step 2 — WRITE:    read P1 + P2 → write merged page Pm (live InsertRecords only)
Step 3 — COMMIT:   append CompactionCommitRecord { Pm_id, replacedPageIds: [P1, P2] }
Step 4 — RETIRE:   lazily delete P1, P2 (correctness does not depend on this completing)
```

**Crash recovery:** PREPARE-only → ignore Pm, restore from originals.
COMMIT present → Pm is canonical regardless of whether P1/P2 are still on disk.
Concurrent compactions on distinct page pairs are supported.

---

## Key Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Journal vs database | Journal | Sequential writes, modify-as-delta, no read-modify-write |
| New module vs extend StorageManager | New module | `Storage<K,V>` is wrong abstraction for append-only |
| Modify capture | Compiler-driven + full-snapshot fallback | No proxy overhead; no API change |
| Page boundary | Pluggable strategy; safepoints always force roll | Flexible + consistent rollback semantics |
| Page ID scheme | Assigned by storage at page-creation time; exposed via `JournalScanner.currentPageId()` | Supports size-based rolling without coupling page IDs to safepoint sequence (ADR-0001) |
| Object storage | Session default + per-object strategy override | Flexibility without domain model pollution |
| Compaction threading | Separate thread/JVM, fully non-blocking | Writer never pauses |
| Page index | Derived on restore by sequential scan using physical page IDs | No ongoing write overhead |
| TMS reconstruction | Surrogate ID on RuleMatchRecord | Compact, causal, no side effects |
| Storage library | Chronicle Queue (default), Aeron Archive (distributed) | Latency + Apache 2.0 |
| Compaction atomicity | Four-step PREPARE/COMMIT via journal appends | No filesystem rename tricks; works for both backends |

---

## Implementation Status

| Phase | Scope | Status |
|-------|-------|--------|
| 1 — Maven setup | Parent + 5 child POMs | ✅ Complete |
| 2 — API | JournalRecord hierarchy, all SPIs, DurableSessionOption | ✅ Complete |
| 3 — Test infra | InMemoryJournalStorage/Scanner, SPI contract tests | ✅ Complete |
| 4a — Core (runtime hooks) | JournalledNamedEntryPoint, JournalledAgenda, JournallingRuntimeEventListener, JournalledSessionFactory | ✅ Complete |
| 4b — Core (restore) | RestoreEngine, ReplayFilter, TMS wiring | ✅ Complete |
| 4c — Core (compaction) | CompactionCoordinator | ✅ Complete |
| 5 — DRL precompiler | DRL `modify` → `ModifyRecord` via standalone `JournalDrlPrecompiler` | ✅ Complete |
| 6 — Chronicle backend | ChronicleJournalStorage, multi-queue scanner, catalog index | ✅ Complete |
| 7 — Aeron backend | SBE schema + AeronJournalStorage/Scanner | ⬜ Pending |
| 8 — Integration tests | End-to-end durability, TMS, compaction, schema evolution | ⬜ Pending |

---

## Out of Scope (v1)

- Schema evolution / migration tooling for renamed `lambdaClassRef`
- Encryption of journal pages
- Session replication / active-active clustering
- Streaming restore
- Multi-entry-point cross-ordering guarantees

# Drools Journal — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build `drools-journal`, a new Drools module providing write-optimized, append-only session durability via typed journal records, replacing the full-object-serialization model of `drools-reliability`.

**Architecture:** A multi-module Maven project adds journal hooks into the Drools session lifecycle (entry point, agenda). A sealed `JournalRecord` hierarchy captures every session mutation as a small, typed record. Restore replays records sequentially in a single pass; compaction runs non-blocking in a separate thread or JVM using a four-step atomic PREPARE/COMMIT protocol.

**Tech Stack:** Java 17+ (sealed interfaces, records), Drools core internals (`NamedEntryPoint`, `DefaultAgenda`), Chronicle Queue OSS (default storage), Aeron Archive OSS (distributed storage), JUnit 5, Maven multi-module.

**Spec:** https://gist.github.com/mdproctor/a9cbc49fa6ef9ca73c824ab1f59402d4

---

## Dependency Graph

```
Phase 1 (Maven setup)
  └─► Phase 2 (API)
        └─► Phase 3 (Test infra)
              └─► Phase 4 (Core)
                    ├─► Phase 6 (Chronicle) ──┐
                    └─► Phase 7 (Aeron)     ──┤
                                              └─► Phase 8 (Integration tests)

Phase 5 (Compiler rewrite) — DEFERRED, upstream Drools patch
```

Phases 6 and 7 are independent of each other and can be worked in parallel.

Phase 5 is deferred — see the note in that section for rationale.

---

## File Map

### `drools-journal-api`
```
src/main/java/org/drools/journal/api/
  JournalRecord.java               ← sealed interface (root of hierarchy)
  InsertRecord.java
  RetractRecord.java
  ModifyRecord.java
  RuleMatchRecord.java
  SafepointRecord.java
  CompactionPrepareRecord.java
  CompactionCommitRecord.java
  EmbeddedPayload.java             ← sealed payload variant (inline bytes)
  ExternalRef.java                 ← sealed payload variant (typeName + dbKey)
  JournalStorage.java              ← SPI: append / scan / latestPosition / close
  JournalScanner.java              ← SPI: Iterator<JournalRecord> + position()
  PageRollStrategy.java            ← SPI: decide(context) → RollDecision
  PageRollStrategies.java          ← built-in factories (SafepointOnly, SizeThreshold, CountThreshold, Composite)
  RollDecision.java                ← enum: CONTINUE | ROLL
  ObjectStorageStrategy.java       ← SPI: decide(fact, handle) → StorageDecision
  StorageDecision.java             ← enum: EMBED | EXTERNAL_REF
  ObjectStorageMode.java           ← enum: session-level default
  DurableSessionOption.java        ← KieSessionConfiguration option + fluent builder
```

### `drools-journal-core`
```
src/main/java/org/drools/journal/core/
  ModifyLambda.java                ← functional interface: apply(Object fact, Object[] params)
  ModifyLambdaRegistry.java        ← session-scoped: register + lookup by lambdaClassRef
  JournalSchemaEvolutionException.java
  JournalledNamedEntryPoint.java   ← extends NamedEntryPoint; intercepts insert/retract/update
  JournalledAgenda.java            ← extends DefaultAgenda; appends RuleMatchRecord after each firing
  ReplayFilter.java                ← AgendaFilter: suppresses already-fired (ruleName, factHandleIds[]) tuples
  RestoreEngine.java               ← 4-phase restore: compaction map → page scan → bulk Rete → TMS wiring → filter install
  CompactionCoordinator.java       ← sparseness tracking + four-step PREPARE/COMMIT protocol
```

### `drools-journal-chronicle`
```
src/main/java/org/drools/journal/chronicle/
  ChronicleJournalStorage.java     ← JournalStorage over Chronicle Queue
  ChronicleJournalScanner.java     ← JournalScanner wrapping ExcerptTailer
```

### `drools-journal-aeron`
```
src/main/java/org/drools/journal/aeron/
  AeronJournalStorage.java         ← JournalStorage over Aeron Archive
  AeronJournalScanner.java         ← JournalScanner wrapping Aeron Archive replay
src/main/resources/sbe/
  journal-records.xml              ← SBE message schema for all 7 record types
```

### `drools-journal-tests`
```
src/main/java/org/drools/journal/tests/
  InMemoryJournalStorage.java      ← non-durable in-process JournalStorage for testing
  InMemoryJournalScanner.java

src/test/java/org/drools/journal/tests/
  JournalStorageContractTest.java  ← abstract parameterized base for SPI conformance
  InMemoryStorageContractTest.java
  ChronicleStorageContractTest.java
  AeronStorageContractTest.java
  DurableSessionTest.java          ← insert / retract / modify / restore
  LogicalInsertRestoreTest.java    ← TMS dependency reconstruction
  ModifyLambdaRestoreTest.java     ← ModifyRecord replay
  CompactionCorrectnessTest.java   ← 4-step protocol + crash scenarios
  SafepointRollbackTest.java       ← rollback to last safepoint
  SchemaEvolutionTest.java         ← JournalSchemaEvolutionException on missing lambdaClassRef
```

---

## Phase 1 — Maven Multi-Module Setup

### Task 1: Maven project skeleton

**Scope:** Create the Maven multi-module parent POM and one child POM per module. Wire dependency relationships: `core` → `api`; `chronicle` → `core`; `aeron` → `core`; `tests` → all others (test scope). Declare Chronicle Queue OSS and Aeron OSS in dependency management. Register SBE Maven plugin in `aeron` module.

**Files — Create:**
- `drools-journal/pom.xml` (parent)
- `drools-journal-api/pom.xml`
- `drools-journal-core/pom.xml`
- `drools-journal-chronicle/pom.xml`
- `drools-journal-aeron/pom.xml`
- `drools-journal-tests/pom.xml`

**Done when:** `mvn validate` passes across all modules with no resolution errors.

---

## Phase 2 — drools-journal-api

All tasks in this phase produce interfaces and data types only — no implementation logic. Every type produced here is immutable and has no dependency on Drools internals.

### Task 2: JournalRecord sealed hierarchy

**Scope:** Define the sealed interface `JournalRecord` and all 7 record implementations as Java `record` classes. Define `EmbeddedPayload` and `ExternalRef` as a sealed alternative pair for `InsertRecord`'s payload field. Field types exactly match spec §3.

**Files — Create:** `JournalRecord.java`, `InsertRecord.java`, `RetractRecord.java`, `ModifyRecord.java`, `RuleMatchRecord.java`, `SafepointRecord.java`, `CompactionPrepareRecord.java`, `CompactionCommitRecord.java`, `EmbeddedPayload.java`, `ExternalRef.java`

**Done when:** All record types compile; each carries only the fields listed in spec §3; `instanceof` pattern matching works across the sealed hierarchy.

### Task 3: JournalStorage SPI

**Scope:** Define `JournalStorage` and `JournalScanner` interfaces as specified in §6.1. Position semantics: a `long` that is monotonically increasing and usable as `fromPosition` in subsequent `scan()` calls.

**Files — Create:** `JournalStorage.java`, `JournalScanner.java`

**Done when:** Interfaces compile with correct signatures; Javadoc on `position()` describes the monotonic contract.

### Task 4: PageRollStrategy SPI

**Scope:** Define `PageRollStrategy` and `RollDecision`. Implement all four built-in strategies in `PageRollStrategies` factory class: `SafepointOnly`, `SizeThreshold(n)`, `CountThreshold(n)`, `Composite(strategies...)`. The enforcement that `SafepointRecord` always forces ROLL lives in core (Task 9), not here.

**Files — Create:** `PageRollStrategy.java`, `RollDecision.java`, `PageRollStrategies.java`

**Done when:** Each strategy returns correct `RollDecision` for its trigger condition; `Composite` returns ROLL when any constituent does.

### Task 5: ObjectStorageStrategy SPI

**Scope:** Define `ObjectStorageStrategy`, `StorageDecision` (EMBED | EXTERNAL_REF), and `ObjectStorageMode` (session-level default enum).

**Files — Create:** `ObjectStorageStrategy.java`, `StorageDecision.java`, `ObjectStorageMode.java`

**Done when:** Interfaces compile; `ObjectStorageMode` values correspond to `StorageDecision` outcomes.

### Task 6: DurableSessionOption

**Scope:** Implement `DurableSessionOption` as a `KieSessionConfiguration` option following the pattern used in `drools-reliability`. Fluent builder with three setters: `withObjectStorage(ObjectStorageMode)`, `withPageRollStrategy(PageRollStrategy)`, `withJournalStorage(JournalStorage)`. Also static factory `newSession()`.

**Files — Create:** `DurableSessionOption.java`
**Files — Study:** `drools-reliability`'s `PersistenceContextManagerOption` (or equivalent) as a pattern reference.

**Done when:** `DurableSessionOption` can be passed to `KieSessionConfiguration` and its values retrieved; all three configuration properties accessible.

---

## Phase 3 — Test Infrastructure

### Task 7: InMemoryJournalStorage

**Scope:** Implement `InMemoryJournalStorage` and `InMemoryJournalScanner` in `drools-journal-tests`. Non-durable, in-process, backed by an in-memory list. No Chronicle or Aeron on the classpath. Must implement the full `JournalStorage` / `JournalScanner` SPI including position semantics.

**Files — Create:** `InMemoryJournalStorage.java`, `InMemoryJournalScanner.java`

**Done when:** Append, scan from arbitrary position, and latestPosition all behave correctly in unit tests.

### Task 8: JournalStorage SPI contract tests

**Scope:** Write `JournalStorageContractTest` as an abstract, parameterized JUnit 5 base class that any `JournalStorage` implementation can extend. Covers: append returns monotonically increasing positions; scan from position N returns records from N onward; empty scan returns nothing; close is idempotent. Run immediately against `InMemoryJournalStorage` via `InMemoryStorageContractTest`.

**Files — Create:** `JournalStorageContractTest.java`, `InMemoryStorageContractTest.java`

**Done when:** All contract tests pass against `InMemoryJournalStorage`.

---

## Phase 4 — drools-journal-core

### Task 9: ModifyLambdaRegistry

**Scope:** Implement `ModifyLambda` functional interface (`void apply(Object fact, Object[] parameters)`) and `ModifyLambdaRegistry` (session-scoped). Registry supports `register(String lambdaClassRef, ModifyLambda)` and `lookup(String lambdaClassRef)`. `lookup` throws `JournalSchemaEvolutionException` when the ref is not found. Registry is populated at KieBase build time (wiring to KieBase lifecycle is part of this task).

**Files — Create:** `ModifyLambda.java`, `ModifyLambdaRegistry.java`, `JournalSchemaEvolutionException.java`

**Done when:** Registration and lookup work; exception thrown with the unresolved ref in the message on missing lookup.

### Task 10: JournalledNamedEntryPoint

**Scope:** Extend `NamedEntryPoint` (following the `ReliableNamedEntryPoint` pattern from `drools-reliability`). Override `insert()`, `insertLogical()`, `retract()`, and `update()` to append the appropriate `JournalRecord` via `JournalStorage` before delegating to super. Apply `ObjectStorageStrategy` on insert/update to decide embed vs `ExternalRef`. Consume the current activation ID from thread-local (set by `JournalledAgenda`) for `insertLogical()`. Until the compiler rewrite (Task 15) is available, all `update()` calls — including those from DRL `modify` blocks — write a full `InsertRecord` (object snapshot fallback).

**Files — Create:** `JournalledNamedEntryPoint.java`
**Files — Study:** `NamedEntryPoint` in drools-kiesession, `ReliableNamedEntryPoint` in drools-reliability-core.

**Done when:** All four intercept paths append correct record types; `ObjectStorageStrategy` decision is respected.

### Task 11: JournalledAgenda

**Scope:** Extend `DefaultAgenda`. After each rule activation fires, append a `RuleMatchRecord` with an auto-incrementing session-scoped `long` ID. Store the current activation ID in thread-local state so `insertLogical()` on the same RHS can reference it. Clear thread-local after the activation completes.

**Files — Create:** `JournalledAgenda.java`
**Files — Study:** `DefaultAgenda` firing loop in drools-core.

**Done when:** `RuleMatchRecord` is written after every activation; thread-local ID is visible to `JournalledNamedEntryPoint.insertLogical()` during the same activation's execution.

### Task 12: ReplayFilter

**Scope:** Implement `ReplayFilter` as an `AgendaFilter`. Initialized with the already-fired set (Set of `(ruleName, long[] factHandleIds)` tuples) built during restore. `accept(Match)` returns `false` if the tuple is in the already-fired set, `true` otherwise.

**Files — Create:** `ReplayFilter.java`

**Done when:** Filter correctly suppresses matches in the already-fired set and passes all others.

### Task 13: RestoreEngine

**Scope:** Implement the four-phase restore protocol from spec §8:

- **Phase 0:** Single-pass scan of the active journal collecting all `CompactionPrepareRecord` and `CompactionCommitRecord` entries. Build a page-state map: PREPARE-only → original pages canonical; PREPARE+COMMIT → merged page canonical.
- **Phase 1:** Sequential scan of all non-retired pages in order. Apply `InsertRecord` (logical and non-logical), `RetractRecord`, `ModifyRecord` (via `ModifyLambdaRegistry`), `RuleMatchRecord` (build `firedMatches` map and already-fired set), `SafepointRecord` (track last consistent checkpoint). Defer TMS wiring to `pendingTmsLinks` list.
- **Phase 1.5:** Bulk Rete re-propagation of all surviving facts (no per-modify `update()` calls).
- **Phase 2:** Wire TMS logical dependencies from `pendingTmsLinks` using `firedMatches`.
- **Phase 3:** Install `ReplayFilter` on the agenda.

Rollback: if the final page is incomplete (records after last `SafepointRecord`), discard those records and restore to the safepoint. Throw `JournalSchemaEvolutionException` on unresolved `lambdaClassRef`.

**Files — Create:** `RestoreEngine.java`

**Done when:** Full restore from a journal produces a session with correct working memory state, TMS chains, and a `ReplayFilter` installed on the agenda.

### Task 14: CompactionCoordinator

**Scope:** Implement background compaction. Maintains `pageId → (liveCount, totalCount)` map — rebuilt from a full journal scan on startup, then updated incrementally as records are written (following the liveness rules in spec §7.1). When `liveCount / totalCount` drops below threshold (default 30%), marks the page as a compaction candidate. Executes the four-step protocol:

1. Append `CompactionPrepareRecord { Pm_id, replacedPageIds }`
2. Read source pages, write merged page Pm with only live entries
3. Append `CompactionCommitRecord { Pm_id, replacedPageIds }`
4. Lazily delete source pages

Multiple concurrent compactions must be supported (distinct page pairs, distinct PREPARE/COMMIT pairs).

**Files — Create:** `CompactionCoordinator.java`

**Done when:** Compaction reduces page count; four-step protocol writes correct journal records in order; concurrent compactions on different page pairs complete without interference.

---

## Phase 5 — DRL Compiler Rewrite (DEFERRED — upstream Drools patch)

### Task 15: Durable-mode modify block compiler rewrite

**Status: DEFERRED.** This task requires modifying the Drools core codebase (`drools-model-codegen`), not `drools-journal`. It will be submitted as a separate patch to the upstream Drools repository once the runtime scaffolding (Phases 1–4, 6–8) is proven.

**Rationale:** The `ModifyRecord` optimization — capturing individual setter parameter values instead of full object snapshots — requires injecting a journal append call at compile time, specifically in `Consequence.addUpdateBitMask()` (`drools-model-codegen/.../generator/Consequence.java`). That method already extracts property names and setter arguments from the AST for BitMask generation; the journal append call would be emitted alongside it when `DurableSessionOption` is active.

Until this patch lands, all `update()` calls (including DRL `modify` blocks) fall back to full `InsertRecord` snapshots via `JournalledNamedEntryPoint`. The system is functionally correct but writes more data per modify than the optimized path would.

**Upstream changes required:**
- `Consequence.addUpdateBitMask()` in `drools-model-codegen` — inject `drools.appendModify(handle, lambdaClassRef, params)` before `drools.update()` when durable mode is active
- `KnowledgeHelper` (or a `DurableKnowledgeHelper` subclass) — add `appendModify()` method to make it available inside generated lambdas
- `ModifyLambdaRegistry` population at KieBase build time — register each lambda ref and parameter types during rule compilation
- Equivalent change in `MVELConsequenceBuilder.rewriteModify()` for the legacy MVEL dialect path

**Scope (when implemented):** For each `modify` block in a rule's RHS, emit an additional journal append call after the property assignments and before the existing `update()` call. The lambda class name follows the pattern `Rule_{ruleName}_modify_{index}`. Plain Java `update()` calls are unaffected (full snapshot fallback).

**Done when:** A DRL rule with a `modify` block compiles to code that appends a `ModifyRecord` with the correct `lambdaClassRef` and parameters; the lambda is registered in `ModifyLambdaRegistry`; `update()` does not emit a second `InsertRecord` for the same modify.

---

## Phase 6 — drools-journal-chronicle

### Task 16: ChronicleJournalStorage

**Scope:** Implement `JournalStorage` and `JournalScanner` using Chronicle Queue OSS. One Chronicle Queue instance per session directory. `append(ByteBuffer)` writes via `ExcerptAppender`. `scan(fromPosition)` creates an `ExcerptTailer` starting at the given index. `latestPosition()` returns Chronicle's last index. Chronicle's roll cycle maps to physical page boundaries; `PageRollStrategy` controls when rolls are triggered. Records serialized via `BytesMarshallable`. Extend `JournalStorageContractTest` with `ChronicleStorageContractTest`.

**Files — Create:** `ChronicleJournalStorage.java`, `ChronicleJournalScanner.java`
**Files — Create (tests):** `ChronicleStorageContractTest.java`

**Done when:** All SPI contract tests pass; append/scan work on local filesystem; multiple readers can scan concurrently.

---

## Phase 7 — drools-journal-aeron

### Task 17: SBE schema

**Scope:** Define `journal-records.xml` covering all 7 `JournalRecord` types. Configure the SBE Maven plugin to generate Java encoders/decoders into the build output. Verify generated classes compile.

**Files — Create:** `src/main/resources/sbe/journal-records.xml`
**Files — Modify:** `drools-journal-aeron/pom.xml` (SBE plugin configuration)

**Done when:** `mvn generate-sources` produces encoder/decoder classes for all 7 record types.

### Task 18: AeronJournalStorage

**Scope:** Implement `JournalStorage` and `JournalScanner` using Aeron Archive OSS. MediaDriver runs embedded in the writer JVM. `append()` publishes to a live Aeron recording. `scan(fromPosition)` replays via the Archive client by recording ID from the given position. Records encoded/decoded using the SBE classes from Task 17. Extend `JournalStorageContractTest` with `AeronStorageContractTest`.

**Files — Create:** `AeronJournalStorage.java`, `AeronJournalScanner.java`
**Files — Create (tests):** `AeronStorageContractTest.java`

**Done when:** All SPI contract tests pass; append/scan round-trip correctly with SBE encoding.

---

## Phase 8 — Integration and Correctness Tests

All tests use `InMemoryJournalStorage` unless the test is specifically validating a storage implementation's crash safety.

### Task 19: Basic session durability

**Scope:** End-to-end tests covering the core durability path: insert facts → fire rules → simulate crash (discard session object) → restore from journal → verify working memory matches expected state. Also covers `retract` and plain `update()` (full-snapshot fallback).

**File:** `DurableSessionTest.java`

**Done when:** Insert/retract/update/fire/restore cycle produces correct working memory.

### Task 20: Logical insert + TMS restore

**Scope:** Tests where rules use `insertLogical()`. Verify: logical facts are present after restore; TMS dependency chains are correctly wired (logical fact retracted when its justifying activation is suppressed by `ReplayFilter`); `justifyingRuleMatchId` correctly links `InsertRecord` to `RuleMatchRecord`.

**File:** `LogicalInsertRestoreTest.java`

**Done when:** TMS chains are intact after restore; logical fact lifecycle is correct under `ReplayFilter`.

### Task 21: Modify via full-snapshot restore

**Scope:** Tests for DRL `modify` blocks under the runtime-only path (compiler rewrite deferred): verify that `update()` from a modify block writes a full `InsertRecord` (object snapshot); verify that on restore the modified object state is correct. Once Task 15 (upstream patch) is available, this test suite should be extended with `ModifyRecord`-specific tests verifying lambda replay.

**File:** `ModifyRestoreTest.java`

**Done when:** Modified fact state is correctly restored via full-object snapshot; modify/restore cycle produces correct working memory.

### Task 22: Compaction correctness

**Scope:** Tests covering the full compaction lifecycle and crash scenarios:
- Insert/retract cycle creates a sparse page → compaction runs → restore from compacted journal gives correct working memory
- Crash simulation: PREPARE written, no COMMIT → restore falls back to original pages (ignores partial Pm)
- Crash simulation: COMMIT written, source pages still on disk → restore reads merged page only
- Concurrent compactions on different page pairs complete independently

**File:** `CompactionCorrectnessTest.java`

**Done when:** All compaction crash scenarios listed in spec §7.3 are covered and produce correct restore state.

### Task 23: Safepoint and rollback

**Scope:** Tests for safepoint mechanics: `SafepointRecord` always causes a page roll; records written after the last `SafepointRecord` are discarded on restore (simulate partial-write crash); session restores to the state at the last `SafepointRecord`.

**File:** `SafepointRollbackTest.java`

**Done when:** Rollback to safepoint discards post-safepoint records and restores correct prior state.

### Task 24: Schema evolution failure mode

**Scope:** Test that when a `ModifyRecord`'s `lambdaClassRef` is not registered in `ModifyLambdaRegistry` (rule renamed or deleted since journal was written), `RestoreEngine` throws `JournalSchemaEvolutionException` with the unresolved ref in the message.

**File:** `SchemaEvolutionTest.java`

**Done when:** Exception is thrown with correct message; no silent corruption or partial restore.

---

## Out of Scope (v1)

Per spec §10:
- Schema evolution / migration tooling for renamed `lambdaClassRef`
- Encryption of journal pages
- Session replication / active-active clustering
- Streaming restore
- Multi-entry-point cross-ordering guarantees

# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

---

## 2026-11-06 — Reorder events to compact non-contiguous pages

**Priority:** low
**Status:** active

Compaction currently merges only *contiguous* runs of sparse pages (#66): the
merged page occupies one journal slot, so merging across a still-live page
would reorder a fact's modify-delta chain on restore. A smarter strategy could
lift that restriction by *reordering events*. Two angles: (1) push records
forward past pages that don't affect them, increasing the odds that sparse
pages become adjacent; (2) when a fact is "held back" by a live page in the
middle, split the journal — leave the non-compactable facts in one page and
emit a following page holding the facts that *are* compactable. Effectively a
stable sort of facts into compactable vs pinned groups, preserving each fact's
own delta order while rearranging across facts.

**Context:** Raised right after fixing #66. The contiguous-only rule is the
safe, correct bug fix; this is the "better algorithm for another day" the fix
deliberately deferred. Depends on preserving per-fact delta ordering while
relaxing global page ordering — related to the "global record ordinal" option
discussed (decouple restore order from page order).

**Promoted to:**

## 2026-10-02 — Gotcha: `Set.of(...)` iteration order is randomized per JVM run

**Priority:** medium
**Status:** active

JDK immutable collections (`Set.of`, `Map.of`) pick their iteration order from
a random salt computed once per JVM start — stable *within* a run, different
*between* runs. Code that flattens one to an array (`toArray`) and relies on
element order will pass or fail depending on the JVM instance, presenting as a
"transient" flake rather than a deterministic bug. Root cause of the #58
`ChroniclePageRetirementIT` flake: `CompactionCoordinator.compact()` built
`replacedPageIds` from a `Set.of(...)` and `spliceIntoIndex` assumed list order.
Lesson: never assume ordering from `Set.of`/`Map.of`; make consumers
order-independent, and when a test flakes "sometimes," suspect per-run
randomization before timing.

**Context:** #58 review/fix of the Chronicle catalog splice. Resolves the
question left open in E3 of the 2026-07-15 Chronicle review below.

**Promoted to:** fixed in #58 (PR #60)

---

## 2026-07-20 — Auto-build ModifyLambdaRegistry from KieBase ConsequenceMetaData

**Priority:** medium
**Status:** active

`RuleImpl.getConsequenceMetaData()` stores `Statement(Type.MODIFY, factClass)` with field names for each modify block — populated by the Drools compiler in `AsmUtil.rewriteModifyDescr()`. This contains enough information to reconstruct the `ModifyLambdaRegistry` automatically at session creation time without the user passing it explicitly. The factory would iterate `kbase.getKiePackages()` → rules → `ConsequenceMetaData.getStatements()`, filter for `MODIFY` type, derive setter names from property names, and build `MethodHandleModifyLambda.forSetters()` instances.

**Caveat:** The metadata is populated by the MVEL/ASM compiler path (`drools-mvel`). Need to verify it's also populated when using the exec-model compilation path (the default in newer Drools). If not, this optimization would only work for the classic dialect.

**Context:** Discovered during #44 (unified session API). Currently using explicit `DurableSessionOption.withModifyLambdaRegistry()` as the simpler approach.

**Promoted to:**

---

## 2026-07-16 — Persist globals via GlobalResolver interception

**Priority:** medium
**Status:** active

Persist session globals transparently by replacing the session's `GlobalResolver` with a journal-aware implementation (similar to drools-reliability's `ReliableGlobalResolver`). On `setGlobal`, write a `GlobalRecord(String name, byte[] serializedValue)` to the journal alongside an in-memory map. On `resolveGlobal`, read from the in-memory map only — the journal is append-only so no random reads. On restore, the `RestoreEngine` replays `GlobalRecord` entries to rebuild the globals map and calls `setGlobal` on the session before re-inserting facts. Non-serializable globals are silently skipped (no record written, no error) — the user is responsible for re-providing those after restore.

Key difference from drools-reliability: their `Storage<String, Object>` is random-access, so they need the `toBeRefreshed` cache and `updateStorage()` flush dance around `FIRE` operations. Our write-through-to-journal + in-memory-map approach is simpler — no stale-read problem, no flush synchronization.

**Context:** Discussion of #38 (unified durability epic), specifically the "globals persistence" parity gap. `ReliableGlobalResolver` and `ReliableGlobalResolverFactory` in `drools-reliability-core` confirmed that drools-reliability *does* persist globals via `GlobalResolver` SPI interception — contrary to initial assumption.

**Promoted to:**

---

## 2026-07-15 — Chronicle subproject code review: defects, contract gaps, and test holes

**Priority:** high
**Status:** active

Full review of `drools-journal-chronicle` (7 production files, 5 test files, ~1346 lines). Three defects, four contract gaps, five test gaps, and four clean-code items. Prioritized below.

### Defects

- **D1 (P1):** `insertLogical` doesn't set `empty = false` — `ChronicleJournalStorage.java:134`. After a logical-insert-only session, `isEmpty()` still returns true, causing restore to be skipped.
- **D2 (P3):** `scan(fromPosition)` ignores `fromPosition` — `ChronicleJournalStorage.java:189`. Always scans from beginning. *(See also: [[2026-07-13 mid-stream resume idea]])*
- **D3 (P2):** `next()` returns null instead of throwing `NoSuchElementException` — `MultiQueueScanner.java:64`. Iterator contract violation.

### Contract gaps

- **C1 (P2):** No write-after-close guard — write methods don't check `closed` flag. `InMemoryJournalStorage` throws `IllegalStateException`; Chronicle silently writes to a closed queue.
- **C2 (P3):** `safepointSequenceNo` not restored on reopen — restarts from 0, creating duplicate safepoint IDs across sessions.
- **C3 (P3):** `currentPageBytes` / `currentRecordCount` not restored on reopen — roll strategy loses page state, can produce oversized pages after restart.
- **C4 (P3):** Synthetic position counter in `MultiQueueScanner.position()` — not usable as `fromPosition` in subsequent scan. *(Same root cause as D2.)*

### Test gaps

- **T1 (P2):** File `ChronicleJournalScannerTest.java` contains class `MultiQueueScannerTest` — name mismatch.
- **T2 (P1):** No `insertLogical` test in `ChronicleJournalStorageTest` — would have caught D1.
- **T3 (P2):** `ChronicleDataRecordHandlerTest` missing round-trip tests for `insertLogical`, `modify`, `ruleMatch`.
- **T4 (P4):** Test naming doesn't consistently follow 3-segment `action_context_expectedResult` convention.
- **T5 (P2):** No test for write-after-close behaviour.

### Clean code / intention expression

- **E1 (P4):** `currentPageBytes` name implies precision; actual values are estimates. `estimatedPageBytes` would be clearer.
- **E2 (P4):** Catalog rebuilt from disk on every `scan()` call — no caching.
- **E3 (P4):** ~~`spliceIntoIndex` silently produces wrong results if `replacedIds` order doesn't match index order. Precondition undecided (acknowledged in test TODO).~~ **RESOLVED in #58 (PR #60)** — rewritten as an order-independent set-membership splice; root cause was `Set.of` per-run iteration-order randomization. See [[2026-10-02 Set.of gotcha]].
- **E4 (P4):** `PayloadCodec` has no format version byte — old data indistinguishable from new if format changes.

### Overall assessment

Architecture is sound — catalog/data separation, methodWriter/methodReader pattern, immutable returns. The P1 items (D1 + T2) are a one-line fix plus its test. The P2 batch hardens contracts and tests. P3 items (reopen state, positional scan) are larger and can wait until the features are exercised. P4 items are naming and future-proofing.

**Context:** Comprehensive manual review of all source and test code in `drools-journal-chronicle`, cross-checked against `JournalStorage` / `JournalScanner` API contracts and the `InMemoryJournalStorage` reference implementation.

**Promoted to:**

---

## 2026-07-16 — Extract common two-pass scan skeleton from RestoreEngine and CompactionCoordinator

**Priority:** medium
**Status:** active

`RestoreEngine.scan()` and `CompactionCoordinator.scanLiveness()` share an identical structural pattern: Phase 0 builds the live page set via `PageIndex.buildLivePageSet`, then Phase 1 re-scans the full journal, buffering records between page boundaries / safepoints, checking `livePageIds.contains(pageId)`, and flushing or discarding. The only difference is what the flush does — restore replays facts, liveness counts insert/retract ratios.

This duplicated skeleton is dangerous: a bug fix to the page-boundary detection or safepoint handling must be applied in both places (and any future consumer). Extract a common scan harness that accepts a flush callback (visitor/consumer), so the traversal logic lives in one place.

**Context:** Noticed while designing #34 (page retirement). Retirement needs the same two-pass structure, making this the third copy if left unrefactored.

**Promoted to:**

---

## 2026-07-13 — scanLiveness needs page lifecycle awareness before compacting merged pages

**Priority:** high
**Status:** active

`scanLiveness` computes liveness for every page in the raw stream, including pages already retired by previous compactions. Retired pages have 0% liveness (their facts get reassigned to merged pages via `factToPage` tracking) and are perpetually flagged as sparse candidates. This has two consequences: (1) wasted compaction cycles on dead pages every run, and (2) dead original pages and live merged pages get mixed in the same candidates set, producing `CompactionCommitRecord`s that reference already-retired page IDs. The fix is to replay the compaction protocol (same logic as `RestoreEngine` Phase 0) to build the live page set before computing liveness — only track pages that are actually canonical.

**Context:** Raised while analysing what happens when two merged pages themselves become sparse (e.g. a series of modifies drives the live/total ratio below 30%) and the compactor tries to merge them. `RestoreEngine.scan()` already solves this with its Phase 0 page index build; `CompactionCoordinator.scanLiveness` needs the equivalent.

**Promoted to:**

## 2026-05-27 — Storage pages vs compaction pages: two notions conflated

**Priority:** medium
**Status:** active

`PageRollStrategy` allows storage to roll a new physical page on size or record count, independently of safepoints. But the compaction design uses `SafepointRecord.sequenceNo` as the page ID — which only works when pages always roll on safepoints. With size-based rolling a page closes without a safepoint, leaving no sequence number to put in `CompactionPrepareRecord`.

Two notions of "page" that currently coincide but will diverge with real backends:
- **Storage page** — physical unit bounded by size/record count (`PageRollStrategy`, Chronicle/Aeron file boundaries)
- **Compaction page** — logical unit bounded by `fireAllRules()` cycles (safepoint intervals)

The `safepointOnly()` default keeps them aligned for now. Resolving the divergence is required before building Chronicle or Aeron backends with size-based rolling.

**Context:** Raised during epic-compaction-coordinator while discussing InMemoryJournalStorage's missing page model and concurrent compaction safety.

**Promoted to:** #18

---

## 2026-05-27 — Refactor JournalStorage write API to semantic methods

**Priority:** medium
**Status:** active

`JournalStorage` currently exposes a raw `append(JournalRecord)` that forces all callers — the event listener, the session, the compactor — to construct specific record types themselves. The write side should mirror what we added as test helpers on `InMemoryJournalStorage`: `insert`, `retract`, `ruleMatch`, `modify`, `compactionPrepare`, `compactionCommit`. Callers then know nothing about record types, sequence numbers, or payload wrapping.

The `scan()` read side stays typed (restore engine needs to pattern-match on record types). Only the write side changes.

**Context:** Raised during epic-compaction-coordinator while wiring `safepoint()` onto the interface. Adding `safepoint()` was the minimal fix; the broader refactor was parked to avoid scope creep mid-epic.

**Promoted to:** #19

---

## 2026-05-26 — Gotcha: KieSession.getObjects() wildcard breaks AssertJ varargs

**Priority:** medium
**Status:** active

KieSession.getObjects() returns `Collection<? extends Object>`. AssertJ's
`containsExactlyInAnyOrder` fails even with explicit `Integer.valueOf()` boxing;
`containsExactlyInAnyOrderElementsOf(List.of(...))` also fails. Only reliable
fix: cast with `@SuppressWarnings("unchecked")` to `Collection<Object>` before
asserting.

**Context:** Hit during end-to-end restore tests in epic-compaction-coordinator,
Task A3 (multipleSessions test). Multiple approaches tried before the cast fix worked.

---

## 2026-05-26 — Technique: safepoint-aligned pages eliminate SPI changes

**Priority:** medium
**Status:** active

Using `SafepointRecord.sequenceNo` as the page ID gives a free, stable, reproducible
page boundary scheme with zero `JournalStorage` SPI changes. Pages fall out naturally
from the safepoint mechanism rather than being explicitly managed by the storage layer.

**Context:** Emerged during compaction design brainstorm when evaluating three options
for the page concept. Chosen for epic-compaction-coordinator.

---

## 2026-05-26 — Technique: periodic full rescan keeps write path clean

**Priority:** medium
**Status:** active

For liveness tracking in CompactionCoordinator, a periodic full journal rescan is
simpler and more correct than incremental decorator interception on the write path.
The coordinator stays stateless between polls; no auxiliary factHandleId map to
keep in sync with concurrent writes.

**Context:** Chosen over the decorator pattern during compaction design brainstorm.
Trades O(journal size) per poll for zero write-path complexity.

---

## 2026-05-25 — Standardize integration test naming convention

**Priority:** low
**Status:** active

Test names across `JournalledKieSessionTest` and `JournalledKieSessionRestoreTest` currently follow different patterns — some use `verb_condition_expectation`, others don't follow a consistent structure. Defining and applying a single naming convention would make the test suite easier to scan and extend.

**Context:** Noticed while splitting restore tests into their own file — the existing names were inconsistent with each other.

**Promoted to:** #23

---

# Gotchas

Hard-won implementation lessons specific to this project.

---

## 2026-07-15 — `DrlNameExpr.toString()` crashes with ClassCastException

MVEL parser produces `DrlNameExpr` nodes instead of standard JavaParser `NameExpr`. Calling `toString()` on any AST subtree containing `DrlNameExpr` throws `ClassCastException` because `DefaultPrettyPrinterVisitor` cannot be cast to `DrlVoidVisitor`.

**Fix:** Use `PrintUtil.printNode(node)` from `org.drools.mvel.parser.printer` instead of `node.toString()` for any node produced by `MvelParser`.

**Context:** #17 — DRL precompiler. Hit when extracting the target variable name from `ModifyStatement.getModifyObject()`.

---

## 2026-07-15 — `stageModify` must execute BEFORE the `modify` block, not after

DRL `modify($p) { setAge(30) }` expands into setter calls followed by `update($p)`, which immediately triggers `objectUpdated` on the listener. If the staging call is placed after the modify block in the consequence, the event has already fired and the listener writes a full `InsertRecord` snapshot instead of a `ModifyRecord`.

**Fix:** The precompiler injects `journal.stageModify(...)` before the `modify` block so the listener has the staged data ready when `objectUpdated` fires.

**Context:** #17 — end-to-end test showed `INSERT id=1 Ticket(closed)` instead of expected `MODIFY`.

---

## 2026-07-15 — `ModifyStatement` AST nodes have no position info

`ModifyStatement.getRange()` returns `(0,0)` to `(0,0)`. The MVEL parser does not populate token ranges for modify nodes. This means you cannot use AST positions for positional text insertion in the original consequence string.

**Fix:** Manipulate the AST directly (insert statements into the `BlockStmt`) and re-serialize the entire block with `PrintUtil.printNode()`. Don't mix AST parsing with string-position manipulation.

**Context:** #17 — initially tried to find the modify position in the original text using `getRange()`, had to switch to full AST manipulation.

---

## 2026-07-15 — `DrlParser` does not parse consequence bodies

`DrlParser` parses the DRL structure (package, imports, rules, LHS patterns) but stores the consequence as a raw string in `RuleDescr.getConsequence()`. It does not parse `modify` blocks, method calls, or any Java/MVEL syntax within the consequence. This is by design — DRL and Java/MVEL are different grammars.

**Fix:** Use two parsers: `DrlParser` for the rule envelope (gives LHS patterns, imports, variable→type bindings) and `MvelParser.parseBlock()` for the consequence body (gives `ModifyStatement` AST nodes).

**Context:** #17 — expected a single parser to handle everything.

---

## 2026-07-15 — Technique: listener as DRL global for staging

Instead of routing staging data through `JournalStorage` (which mixes persistence concerns with inter-component signaling), make the `JournallingRuntimeEventListener` itself the DRL global. The consequence calls `journal.stageModify(...)` directly on the listener, and `objectUpdated` reads its own fields. Zero indirection, single-threaded safety guaranteed by Drools session model.

**Context:** #17 — initial design put `stageModify` on `JournalStorage`, which required the SPI to know about staging. Moving it to the listener eliminated the coupling.

---

## 2026-05-19 — GitHub `Closes #N` only auto-closes issues on the default branch

A commit message containing `Closes #N` only triggers GitHub's auto-close when the commit lands on the **default branch** (usually `main`). Pushing to a feature or epic branch does nothing — the issue stays open. Fix: close the issue manually with `gh issue close N` after pushing to the feature branch; the `Closes #N` keyword will fire again automatically when the branch is eventually merged.

---

## 2026-05-19 — `long[]` in Java records: auto-generated `equals`/`hashCode` use reference equality

Java's auto-generated record `equals` and `hashCode` call `Arrays.equals` for object array components but use `==` (reference equality) for primitive arrays such as `long[]`. Two records with identical `long[]` content but different array instances compare as **not equal** unless you explicitly override both methods using `Arrays.equals` / `Arrays.hashCode`. The symptom is silent Set/Map lookup misses with no compile-time warning.

---

## 2026-05-14 — JournalledAgenda NPE: entryPointsManager not yet set when createAgenda() runs

`StatefulKnowledgeSessionImpl` constructs in this order: `createAgenda()` (line 352) then `createEntryPointsManager()` (line 354). Any attempt to access `workingMemory.getDefaultEntryPoint()` from within the agenda constructor throws NPE because `entryPointsManager` is still null. Fix: lazy resolution — resolve `JournalledObjectStore` on first event, not at construction time.

---

## 2026-05-14 — IdentityObjectStore.updateHandle() calls removeHandle() + addHandle() internally

`MapObjectStore.updateHandle()` is implemented as `removeHandle(handle); handle.setObject(object); addHandle(handle, object)`. If you override `removeHandle()` or `addHandle()` for journalling, `session.update()` silently produces a spurious `RetractRecord` + `InsertRecord` pair. Fix: override `updateHandle()` and use `super.removeHandle()` + `super.addHandle()` to bypass journalling, leaving `beforeUpdate()` as the sole journal write for updates.

---

## 2026-05-14 — Maven test-jar is bound to package phase by default

`maven-jar-plugin test-jar` goal is bound to `package` by default. When running `mvn test` in a reactor, the `package` phase does not execute, so downstream modules that depend on the test-jar get a resolution failure. Fix: rebind the goal to `process-test-classes` in the producing module's pom.

---

## 2026-05-14 — Passing per-session context to SPI-instantiated factories via Environment

Drools SPI creates `RuntimeComponentFactory` via no-arg constructor as a singleton — no way to pass per-session context at factory creation time. Solution: store context in `Environment` (`kbase.newKieSession(null, env)`) and read it inside `createAgenda()` via `workingMemory.getEnvironment()` and inside `createObjectStore()` via `reteEvaluator.getEnvironment()`. The environment is threaded through all construction points automatically.

---

## 2026-05-14 — Logical insert detection: no isLogical() on InternalFactHandle

There is no public `isLogical()` method on `InternalFactHandle`. The only reliable detection in a `RuleRuntimeEventListener` is:
```java
handle.getEqualityKey() != null && handle.getEqualityKey().getStatus() == EqualityKey.JUSTIFIED
```
The equality key is set to `JUSTIFIED` in `TruthMaintenanceSystemImpl.insert()` (lines 129-130) before the belief system call chain, so it is reliably present by the time `fireObjectInserted` fires. Ordering confirmed: `beforeMatchFired` → `objectInserted` (for logical inserts) → `afterMatchFired`.

---

## 2026-05-12 — No thread-local for current activation ID: use a plain field instead

**Priority:** medium
**Status:** active

Rather than a static `CurrentActivation` thread-local, `JournalledObjectStore` holds a plain `long currentActivationId = -1L` field. `JournalledAgenda` sets and clears it directly via a method call before and after firing each rule. This is simpler, easier to test, and carries no classloader leak risk.

**Context:** The thread-local design was initially proposed to avoid modifying the Drools call stack. Rejected because `JournalledAgenda` already has a reference to the session and can reach `JournalledObjectStore` directly — making the extra indirection unnecessary. Key assumption: **Drools sessions are single-threaded** — one thread fires rules on one session at a time, so a plain instance field is safe. If that assumption ever breaks (e.g. parallel agenda mode), this will need revisiting.

**Promoted to:**

---

## 2026-05-14 — Replace JournalledObjectStore/NamedEntryPoint with RuleRuntimeEventListener

**Priority:** medium
**Status:** active

Instead of `JournalledObjectStore`, `JournalledNamedEntryPoint`, and `JournalledEntryPointFactory`, a single object implementing both `AgendaEventListener` and `RuleRuntimeEventListener` registered directly on `JournalledKieSession` after construction handles all journalling. Logical inserts are detected via `handle.getEqualityKey() != null && handle.getEqualityKey().getStatus() == EqualityKey.JUSTIFIED` — this is reliably set before `fireObjectInserted` fires (confirmed in `SimpleBeliefSystem`). Ordering `beforeMatchFired` → `objectInserted` → `afterMatchFired` is guaranteed for rule-driven logical inserts.

**Restore interaction:** during restore (`ReplayFilter` / issue #10), add a `journallingEnabled` boolean flag on `JournalledKieSession`. Set it to `false` before replaying facts into the session, then set it back to `true` before resuming normal processing. The listener checks the flag and skips all writes while disabled. Simple, explicit, no special plumbing needed.

**Context:** Both the listener approach and the current `JournalledObjectStore` approach require the same restore-time suppression — the refactor doesn't change the restore complexity. Safe to do after restore is implemented.

**Promoted to:** #20

---

## 2026-05-14 — Upstream: simplify NamedEntryPoint construction in Drools core

**Priority:** medium
**Status:** active

`NamedEntryPoint` wires its `objectStore` inside its own constructor via `createObjectStore()`, which is called before any subclass field can be set (Java `super()` must be first). This makes it impossible for subclasses to pass context (e.g. a `JournalStorage`) into `createObjectStore()` via normal constructor parameters — forcing workarounds like reading from `Environment` or thread-locals.

A cleaner design: separate construction from initialisation. Options include making `objectStore` `protected` (so subclasses can replace it after `super()`), or moving the `createObjectStore()` call out of the constructor into a separate `init()` method called by the factory after construction. Either change would let `JournalledNamedEntryPoint` receive its journal as a plain constructor parameter and set it before the object store is created.

**Context:** Encountered while implementing `JournalledEntryPointFactory`. The `Environment`-based workaround we adopted is functional but is a direct consequence of this construction rigidity. Worth raising as an upstream improvement to `drools-kiesession`.

**Promoted to:** #24

---

## 2026-05-14 — Replace ThreadLocal<JournalStorage> hack with per-thread factory delegation

**Priority:** medium
**Status:** active

`JournalledSessionFactory.create()` currently smuggles `JournalStorage` to the SPI-registered `JournalledRuntimeComponentFactory` via a `ThreadLocal<JournalStorage>`. This is a naked parameter-passing hack with no type-safety and non-obvious risk.

Better design: the thread-local holds a fully initialised `JournalledRuntimeComponentFactory` instance (constructed explicitly with its `JournalStorage`). The globally SPI-registered factory is a thin shell that delegates every method to whatever is in the thread slot. No raw parameters float around — the factory carries its own context and the delegation is explicit.

**Context:** Needed because `kbase.newKieSession()` calls `RuntimeComponentFactory.get()` deep in `StatefulKnowledgeSessionImpl`'s constructor, leaving no direct way to inject a per-call factory. The SPI mechanism provides only a no-arg singleton. Thread-local is the shortest path; per-thread factory is the right path.

**Promoted to:** #21

## 2026-05-12 — Make ObjectStorageStrategy return Payload instead of StorageDecision

**Priority:** medium
**Status:** active

Change `ObjectStorageStrategy` from returning `StorageDecision` (EMBED/EXTERNAL_REF) to returning a `Payload` directly. The strategy implementation then owns both the decision and the execution — for EMBED it serializes and returns `EmbeddedPayload`; for EXTERNAL_REF it stores in the external system and returns `ExternalRef(typeName, key)`. Two concrete implementations: `EmbedStrategy` (Java serialization) and a test-friendly `ExternalRefStrategy` (key supplier function). Chronicle/Aeron implementations come later when those modules are built. Also consider introducing a `JournalRecordFactory` to centralise creation of `InsertRecord`, `RetractRecord`, and other record types, keeping `JournalledObjectStore` thin and record construction independently testable.

**Context:** Emerged during TDD of `JournalledObjectStore` — the `dbKey` for `ExternalRef` is storage-implementation-specific (Chronicle index, Aeron position, DB row ID) and cannot be computed in core. The current design leaves `dbKey` as a placeholder `String.valueOf(handle.getId())`. The factory point surfaced from the same session when noticing the store is doing too much.

**Promoted to:** #22

---

## 2026-07-10 — Chronicle Queue `acquireAppender()` is thread-local — no external synchronization needed

Chronicle Queue's `SingleChronicleQueue.acquireAppender()` returns a `ThreadLocal`-cached `ExcerptAppender` via `CleaningThreadLocal`. Each thread gets its own appender automatically. Writes are serialized by Chronicle's internal write lock (held during `writingDocument()` until `DocumentContext.close()`). Adding external synchronization (`ReentrantLock`, `synchronized`) is unnecessary and adds contention.

**Fix:** For multi-threaded access (e.g. session thread + compaction thread), call `queue.acquireAppender()` from each thread rather than sharing a single appender field. Cache a `sessionWriter` proxy for the session thread; create fresh writers for the compaction thread's call chains.

**Context:** #33

---

## 2026-07-10 — Chronicle Queue `methodWriter()` creates a new proxy per call — cache it

`ExcerptAppender.methodWriter(Class)` creates a dynamically-compiled proxy via Chronicle Wire's `VanillaMethodWriterBuilder` on every invocation. It is not cached internally. Calling it per record in a loop creates unnecessary garbage.

**Fix:** Create the proxy once per call chain and pass it as a parameter to internal methods. For the session thread, cache as a field (`sessionWriter`). For compaction, create once in `writeMergedPage` and pass to `writeRecord`.

**Context:** #33

---

## 2026-07-13 — Multi-queue scanner: mid-stream resume via cumulative record index

**Priority:** low
**Status:** active

The multi-queue scanner always scans from the beginning (`scan(0)`). Mid-stream resume via `scan(fromPosition)` is not supported — no current caller needs it.

If needed, the scanner could build a cumulative index at scan time: each page's starting record offset is the sum of all prior pages' record counts. `scan(fromPosition)` would binary-search this index to find the target page, open it, and skip forward within it. The record counts could also be stored in the catalog alongside `PageCreated` entries to avoid the counting pass.

**Context:** Raised during #42 (multi-queue architecture). The 4 position-related contract tests are `@Disabled` for Chronicle until this is implemented.

---

## 2026-06-24 — Chronicle roll cycles are time-based — logical page IDs must be embedded in entries

Chronicle Queue's roll cycle (the mechanism that creates new `.cq4` files) is wall-clock-based. There is no public API to force a roll on demand without controlling the system clock. This means Chronicle file boundaries cannot be used as logical page boundaries for the journal.

**Fix:** Embed the logical page ID as the first argument of every `ChronicleWriteOps` method call. The scanner reads it back via `ChronicleRecordHandler` fields. Page tracking is fully independent of Chronicle's file structure. `ChronicleJournalScanner.currentPageId()` must NOT use `tailer.cycle()` — it must return `handler.currentPageId`.

**Context:** #14

---

## 2026-06-24 — `appender.methodWriter(Class<T>)` returns `T`, not `MethodWriter<T>`

In Chronicle Queue 5.x, `ExcerptAppender.methodWriter(ChronicleWriteOps.class)` returns a proxy typed as `ChronicleWriteOps` directly. Declaring `private final MethodWriter<ChronicleWriteOps> writer` is a compile error.

**Fix:** Declare as `private final ChronicleWriteOps writer`.

**Context:** #14

---

## 2026-06-24 — Package-private interface in `internal` subpackage blocks parent package

An interface defined without `public` in `org.drools.journal.chronicle.internal` is invisible to classes in `org.drools.journal.chronicle`. Java package visibility does not cross subpackage boundaries.

**Fix:** Declare `ChronicleWriteOps` as `public`. The `internal` package name signals the convention; the visibility modifier must still allow access from sibling packages.

**Context:** #14

---

## 2026-07-13 — `latestPosition() >= 0` is wrong for multi-queue reopen

When the active page queue is empty (safepoint called `roll()`, creating an empty next page), `latestPosition()` returns -1 even though the journal has data in earlier pages. `JournalledSessionFactory` skips restore entirely — session reopens with no facts.

**Fix:** Added `isEmpty()` to `JournalStorage` SPI. The correct test is "does this journal have data?" not "what's the latest position number?" For Chronicle, `reopenExisting` always returns `false` (if we're reopening, data exists by definition).

**Context:** #42

---

## 2026-07-13 — Scanner look-ahead corrupts `currentPageId()` across page boundaries

In a multi-queue scanner, `next()` calls `advance()` which may exhaust the current queue and open the next one. If `openNextNonEmptyQueue()` sets `currentPageId` directly, then `currentPageId()` reports the *next* page immediately after `next()` returns the last record of the *previous* page. Callers (RestoreEngine) that check `currentPageId()` between `next()` calls see the wrong page.

**Fix:** Separate `currentPageId` (page of last returned record, updated in `next()`) from `bufferedPageId` (page of the look-ahead record, updated in `openNextNonEmptyQueue()`). Same pattern the old single-queue scanner used.

**Context:** #42

---

## 2026-07-31 — Chronicle merged pages without SafepointRecord are silently discarded by RestoreEngine

Merged pages produced by `CompactionCoordinator.compact()` originally contained only `InsertRecord`s — no trailing `SafepointRecord`. `RestoreEngine.scan()` flushes pending records at safepoints or physical page boundaries; trailing records after the last safepoint are discarded as incomplete. With the in-memory backend, merged pages happen to flush at page boundaries (the scanner transitions to the next page), but with Chronicle, if a merged page is the last scanned page, its records are dropped silently — restore returns 0 surviving facts with no error.

Compounding the diagnosis: Chronicle puts `CompactionPrepareRecord` and `CompactionCommitRecord` in the catalog queue, not in data pages. `PageIndex.buildLivePageSet()` (used by RestoreEngine) never sees them in the data stream. The in-memory backend puts them inline on the data page, so `PageIndex` works. This means the two backends have fundamentally different compaction metadata visibility — the safepoint fix makes merged pages self-contained regardless of where PREPARE/COMMIT records live.

**Fix:** Append a `SafepointRecord(-1, 0L)` to the merged page records in `CompactionCoordinator.compact()` before calling `writeMergedPage()`. Every page — session or merged — must have a trailing safepoint to be considered sealed.

**Context:** #36

---

## 2026-07-20 — Drools `SessionConfiguration` is closed to external option types

`SessionConfiguration.setOption()` uses a hardcoded switch on `propertyName()`. Options not in the switch fall through to `compConfig.setOption(option)`, which dispatches by `option.type()` to a fixed set of factories (`Base`, `Rule`, `Flow`) registered at construction time in `RuleBaseFactory.newKnowledgeSessionConfiguration()`. There is no SPI to inject additional factories, and the `configurations` map on `CompositeConfiguration` is private with no public mutator. `PersistedSessionOption` works only because it has a dedicated field and `case` in `SessionConfiguration` (upstream drools-core). Subclassing `PersistedSessionOption` is also blocked — both constructors are `private`. Creating a custom `CompositeSessionConfiguration` with a fourth factory doesn't help either: `kbase.newKieSession()` calls `conf.as(SessionConfiguration.KEY)` which extracts only the `Base` delegate, so the factory method never sees the custom delegate.

**Fix:** Pass custom options via `Environment` instead of `SessionConfiguration`. The `Environment` is an untyped map that reaches `createStatefulSession()` and supports arbitrary keys.

**Context:** #44

---

## 2026-08-26 — Split ReplayFilter's dual responsibility

**Priority:** low
**Status:** active

`ReplayFilter` currently serves two unrelated roles: (1) an `AgendaFilter` that blocks already-fired rules during and after restore, and (2) a match cache that stores live `Match` objects so `wireTms` can look them up to re-link logical facts to their justifiers. Role 2 is only needed during the one-time restore sequence, but the cache lives on the filter permanently.

Split into: `ReplayFilter` (just the `AgendaFilter` with `accept()` and the `firedMatches` set) and a separate `MatchCache` collaborator that the filter populates during `fireAllRules` and that `wireTms` consumes. After `wireTms` completes, the cache can be discarded.

Also: the `ReplayFilter` constructor takes `Collection<RuleMatchRecord>` only to immediately extract `(packageName, ruleName, factHandleIds)` into `MatchKey` objects. The caller (`buildReplayFilter`) constructs `RuleMatchRecord` objects just for this handoff. Pass keys directly instead.

**Context:** discovered during documentation review of `JournalledRuntimeComponentFactory.restore()`

---

## 2026-08-26 — Flaky ChroniclePageRetirementIT tests

**Priority:** medium
**Status:** active

`singleCompaction_chronicle_restoreShowsSurvivingFacts` and `twoConcurrentCompactions_disjointPageSets_restoreShowsSurvivingFacts` fail intermittently — `survivingFacts()` returns empty after compaction + close + reopen of `ChronicleJournalStorage`. Passes on retry with no code changes. Likely a Chronicle Queue flush/sync timing issue: queue files may not be fully persisted before the storage is closed and reopened, causing the restore scan to see empty data.

**Observed:** 2026-08-26, during a `mvn clean install` run. Re-running the same build immediately after produced a clean pass.

**Next step:** investigate whether `ChronicleJournalStorage.close()` needs an explicit `queue.close()` ordering or sync barrier before directory cleanup.

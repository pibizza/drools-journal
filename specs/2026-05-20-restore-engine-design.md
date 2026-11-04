# RestoreEngine Design Spec

**Date:** 2026-05-20
**Issue:** #11
**Branch:** epic-journal-aware-session

---

## Goal

Implement `RestoreEngine` — the four-phase protocol that reconstructs a `JournalledKieSession`
from an existing journal, and the preparatory refactor that makes transparent restore possible.

---

## Public API

A single factory method replaces the current `create()` / `restore()` distinction. The caller
never decides whether the journal is new or existing — the factory inspects the storage.

```java
// new session (journal empty)
JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, lambdaRegistry);

// restored session (journal has data)
JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, lambdaRegistry);

// always safe — caller never thinks about state
session.insert(new Fact(...));
session.fireAllRules();
```

**Detection rule:** `storage.latestPosition() == -1` → new session (skip restore).
Otherwise → run the four-phase restore protocol before returning the session.

`lambdaRegistry` is always required. Pass an empty registry when no `ModifyRecord`s are
expected (compiler rewrite Task 15 not yet landed). An unresolved `lambdaClassRef` throws
`JournalSchemaEvolutionException` at restore time.

The existing `JournalledSessionFactory.create()` becomes a private implementation detail or
is removed.

---

## Preparatory Refactor — Journal Writes Move to a Listener

Currently `JournalledObjectStore.addHandle()` and `removeHandle()` write directly to the
journal. This makes it impossible to re-propagate facts into Rete during restore without
producing spurious journal records.

**Change:** extract journal writes into a `JournallingRuntimeEventListener` (implementing
`RuleRuntimeEventListener`), following the same pattern `JournalledAgenda` already uses for
`RuleMatchRecord` via `AgendaEventListener`.

| Event | Journal write |
|---|---|
| `objectInserted` | `InsertRecord` (reads `logical` flag and `justifyingRuleMatchId` from `JournalledObjectStore`) |
| `objectDeleted` | `RetractRecord` |
| `objectUpdated` | `InsertRecord` (full-snapshot fallback, `logical=false`) |

`JournalledObjectStore` retains `currentActivationId` state (set/cleared by `JournalledAgenda`
as today). The listener reads it to populate `logical` and `justifyingRuleMatchId`.

**During restore:** the listener is not installed. All `addHandle()` calls flow into Rete with
no journal side-effects.

**After restore:** the listener is installed. Normal journalling resumes.

---

## Payload Deserialization

`JournalPayloadBuilder` gains a `deserialize(Payload) → Object` counterpart, the reverse of
the existing `build()` method:

- `EmbeddedPayload` → Java deserialization of `bytes[]`
- `ExternalRef` → throw `UnsupportedOperationException` (out of scope for v1)

---

## Four-Phase Restore Protocol

### Phase 0 — Compaction Map

Single-pass scan of the full journal collecting `CompactionPrepareRecord` and
`CompactionCommitRecord` entries. Build a page-state map:

- PREPARE only → original pages canonical (merged page write was incomplete)
- PREPARE + COMMIT → merged page canonical, replaced pages retired

**Current status:** `CompactionCoordinator` (Task 14) is not yet implemented, so the journal
contains no compaction records. Phase 0 always produces an empty map for now. The interaction
between page retirement and the flat `JournalScanner` stream is a design gap deferred to
Task 14.

### Phase 1 — Sequential Scan with Safepoint Buffering

Records are accumulated between `SafepointRecord` markers. A flush happens at each safepoint;
the trailing buffer (post-crash partial write) is discarded at end-of-scan.

```
pending = []
for each record in scanner:
    if SafepointRecord   → apply all pending to working memory model; pending = []
    else                 → pending.add(record)
// end of scan: discard pending (post-crash incomplete write)
```

Working memory model accumulated during flush:

| Record type | Action |
|---|---|
| `InsertRecord` (logical=false) | `survivingFacts.put(factHandleId, deserialize(payload))` |
| `InsertRecord` (logical=true) | same, plus `pendingTmsLinks.add(factHandleId, justifyingRuleMatchId)` |
| `RetractRecord` | `survivingFacts.remove(factHandleId)` |
| `ModifyRecord` | lookup `lambdaRegistry`, apply lambda to current object in `survivingFacts`; throw `JournalSchemaEvolutionException` if ref not found |
| `RuleMatchRecord` | `firedMatches.add(record)`; `firedMatchesById.put(record.id(), record)` |
| `SafepointRecord` | flush trigger (see above) |

### Phase 1.5 — Bulk Rete Re-propagation and ID Translation

`ReplayFilter` is installed on the agenda **before** any `insert()` call, so that as Rete
evaluates rules during re-propagation it can capture the resulting `InternalMatch` objects
into `matchCache` (needed for Phase 2 TMS wiring) while still suppressing those activations
from firing.

No journal listener is installed yet. Insertions flow into Rete cleanly.

```java
session.getAgenda().setFilter(replayFilter);  // installed before re-propagation

Map<Long, FactHandle> oldToNew = new HashMap<>();
for (var entry : survivingFacts.entrySet()) {
    FactHandle h = session.insert(entry.getValue());  // no listener → no journal write
    oldToNew.put(entry.getKey(), h);
    // Rete evaluates rules → ReplayFilter.accept() suppresses and caches InternalMatch
}
```

`oldToNew` is the translation map from original journal `factHandleId` values to the
live `FactHandle`s in the restored session. This eliminates the need to force specific
handle IDs and avoids any `FactHandleFactory` counter manipulation.

### Phase 2 — TMS Wiring

After re-propagation, `matchCache` inside `ReplayFilter` holds a `Map<MatchKey, InternalMatch>`
populated by `accept()` for every suppressed activation. Wire each pending TMS link:

```java
for (PendingTmsLink link : pendingTmsLinks) {
    InternalFactHandle logicalHandle = (InternalFactHandle) oldToNew.get(link.factHandleId());
    RuleMatchRecord justifier = firedMatchesById.get(link.justifyingRuleMatchId());
    MatchKey key = translateKey(justifier, oldToNew);
    InternalMatch internalMatch = matchCache.get(key);
    ObjectTypeConf typeConf = session.getObjectTypeConfigurationRegistry()
                                     .getOrCreateObjectTypeConf(logicalHandle.getEntryPointId(),
                                                                logicalHandle.getObject());
    tms.readLogicalDependency(logicalHandle, logicalHandle.getObject(), null, internalMatch, typeConf);
}
```

`tms.readLogicalDependency()` is explicitly marked "used when deserialising" in
`TruthMaintenanceSystemImpl` — this is the established Drools path for this operation,
mirroring `ProtobufInputMarshaller`.

### Phase 3 — Install Journal Listener

```java
session.getRuleRuntimeEventSupport()
       .addEventListener(new JournallingRuntimeEventListener(journal, objectStore));
```

From this point forward all mutations are journalled normally. The `ReplayFilter` installed
in Phase 1.5 remains active for the life of the session — it correctly suppresses only the
already-fired activations and passes new ones through.

---

## Data Structures Summary

| Name | Type | Purpose |
|---|---|---|
| `survivingFacts` | `Map<Long, Object>` | oldId → deserialized object, updated on Insert/Retract/Modify |
| `firedMatches` | `List<RuleMatchRecord>` | all fired matches (original IDs), passed to `ReplayFilter` after translation |
| `firedMatchesById` | `Map<Long, RuleMatchRecord>` | keyed by `RuleMatchRecord.id`, for TMS link resolution |
| `pendingTmsLinks` | `List<PendingTmsLink>` | `(factHandleId, justifyingRuleMatchId)` pairs deferred to Phase 2 |
| `oldToNew` | `Map<Long, FactHandle>` | old journal factHandleId → live session FactHandle |
| `matchCache` | `Map<MatchKey, InternalMatch>` | inside extended `ReplayFilter`; populated during Phase 1.5 re-propagation |

`PendingTmsLink` is a small private record: `record PendingTmsLink(long factHandleId, long justifyingRuleMatchId) {}`

---

## Error Handling

| Condition | Behaviour |
|---|---|
| `ModifyRecord` with unknown `lambdaClassRef` | throw `JournalSchemaEvolutionException` |
| `InsertRecord` with `ExternalRef` payload | throw `UnsupportedOperationException` |
| Empty journal (`latestPosition() == -1`) | skip restore, return fresh session |
| Post-safepoint trailing records | silently discarded (safepoint rollback) |
| TMS link with no matching `InternalMatch` | throw `IllegalStateException` (journal corruption) |

---

## Files Affected

| File | Change |
|---|---|
| `JournalledObjectStore.java` | remove direct journal writes from `addHandle()` / `removeHandle()` |
| `JournallingRuntimeEventListener.java` | new — carries the extracted journal writes |
| `JournalPayloadBuilder.java` | add `deserialize(Payload) → Object` |
| `ReplayFilter.java` | extend to cache `InternalMatch` objects during `accept()` |
| `RestoreEngine.java` | new — orchestrates all four phases |
| `JournalledSessionFactory.java` | replace `create()` with `open(kbase, storage, lambdaRegistry)` |

---

## Out of Scope

- Compaction page retirement interaction with `JournalScanner` (deferred to Task 14)
- `ExternalRef` payload restore (v1 limitation)
- Multi-entry-point restore (single default entry point assumed for v1)

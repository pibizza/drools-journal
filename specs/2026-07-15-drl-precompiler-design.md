# DRL Precompiler Design — Modify-as-Delta Capture

**Date:** 2026-07-15
**Issue:** #17 — DRL modify → ModifyRecord delta capture via DRL precompiler
**Upstream ref:** apache/incubator-kie-drools#6682 §5 (Modify-as-Lambda Compiler Rewrite)
**Status:** Design approved

---

## Problem

When a DRL `modify` block fires, `objectUpdated` on `JournallingRuntimeEventListener`
writes a full `InsertRecord` snapshot of the entire object. This is correct but
wasteful — the journal stores the complete serialized object even when only one
property changed. The `ModifyRecord` type exists in the record hierarchy
specifically for delta capture, but nothing populates it today.

The original plan (Phase 5) required patching `Consequence.addUpdateBitMask()` in
upstream `drools-model-codegen`. Investigation confirmed this is not viable:

1. The Drools compilation pipeline has no plugin/hook mechanism.
2. `KieBase` does not retain DRL source text.
3. `drools-reliability` uses the same full-snapshot approach.

## Solution

A standalone `JournalDrlPrecompiler` that transforms DRL text → DRL text before
`KieHelper.build()`. It parses the DRL via `DrlParser` (from `drools-drl`),
identifies `modify` blocks, and for each one:

1. Extracts setter names and argument expressions from the modify block AST.
2. Resolves the fact type from the LHS pattern declarations.
3. Creates a `MethodHandleModifyLambda` and registers it in the `ModifyLambdaRegistry`.
4. Rewrites the consequence text to inject a `stageModify()` call after the modify block.
5. Injects a `global JournalStorage journal;` declaration in the DRL header.

The system is opt-in. If the user skips the precompiler, `objectUpdated` writes
full `InsertRecord` snapshots as today. Correctness is never at risk.

---

## Components

### New classes (drools-journal-core)

**`JournalDrlPrecompiler`** — stateless utility.

```java
public static String rewrite(String drl, ModifyLambdaRegistry registry, ClassLoader classLoader)
```

Parses DRL via `DrlParser`, walks `RuleDescr` nodes. For each modify block:
- Extracts the target variable name and setter calls from the AST.
- Resolves the fact type from the LHS pattern declarations using the provided `ClassLoader`.
- Generates a deterministic `lambdaClassRef`: `Rule_{ruleName}_modify_{index}`.
- Creates a `MethodHandleModifyLambda` and registers it in the registry.
- Rewrites the consequence text to append a `stageModify()` call.
- Injects `global org.drools.journal.api.JournalStorage journal;` once per package.

**`MethodHandleModifyLambda`** — generic, reusable `ModifyLambda` implementation.

```java
public class MethodHandleModifyLambda implements ModifyLambda {

    private final MethodHandle[] setters;

    public MethodHandleModifyLambda(Class<?> factType, String... setterNames) {
        // Resolve each setter by name on factType.
        // Drools guarantees the setter exists with that exact name.
        // Parameter type is discovered from the Method itself.
    }

    @Override
    public void apply(Object fact, Object[] params) {
        // Invoke each MethodHandle with the corresponding param.
    }
}
```

### Changes to existing code

**`JournalStorage`** — new method:

```java
void stageModify(String lambdaClassRef, Object[] params);
```

Stores the pending modify data (lambdaClassRef + params) for the next
`objectUpdated` event to consume. Does not write a journal record itself.

**`JournallingRuntimeEventListener.objectUpdated()`** — modified behavior:

- If staged modify data is present: serialize `Object[]` → `byte[]`, write
  `ModifyRecord(factHandleId, lambdaClassRef, serializedParams)`, clear
  staged data.
- If no staged modify data: write `InsertRecord` (full snapshot), as today.

**`JournalledSessionFactory.open()`** — takes `ModifyLambdaRegistry` as an
additional parameter. Calls `session.setGlobal("journal", storage)` to make
the journal accessible in DRL consequences.

**`ModifyLambdaRegistry`** — no changes. Already exists with `register()` and
`lookup()`.

**`RestoreEngine`** — no changes. Already handles `ModifyRecord` via
`registry.lookup(lambdaClassRef).apply(fact, params)`.

---

## Data Flow

### Startup (fresh or restart)

```
DRL source
  → JournalDrlPrecompiler.rewrite(drl, registry, classLoader)
     ├── parse via DrlParser → PackageDescr
     ├── for each modify block:
     │     create MethodHandleModifyLambda(factType, setterNames)
     │     register in registry with deterministic lambdaClassRef
     ├── rewrite consequence: inject stageModify() call after modify block
     └── inject global JournalStorage declaration
  → rewritten DRL string
  → KieHelper.build() → KieBase
  → JournalledSessionFactory.open(kbase, storage, registry)
     ├── session.setGlobal("journal", storage)
     ├── if not empty: restore (registry available for ModifyRecord replay)
     └── attach JournallingRuntimeEventListener
```

### Runtime — precompiled rule fires

```
modify($p) { setAge($newAge), setName($computed) }
  → compiled consequence executes:
     1. $p.setAge($newAge);  $p.setName($computed);
     2. journal.stageModify("Rule_UpdateAge_modify_0",
                            new Object[]{ $newAge, $computed });
     3. update($p);  → triggers objectUpdated on listener
  → listener sees staged modify data
  → serializes Object[] → byte[]
  → writes ModifyRecord(factHandleId, lambdaClassRef, serializedParams)
  → clears staged data
```

### Runtime — non-precompiled rule (fallback)

```
update($p);  → triggers objectUpdated on listener
  → listener sees no staged modify data
  → writes InsertRecord (full object snapshot, as today)
```

### Restore

```
RestoreEngine scans journal
  → encounters ModifyRecord(factHandleId, lambdaClassRef, params)
  → registry.lookup(lambdaClassRef) → MethodHandleModifyLambda
  → deserialize byte[] → Object[]
  → lambda.apply(fact, params)
```

### Crash recovery

The same DRL source is reprocessed by the precompiler on every startup.
Deterministic `lambdaClassRef` naming (`Rule_{ruleName}_modify_{index}`)
ensures the registry is repopulated with identical entries. If the DRL has
changed (schema evolution), `JournalSchemaEvolutionException` fires on
unresolved `lambdaClassRef` — existing designed behavior.

---

## DRL Rewriting Example

### Input

```java
package com.example;
import com.example.Person;

rule "UpdateAge"
when
    $p : Person(age < 30)
    $newAge : Integer() from 30
then
    modify($p) {
        setAge($newAge),
        setName("Senior")
    }
end
```

### Output

```java
package com.example;
import com.example.Person;
global org.drools.journal.api.JournalStorage journal;

rule "UpdateAge"
when
    $p : Person(age < 30)
    $newAge : Integer() from 30
then
    modify($p) {
        setAge($newAge),
        setName("Senior")
    }
    journal.stageModify("Rule_UpdateAge_modify_0",
                        new Object[]{ $newAge, "Senior" });
end
```

The modify block is untouched — Drools compiles it normally. The `stageModify`
call is injected after the modify block, referencing the same argument
expressions verbatim. The `global` declaration is injected once per package.

---

## Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Parser | `DrlParser` from `drools-drl` | Handles all DRL syntax correctly; modify blocks already structured in AST |
| Lambda implementation | `MethodHandle`-based | Resolved once at startup, JIT-optimized, no reflection on hot path |
| Write trigger | `objectUpdated` on listener (not `stageModify`) | Single write point, natural fallback, no suppression flags |
| Staged data mechanism | Field on listener, set/clear pattern | Single-threaded (Drools sessions), same pattern as `currentActivationId` |
| Journal injection | DRL `global` declaration | Standard DRL mechanism, precompiler injects it, factory sets it |
| Crash recovery | Rerun precompiler on same DRL | Deterministic naming ensures identical registry population |
| Scope | DRL7 `modify` blocks only | DRLX support deferred |

---

## Not In Scope

- Lambda caching/deduplication across rules (optimization for later)
- DRLX syntax support (`alerts.update(t{...})`)
- Schema evolution / migration tooling
- Multiple modify blocks on different variables in the same rule (extend later)
- Removing `Environment`-based journal wiring (separate issue #44)

---

## Dependencies

- `drools-drl-parser` — for `DrlParser` and `PackageDescr` AST
- Existing: `ModifyLambdaRegistry`, `ModifyLambda`, `ModifyRecord`,
  `JournalSchemaEvolutionException` — all already in place

---

## Acceptance Criteria

From issue #17:

- [ ] `JournalDrlPrecompiler.rewrite(drl, registry, classLoader)` transforms modify blocks
- [ ] `MethodHandleModifyLambda` resolves setters via `MethodHandle`, applies correctly
- [ ] `stageModify()` stages data, `objectUpdated` writes `ModifyRecord`
- [ ] No duplicate `InsertRecord` emitted for precompiled modify blocks
- [ ] Non-precompiled rules still produce `InsertRecord` snapshots (fallback)
- [ ] `ModifyRecord` replayed correctly via `ModifyLambdaRegistry` on restore
- [ ] `JournalSchemaEvolutionException` thrown when `lambdaClassRef` is unresolved
- [ ] Deterministic `lambdaClassRef` naming survives restart

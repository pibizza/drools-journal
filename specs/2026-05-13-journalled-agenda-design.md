# JournalledAgenda Design

**Date:** 2026-05-13
**Issue:** #9
**Epic:** #6 — journal-aware session runtime core
**Plan:** `plans/2026-04-21-drools-journal.md` Task 11

---

## Context

`JournalledAgenda` is the agenda-side counterpart to `JournalledNamedEntryPoint`.
Its job is to stamp each rule activation with a session-scoped ID before the RHS
runs and to append a `RuleMatchRecord` after it completes. The ID flows into
`JournalledObjectStore` so that any `insertLogical()` call on the same RHS can
record which activation justified the logical fact.

---

## Architecture

`JournalledAgenda extends DefaultAgenda`. No other `DefaultAgenda` methods are
overridden. All behaviour is driven by a private `AgendaEventListener` registered
in the constructor.

### Constructor signature

```java
JournalledAgenda(
    InternalRuleBase kieBase,
    InternalWorkingMemory workingMemory,
    FactHandleFactory factHandleFactory,
    JournalStorage journal,
    JournalledObjectStore store)
```

Construction:
1. `super(kieBase, workingMemory, factHandleFactory)`
2. `workingMemory.getAgendaEventSupport().addEventListener(new JournallingListener())`

### Fields

| Field | Type | Purpose |
|-------|------|---------|
| `journal` | `JournalStorage` | append target for `RuleMatchRecord` |
| `store` | `JournalledObjectStore` | set/clear activation ID around each firing |
| `nextActivationId` | `long` | monotonically increasing counter, starts at 0 |

`nextActivationId` is a plain `long` — Drools fires activations on a single thread.

---

## Listener Behaviour

### `beforeMatchFired`

Runs immediately before `consequence.evaluate()`.

```
++nextActivationId
store.setCurrentActivationId(nextActivationId)
```

Any `insertLogical()` call inside the RHS will read this ID from the store
and embed it as `justifyingRuleMatchId` in the resulting `InsertRecord`.

### `afterMatchFired`

Runs immediately after `consequence.evaluate()` returns (or throws and is handled).

```
ruleName      ← event.getMatch().getRule().getName()
factHandleIds ← ((InternalMatch) event.getMatch())
                    .getFactHandles(internalMatch.getTuple())
                    .stream()
                    .mapToLong(fh → ((InternalFactHandle) fh).getId())
                    .toArray()

journal.append(new RuleMatchRecord(nextActivationId, ruleName, factHandleIds))
store.clearCurrentActivationId()
```

All other `AgendaEventListener` methods are no-ops.

---

## Ordering Guarantee

The ordering inside `RuleExecutor.fireActivation()` is:

```
AgendaEventListener.beforeMatchFired   ← set activation ID here
  consequence.evaluate()               ← insertLogical() runs here
AgendaEventListener.afterMatchFired    ← append RuleMatchRecord + clear ID here
```

This is enforced by `RuleExecutor` — no additional synchronisation is needed.

---

## Known Limitations (v1)

If a rule consequence throws and the exception propagates out of
`RuleExecutor.fireActivation()`, `afterMatchFired` is never called. In that case
`clearCurrentActivationId()` is not invoked, leaving `store.currentActivationId`
stale until the next activation fires. No `RuleMatchRecord` is written for the
failed activation. This is acceptable for v1 — a consequence exception leaves the
session in an error state where journal-based restore is not expected.

---

## Testing

**`JournalledAgendaTest`** — integration-style unit test backed by `InMemoryJournalStorage`.

### Test 1 — `RuleMatchRecord` written per activation

Wire a KieSession with `JournalledAgenda` and `JournalledObjectStore`. Define a
rule that fires once. Insert a fact and call `fireAllRules()`. Scan the journal
and assert:
- exactly one `RuleMatchRecord` is present
- `ruleName` matches the rule
- `factHandleIds` contains the inserted fact's handle ID
- `id` equals 1 (first activation)

Fire a second rule, assert `id` is 2 (monotonically increasing).

### Test 2 — `insertLogical` coordination

Define a rule whose RHS calls `insertLogical()`. After `fireAllRules()`, scan
the journal and assert:
- the `InsertRecord` for the logically inserted fact has a non-negative
  `justifyingRuleMatchId`
- that `justifyingRuleMatchId` equals the `id` of the `RuleMatchRecord`
  written for the same activation

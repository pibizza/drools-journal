# Unified Session API Design

**Issue:** #44 — Unify session API with drools-reliability  
**Date:** 2026-07-20  
**Status:** Approved

## Goal

Replace `JournalledSessionFactory.open(kbase, storage)` with the standard
Drools session creation contract: `kbase.newKieSession(conf, null)`. This
converges drools-journal's API surface with drools-reliability, so users
see the same pattern for creating durable sessions.

## Target API

```java
KieSessionConfiguration conf = KieServices.get().newKieSessionConfiguration();
conf.setOption(DurableSessionOption.newSession()
        .withJournalStorage(storage)
        .withCompactionInterval(Duration.ofSeconds(30))    // optional, default 60s
        .withModifyLambdaRegistry(registry));              // optional, default empty
KieSession session = kbase.newKieSession(conf, null);
```

## Design Decisions

### Auto-detect vs explicit new/restore

**Decision:** Keep auto-detection via `storage.isEmpty()`.

The journal storage instance *is* the session identity — unlike
drools-reliability's `StorageManager` which multiplexes across sessions.
Forcing the user to track session IDs adds ceremony without safety benefit.

### StorageManager SPI

**Decision:** Out of scope. The user passes `JournalStorage` explicitly via
`DurableSessionOption.withJournalStorage()`. A `StorageManager`-style
discovery mechanism can be added later if needed.

## Changes

### 1. DurableSessionOption — add OptionKey, compaction interval, and ModifyLambdaRegistry

Add a static `OptionKey` so the option can be stored/retrieved through the
Drools composite configuration mechanism (`SessionConfiguration` delegates
unknown options to `compConfig`):

```java
public static final OptionKey<DurableSessionOption> KEY =
        new OptionKey<>(PROPERTY_NAME);
```

Add a `compactionInterval` field with a `withCompactionInterval(Duration)`
builder method. Default: `CompactionCoordinator.DEFAULT_INTERVAL` (60s).
`Duration.ZERO` disables compaction.

Add a `modifyLambdaRegistry` field with a `withModifyLambdaRegistry(ModifyLambdaRegistry)`
builder method. Default: `new ModifyLambdaRegistry()` (empty). This requires
moving `ModifyLambda` and `ModifyLambdaRegistry` from `drools-journal-core`
to `drools-journal-api` — they are small classes with no core dependencies.

**Future optimization:** The `ConsequenceMetaData` on `RuleImpl` inside the
KieBase already stores fact class + property names for each modify block.
A future improvement could auto-build the registry from the KieBase,
eliminating the need for the user to pass it.

### 2. JournalledRuntimeComponentFactory — activation check + initialization

Change the activation check from environment-based to config-based:

**Before:**
```java
if (environment == null || environment.get(JournalledSessionFactory.JOURNAL_KEY) == null) {
    return super.createStatefulSession(ruleBase, environment, sessionConfig, fromPool);
}
```

**After:**
```java
DurableSessionOption opt = sessionConfig.getOption(DurableSessionOption.KEY);
if (opt == null) {
    return super.createStatefulSession(ruleBase, environment, sessionConfig, fromPool);
}
```

Move the full initialization sequence from `JournalledSessionFactory.open()`
into `createStatefulSession()`:

1. Create `JournalledKieSession` (passing storage as constructor argument)
2. Restore if `storage.isEmpty()` is false
3. Wire `JournallingRuntimeEventListener` (agenda + runtime)
4. Set `"journal"` global if declared in the KieBase
5. Create and start `CompactionCoordinator`

The `restore()`, `hasJournalGlobal()`, and helper methods
(`insertNonLogicalFacts`, `buildReplayFilter`, `wireTms`) move verbatim
from `JournalledSessionFactory`.

### 3. JournalledKieSession — storage from constructor

Replace environment-based storage lookup with a constructor parameter:

**Before:**
```java
public JournalledKieSession(long id, InternalKnowledgeBase kBase,
                            boolean initInitFactHandle,
                            SessionConfiguration config,
                            Environment environment) {
    super(id, kBase, initInitFactHandle, config, environment);
    this.journal = (JournalStorage) environment.get(JournalledSessionFactory.JOURNAL_KEY);
}
```

**After:**
```java
public JournalledKieSession(long id, InternalKnowledgeBase kBase,
                            boolean initInitFactHandle,
                            SessionConfiguration config,
                            Environment environment,
                            JournalStorage storage) {
    super(id, kBase, initInitFactHandle, config, environment);
    this.journal = storage;
}
```

### 4. Delete JournalledSessionFactory

No deprecation, no forwarding — delete entirely. The `JOURNAL_KEY` constant
goes with it.

### 5. Migrate all tests

All tests switch from `JournalledSessionFactory.open(...)` to
`kbase.newKieSession(conf, null)`. Tests needing `getJournalStorage()`
downcast to `JournalledKieSession` — this is a test-only concern.

## Module impact

| Module | Changes |
|--------|---------|
| `drools-journal-api` | `DurableSessionOption`: add `KEY`, `compactionInterval`, `modifyLambdaRegistry` fields + builders. Receive `ModifyLambda` and `ModifyLambdaRegistry` from core |
| `drools-journal-core` | `JournalledRuntimeComponentFactory`: absorb init logic. `JournalledKieSession`: new constructor signature. Delete `JournalledSessionFactory`. Move `ModifyLambda` and `ModifyLambdaRegistry` to API module |
| `drools-journal-tests` | Migrate all test call sites |
| `drools-journal-chronicle` | No changes |
| `drools-journal-aeron` | No changes |

## What this does NOT change

- `JournalledKieSession` still extends `StatefulKnowledgeSessionImpl` — the
  runtime behavior is identical
- `JournallingRuntimeEventListener` is unchanged
- `CompactionCoordinator` is unchanged
- `RestoreEngine` and restore logic are unchanged (moved, not rewritten)
- `ObjectStorageMode` and `PageRollStrategy` stay on `DurableSessionOption`
  as they are today

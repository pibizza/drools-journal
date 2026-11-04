# Unified Session API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `JournalledSessionFactory.open(kbase, storage)` with the standard Drools contract `kbase.newKieSession(conf, null)`, converging the API with drools-reliability.

**Architecture:** `DurableSessionOption` carries all config (storage, compaction interval, modify lambda registry). `JournalledRuntimeComponentFactory` checks the session config for the option and performs all initialization (restore, listener wiring, compaction). `JournalledSessionFactory` is deleted.

**Tech Stack:** Java 21, Maven, Drools 9.x SPI (`RuntimeComponentFactory`, `SessionConfiguration`)

## Global Constraints

- Java 21 (`maven.compiler.release=21`)
- Always `mvn clean install` — never incremental
- License header: `Copyright (c) 2026 Drools Journal Authors` (Apache 2.0)
- `final` on fields and parameters only, not local variables
- No `final` on local variables
- Test naming: `action_context_expectedResult` (three camelCase segments separated by underscores)
- IT test classes use `*IT` suffix

---

### Task 1: Move ModifyLambda, ModifyLambdaRegistry, and JournalSchemaEvolutionException to drools-journal-api

**Files:**
- Move: `drools-journal-core/.../core/ModifyLambda.java` → `drools-journal-api/.../api/ModifyLambda.java`
- Move: `drools-journal-core/.../core/ModifyLambdaRegistry.java` → `drools-journal-api/.../api/ModifyLambdaRegistry.java`
- Move: `drools-journal-core/.../core/JournalSchemaEvolutionException.java` → `drools-journal-api/.../api/JournalSchemaEvolutionException.java`
- Modify: all files in core and tests that import from `org.drools.journal.core.ModifyLambda*` or `JournalSchemaEvolutionException`

**Interfaces:**
- Produces: `org.drools.journal.api.ModifyLambda`, `org.drools.journal.api.ModifyLambdaRegistry`, `org.drools.journal.api.JournalSchemaEvolutionException` — same signatures, new package

- [ ] **Step 1: Create the three files in drools-journal-api with the new package**

  Create `drools-journal-api/src/main/java/org/drools/journal/api/ModifyLambda.java`:
  ```java
  package org.drools.journal.api;

  @FunctionalInterface
  public interface ModifyLambda {
      void apply(Object fact, Object[] parameters);
  }
  ```

  Create `drools-journal-api/src/main/java/org/drools/journal/api/JournalSchemaEvolutionException.java`:
  ```java
  package org.drools.journal.api;

  public class JournalSchemaEvolutionException extends RuntimeException {
      public JournalSchemaEvolutionException(final String lambdaClassRef) {
          super("No ModifyLambda registered for: " + lambdaClassRef);
      }
  }
  ```

  Create `drools-journal-api/src/main/java/org/drools/journal/api/ModifyLambdaRegistry.java`:
  ```java
  package org.drools.journal.api;

  import java.util.HashMap;
  import java.util.Map;

  public class ModifyLambdaRegistry {
      private final Map<String, ModifyLambda> lambdas = new HashMap<>();

      public void register(final String lambdaClassRef, final ModifyLambda lambda) {
          lambdas.put(lambdaClassRef, lambda);
      }

      public ModifyLambda lookup(final String lambdaClassRef) {
          ModifyLambda lambda = lambdas.get(lambdaClassRef);
          if (lambda == null) {
              throw new JournalSchemaEvolutionException(lambdaClassRef);
          }
          return lambda;
      }
  }
  ```

- [ ] **Step 2: Delete the three files from drools-journal-core**

  Remove:
  - `drools-journal-core/src/main/java/org/drools/journal/core/ModifyLambda.java`
  - `drools-journal-core/src/main/java/org/drools/journal/core/ModifyLambdaRegistry.java`
  - `drools-journal-core/src/main/java/org/drools/journal/core/JournalSchemaEvolutionException.java`

- [ ] **Step 3: Update all imports in drools-journal-core and drools-journal-tests**

  Change `org.drools.journal.core.ModifyLambda` → `org.drools.journal.api.ModifyLambda` (and same for `ModifyLambdaRegistry`, `JournalSchemaEvolutionException`) in:
  - `MethodHandleModifyLambda.java`
  - `MethodHandleModifyLambdaTest.java`
  - `ModifyLambdaRegistryTest.java`
  - `RestoreEngine.java`
  - `JournalDrlPrecompiler.java`
  - `JournalledSessionFactory.java`
  - `JournalledKieSessionTest.java`
  - `ChronicleJournalledKieSessionIT.java`

- [ ] **Step 4: Build and verify**

  Run: `mvn clean install` from the project root.
  Expected: BUILD SUCCESS, all 160+ tests pass.

- [ ] **Step 5: Commit**

  ```
  refactor: move ModifyLambda and registry to drools-journal-api module

  Refs #44
  ```

---

### Task 2: Add OptionKey, compactionInterval, and modifyLambdaRegistry to DurableSessionOption

**Files:**
- Modify: `drools-journal-api/src/main/java/org/drools/journal/api/DurableSessionOption.java`

**Interfaces:**
- Consumes: `ModifyLambdaRegistry` from Task 1 (now in `org.drools.journal.api`)
- Produces: `DurableSessionOption.KEY` (`OptionKey<DurableSessionOption>`), `withCompactionInterval(Duration)`, `withModifyLambdaRegistry(ModifyLambdaRegistry)`, `getCompactionInterval()`, `getModifyLambdaRegistry()`

- [ ] **Step 1: Add the three new fields and builders to DurableSessionOption**

  Add to `DurableSessionOption.java`:
  ```java
  import java.time.Duration;
  import org.kie.api.conf.OptionKey;

  // Static field:
  public static final OptionKey<DurableSessionOption> KEY =
          new OptionKey<>(PROPERTY_NAME);

  // Instance fields (after existing fields):
  private Duration compactionInterval = Duration.ofSeconds(60);
  private ModifyLambdaRegistry modifyLambdaRegistry = new ModifyLambdaRegistry();

  // Getters:
  public Duration getCompactionInterval() {
      return compactionInterval;
  }

  public ModifyLambdaRegistry getModifyLambdaRegistry() {
      return modifyLambdaRegistry;
  }

  // Builders:
  public DurableSessionOption withCompactionInterval(final Duration interval) {
      if (interval == null) {
          throw new IllegalArgumentException("Compaction interval must not be null");
      }
      this.compactionInterval = interval;
      return this;
  }

  public DurableSessionOption withModifyLambdaRegistry(final ModifyLambdaRegistry registry) {
      if (registry == null) {
          throw new IllegalArgumentException("ModifyLambdaRegistry must not be null");
      }
      this.modifyLambdaRegistry = registry;
      return this;
  }
  ```

  Update `toString()` to include the new fields.

- [ ] **Step 2: Build and verify**

  Run: `mvn clean install` from the project root.
  Expected: BUILD SUCCESS — purely additive, no callers changed.

- [ ] **Step 3: Commit**

  ```
  feat: add OptionKey, compactionInterval and modifyLambdaRegistry to DurableSessionOption

  Refs #44
  ```

---

### Task 3: Add storage parameter to JournalledKieSession constructor

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledKieSession.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledRuntimeComponentFactory.java`

**Interfaces:**
- Consumes: `JournalStorage` from `drools-journal-api`
- Produces: `JournalledKieSession(long, InternalKnowledgeBase, boolean, SessionConfiguration, Environment, JournalStorage)` — new constructor signature

- [ ] **Step 1: Change JournalledKieSession constructor to accept JournalStorage**

  In `JournalledKieSession.java`, change:
  ```java
  public JournalledKieSession(final long id,
                              final InternalKnowledgeBase kBase,
                              final boolean initInitFactHandle,
                              final SessionConfiguration config,
                              final Environment environment) {
      super(id, kBase, initInitFactHandle, config, environment);
      this.journal = (JournalStorage) environment.get(JournalledSessionFactory.JOURNAL_KEY);
  }
  ```
  to:
  ```java
  public JournalledKieSession(final long id,
                              final InternalKnowledgeBase kBase,
                              final boolean initInitFactHandle,
                              final SessionConfiguration config,
                              final Environment environment,
                              final JournalStorage storage) {
      super(id, kBase, initInitFactHandle, config, environment);
      this.journal = storage;
  }
  ```

- [ ] **Step 2: Update JournalledRuntimeComponentFactory to pass storage from environment**

  The factory currently creates `JournalledKieSession` without passing storage explicitly (the old constructor read it from the environment). Update the factory to extract storage from the environment and pass it to the new constructor:

  ```java
  JournalStorage storage = (JournalStorage) environment.get(JournalledSessionFactory.JOURNAL_KEY);
  JournalledKieSession session = new JournalledKieSession(
          kbase.nextWorkingMemoryCounter(), kbase, true, sessionConfig, environment, storage);
  ```

  This keeps `JournalledSessionFactory.open()` working — the old env-based path still functions, but the constructor no longer reads from the environment itself.

- [ ] **Step 3: Build and verify**

  Run: `mvn clean install` from the project root.
  Expected: BUILD SUCCESS — all existing tests pass unchanged.

- [ ] **Step 4: Commit**

  ```
  refactor: pass JournalStorage as constructor parameter to JournalledKieSession

  Refs #44
  ```

---

### Task 4: Move initialization into JournalledRuntimeComponentFactory — config-based activation

This is the core change. The factory gains a second activation path: if `DurableSessionOption` is present on the session config, it creates the session and performs all initialization (restore, listener wiring, compaction). The old environment-based path remains for now (removed in Task 6).

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledRuntimeComponentFactory.java`

**Interfaces:**
- Consumes: `DurableSessionOption.KEY` from Task 2, `JournalledKieSession(...)` from Task 3
- Produces: Config-based session creation — `kbase.newKieSession(conf, null)` works end-to-end

- [ ] **Step 1: Write a failing integration test for the new API**

  Add to `JournalledKieSessionTest.java`:
  ```java
  @Test
  void newApi_insertAndFire_producesInsertMatchAndSafepoint() {
      InMemoryJournalStorage storage = new InMemoryJournalStorage();
      KieSessionConfiguration conf = KieServices.get().newKieSessionConfiguration();
      conf.setOption(DurableSessionOption.newSession()
              .withJournalStorage(storage)
              .withCompactionInterval(Duration.ZERO));

      try (KieSession session = new KieHelper().addContent(RULE, ResourceType.DRL)
              .build().newKieSession(conf, null)) {
          session.insert(42);
          session.fireAllRules();
      }

      assertThat(storage).hasToString("""
              INSERT  id=1  Integer(42)
              MATCH  id=1  pkg=org.drools.journal.test  rule=ProcessFact  facts=[1]
              SAFEPOINT  seq=0
              """);
  }
  ```

- [ ] **Step 2: Run the test to verify it fails**

  Run: `mvn clean install` from the project root.
  Expected: FAIL — the factory doesn't handle `DurableSessionOption` yet, so `kbase.newKieSession(conf, null)` creates a plain session with no journalling.

- [ ] **Step 3: Implement config-based activation in JournalledRuntimeComponentFactory**

  Rewrite `createStatefulSession()` to check `DurableSessionOption` first, then fall back to the old environment path:

  ```java
  @Override
  public InternalWorkingMemory createStatefulSession(final RuleBase ruleBase,
                                                     final Environment environment,
                                                     final SessionConfiguration sessionConfig,
                                                     final boolean fromPool) {
      DurableSessionOption opt = sessionConfig.getOption(DurableSessionOption.KEY);
      if (opt != null) {
          return createJournalledSession(ruleBase, environment, sessionConfig, fromPool, opt);
      }
      if (environment != null && environment.get(JournalledSessionFactory.JOURNAL_KEY) != null) {
          return createLegacySession(ruleBase, environment, sessionConfig, fromPool);
      }
      return super.createStatefulSession(ruleBase, environment, sessionConfig, fromPool);
  }
  ```

  Add `createJournalledSession()` — this absorbs all the logic from `JournalledSessionFactory.open()`:

  ```java
  private InternalWorkingMemory createJournalledSession(final RuleBase ruleBase,
                                                        final Environment environment,
                                                        final SessionConfiguration sessionConfig,
                                                        final boolean fromPool,
                                                        final DurableSessionOption opt) {
      JournalStorage storage = opt.getJournalStorage();
      InternalKnowledgeBase kbase = (InternalKnowledgeBase) ruleBase;

      JournalledKieSession session;
      if (fromPool || kbase.getSessionPool() == null) {
          session = new JournalledKieSession(
                  kbase.nextWorkingMemoryCounter(), kbase, true, sessionConfig, environment, storage);
          if (sessionConfig.isKeepReference()) {
              kbase.addStatefulSession(session);
          }
      } else {
          return (InternalWorkingMemory) kbase.getSessionPool().newKieSession(sessionConfig);
      }

      if (!storage.isEmpty()) {
          restore(session, storage, opt.getModifyLambdaRegistry());
      }

      JournallingRuntimeEventListener listener =
              new JournallingRuntimeEventListener(storage, new EmbedStrategy());
      session.addEventListener((org.kie.api.event.rule.RuleRuntimeEventListener) listener);
      session.addEventListener((org.kie.api.event.rule.AgendaEventListener) listener);
      if (hasJournalGlobal(kbase)) {
          session.setGlobal("journal", listener);
      }

      Duration interval = opt.getCompactionInterval();
      CompactionCoordinator coordinator = new CompactionCoordinator(storage, interval);
      if (!interval.isZero()) {
          coordinator.start();
      }
      session.setCompactionCoordinator(coordinator);

      return session;
  }
  ```

  Extract `createLegacySession()` from the old code (environment-based path) — same as current logic, just renamed.

  Move `restore()`, `hasJournalGlobal()`, `insertNonLogicalFacts()`, `buildReplayFilter()`, and `wireTms()` from `JournalledSessionFactory` into this class. These are private methods, copied verbatim.

- [ ] **Step 4: Run the test to verify it passes**

  Run: `mvn clean install` from the project root.
  Expected: BUILD SUCCESS — all tests pass including the new one.

- [ ] **Step 5: Commit**

  ```
  feat: config-based session creation via DurableSessionOption

  JournalledRuntimeComponentFactory now checks DurableSessionOption on the
  session config and performs full initialization (restore, listener wiring,
  compaction). kbase.newKieSession(conf, null) works end-to-end.

  Refs #44
  ```

---

### Task 5: Migrate all tests to the new API

Migrate every `JournalledSessionFactory.open(...)` call to `kbase.newKieSession(conf, null)` with `DurableSessionOption`. Three test files, ~25 call sites.

**Files:**
- Modify: `drools-journal-tests/src/test/java/org/drools/journal/test/JournalledKieSessionTest.java`
- Modify: `drools-journal-tests/src/test/java/org/drools/journal/test/JournalledKieSessionRestoreTest.java`
- Modify: `drools-journal-tests/src/test/java/org/drools/journal/test/ChronicleJournalledKieSessionIT.java`

**Interfaces:**
- Consumes: `DurableSessionOption` API from Tasks 1-2, config-based creation from Task 4

The pattern for each migration is:

**Before:**
```java
JournalledKieSession session = JournalledSessionFactory.open(kbase, storage, Duration.ZERO)
```

**After:**
```java
KieSessionConfiguration conf = KieServices.get().newKieSessionConfiguration();
conf.setOption(DurableSessionOption.newSession()
        .withJournalStorage(storage)
        .withCompactionInterval(Duration.ZERO));
KieSession session = (KieSession) kbase.newKieSession(conf, null);
```

For tests that pass a `ModifyLambdaRegistry`:

**Before:**
```java
JournalledSessionFactory.open(kbase, storage, registry, Duration.ZERO)
```

**After:**
```java
conf.setOption(DurableSessionOption.newSession()
        .withJournalStorage(storage)
        .withModifyLambdaRegistry(registry)
        .withCompactionInterval(Duration.ZERO));
KieSession session = kbase.newKieSession(conf, null);
```

For tests that need `getJournalStorage()`, downcast:
```java
JournalledKieSession js = (JournalledKieSession) session;
js.getJournalStorage();
```

- [ ] **Step 1: Add a helper method to reduce boilerplate in test files**

  In each test class, add a private helper:
  ```java
  private static KieSessionConfiguration journalConfig(JournalStorage storage) {
      KieSessionConfiguration conf = KieServices.get().newKieSessionConfiguration();
      conf.setOption(DurableSessionOption.newSession()
              .withJournalStorage(storage)
              .withCompactionInterval(Duration.ZERO));
      return conf;
  }
  ```

  And for precompiler tests, an overload:
  ```java
  private static KieSessionConfiguration journalConfig(JournalStorage storage,
                                                        ModifyLambdaRegistry registry) {
      KieSessionConfiguration conf = KieServices.get().newKieSessionConfiguration();
      conf.setOption(DurableSessionOption.newSession()
              .withJournalStorage(storage)
              .withModifyLambdaRegistry(registry)
              .withCompactionInterval(Duration.ZERO));
      return conf;
  }
  ```

- [ ] **Step 2: Migrate JournalledKieSessionTest (7 tests)**

  Replace all `JournalledSessionFactory.open(...)` calls. Remove `JournalledKieSession` type from `try` declarations — use `KieSession`. Remove imports of `JournalledSessionFactory` and `JournalledKieSession`.

- [ ] **Step 3: Migrate JournalledKieSessionRestoreTest (8 tests)**

  Same pattern. Remove `JournalledSessionFactory` and `JournalledKieSession` imports.

- [ ] **Step 4: Migrate ChronicleJournalledKieSessionIT (5 tests)**

  Same pattern. The precompiler tests use the `journalConfig(storage, registry)` overload.

- [ ] **Step 5: Remove ChroniclePageRetirementIT dependency on JournalledSessionFactory**

  `ChroniclePageRetirementIT` imports `JournalledSessionFactory` and `JournalledKieSession` but doesn't use them in any test method (it works directly with storage). Remove the unused imports.

- [ ] **Step 6: Build and verify**

  Run: `mvn clean install` from the project root.
  Expected: BUILD SUCCESS — all tests pass with the new API. No test references `JournalledSessionFactory` anymore.

- [ ] **Step 7: Commit**

  ```
  refactor: migrate all tests to kbase.newKieSession(conf, null) API

  Refs #44
  ```

---

### Task 6: Delete JournalledSessionFactory and remove legacy path

**Files:**
- Delete: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledSessionFactory.java`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledRuntimeComponentFactory.java`

**Interfaces:**
- Consumes: All tests migrated (Task 5) — no remaining references to `JournalledSessionFactory`

- [ ] **Step 1: Verify no remaining references**

  Run: `grep -rn "JournalledSessionFactory" drools-journal-*/src --include="*.java"`
  Expected: Only hits in `JournalledSessionFactory.java` itself and `JournalledRuntimeComponentFactory.java` (the `JOURNAL_KEY` reference in the legacy path).

- [ ] **Step 2: Delete JournalledSessionFactory.java**

  Remove `drools-journal-core/src/main/java/org/drools/journal/core/JournalledSessionFactory.java`.

- [ ] **Step 3: Remove the legacy environment-based path from JournalledRuntimeComponentFactory**

  Remove the `createLegacySession()` method and the environment check from `createStatefulSession()`:

  ```java
  @Override
  public InternalWorkingMemory createStatefulSession(final RuleBase ruleBase,
                                                     final Environment environment,
                                                     final SessionConfiguration sessionConfig,
                                                     final boolean fromPool) {
      DurableSessionOption opt = sessionConfig.getOption(DurableSessionOption.KEY);
      if (opt == null) {
          return super.createStatefulSession(ruleBase, environment, sessionConfig, fromPool);
      }
      return createJournalledSession(ruleBase, environment, sessionConfig, fromPool, opt);
  }
  ```

  Remove the `JOURNAL_KEY` import.

- [ ] **Step 4: Build and verify**

  Run: `mvn clean install` from the project root.
  Expected: BUILD SUCCESS — all tests pass with `JournalledSessionFactory` gone.

- [ ] **Step 5: Commit**

  ```
  feat: remove JournalledSessionFactory — unified API complete

  Session creation now uses the standard kbase.newKieSession(conf, null)
  contract exclusively. JournalledSessionFactory and the environment-based
  activation path are deleted.

  Closes #44
  ```

# DRL Precompiler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `JournalDrlPrecompiler` — a standalone DRL text → DRL text transformer that rewrites `modify` blocks to emit delta-based `ModifyRecord`s, replacing full-object snapshots with compact setter-parameter captures.

**Architecture:** The precompiler parses DRL via `DrlParser` (from `drools-drl`), walks rule descriptors to find modify blocks in consequence text, extracts setter names and argument expressions, creates `MethodHandleModifyLambda` instances for replay, registers them in `ModifyLambdaRegistry`, and injects `stageModify()` calls into the rewritten DRL. The staging mechanism uses a pending-modify field: `stageModify()` stores delta data, and the existing `objectUpdated` listener consumes it to write a `ModifyRecord` instead of a full `InsertRecord`.

**Tech Stack:** Java 21, Drools DRL parser (`drools-drl-ast`, `drools-drl-parser`), `java.lang.invoke.MethodHandle`, JUnit 5, AssertJ

## Global Constraints

- Java 21 (`maven.compiler.release=21`)
- License header: `Copyright (c) 2026 Drools Journal Authors` (Apache 2.0)
- Always `mvn clean install` — never incremental builds
- `final` on fields and parameters only, not local variables
- Test naming: `action_context_expectedResult` (three camelCase segments)
- All commits reference issue: `Refs #17`
- Push to origin after each commit

---

### Task 1: MethodHandleModifyLambda

**Files:**
- Create: `drools-journal-core/src/main/java/org/drools/journal/core/MethodHandleModifyLambda.java`
- Test: `drools-journal-core/src/test/java/org/drools/journal/core/MethodHandleModifyLambdaTest.java`

**Interfaces:**
- Consumes: `ModifyLambda` interface (`void apply(Object fact, Object[] params)`)
- Produces: `MethodHandleModifyLambda(Class<?> factType, String... setterNames)` — used by `JournalDrlPrecompiler` in Task 3 to create lambdas from parsed modify blocks

The test needs a simple domain class. The existing integration tests in `drools-journal-tests` use domain objects that require a Drools dependency. For this unit test, define a static inner class.

- [ ] **Step 1: Write the test class with first test**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * ... (Apache 2.0 header)
 */
package org.drools.journal.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodHandleModifyLambdaTest {

    public static class Person {
        private int age;
        private String name;

        public Person(int age, String name) {
            this.age = age;
            this.name = name;
        }

        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    void apply_singleSetter_modifiesFact() {
        MethodHandleModifyLambda lambda =
                new MethodHandleModifyLambda(Person.class, "setAge");
        Person person = new Person(20, "Alice");

        lambda.apply(person, new Object[]{ 30 });

        assertThat(person.getAge()).isEqualTo(30);
        assertThat(person.getName()).isEqualTo("Alice");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn clean install` from project root.
Expected: Compilation error — `MethodHandleModifyLambda` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * ... (Apache 2.0 header)
 */
package org.drools.journal.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;

public class MethodHandleModifyLambda implements ModifyLambda {

    private final MethodHandle[] setters;

    public MethodHandleModifyLambda(final Class<?> factType, final String... setterNames) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        setters = new MethodHandle[setterNames.length];
        for (int i = 0; i < setterNames.length; i++) {
            setters[i] = resolveSetter(lookup, factType, setterNames[i]);
        }
    }

    @Override
    public void apply(final Object fact, final Object[] params) {
        for (int i = 0; i < setters.length; i++) {
            try {
                setters[i].invoke(fact, params[i]);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to invoke setter at index " + i, e);
            }
        }
    }

    private static MethodHandle resolveSetter(final MethodHandles.Lookup lookup,
                                              final Class<?> factType,
                                              final String setterName) {
        Method method = Arrays.stream(factType.getMethods())
                .filter(m -> m.getName().equals(setterName))
                .filter(m -> m.getParameterCount() == 1)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No single-arg method '" + setterName + "' on " + factType.getName()));
        try {
            return lookup.unreflect(method);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access " + setterName + " on " + factType.getName(), e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn clean install` from project root.
Expected: All tests pass including the new `apply_singleSetter_modifiesFact`.

- [ ] **Step 5: Add tests for multiple setters, unknown setter, and round-trip with registry**

```java
@Test
void apply_multipleSetters_modifiesAllProperties() {
    MethodHandleModifyLambda lambda =
            new MethodHandleModifyLambda(Person.class, "setAge", "setName");
    Person person = new Person(20, "Alice");

    lambda.apply(person, new Object[]{ 30, "Bob" });

    assertThat(person.getAge()).isEqualTo(30);
    assertThat(person.getName()).isEqualTo("Bob");
}

@Test
void constructor_unknownSetter_throwsIllegalArgument() {
    assertThatThrownBy(() ->
            new MethodHandleModifyLambda(Person.class, "setNoSuchField"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("setNoSuchField");
}

@Test
void registryRoundTrip_lookupReturnsSameLambda() {
    MethodHandleModifyLambda lambda =
            new MethodHandleModifyLambda(Person.class, "setAge");
    ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
    registry.register("Rule_Test_modify_0", lambda);

    ModifyLambda looked = registry.lookup("Rule_Test_modify_0");

    Person person = new Person(20, "Alice");
    looked.apply(person, new Object[]{ 99 });
    assertThat(person.getAge()).isEqualTo(99);
}
```

- [ ] **Step 6: Run tests, verify all pass**

Run: `mvn clean install` from project root.
Expected: All 4 tests in `MethodHandleModifyLambdaTest` pass.

- [ ] **Step 7: Commit**

```
git add drools-journal-core/src/main/java/org/drools/journal/core/MethodHandleModifyLambda.java \
       drools-journal-core/src/test/java/org/drools/journal/core/MethodHandleModifyLambdaTest.java
git commit -m "feat: add MethodHandleModifyLambda for modify-block replay

Refs #17"
git push origin
```

---

### Task 2: stageModify on JournalStorage and listener integration

**Files:**
- Modify: `drools-journal-api/src/main/java/org/drools/journal/api/JournalStorage.java` — add `stageModify()` and `consumeStagedModify()`
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournallingRuntimeEventListener.java` — modify `objectUpdated()` to check for staged data
- Modify: `drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalStorage.java` — implement `stageModify()` and `consumeStagedModify()`
- Test: `drools-journal-core/src/test/java/org/drools/journal/core/JournallingRuntimeEventListenerTest.java` — add tests for staged modify flow

**Interfaces:**
- Consumes: `JournalStorage.modify(long, String, byte[])` (existing)
- Produces: `JournalStorage.stageModify(String lambdaClassRef, Object[] params)` and `JournalStorage.consumeStagedModify()` — used by DRL consequences (via global) and by the listener

The staging is on `JournalStorage` because DRL accesses it as a global. The listener already holds a reference to the same storage instance, so it can call `consumeStagedModify()`. A simple record holds the staged data:

- [ ] **Step 1: Define the staged modify data holder and add methods to JournalStorage**

Add to `JournalStorage.java` after the existing `modify` method:

```java
record StagedModify(String lambdaClassRef, Object[] params) {}

void stageModify(String lambdaClassRef, Object[] params);

StagedModify consumeStagedModify();
```

- [ ] **Step 2: Implement in InMemoryJournalStorage**

Add fields and methods to `InMemoryJournalStorage`:

```java
private StagedModify stagedModify;

@Override
public synchronized void stageModify(final String lambdaClassRef, final Object[] params) {
    stagedModify = new StagedModify(lambdaClassRef, params);
}

@Override
public synchronized StagedModify consumeStagedModify() {
    StagedModify result = stagedModify;
    stagedModify = null;
    return result;
}
```

- [ ] **Step 3: Write failing test for objectUpdated with staged modify**

Add to `JournallingRuntimeEventListenerTest.java`:

```java
@Test
void objectUpdated_withStagedModify_writesModifyRecord() {
    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    JournallingRuntimeEventListener listener =
            new JournallingRuntimeEventListener(storage, new EmbedStrategy());

    storage.insert(1L, new EmbedStrategy().store("initial", null));

    storage.stageModify("Rule_Test_modify_0", new Object[]{ 30, "Bob" });

    // simulate objectUpdated event
    // ... (use existing test patterns from JournallingRuntimeEventListenerTest)

    // assert that the last record is a ModifyRecord, not an InsertRecord
    try (JournalScanner scanner = storage.scan(0)) {
        // skip the initial insert
        scanner.next();
        JournalRecord record = scanner.next();
        assertThat(record).isInstanceOf(ModifyRecord.class);
        ModifyRecord modify = (ModifyRecord) record;
        assertThat(modify.lambdaClassRef()).isEqualTo("Rule_Test_modify_0");
    }
}
```

Note: the exact test setup depends on how `ObjectUpdatedEvent` is constructed in the existing test class. Follow the existing test patterns.

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn clean install` from project root.
Expected: Fails — `objectUpdated` still writes an `InsertRecord`.

- [ ] **Step 5: Modify objectUpdated in JournallingRuntimeEventListener**

Replace the current `objectUpdated` implementation:

```java
@Override
public void objectUpdated(final ObjectUpdatedEvent event) {
    InternalFactHandle handle = (InternalFactHandle) event.getFactHandle();
    JournalStorage.StagedModify staged = journal.consumeStagedModify();
    if (staged != null) {
        byte[] serialized = serializeParams(staged.params());
        journal.modify(handle.getId(), staged.lambdaClassRef(), serialized);
    } else {
        journal.insert(handle.getId(), strategy.store(event.getObject(), handle));
    }
}

private static byte[] serializeParams(final Object[] params) {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
         ObjectOutputStream oos = new ObjectOutputStream(bos)) {
        oos.writeObject(params);
        return bos.toByteArray();
    } catch (IOException e) {
        throw new RuntimeException("Failed to serialize modify parameters", e);
    }
}
```

Add the necessary imports (`ByteArrayOutputStream`, `ObjectOutputStream`, `IOException`).

- [ ] **Step 6: Run tests to verify all pass**

Run: `mvn clean install` from project root.
Expected: New test passes. All existing tests still pass (non-staged path unchanged).

- [ ] **Step 7: Add test for fallback — objectUpdated without staging writes InsertRecord**

```java
@Test
void objectUpdated_withoutStagedModify_writesInsertRecord() {
    // same setup, but do NOT call stageModify before the update event
    // assert the record is InsertRecord, not ModifyRecord
}
```

- [ ] **Step 8: Run tests, verify all pass**

Run: `mvn clean install` from project root.
Expected: All tests pass.

- [ ] **Step 9: Commit**

```
git add drools-journal-api/src/main/java/org/drools/journal/api/JournalStorage.java \
       drools-journal-core/src/main/java/org/drools/journal/core/JournallingRuntimeEventListener.java \
       drools-journal-core/src/test/java/org/drools/journal/core/InMemoryJournalStorage.java \
       drools-journal-core/src/test/java/org/drools/journal/core/JournallingRuntimeEventListenerTest.java
git commit -m "feat: add stageModify/consumeStagedModify for delta-based modify capture

objectUpdated now writes ModifyRecord when modify data has been staged,
falls back to InsertRecord snapshot when it hasn't.

Refs #17"
git push origin
```

---

### Task 3: JournalDrlPrecompiler

**Files:**
- Create: `drools-journal-core/src/main/java/org/drools/journal/core/JournalDrlPrecompiler.java`
- Modify: `drools-journal-core/pom.xml` — add `drools-drl-parser` dependency if not already present
- Test: `drools-journal-core/src/test/java/org/drools/journal/core/JournalDrlPrecompilerTest.java`

**Interfaces:**
- Consumes: `ModifyLambdaRegistry.register(String, ModifyLambda)`, `MethodHandleModifyLambda(Class<?>, String...)` (both from Task 1)
- Produces: `JournalDrlPrecompiler.rewrite(String drl, ModifyLambdaRegistry registry, ClassLoader classLoader)` → rewritten DRL string

The precompiler operates on the DRL consequence text as a string. The DRL parser (`DrlParser`) gives us `PackageDescr` → `RuleDescr` → consequence as a string. The consequence text is not further parsed into an AST by `DrlParser` — it's a raw string. The precompiler needs to find modify blocks within that string.

Drools' own `MVELConsequenceBuilder.rewriteModify()` demonstrates how to parse modify blocks from consequence text: find `modify`, extract the target expression from the parentheses, extract the setter expressions from the braces. The precompiler follows the same text-scanning approach.

For each modify block found:
1. Extract the target variable name (e.g., `$p`)
2. Extract each setter call expression (e.g., `setAge($newAge)`)
3. Parse setter names and argument expressions from each call
4. Resolve the fact type from `PatternDescr.getObjectType()` + `PatternDescr.getIdentifier()` in the LHS
5. Create `MethodHandleModifyLambda` and register it
6. Append a `stageModify()` call after the modify block in the consequence text
7. Insert `global org.drools.journal.api.JournalStorage journal;` into the DRL

- [ ] **Step 1: Add drools-drl-parser dependency to pom.xml if needed**

Check if `drools-drl-parser` or `drools-drl-ast` is already a transitive dependency. If not, add to `drools-journal-core/pom.xml`:

```xml
<dependency>
    <groupId>org.drools</groupId>
    <artifactId>drools-drl-parser</artifactId>
</dependency>
```

- [ ] **Step 2: Write the test class with a simple domain POJO**

The test needs a fact type on the classpath. Reuse the `Person` inner class pattern or create a test-scoped POJO. The `ClassLoader` passed to the precompiler must be able to resolve it.

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * ... (Apache 2.0 header)
 */
package org.drools.journal.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalDrlPrecompilerTest {

    public static class Person {
        private int age;
        private String name;

        public Person() {}
        public Person(int age, String name) { this.age = age; this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    void rewrite_singleModifyBlock_injectsStageModifyCall() {
        String drl = """
                package org.drools.journal.core;
                import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

                rule "UpdateAge"
                when
                    $p : Person(age < 30)
                then
                    modify($p) {
                        setAge(30)
                    }
                end
                """;

        ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
        String rewritten = JournalDrlPrecompiler.rewrite(
                drl, registry, getClass().getClassLoader());

        assertThat(rewritten).contains("global org.drools.journal.api.JournalStorage journal;");
        assertThat(rewritten).contains("journal.stageModify(");
        assertThat(rewritten).contains("Rule_UpdateAge_modify_0");
        assertThat(registry.lookup("Rule_UpdateAge_modify_0"))
                .isInstanceOf(MethodHandleModifyLambda.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn clean install` from project root.
Expected: Compilation error — `JournalDrlPrecompiler` does not exist.

- [ ] **Step 4: Write the precompiler implementation**

```java
/*
 * Copyright (c) 2026 Drools Journal Authors
 * ... (Apache 2.0 header)
 */
package org.drools.journal.core;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.drools.drl.ast.descr.PackageDescr;
import org.drools.drl.ast.descr.PatternDescr;
import org.drools.drl.ast.descr.RuleDescr;
import org.drools.drl.parser.DrlParser;

public final class JournalDrlPrecompiler {

    private static final String GLOBAL_DECL =
            "global org.drools.journal.api.JournalStorage journal;";

    private JournalDrlPrecompiler() {}

    public static String rewrite(final String drl,
                                 final ModifyLambdaRegistry registry,
                                 final ClassLoader classLoader) {
        PackageDescr pkg = parseDrl(drl);
        String result = drl;
        int modifyIndex = 0;

        for (RuleDescr rule : pkg.getRules()) {
            String consequence = (String) rule.getConsequence();
            if (consequence == null || !consequence.contains("modify")) {
                continue;
            }

            String rewritten = rewriteConsequence(
                    consequence, rule, pkg, registry, classLoader, modifyIndex);
            if (!rewritten.equals(consequence)) {
                result = result.replace(consequence, rewritten);
                modifyIndex++;
            }
        }

        if (modifyIndex > 0) {
            result = injectGlobalDeclaration(result, pkg);
        }

        return result;
    }

    // Parse DRL text into PackageDescr using DrlParser.
    // Extract modify blocks from consequence text.
    // For each modify block:
    //   - find target variable and setter calls
    //   - resolve fact type from LHS patterns
    //   - create MethodHandleModifyLambda and register
    //   - append stageModify() call
    // Implementation details follow the patterns from
    // MVELConsequenceBuilder.rewriteModify() for text scanning.
    // ... (full implementation)
}
```

The full implementation of `rewriteConsequence`, `parseDrl`, `injectGlobalDeclaration`, and the modify-block text-scanning logic follows the patterns from `MVELConsequenceBuilder.rewriteModify()` (text-based extraction of target expression and setter expressions from `modify(target) { expr, expr }` blocks) and `DataStoreUpdateRewriter` (inject call after the block, serialize back).

Key helper methods:
- `parseDrl(String)` — calls `new DrlParser().parse(null, drl)` returning `PackageDescr`
- `findModifyBlocks(String consequence)` — scans consequence text for `modify(...)  { ... }` blocks, returns list of `(targetVar, setterExpressions[], startPos, endPos)`
- `resolveFactType(String varName, RuleDescr, PackageDescr, ClassLoader)` — walks LHS `PatternDescr` nodes matching `identifier == varName`, resolves `objectType` via `Class.forName` against imports
- `extractSetterName(String expression)` — extracts method name from `setAge(30)` → `"setAge"`
- `extractSetterArg(String expression)` — extracts argument text from `setAge(30)` → `"30"`
- `buildStageModifyCall(String lambdaClassRef, List<String> argExpressions)` — builds `journal.stageModify("Rule_X_modify_0", new Object[]{ 30 });`

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn clean install` from project root.
Expected: `rewrite_singleModifyBlock_injectsStageModifyCall` passes.

- [ ] **Step 6: Add tests for additional scenarios**

```java
@Test
void rewrite_multipleSetters_capturesAllArgs() {
    String drl = """
            package org.drools.journal.core;
            import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

            rule "UpdateBoth"
            when
                $p : Person()
            then
                modify($p) {
                    setAge(30),
                    setName("Bob")
                }
            end
            """;

    ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
    String rewritten = JournalDrlPrecompiler.rewrite(
            drl, registry, getClass().getClassLoader());

    assertThat(rewritten).contains("new Object[]{ 30, \"Bob\" }");

    // verify lambda replays correctly
    Person person = new Person(20, "Alice");
    registry.lookup("Rule_UpdateBoth_modify_0").apply(person, new Object[]{ 30, "Bob" });
    assertThat(person.getAge()).isEqualTo(30);
    assertThat(person.getName()).isEqualTo("Bob");
}

@Test
void rewrite_noModifyBlock_returnsUnchanged() {
    String drl = """
            package org.drools.journal.core;
            import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

            rule "SimpleInsert"
            when
                $p : Person()
            then
                System.out.println($p);
            end
            """;

    ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
    String rewritten = JournalDrlPrecompiler.rewrite(
            drl, registry, getClass().getClassLoader());

    assertThat(rewritten).isEqualTo(drl);
    assertThat(rewritten).doesNotContain("global");
}

@Test
void rewrite_variableArgs_preservesExpressionText() {
    String drl = """
            package org.drools.journal.core;
            import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

            rule "UpdateWithVar"
            when
                $p : Person()
                $newAge : Integer()
            then
                modify($p) {
                    setAge($newAge)
                }
            end
            """;

    ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
    String rewritten = JournalDrlPrecompiler.rewrite(
            drl, registry, getClass().getClassLoader());

    assertThat(rewritten).contains("new Object[]{ $newAge }");
}

@Test
void rewrite_globalDeclInjectedOnce_multipleRules() {
    String drl = """
            package org.drools.journal.core;
            import org.drools.journal.core.JournalDrlPrecompilerTest.Person;

            rule "Rule1"
            when $p : Person() then modify($p) { setAge(1) } end

            rule "Rule2"
            when $p : Person() then modify($p) { setAge(2) } end
            """;

    ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
    String rewritten = JournalDrlPrecompiler.rewrite(
            drl, registry, getClass().getClassLoader());

    int count = rewritten.split("global org.drools.journal.api.JournalStorage journal;", -1).length - 1;
    assertThat(count).isEqualTo(1);

    assertThat(registry.lookup("Rule_Rule1_modify_0")).isNotNull();
    assertThat(registry.lookup("Rule_Rule2_modify_0")).isNotNull();
}
```

- [ ] **Step 7: Run tests, verify all pass**

Run: `mvn clean install` from project root.
Expected: All tests pass.

- [ ] **Step 8: Commit**

```
git add drools-journal-core/pom.xml \
       drools-journal-core/src/main/java/org/drools/journal/core/JournalDrlPrecompiler.java \
       drools-journal-core/src/test/java/org/drools/journal/core/JournalDrlPrecompilerTest.java
git commit -m "feat: add JournalDrlPrecompiler for DRL modify-block rewriting

Parses DRL via DrlParser, extracts modify blocks, creates
MethodHandleModifyLambda instances, registers them, and injects
stageModify() calls into the rewritten DRL.

Refs #17"
git push origin
```

---

### Task 4: Factory wiring and end-to-end integration test

**Files:**
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/JournalledSessionFactory.java` — add overload accepting `ModifyLambdaRegistry`, set journal global
- Modify: `drools-journal-core/src/main/java/org/drools/journal/core/RestoreEngine.java` — apply `ModifyRecord` lambdas during flush (currently only checks for existence, does not apply)
- Test: `drools-journal-tests/src/test/java/org/drools/journal/test/JournalledKieSessionTest.java` — add end-to-end test using precompiler

**Interfaces:**
- Consumes: `JournalDrlPrecompiler.rewrite(String, ModifyLambdaRegistry, ClassLoader)` (Task 3), `stageModify`/`consumeStagedModify` (Task 2), `MethodHandleModifyLambda` (Task 1)
- Produces: Complete end-to-end path: precompile DRL → build KieBase → open session → fire rule with modify → journal contains `ModifyRecord` → restore replays correctly

- [ ] **Step 1: Add `ModifyLambdaRegistry` parameter to JournalledSessionFactory.open()**

Add a new overload to `JournalledSessionFactory`:

```java
public static JournalledKieSession open(final KieBase kbase,
                                        final JournalStorage storage,
                                        final ModifyLambdaRegistry registry) {
    return open(kbase, storage, registry, CompactionCoordinator.DEFAULT_INTERVAL);
}

public static JournalledKieSession open(final KieBase kbase,
                                        final JournalStorage storage,
                                        final ModifyLambdaRegistry registry,
                                        final Duration compactionInterval) {
    Environment env = KieServices.get().newEnvironment();
    env.set(JOURNAL_KEY, storage);
    JournalledKieSession session = (JournalledKieSession) kbase.newKieSession(null, env);
    session.setGlobal("journal", storage);
    if (!storage.isEmpty()) {
        restore(session, storage, registry);
    }
    JournallingRuntimeEventListener listener =
            new JournallingRuntimeEventListener(storage, new EmbedStrategy());
    session.addEventListener((org.kie.api.event.rule.RuleRuntimeEventListener) listener);
    session.addEventListener((org.kie.api.event.rule.AgendaEventListener) listener);
    CompactionCoordinator coordinator = new CompactionCoordinator(storage, compactionInterval);
    coordinator.start();
    session.setCompactionCoordinator(coordinator);
    return session;
}
```

Update the existing `open()` methods to delegate to the new overload with `new ModifyLambdaRegistry()`.
Update the private `restore()` method to accept and use the registry parameter.

- [ ] **Step 2: Fix RestoreEngine.flush() to actually apply ModifyRecord lambdas**

The current `flush()` only checks for existence of the lambda but does not apply it. Update the `ModifyRecord` case:

```java
} else if (record instanceof ModifyRecord modify) {
    ModifyLambda lambda = lambdaRegistry.lookup(modify.lambdaClassRef());
    Object fact = survivingFacts.get(modify.factHandleId());
    if (fact != null) {
        Object[] params = deserializeParams(modify.parameters());
        lambda.apply(fact, params);
    }
}
```

Add the `deserializeParams` helper:

```java
private static Object[] deserializeParams(final byte[] data) {
    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
        return (Object[]) ois.readObject();
    } catch (IOException | ClassNotFoundException e) {
        throw new RuntimeException("Failed to deserialize modify parameters", e);
    }
}
```

- [ ] **Step 3: Write end-to-end integration test**

Add to `JournalledKieSessionTest.java` in `drools-journal-tests`:

```java
@Test
void modifyWithPrecompiler_writesModifyRecord_restoresCorrectly() {
    String drl = """
            package org.drools.journal.test;
            import org.drools.journal.test.JournalledKieSessionTest.TestFact;

            rule "SetProcessed"
            when
                $f : TestFact(processed == false)
            then
                modify($f) {
                    setProcessed(true)
                }
            end
            """;

    ModifyLambdaRegistry registry = new ModifyLambdaRegistry();
    String rewritten = JournalDrlPrecompiler.rewrite(
            drl, registry, getClass().getClassLoader());

    KieBase kbase = new KieHelper()
            .addContent(rewritten, ResourceType.DRL)
            .build();

    InMemoryJournalStorage storage = new InMemoryJournalStorage();
    try (JournalledKieSession session =
                 JournalledSessionFactory.open(kbase, storage, registry, Duration.ZERO)) {
        session.insert(new TestFact("item1", false));
        session.fireAllRules();
    }

    // Verify journal contains ModifyRecord (not just InsertRecord snapshots)
    // Verify restore produces correct state
    // ... (assert using JournalPrinter or direct scan)
}
```

Note: `TestFact` may need to be created or adapted from existing test domain classes. Check the existing test patterns in `JournalledKieSessionTest.java`.

- [ ] **Step 4: Run tests, verify all pass**

Run: `mvn clean install` from project root.
Expected: All tests pass including the new end-to-end test.

- [ ] **Step 5: Commit**

```
git add drools-journal-core/src/main/java/org/drools/journal/core/JournalledSessionFactory.java \
       drools-journal-core/src/main/java/org/drools/journal/core/RestoreEngine.java \
       drools-journal-tests/src/test/java/org/drools/journal/test/JournalledKieSessionTest.java
git commit -m "feat: wire precompiler into session factory and fix ModifyRecord restore

JournalledSessionFactory.open() accepts ModifyLambdaRegistry, sets
journal global. RestoreEngine.flush() now applies ModifyRecord lambdas
to surviving facts. End-to-end test confirms modify → ModifyRecord →
restore round-trip.

Refs #17"
git push origin
```

---

### Task 5: ChronicleJournalStorage stageModify and contract compliance

**Files:**
- Modify: `drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java` — implement `stageModify()` and `consumeStagedModify()`
- Test: verify via existing `ChronicleStorageContractTest` (extends `JournalStorageContractTest`) — add staging test if the contract test base gains one

**Interfaces:**
- Consumes: `JournalStorage.stageModify(String, Object[])`, `JournalStorage.consumeStagedModify()` (from Task 2)
- Produces: Chronicle backend that passes the full storage contract including staging

- [ ] **Step 1: Implement stageModify and consumeStagedModify in ChronicleJournalStorage**

Add fields and methods:

```java
private JournalStorage.StagedModify stagedModify;

@Override
public void stageModify(final String lambdaClassRef, final Object[] params) {
    stagedModify = new StagedModify(lambdaClassRef, params);
}

@Override
public StagedModify consumeStagedModify() {
    StagedModify result = stagedModify;
    stagedModify = null;
    return result;
}
```

- [ ] **Step 2: Run full build to verify contract test passes**

Run: `mvn clean install` from project root.
Expected: All tests pass including `ChronicleStorageContractTest`.

- [ ] **Step 3: Commit**

```
git add drools-journal-chronicle/src/main/java/org/drools/journal/chronicle/ChronicleJournalStorage.java
git commit -m "feat: implement stageModify for Chronicle backend

Refs #17"
git push origin
```

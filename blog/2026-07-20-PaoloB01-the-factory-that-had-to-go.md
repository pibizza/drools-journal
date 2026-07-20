---
layout: post
title: "The Factory That Had to Go"
date: 2026-07-20
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [api-design, session-api, drools-spi]
---

## The Factory That Had to Go

`JournalledSessionFactory.open(kbase, storage)` has been the entry point since day one. It works — it smuggles the storage via `Environment`, calls `kbase.newKieSession(null, env)`, then does restore, listener wiring, and compaction on the returned session. But it's a custom API that looks nothing like how drools-reliability creates durable sessions. If drools-journal is going to be a credible alternative, I wanted the same contract: `kbase.newKieSession()` with configuration, no factory class.

Issue #44 had all the details already — this was about execution.

## The config path that wasn't

I brought Claude in to figure out how to wire `DurableSessionOption` into `SessionConfiguration`. The plan was straightforward: add an `OptionKey`, have the Drools composite config store it, and read it in the SPI factory. Clean, symmetric with `PersistedSessionOption`.

It didn't work. We dug through `SessionConfiguration`, `CompositeConfiguration`, `RuleBaseFactory`, and `SessionConfigurationFactories` — four layers deep. The picture that emerged:

- `PersistedSessionOption` has a dedicated `private` field and a hardcoded `case` in `SessionConfiguration.setOption()`. It's first-class infrastructure, not a plugin.
- The `default` case in `setOption()` dispatches to `compConfig`, which looks up a delegate by `option.type()`. Only three types exist: `Base`, `Rule`, `Flow`. No SPI to add a fourth.
- `CompositeConfiguration`'s factory map is private with no public mutator. The factories are passed at construction time in `RuleBaseFactory.newKnowledgeSessionConfiguration()` — hardcoded.
- Subclassing `PersistedSessionOption` to piggyback on its switch case? Both constructors are `private`.
- Creating a custom `CompositeSessionConfiguration` with a fourth factory? `kbase.newKieSession()` calls `conf.as(SessionConfiguration.KEY)`, which extracts only the `Base` delegate. The factory method never sees the custom delegate.

Five approaches, all dead ends. `SessionConfiguration` is closed to extension from outside drools-core.

## The Environment, again

The pragmatic answer was what we'd been trying to avoid: pass the `DurableSessionOption` via `Environment`. But this time as a proper typed object, not the raw storage smuggling that `JournalledSessionFactory` used:

```java
Environment env = KieServices.get().newEnvironment();
env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession()
        .withJournalStorage(storage)
        .withCompactionInterval(Duration.ZERO));
KieSession session = kbase.newKieSession(null, env);
```

All the config on one object. The factory reads it, does everything — restore, listener, compaction. `JournalledSessionFactory` becomes unnecessary.

## The ModifyLambdaRegistry detour

A question surfaced: the precompiler tests pass a `ModifyLambdaRegistry` to the factory. Where does it go with the new API?

The registry maps lambda class refs to method-handle wrappers — needed at restore time to replay `ModifyRecord` entries. I pointed out that the rewritten DRL has everything needed to reconstruct it. Claude went further and found `ConsequenceMetaData` on `RuleImpl` — the Drools compiler already stores modify statement metadata (fact class + field names) on the compiled rules. In theory, the KieBase itself is a sufficient source of truth.

But the MVEL/ASM compiler path question, the exec-model path uncertainty — it was scope creep. I pulled back: add `withModifyLambdaRegistry()` to `DurableSessionOption`, move the three small classes (`ModifyLambda`, `ModifyLambdaRegistry`, `JournalSchemaEvolutionException`) to the API module. Simple. The auto-discovery idea is logged for later.

## Six commits, one deletion

The implementation was mechanical once the design settled. Six commits:

1. Move `ModifyLambda` + registry to `drools-journal-api`
2. Add `compactionInterval` and `modifyLambdaRegistry` to `DurableSessionOption`
3. Pass storage as a constructor parameter to `JournalledKieSession`
4. Config-based activation in `JournalledRuntimeComponentFactory` — TDD, red then green
5. Migrate all 25 test call sites across 3 files
6. Delete `JournalledSessionFactory`

161 tests pass. PR #49 merged to main.

## What it means

The session creation API is now `kbase.newKieSession(null, env)` — standard Drools. Not `kbase.newKieSession(conf, null)` as I originally wanted, because `SessionConfiguration` is closed. But the factory class is gone, and the entire initialization lives inside the SPI where it belongs.

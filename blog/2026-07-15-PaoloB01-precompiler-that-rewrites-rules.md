---
layout: post
title: "The Precompiler That Rewrites Rules"
date: 2026-07-15
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [precompiler, modify, drl-rewriting, tdd]
---

## The Precompiler That Rewrites Rules

The modify-as-delta capture had been sitting in the plan as "Phase 5 —
deferred, upstream Drools patch" since April. The original idea was to patch
`Consequence.addUpdateBitMask()` in `drools-model-codegen`. Last session's
investigation killed that — no plugin hooks in the compilation pipeline, no
DRL source retained in KieBase. But it also surfaced a simpler path: a
standalone precompiler that transforms DRL text before `KieHelper.build()`.

Today I wanted to see if we could actually build it.

## Two reference implementations

I'd been pointed to two codebases that do AST-level rewriting of DRL
consequence blocks. We cloned both to study them.

`DataStoreUpdateRewriter` in tkobayas's `drlx-parser` showed the clean
pattern: parse consequence body into a JavaParser `BlockStmt`, walk the AST,
replace matching nodes, serialize back. `MVELToJavaRewriter` in MVEL showed
how `ModifyStatement` gets expanded — setter calls scoped to the target
object, `update()` appended.

The interesting finding: DRL consequences are parsed by two separate
grammars. `DrlParser` handles the rule envelope — package, imports, LHS
patterns — and stores the consequence as a raw string. `MvelParser` handles
the Java/MVEL syntax inside it, including `modify` blocks as proper AST
nodes. Nobody parses both in one pass.

## The design conversation

The brainstorm surfaced several decisions quickly. DRL7 first, DRLX later.
`DrlParser` + `MvelParser` for parsing (Approach A). The precompiler
lives in drools-journal, not upstream.

The `ModifyLambda` question was more interesting. The precompiler runs
before compilation — it has setter names as strings but no compiled classes
to build lambdas from. I started thinking about bytecode generation or
in-memory compilation, but `MethodHandle` turned out to be the right tool.
Resolve each setter once at startup via `MethodHandle` lookup, then
invocation is JIT-optimized to near-native speed. The domain classes are on
the classpath because Drools needs them to compile the rules.

Then the crash recovery question: if the JVM crashes, how do we recreate
the lambdas? The answer is simple — rerun the precompiler on the same DRL.
Deterministic naming (`Rule_{ruleName}_modify_{index}`) means the registry
is repopulated identically.

## Where the staging goes

The original plan had `stageModify` on `JournalStorage` — the DRL consequence
calls `journal.stageModify(...)` where `journal` is the storage global, and
the listener reads from it. I didn't like this. It mixed persistence concerns
with inter-component signaling, and the storage SPI shouldn't know about
staging.

The simplest answer: make the listener itself the DRL global. The consequence
calls `stageModify` directly on the listener. `objectUpdated` reads its own
fields. Zero indirection.

## The ordering bug

Claude put the `stageModify` call *after* the modify block in the rewritten
DRL. The end-to-end test showed `INSERT id=1 Ticket(closed)` instead of the
expected `MODIFY`. The problem was obvious once I saw it — Drools expands
`modify($p) { setStatus("closed") }` into setter calls followed by
`update($p)`, which fires `objectUpdated` immediately. By the time our
staging call executed, the event had already fired and the listener had
written a full snapshot.

The fix: inject `stageModify` *before* the modify block, not after.

## String scanning vs AST manipulation

Claude's initial implementation scanned the consequence string for the word
"modify" to find insertion positions. I pushed back — we have a parsed AST
from `MvelParser`, why are we scanning strings? The `ModifyStatement` nodes
are right there.

We tried using the AST node positions, but `ModifyStatement.getRange()`
returns `(0,0)` — the MVEL parser doesn't populate token ranges for modify
nodes. So we couldn't use positions for text insertion.

The clean solution: don't insert into the string at all. Manipulate the AST
directly — add a new statement to the `BlockStmt` before the
`ModifyStatement` node — then serialize the whole block back with
`PrintUtil.printNode()`. Parse → modify → serialize. No string positions, no
scanning.

One gotcha with `PrintUtil`: the standard JavaParser `toString()` crashes on
MVEL AST nodes (`DrlNameExpr` can't be cast to `DrlVoidVisitor`). You must
use `PrintUtil.printNode()` for anything produced by `MvelParser`.

## Reusing Drools' own type resolver

The precompiler needs to resolve type names from DRL imports — `Person` in the
LHS becomes a `Class<?>` for `MethodHandle` lookup. Claude wrote a hand-rolled
`loadClass` with inner-class `$` substitution. I asked if Drools already had
this. It does: `ClassTypeResolver` in `drools-util` handles imports, inner
classes (progressively replacing `.` with `$`), wildcard imports, `java.lang`
defaults, and caching. One line replaced 30.

## What we shipped

Seven commits on `feat/17-drl-precompiler`, merged as PR #47:

- `MethodHandleModifyLambda` — generic lambda backed by `MethodHandle[]`,
  factory method pattern
- `stageModify` on the listener — staging fields, `objectUpdated` writes
  `ModifyRecord` when staged, `InsertRecord` when not
- `JournalDrlPrecompiler` — parses via `DrlParser` + `MvelParser`, AST
  manipulation for insertion, `ClassTypeResolver` for type resolution
- `JavaSerializer` — extracted shared serialize/deserialize, eliminated
  duplication across 3 classes
- `RestoreEngine` — now applies `ModifyRecord` lambdas to surviving facts
- Factory wiring — `open()` accepts `ModifyLambdaRegistry`, sets listener
  as global
- Chronicle IT — write + restore tests on the real backend

The whole pipeline works: precompile DRL → build KieBase → fire rule with
modify → journal contains `ModifyRecord` → restart → precompile again →
restore replays the lambda → fact has the correct state.

Also reviewed the entire chronicle subproject against best practices and
logged 12 findings in IDEAS.md — 3 defects, 4 contract gaps, 5 test gaps.
The `insertLogical` missing `empty = false` bug is the one I'd fix first.

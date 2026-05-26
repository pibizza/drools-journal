---
layout: post
title: "Thinking Before Restore"
date: 2026-05-21
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [restore, design, tms]
---

`RestoreEngine` is the most complex component in the whole journal. Four phases,
TMS wiring, safepoint rollback, compaction awareness. I wanted to think it through
properly before writing a line of code.

So this session was just brainstorming. No compiler, no tests — a conversation
about design.

## The One Door In

The first question was the public API. The previous session left `JournalledSessionFactory.create()`
as the only entry point. For restore, we'd need something new.

I proposed three options: explicit `create()` / `restore()` split; a constructor plus a
`restore()` call; or a single `open()` that detects the state of the journal. Then I asked
what `drools-reliability` does.

Claude went and looked. The reliability pattern wires restore through the component factory —
`ReliableSessionInitializer.initReliableSession()` is called after construction, checks
`isNewSession()`, and re-propagates from the live store if needed.

It's a reasonable pattern, but it requires the caller to signal intent. I didn't want that.
If the journal is empty, create fresh. If it has data, restore. The caller shouldn't have
to think about it.

```java
JournalledSessionFactory.open(kbase, storage, lambdaRegistry)
```

One method. Detection inside. `storage.latestPosition() == -1` means empty; anything else
means restore. The user never manages state.

## The Listener the Room Missed

Claude then proposed three ways to handle bulk Rete re-propagation during restore without
writing duplicate journal records to storage: a bypass flag on `JournalledObjectStore`,
direct Rete propagation via internals, or swapping the object store temporarily.

All three are more complicated than necessary.

I pointed out what we'd already built: `JournalledAgenda` uses an `AgendaEventListener`
to record `RuleMatchRecord` entries — the journal write happens in the listener, not in
the agenda itself. The same pattern could apply to `JournalledObjectStore`. Move the
`InsertRecord` and `RetractRecord` writes into a `JournallingRuntimeEventListener`. During
restore, don't install it. Facts flow into Rete cleanly. After restore, install it. Normal
journalling resumes.

Zero flags. Zero store swaps. The `drools-reliability` `StoresOnlySessionInitializer` does
exactly this — `populateSessionFromStorage()` runs first, listeners get added after.

## One Pass, One Discard

Safepoint rollback was the next question. Claude offered two options: a two-pass scan
(find last safepoint position, then replay up to it) or a dual-state scan (committed
snapshot plus pending delta, apply committed at end if incomplete).

I simplified both. We don't need to materialise two copies of working memory state.
We just need to accumulate raw journal records between safepoints:

```
pending = []
for each record:
    if SafepointRecord → apply pending; pending = []
    else               → pending.add(record)
// end of scan: discard pending
```

One pass. Records between safepoints are small typed objects — cheap to buffer. The
"rollback" is just not flushing the final list. If the journal ends cleanly on a safepoint,
there's nothing to discard.

## The ID Problem That Wasn't

The trickiest-sounding issue was FactHandle ID preservation. Journal records reference
original fact handle IDs. During restore, `session.insert(fact)` assigns new IDs.
`ReplayFilter` compares against the old IDs. Nothing matches.

Claude proposed forcing original IDs via low-level `FactHandleFactory` manipulation.
I asked instead: can't we just build a translation map?

`session.insert()` returns a `FactHandle`. Store `oldId → newHandle`. When building
`ReplayFilter`, translate the `RuleMatchRecord.factHandleIds[]` through the map before
passing them in. `ReplayFilter` sees only translated IDs and works normally.

No `FactHandleFactory` internals. No counter resets. Drools manages IDs naturally.
The translation map is the only bridge.

## TMS Was the Unknown

The one genuinely open question was TMS wiring. Logical inserts record a
`justifyingRuleMatchId` in their `InsertRecord`. After restore, the rule that created
the logical dependency is suppressed by `ReplayFilter` — it never fires, so Drools
never creates the dependency. If the justifying facts are later retracted, the logical
fact should be retracted too — but there's nothing tracking that anymore.

We explored the Drools internals. `TruthMaintenanceSystemImpl.readLogicalDependency()`
is explicitly commented "used when deserialising." That's us.

The `ProtobufInputMarshaller` shows the full pattern: `PBActivationsFilter` installs
before re-propagation, captures live `InternalMatch` objects for every suppressed
activation in a `tuplesCache`, then `readLogicalDependency()` is called with those
matches to wire the TMS chains. We can do the same — extend `ReplayFilter` to cache
the `InternalMatch` objects it suppresses, then use them in Phase 2.

The path exists. It's not guesswork; it follows established Drools serialisation
machinery exactly.

## The Spec

All of this went into `specs/2026-05-20-restore-engine-design.md`. Six files to touch:
`JournalledObjectStore`, a new `JournallingRuntimeEventListener`, `JournalPayloadBuilder`
(needs a `deserialize()` path), `ReplayFilter` (extended to cache matches), `RestoreEngine`
itself, and `JournalledSessionFactory` getting its `open()` method.

Implementation is next.

---
layout: post
title: "The Store That Wasn't There"
date: 2026-07-30
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
---

I merged the benchmark PR first thing — `feat/37-journal-benchmarks` into `main`,
clean fast-forward. The `epic-compaction-coordinator` branch had already been merged,
so the feature branch went directly against `main`. One less indirection.

Then I picked up #35: `ExternalRefStrategy.load()`.

## A placeholder graduating to an API

The restore-engine spec from May had explicitly deferred external-ref restore as a
v1 limitation. `load()` threw `UnsupportedOperationException` — deliberately. The
idea was always that facts stored in an external database would only keep a key in
the journal, and a caller-supplied function would retrieve them on restore. The store
half worked; the load half was a stub.

The fix itself was small. Claude and I added a `Function<ExternalRef, Object>` loader
to the `ExternalRefStrategy` constructor, symmetric with the existing
`Function<Object, String>` key supplier. Two functions in, round-trip complete.

```java
new ExternalRefStrategy(
    fact -> "key-" + fact,                    // store: fact → key
    ref -> externalStore.get(ref.dbKey()))     // load: ref → fact
```

We also decided that a null return from the loader is a data integrity problem, not a
normal case — if the journal says a fact exists in the external store and it's not
there, that's an `IllegalStateException`.

## The API surface question

The interesting part wasn't the strategy itself — it was how users configure it.
`DurableSessionOption` lives in `drools-journal-api`. `ExternalRefStrategy` lives in
`drools-journal-core`. Core depends on api, not the reverse.

So the option can't construct the strategy. I considered three approaches: expose the
strategy class directly, add per-strategy builder methods, or use factory methods on
the interface. The cleanest answer turned out to be the simplest — one method:

```java
DurableSessionOption.newSession()
    .withJournalStorage(storage)
    .withExternalRefStorage(keySupplier, loader)
```

`EmbedStrategy` is the default. The only user action is opting in to external-ref mode.
One method covers both the mode selection and the configuration. The user never sees
`ExternalRefStrategy`, `ObjectStorageStrategy`, or the `ObjectStorageMode` enum.

We removed the old `withObjectStorage(ObjectStorageMode)` from the public API — it was
a trap waiting to happen, since calling `withObjectStorage(EXTERNAL_REF)` without
providing the functions would NPE at runtime. The enum stays as an internal signal:
`withExternalRefStorage()` sets it to `EXTERNAL_REF`, and the factory reads it to
decide which strategy to build. Explicit internally, clean externally.

## Two call sites, one strategy

The factory had two places hardcoding `new EmbedStrategy()` — the write path (the
listener) and the restore path (`RestoreEngine`). Both now read from the option via
a single `buildStrategy(opt)` call. `RestoreEngine` already had a three-arg constructor
accepting a strategy, so no changes needed there.

The integration test uses a `ConcurrentHashMap` as the external store: insert a fact,
the key supplier writes to the map, session closes, session reopens, the loader
retrieves from the map, rule doesn't re-fire. Full round-trip through Chronicle.

## One asymmetry left

I noticed at the end that `EMBED` mode has no explicit setter — it's just the field
default. `EXTERNAL_REF` has `withExternalRefStorage()`, but there's no
`withEmbedStorage()` to match. It works, but it's implicit where the rest of the API
is explicit. I left it as an open question in the spec rather than act on it now.

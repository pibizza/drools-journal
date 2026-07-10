---
layout: post
title: "Designing the Chronicle Backend"
date: 2026-06-24
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [chronicle, design, serialization]
---

The divergence question I left open at the end of the last entry — storage
pages versus safepoint-driven compaction pages — got resolved in the session
between then and now. PR #29 separated those two concerns:
`JournalScanner.currentPageId()` exposes physical page identity independently
of safepoints, so Chronicle can roll its files on any schedule it wants. With
that settled, I started the Chronicle epic today: not with code, but with a
design session.

## MethodWriter Instead of BytesMarshallable

I brought Claude in to work through the serialization options. Chronicle Queue
offers `BytesMarshallable` (manual per-type), `SelfDescribingMarshallable`
(text/binary wire), and `MethodWriter`/`MethodReader` (interface-based
dispatch).

We landed on `MethodWriter`/`MethodReader`. The journal write API — `insert`,
`retract`, `ruleMatch`, `safepoint` — is already a set of named operations.
`MethodWriter` turns that into a Chronicle encoding automatically: call
`writer.insert(...)`, Chronicle records the method name and arguments as a
binary entry. `MethodReader` reads it back by dispatching to a handler that
builds `JournalRecord` instances. No manual type-tagging needed.

One constraint: `Payload` (a sealed interface) can't pass through Chronicle
Wire without making the API module depend on Chronicle's marshalling types. The
fix is `PayloadCodec` — an internal class that encodes `Payload` to `byte[]`
before the write and decodes it back in the handler.

## The Page ID Problem Claude Caught

My initial spec used `tailer.cycle()` — Chronicle's roll cycle counter — as
the logical page ID. Chronicle creates a new `.cq4` file on each cycle, so I
assumed each file boundary would serve as a page boundary.

Claude caught the flaw during spec self-review: Chronicle's roll cycle is
wall-clock-based. There's no public API to force a roll on demand. A safepoint
can't trigger a new Chronicle file — the file rolls when the clock says it
should.

The correct approach: embed the logical page ID as the first argument of every
`ChronicleWriteOps` method call. The storage tracks a string counter ("0",
"1", "2") and passes it through every write. After a safepoint, the counter
advances. The scanner's handler reads the ID back from the received argument —
no file boundary inference.

## Three Type Catches in the Plan

Once the spec was approved we moved to the implementation plan. Claude did a
self-review pass on the code and found three issues.

First: `appender.methodWriter(ChronicleWriteOps.class)` returns
`ChronicleWriteOps` directly, not `MethodWriter<ChronicleWriteOps>`. The field
declaration was a compile error.

Second: `ChronicleWriteOps` defined package-private in
`org.drools.journal.chronicle.internal` is invisible to
`ChronicleJournalStorage` in `org.drools.journal.chronicle`. Java package
visibility doesn't cross subpackage boundaries — it needs `public`.

Third: `ChronicleRecordHandler` fields accessed from the scanner have the same
problem.

None would survive a compile. Better to find them in the plan than
mid-implementation.

## The Interface That Was Already There

The design proposed `PageRollStrategy` as a new interface in
`drools-journal-chronicle`. When we read the actual source before writing the
plan, it was already in `drools-journal-api` — with a richer signature
(`RollDecision decide(PageContext context)`), and with `safepointOnly()`,
`sizeThreshold()`, `countThreshold()`, and `composite()` factory methods
already implemented. The previous session had built it as part of the physical
page ID work.

We updated the spec before it became a plan bug. The Chronicle module needs
three new implementation classes — `ChronicleWriteOps`, `ChronicleJournalStorage`,
`ChronicleJournalScanner` — plus two internal helpers. No new API additions.

The plan has four tasks, each ending with a commit: dependency setup, internal
serialization classes, storage and scanner with the full contract test suite,
and an end-to-end smoke test. Issue #14 is assigned. The next session starts
with `mvn clean install`.

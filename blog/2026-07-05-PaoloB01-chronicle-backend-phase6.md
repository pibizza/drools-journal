---
layout: post
title: "Wiring In Chronicle"
date: 2026-07-05
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [chronicle, implementation, java21, debugging]
---

## Wiring In Chronicle

The Chronicle backend is implemented. `ChronicleJournalStorage` and
`ChronicleJournalScanner` are written, tested, and merged as PR #30.
21 contract tests pass. Two end-to-end restore tests confirm that a session
can write, close, reopen, and find its facts.

The plan was written against `chronicle-queue 5.27ea1`. Before writing a line,
I asked Claude to verify the version. Claude came back with `5.27ea5` as the
latest on Maven Central. I pointed out there was a `2026.4` on Sonatype —
Chronicle had moved to a year-based release scheme. We confirmed it was still
Apache 2.0 via the POM header (`SPDX-License-Identifier: Apache-2.0`) and
went with that.

## Java 21, and the first adaptation

The plan assumed Java 17. `ChronicleJournalStorage.writeRecord()` uses pattern
matching in switch — that's Java 21. When the compile failed, I said don't
rewrite it, just bump the release. One property in the parent POM did it:

```xml
<maven.compiler.release>21</maven.compiler.release>
```

Chronicle itself pushed back next. Every test failed with:

```
java.lang.IllegalAccessException: class is not public:
    sun.nio.ch.UnixFileDispatcherImpl.map0
```

Chronicle uses `MethodHandles.lookup().unreflect()` to access mmap internals,
and Java's module system blocks it. One `--add-opens` flag doesn't fix it —
Chronicle needs 9, spread across `java.base`, `jdk.unsupported`, and
`jdk.compiler`. They're documented on their support page. Claude initially
went down a side road trying to decompile Chronicle's own JARs to find them;
I redirected it to do a web search, which took ten seconds.

The flags also need to go into both `maven-surefire-plugin` and
`maven-failsafe-plugin`. Files ending in `*IT.java` run under Failsafe,
not Surefire — silently. I found that out when the e2e tests failed after
the unit tests passed.

## Three bugs in the first test run

The contract suite has 21 cases. First run: 21 failures. The JVM flags fixed
19. Two remained.

**`currentPageId()` was one record ahead.** `ChronicleJournalScanner.advance()`
reads the next entry and dispatches it through `ChronicleRecordHandler`, setting
`handler.currentPageId` as a side effect. `next()` called `advance()` before
returning — so `currentPageId()` always reflected the buffered record, not the
one just handed back. Fix: snapshot `bufferedPageId` during `advance()` and
expose it only when `next()` consumes the buffer.

**`EmbeddedPayload.equals()` used reference equality.** Java records generate
`equals()` using `Objects.equals()` for each component. For `byte[]`, that's
reference equality. The in-memory storage had never caught this because it
returns the same array object. Chronicle deserialises a fresh array every time.
Fix: override `equals()` and `hashCode()` using `Arrays.*`.

The e2e restore tests revealed a third bug. When `ChronicleJournalStorage`
opens at an existing path, `lastWrittenPosition` initialises to -1.
`JournalledSessionFactory.open()` checks `storage.latestPosition() >= 0`
to decide whether to restore — with -1, it skips restore entirely and starts
fresh. Fix: call `queue.lastIndex()` in the constructor. Chronicle returns
`Long.MIN_VALUE` for an empty queue; anything else is the last written index.

## A branch created after the commit

Claude committed the first task directly to `main`. No feature branch, no PR.
The commit hadn't been pushed yet, so the recovery was clean: create
`feat/14-chronicle-backend` at the current HEAD, reset `main` back to
`origin/main`, switch branches. PR #30 went through normally after that.
I've added an explicit rule to CLAUDE.md so it doesn't happen again.

## PayloadCodec's missing commit

After the merge, `git checkout main` refused — `PayloadCodec.java` had
uncommitted local changes. The `public` visibility fix was in the working tree
but had never been staged. `PayloadCodec` lives in
`org.drools.journal.chronicle.internal`; `ChronicleJournalStorage` is in
`org.drools.journal.chronicle`. Java doesn't have friend packages — the methods
had to be `public`. The build had passed locally because Maven was using the
compiled `.class` file.

The committed code on GitHub had `final class PayloadCodec`. A clean checkout
would have failed to compile. Hotfix committed directly to `main`, pushed.

## The synchronization gap we're leaving open

`ChronicleJournalStorage` has no internal synchronization. The session write
thread and the compaction coordinator both call through the same
`ExcerptAppender`, which Chronicle documents as not thread-safe. The design
says external synchronization is the caller's responsibility — that's not
wrong, but nothing provides it yet. It doesn't matter until the compaction
coordinator is wired into Chronicle, and that's not Phase 6. I wanted it on
record before moving on.

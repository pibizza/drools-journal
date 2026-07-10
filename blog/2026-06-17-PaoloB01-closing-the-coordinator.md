---
layout: post
title: "Closing the Coordinator"
date: 2026-06-17
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [compaction, tdd, lifecycle]
---

The last piece of the compaction epic was the simplest to describe: give
`CompactionCoordinator` a constructor, a `start()`, and a `stop()`, then
wire those into `JournalledSessionFactory.open()` and
`JournalledKieSession.dispose()`. Three files, one commit.

Before touching any of those files, I wanted tests. The question was what
to test. You can't reliably verify that a background thread fires after
sixty seconds — that's timing-based and flaky by nature. But you can test
the guard that prevents the thread from starting at all.

## Duration.ZERO as the Safe Sentinel

The pattern we settled on: `Duration.ZERO` means "disabled." In `start()`,
the first line is `if (Duration.ZERO.equals(interval)) return;`. Tests
instantiate the coordinator with `Duration.ZERO` and drive `scanLiveness()`
and `compact()` directly — no scheduler, no thread, no timing.

Verifying the guard is deterministic:

```java
coordinator.start();

boolean compactorThreadAlive = Thread.getAllStackTraces().keySet().stream()
        .anyMatch(t -> "drools-journal-compactor".equals(t.getName()));
assertThat(compactorThreadAlive).isFalse();
```

`Thread.getAllStackTraces()` gives a point-in-time snapshot of every live
thread. If the guard works, there is no compactor thread — no sleep needed.
The session-level test does the same after `dispose()`.

These compiled to errors, not test failures — the constructor and methods
didn't exist yet. That was the red state.

## Three Files, One Commit

The implementation followed the plan closely. `start()` creates a
`ScheduledExecutorService` with a daemon thread named
`drools-journal-compactor`. `stop()` shuts it down with a five-second grace
period before forcing termination. `runCycle()` is three lines: scan
liveness, collect sparse pages, compact if any.

`JournalledSessionFactory.open()` gained a `Duration` overload; the one-arg
version delegates with `DEFAULT_INTERVAL = 60s`. The coordinator is created,
started, and handed to the session. `JournalledKieSession.dispose()` stops
it. Callers see none of this.

76 tests, all green. The commit closed issue #12.

## Closing the Epic

The epic close was the first time I ran `epic-close` end to end. The journal
merge updated `§Compaction Protocol` in `DESIGN.md` — adding the safepoint
trigger definition, the page concept, the liveness strategy, and the session
lifecycle wiring. All three design specs were posted to the GitHub issue
before it closed.

The Chronicle backend is next. There's a known question to settle first:
storage pages and compaction pages are currently the same thing only because
`InMemoryJournalStorage` rolls on safepoints. Chronicle uses size-based
rolling — a page can close without a safepoint. That divergence needs a
decision before building anything real.

---
layout: post
title: "The Last Test Closes Nine"
date: 2026-05-18
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [drools-internals, testing, agenda]
---

One test remaining on issue #9: verify that two fired activations produce
`RuleMatchRecord` ids 1 and 2 in insertion order.

## Claude Gets the Ordering Backwards

Claude assumed Drools uses LIFO conflict resolution for same-salience
activations. The reasoning: newer activations are typically prioritised.
So the golden output it drafted put `facts=[2]` first — second inserted
fact fires first.

`mvn clean install` disagreed.

Drools is FIFO here. `Integer(1)` fires before `Integer(2)`. The correct output:

```
MATCH  id=1  rule=ProcessFact  facts=[1]
MATCH  id=2  rule=ProcessFact  facts=[2]
```

I fixed the expected string. All five integration tests green. Issue #9 closed.

## Clean or Don't Bother

During the session, Claude tried to run just the tests module incrementally
to save time. I stopped it: always `mvn clean install`. Stale compiled files
produce confusing failures, and the full clean costs nothing on a build this
fast. It's in CLAUDE.md now.

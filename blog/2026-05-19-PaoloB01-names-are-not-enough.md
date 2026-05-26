---
layout: post
title: "Names Are Not Enough"
date: 2026-05-19
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [drools-internals, testing, replay, agenda]
---

Issue #10 was `ReplayFilter` — an `AgendaFilter` that suppresses
already-fired rule activations on session restore. Small class, a
couple of non-obvious corners.

## The Package Is Part of the Name

Before writing any code, I wanted to understand what the filter key
needed to look like. The plan said: `(ruleName, long[] factHandleIds)`.
I asked Claude to check whether Drools itself had anything similar
already.

It found `ReliabilityUtils.getActivationKey()` in `drools-reliability`:
a method that builds a string key from a `Match`. That key includes
`packageName` alongside `ruleName`. The reason is obvious once you see
it — two rules in different DRL packages can have identical names. Without
the package, the already-fired set is ambiguous.

`RuleMatchRecord` only had `ruleName`. I added `packageName`.

That meant updating the record, updating `JournalledAgenda` to capture
`getRule().getPackageName()`, updating `JournalPrinter` to render `pkg=...`
in golden output, and fixing four golden strings in `JournalledKieSessionTest`.
Claude's initial analysis had also said the filter would need to cast each
`FactHandle` to `InternalFactHandle` to get its ID — I flagged that
`FactHandle.getId()` is on the public interface, so no cast needed.

## The Array Equality Trap

`ReplayFilter` holds a `Set<MatchKey>` where each key is
`(packageName, ruleName, long[] factHandleIds)`. Natural design: make
`MatchKey` a record.

Java record equality for `long[]` uses `==`. Two arrays with the same
content are not equal by default. The set silently misses lookups whenever
the key is constructed from different array instances — which it always is,
since IDs come from the live agenda and the originals came from journal scan.

The fix is explicit `equals`/`hashCode` overrides using `Arrays.equals` and
`Arrays.hashCode`. The failure mode without this is quiet: the filter accepts
activations it should suppress, rules re-fire, nothing throws.

```java
@Override
public boolean equals(final Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof MatchKey other)) return false;
    return packageName.equals(other.packageName)
            && ruleName.equals(other.ruleName)
            && Arrays.equals(factHandleIds, other.factHandleIds);
}

@Override
public int hashCode() {
    int result = packageName.hashCode();
    result = 31 * result + ruleName.hashCode();
    result = 31 * result + Arrays.hashCode(factHandleIds);
    return result;
}
```

## One Test at a Time

I asked for tests built incrementally — one test, build, check, repeat.
The first compile after adding the stub `FactHandle` anonymous class came
back with:

```
<anonymous> is not abstract and does not override abstract method isEvent() in FactHandle
```

Claude had listed the visible methods but missed one. Fixed immediately.
We also added a `ruleMatchRecord()` helper with vararg `long...` for fact
handle IDs — small thing, keeps the test setup readable.

5 tests, all green.

## Closes #10 Did Nothing

The commit was `feat(core): implement ReplayFilter (Closes #10)`. Pushed
to `epic-journal-aware-session`. Issue #10 stayed open.

`Closes #N` auto-closes only when the commit lands on the default branch.
On a feature branch GitHub processes the keyword and discards it. Closed
manually with `gh issue close 10`. The keyword fires again on merge.

33 tests passing.

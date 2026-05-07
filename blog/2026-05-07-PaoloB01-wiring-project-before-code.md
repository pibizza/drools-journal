---
layout: post
title: "Wiring the project before writing the code"
date: 2026-05-07
type: phase-update
entry_type: note
subtype: diary
projects: [drools-journal]
tags: [tooling, issue-tracking, testing]
---

The drools-journal module had seven commits and no infrastructure. No GitHub
issues, no DESIGN.md, no test coverage for the SPI. Before touching any more
Java, I wanted the project to actually work as a project.

I brought Claude in to help wire this up. We started with the skills setup —
issue-workflow, retro-issues, and a handful of session-management skills
(handover, idea-log, write-blog) that had been sitting in the local cc-praxis
repo but never installed. That part was straightforward: copy directories to
`~/.claude/skills/`.

GitHub CLI was the first real friction. The session-start hook had been
suggesting `/issue-workflow` for a while, but without `gh` installed and
authenticated it goes nowhere. Once that was sorted, we ran the full setup:
standard labels (`epic`, `performance`, `security`, `refactor`), Work Tracking
written to `CLAUDE.md`.

Then the retrospective. Seven commits, all meaningful, none linked to anything
on GitHub. The retro-issues skill grouped them into one epic — "Establish
drools-journal foundation" — with three child issues covering the Maven skeleton,
the API module, and the in-memory test storage. We created the issues, closed
the children immediately with a retrospective note, and updated the epic's scope
checklist.

The optional step after retro-issues is amending the historical commits to add
`Closes #N` footers. Since this is a private repo and I'm the only committer,
the force-push wasn't a concern. We ran git-filter-repo — and it broke
immediately. The callback signature changed between versions: the old
single-argument `callback(commit)` fails silently at import and crashes at
runtime with a `TypeError`. Claude patched the script to
`callback(commit, metadata=None)` and we re-ran it cleanly. That fix is now
in the local cc-praxis repo.

Two things I had to correct during this session. Claude tried to put
`retro-issues.md` into `docs/` in the project repo — the workspace CLAUDE.md is
explicit that methodology artifacts stay in the workspace, not the project. And
later, when I asked to implement Task 8, Claude started writing code before
creating a GitHub issue. I stopped it both times. Once the issue existed (#5,
under epic #1), we implemented: `JournalStorageContractTest`, an abstract JUnit 5
base class that any storage backend can extend. Thirteen tests covering position
monotonicity, scan coverage, content fidelity, and close idempotency. All 13
pass. The commit's `Closes #5` footer auto-closed the issue on push. Epic #1
closed too — the foundation is done.

A DESIGN.md was the other gap `cc-praxis-check` surfaced. We drafted it from the
upstream tracking issue (apache/incubator-kie-drools#6682), which embeds the
full design spec — a better source than the implementation plan alone.

Phase 4 is next: six components in `drools-journal-core`. `ModifyLambdaRegistry`,
`JournalledNamedEntryPoint`, `JournalledAgenda`, `ReplayFilter`, `RestoreEngine`,
`CompactionCoordinator`. The foundation was the quiet part.

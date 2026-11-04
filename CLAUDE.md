# drools-journal

**Repo:** /home/pbizzarr/develop/kiegroups/drools/drools-journal
**Main branch:** main

Code and all methodology artifacts live together in this single repository.
(Previously split across a separate `drools-journal-workspace` repo — unified
in #67. The old workspace repo is archived/frozen for history only.)

## Project Type

type: java

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` |
| write-blog | `blog/` |
| retro-issues | `retro-issues.md` |

## Structure

- Maven modules: `drools-journal-api`, `drools-journal-core`, `drools-journal-chronicle`, `drools-journal-aeron`, `drools-journal-benchmarks`, `drools-journal-tests`
- `DESIGN.md` — primary design document
- `HANDOFF.md` — session handover (single file, overwritten each session)
- `retro-issues.md` — retrospective issue mapping audit trail (single file)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (superpowers output)
- `plans/` — implementation plans (superpowers output)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records with INDEX.md
- `blog/` — project diary entries with INDEX.md
- `design/` — design journal (`JOURNAL.md`) and epic metadata

## Rules

- **Never implement directly on `main`** — all implementation work goes on a feature branch (e.g. `feat/14-chronicle-backend`). Open a PR and merge. Direct commits to `main` are for hotfixes only.
- Push to `origin` after every commit — don't let work accumulate locally across sessions.

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** pibizza/drools-journal
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — when the user says "implement", "start coding",
  "execute the plan", "let's build", or similar: check if an active issue or epic
  exists. If not, run issue-workflow Phase 1 to create one **before writing any code**.
- **Before writing any code** — check if an issue exists for what's about to be
  implemented. If not, draft one and assess epic placement (issue-workflow Phase 2)
  before starting. Also check if the work spans multiple concerns.
- **Before any commit** — run issue-workflow Phase 3 (via git-commit) to confirm
  issue linkage and check for split candidates. This is a fallback — the issue
  should already exist from before implementation began.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
  If the user explicitly says to skip ("commit as is", "no issue"), ask once to confirm
  before proceeding — it must be a deliberate choice, not a default.

## Writing Style Guide

No personal style guide configured — blog entries use `write-blog/defaults/common-voice.md`. To configure a personal voice, create a style guide and set `PERSONAL_WRITING_STYLES_PATH`. Load the voice and complete the pre-draft voice classification (I / we / Claude-named) before drafting any entry.

## Code Conventions

- **License header:** All Java source files use `Copyright (c) 2026 Drools Journal Authors` (Apache 2.0). Do **not** use the Apache Software Foundation boilerplate — it appears in Maven archetypes but is incorrect for this project.
- **Integration tests:** live in `drools-journal-tests`. Use `assertThat(storage).hasToString(...)` with `JournalPrinter` (from `drools-journal-core` test-jar) for golden-output journal assertions — one readable string per test, not per-field `assertThat` chains.
- **Session creation:** `kbase.newKieSession(null, env)` with `DurableSessionOption` on the `Environment` is the public API. Build the environment with `env.set(DurableSessionOption.PROPERTY_NAME, DurableSessionOption.newSession().withJournalStorage(storage))`. Default compaction interval is 60s — pass `.withCompactionInterval(Duration.ZERO)` to disable (the safe choice for tests). For precompiler tests, pass `.withModifyLambdaRegistry(registry)`. Returns `KieSession`; downcast to `JournalledKieSession` only when `getJournalStorage()` is needed.
- **`final` usage:** Use `final` on fields and parameters only. Do **not** use `final` on local variables — it adds noise without benefit in short-scoped method bodies.
- **Test naming:** Integration test methods use `action_context_expectedResult` — three camelCase segments separated by underscores (e.g. `insertAndFire_singleFact_producesInsertMatchAndSafepoint`).
- **IT test class naming:** end-to-end and integration test *classes* use the `*IT` suffix (e.g. `ChronicleJournalledKieSessionIT`). These run under Maven Failsafe, not Surefire — JVM `argLine` flags must be configured in both plugins separately.
- **Multi-queue Chronicle layout:** `ChronicleJournalStorage.atPath(path)` creates a root directory containing `catalog/` (page lifecycle queue) and `page-{id}/` (per-page data queues). The scanner reads the catalog first to build page ordering, then chains data queues. `JournalStorage.isEmpty()` (not `latestPosition()`) is the correct test for whether restore is needed.

## Build Commands

- **Always `mvn clean install`** — never run incremental Maven builds. Stale compiled files cause confusing failures.
- **Java 21 required** — `maven.compiler.release=21` is set in the parent POM. Builds will fail on Java 17.

## Context Management

If the conversation is getting very long or you notice context pressure,
proactively suggest writing a handover before continuing.

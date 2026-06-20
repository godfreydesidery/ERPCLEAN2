# 0043 — Schema freeze: additive-only migrations, durable database in all environments

- **Status:** Accepted
- **Date:** 2026-06-20
- **Deciders:** owner, solutions-architect, devops-engineer, backend-engineer
- **Context source:** owner decision (2026-06-20); executes the schema-freeze half of the
  ADR-0038 production-hardening cutover. Runbook: `docs/ops/migrations-and-seeding.md`.

## Context

Through initial development the database was treated as **ephemeral**: migrations were edited in
place, consolidated, and renumbered, and dev/QA databases were recreated at will (the now-retired
"dev-phase edit-and-recreate" stance, including ADR-0039 D-10's "seed lives in edited migrations").
That was cheap because there was no data to lose.

That is no longer true. QA now holds **real, persistent data carried across releases** — the keep-data
deploy (`infra/qa/deploy.sh`) mounts the existing `erpclean2-data` volume on every redeploy, and
local development is expected to persist its volume too. On a populated database, editing a shipped
migration changes its checksum and the app refuses to boot (`validate-on-migrate`), while every
fresh CI database stays green — a silent, environment-specific failure. ADR-0038 anticipated this and
defined a cutover checklist; the owner has now triggered the schema-freeze portion.

Forces: keep real data safe and migrations replayable on populated tables, vs. the convenience of
rewriting history while the schema settles.

## Decision

As of **2026-06-20 the schema is frozen and the database is durable in every environment (local, QA,
production)**:

1. **Additive-only, immutable migrations.** Never edit, rename, or delete an applied
   `V<n>__*.sql`. Any schema or seed change is a **new `V<n>`** (next free version) — except
   convergent reference data (permission codes + grants), which is edited in the repeatable
   `R__seed_permissions.sql` (it upserts and self-heals).
2. **No database is ever wiped or recreated** as routine: no `flyway clean`, no
   `docker compose down -v` locally, no dropping the QA volume on a release. A wipe is a deliberate,
   non-routine decision to rebuild an environment from scratch.
3. **Author against populated tables**: expand→backfill→constrain across separate migrations (never
   single-shot `NOT NULL`/`UNIQUE`/`FK` on a populated table); `CREATE INDEX CONCURRENTLY` in its
   own non-transactional migration; widen `CHECK` constraints additively.
4. **CI enforces it.** `scripts/check-migrations.sh` (job `migration-hygiene`) runs rule 1
   (no duplicate versions) **and** rule 2 (immutability vs the PR base branch) — editing a shipped
   migration now fails the PR.

This supersedes the dev-phase edit-and-recreate guidance, including ADR-0039 **D-10** ("dev-phase:
edit existing migrations in place").

## Consequences

- **Easier / safer:** QA and local data persist across releases; deploys are keep-data by default;
  real-data migration regressions are caught by `MigrationKeepDataIT` and the immutability gate
  rather than in production.
- **Harder / constrained:** schema changes carry the expand→backfill→constrain ceremony and cannot
  be "cleaned up" by rewriting earlier migrations; migration consolidation/renumbering is no longer
  permitted (runbook §7 retained only as a historical record).
- **Do not undo by accident:** a future contributor must not edit an applied migration to "fix" a
  schema — it will pass on a fresh CI DB but break boot on the durable QA/prod DB. Add a new `V<n>`.
- **Still open (prod-deploy hardening, runbook §6):** backup-before-migrate gate in the deploy path,
  build-once/tagged-artifact + rollback target, and paired revert migrations for risky changes.

## Alternatives considered

- **Stay ephemeral / keep editing migrations.** Rejected: QA already carries real data and local is
  meant to persist; editing a shipped migration breaks boot on a populated DB, and recreating the DB
  loses data that now matters.
- **Freeze, but allow periodic consolidation in a maintenance window.** Rejected: renumbering
  rewrites history and requires wiping every durable database, defeating the durability guarantee and
  the CI immutability gate; the marginal tidiness is not worth the operational risk.
- **Docs-only rule, no CI gate.** Rejected: relies on everyone remembering. Enabling
  `check-migrations.sh` rule 2 makes the constraint mechanical and catches violations at PR time.

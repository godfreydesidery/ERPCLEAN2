# Migrations & Seeding Runbook

**Owner:** devops-engineer / backend-engineer
**Status:** active
**Related:** ADR-0038 (production-hardening), `scripts/check-migrations.sh`, `backend/src/main/resources/db/migration/`

This runbook is the single source of truth for how the ERP database schema and seed data evolve.

> **Schema freeze — 2026-06-20.** The DB is now **durable in every environment** (local, QA, prod):
> it is never wiped or recreated, and every migration runs against real, populated, multi-tenant
> data. Migrations are **append-only and immutable** — see §2. CI rule 2 (immutability) is **ON**
> (§4). The pre-cutover "edit-and-recreate / consolidate" workflow in §7 no longer applies.

---

## 1. The model

Three mechanisms, each with a different job and a different repeatability:

| Mechanism | What it does | When it runs | Rule |
|---|---|---|---|
| **Versioned migrations** `V<n>__*.sql` | All DDL + per-company financial seeds (CoA, gl_configs, fiscal periods, UOM, tax rates, dimensions, leave types, PAYE bands, document templates) | once, in version order | append-only after cutover; **immutable once shipped** |
| **Repeatable migration** `R__seed_permissions.sql` | The **canonical RBAC permission catalogue** + the `ORG_ADMIN` grant | after every versioned migration, **whenever its checksum changes** | edit freely — it converges |
| **`BootstrapRunner`** (Java) | First-run tenant provisioning: org + company + default branch + root admin + that company's defaults | app startup, guarded by `organisations.count() == 0` | idempotent |

### Why permissions are Repeatable but CoA/gl_configs are not

Permissions and their grants are **convergent reference data** — the table should always *equal* the
catalogue. Nothing in the schema references a permission row at migrate time (the `role_permission`
FK is satisfied because `R__` inserts permissions before granting), so it is safe to seed them in a
repeatable migration that runs **last**. Editing a permission is then a one-line change here that
converges on the next migrate — instead of an append-only `Vn` that can only *add* rows (the trap
that left dead `SALES.POS.*` codes behind after the V43→V83 fix).

Per-company **financial** seeds (CoA, gl_configs, …) are the opposite: later versioned migrations
**FK to rows they seed** (e.g. `asset_categories` references CoA accounts seeded earlier). They must
stay **inline** in their versioned migration so they exist when the dependent DDL runs. Do **not**
move them to a repeatable migration.

### Adding a permission (the common case)

1. Add the code to the `VALUES` list in `R__seed_permissions.sql` (kept alphabetical).
2. That's it — the `ON CONFLICT (code) DO UPDATE` upsert + the `CROSS JOIN` grant mean it appears
   and is granted to `ORG_ADMIN` on the next migrate, on every environment. No `Vn` file.
3. To **rename/retire** a code: change/remove it here. (Removing it leaves the old row in the table;
   if it must be hard-deleted, add an explicit `DELETE FROM permissions WHERE code = '…'` at the top
   of this file — repeatable, so it self-heals everywhere.)

---

## 2. Authoring discipline for a DURABLE database (expand / contract)

Once staging/prod data is durable, every migration is an **online schema change against populated
tables under load**. The empty-DB CI build cannot catch these — they only fail on real data:

- **Never single-shot `NOT NULL` / `UNIQUE` / `CHECK` / `FK` on a populated table.** Use the
  expand→backfill→constrain pattern across **separate** migrations: add the column nullable →
  backfill in batches → add the constraint once the data conforms. (This is the class that produced
  ISSUE #12 — the `uid VARCHAR(26)` overflow that only fired on keep-data upgrades.)
- **`CREATE INDEX CONCURRENTLY`** for large tables — and that statement must be in **its own**
  migration, marked non-transactional, because Flyway wraps a migration in a transaction by default
  and `CONCURRENTLY` cannot run inside one. Plain `CREATE INDEX` takes a write lock = downtime on
  `gl_entries` / `stock_*`.
- **Widen `CHECK` constraints additively** with the DROP-IF-EXISTS / ADD pattern already used for the
  `chk_*_origin` / `chk_*_source_type` constraints, and keep each new constraint a **superset** of
  the previous one so existing rows stay valid.
- **Immutability is absolute after cutover.** Never edit / rename / delete a shipped migration — its
  checksum changes and the app refuses to boot (`validate-on-migrate`) on the durable DB while every
  fresh dev/CI database stays green. Corrections are **new** migrations (or, for convergent reference
  data, an edit to the `R__` file).
- **One version per merge.** Two branches both adding `V65__` is a duplicate-version boot failure.
  `scripts/check-migrations.sh` (CI job `migration-hygiene`) catches this at PR time.
- `*.sql` is pinned to **LF** in `.gitattributes` — a CRLF↔LF flip silently changes a checksum.

---

## 3. Flyway configuration (prod-hardened)

Set explicitly in `application.yml` (applies to all profiles; safe because no code or test invokes
`flyway clean`):

| Setting | Value | Why |
|---|---|---|
| `clean-disabled` | `true` | `flyway clean` is a full data-drop; never allow it against a persistent DB. |
| `validate-on-migrate` | `true` | Checksum drift on an applied migration must fail fast at boot. |
| `out-of-order` | `false` | A lower-versioned migration arriving after a higher one is rejected, not silently interleaved. |
| `baseline-on-migrate` | `false` | Never auto-baseline. A restored `pg_dump` carries `flyway_schema_history`; auto-baselining a history-less HEAD dump would re-run V2..HEAD onto an existing schema = mass failure. Adopt legacy DBs manually. |

---

## 4. CI gates

- **`integration-test`** (REQUIRED) — Testcontainers Postgres runs all migrations + `R__` on a fresh
  DB, Hibernate `ddl-auto: validate`, ArchUnit, and **`MigrationKeepDataIT`** (the keep-data
  forward-migration test that proves migrations apply onto a populated, pre-existing company).
- **`migration-hygiene`** (REQUIRED) — `scripts/check-migrations.sh`:
  - **Rule 1 (ON):** no duplicate Flyway versions.
  - **Rule 2 (immutability, ON since 2026-06-20):** an applied versioned migration must not be
    edited, renamed, or deleted (diffed against the PR base branch). Repeatable `R__*` files are
    exempt — they are designed to change and re-run.

---

## 5. Failed-migration recovery

A migration that fails midway (or a half-applied non-transactional statement) leaves a
`success = false` row in `flyway_schema_history`; the app will not start until it is resolved.

1. Diagnose from the boot log / `SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC`.
2. Fix the **data or the migration** (a *new* migration if already shipped elsewhere).
3. `flyway repair` to clear the failed row / realign checksums, then migrate again.
4. **Always take a backup first** (§6).

---

## 6. Production cutover checklist (flip these the day prod data goes durable)

> **Status (2026-06-20):** the **schema-freeze** half is DONE — data is durable everywhere and
> immutability (step 2) is enforced. The remaining **prod-deploy** gates (steps 1, 3, 4) are still
> open and tracked for the prod rollout.

1. **Backup-before-migrate gate** in the deploy path: take a `pg_dump` (see `infra/prod/backup.sh`)
   **before** the new container runs Flyway; abort the deploy if the backup fails. Add a pre-traffic
   `flyway validate` / `flyway info` step that fails fast before the container is marked healthy.
   *(Open — prod deploy.)*
2. ✅ **Immutability (Rule 2) ENABLED** in `.github/workflows/backend-ci.yml` → job
   `migration-hygiene` runs `BASE=origin/${{ github.base_ref || 'main' }} bash ../scripts/check-migrations.sh`.
   Migrations are append-only forever from here.
3. **Reproducible artifact + rollback target** — build the image once in CI, tag with the git SHA,
   deploy that tag; rollback = redeploy previous tag + restore backup.
4. **Forward-only undo** — Flyway Community has no `undo`; author a paired revert migration alongside
   any genuinely risky change.

---

## 7. Consolidating migrations (HISTORICAL — no longer permitted)

> **Closed as of the 2026-06-20 schema freeze.** Consolidation/renumbering rewrites migration
> history and requires wiping every DB — both are now disallowed (the DB is durable everywhere and
> migrations are immutable, §2/§4). This section is retained only as the record of the pre-freeze
> 2026-06 cleanup below. Do not run this workflow.

Pre-cutover only (historical). The faithfulness bar was: a fresh migrate after consolidation must
produce a **byte-identical schema** and an explainable diff in seed data. Harness (used for the
2026-06 cleanup):

1. Migrate the **original** tree (git HEAD) and the **working tree** into two scratch databases via
   the Flyway image (disk-based, so it reflects edits — *not* a prebuilt jar, which migrates from its
   own baked-in classpath).
2. `pg_dump --schema-only --exclude-table=flyway_schema_history` both (filter pg_dump's random
   `\restrict` tokens) and `diff` — must be empty.
3. Diff the permission catalogue + `ORG_ADMIN` grants — must match (modulo intended changes).
4. `mvn clean verify` (Testcontainers + `ddl-validate` + `MigrationKeepDataIT`).
5. **Reset all dev/QA databases** — consolidation renumbers/rewrites history, so every ephemeral DB
   must be dropped and rebuilt.

### Record — 2026-06 consolidation (this branch)

- Moved all permission + `ORG_ADMIN` grant seeds out of 50+ versioned migrations into the single
  repeatable `R__seed_permissions.sql`; dropped the 7 dead `SALES.POS.*` codes (superseded by `POS.*`
  in the old V83). Catalogue 224 → **217**.
- Deleted 10 empty/no-op migrations (perms-only markers + reserved placeholders: old
  V15/V19/V21/V22/V63/V68/V70/V76/V81/V83).
- Folded the POS till-code fix (old V82) into V43 (`pos_tills.code` nullable, unique on
  `(company_id, name)`).
- Renumbered the remaining **64** migrations contiguously (V1..V64).
- Verified schema **byte-identical** to pre-consolidation HEAD at every step.
- **Follow-up:** the `SALES.POS.*` codes are still referenced in docs (RBAC matrix
  `docs/testing/test-cases/02-rbac-authorization-matrix.md`, `docs/system/04-security.md`, etc.) —
  update those to the canonical `POS.*` codes.

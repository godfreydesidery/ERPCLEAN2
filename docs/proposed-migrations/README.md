# Proposed migrations (drafts — NOT active)

These are **draft** Flyway migrations for the deferred items in
[../DEFERRED-ITEMS.md](../DEFERRED-ITEMS.md). They are **not** in
`backend/src/main/resources/db/migration/` on purpose:

- The schema is frozen/additive and **durable in every environment** — once QA/prod run a
  migration it can never be edited. We do not apply schema for a feature that has no code yet.
- Files use a **provisional** sequential `V<n>` matching the agreed build order
  (**D-4 → D-1 → D-6 → D-7 → D-8**). As each feature is built its real migration lands in the active
  folder and its proposal is removed here; the remaining proposals keep their projected numbers.
  Still **re-verify the next-free `V<n>` against `origin/develop`** at build time.

**Build progress:** ✅ **D-4** (`V79`) · ✅ **D-1** (`V80`/`V81`, ADR-0048) · ✅ **D-6** (`V82`,
ADR-0049) · ✅ **D-7** (`V83` cash count + `V84` petty cash, ADR-0050 PR-A/PR-B) — all in the active
folder, their proposals removed. **Only `V85` (D-8) remains.**

**Process (migration-approval rule):** before any of these moves into the active migration folder,
the DDL + the assigned `V<n>` is presented for owner approval, an ADR is written, and it ships in
the same PR as the entity/service/UI that uses it.

## Files (remaining)

| Item | File(s) | Adds |
|------|---------|------|
| D-8 | `V85__…` | `stock_locations.agent_id` + `van_reconciliations`(+lines) |

**Built (now in the active migration folder):** D-4 → `V79`; D-1 → `V80`/`V81`; D-6 → `V82`;
D-7 → `V83` (cash count) + `V84` (petty cash).

**No migration needed:** D-2 (reversal methods only), D-3 (`purchase_requisitions.converted_to_uid`
already exists), **D-5** (`agents.app_user_id` already exists — the DEFERRED-ITEMS.md note is wrong).

## Besides the table DDL, each feature also needs
1. **Permission seeds** in the repeatable `R__seed_permissions.sql` (e.g. `SALES_SETTINGS.MANAGE`,
   `FISCAL.MANAGE`, `CASH_COUNT.MANAGE`, `PETTY_CASH.MANAGE`, `VAN_RECON.MANAGE` + `.VIEW`, with
   `ORG_ADMIN` grants) — else non-root users get a silent 403.
2. **Per-company rows** (`sales_settings`, `petty_cash_funds`) provisioned in **app code**
   (CompanyProvisioningService), not backfilled via Flyway.

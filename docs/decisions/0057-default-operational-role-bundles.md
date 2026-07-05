# ADR-0057: Default operational role bundles (ship working roles out of the box)

- **Status:** Accepted (2026-07-05) — owner-approved; seeded in `R__seed_permissions.sql` (12 roles +
  498 grants). Pairs with the design note [docs/design/default-operational-roles.md](../design/default-operational-roles.md).
- **Owner decisions on the open questions:** OQ-1 **org-wide shared** roles · OQ-2 **additive floor**
  (`ON CONFLICT DO NOTHING`, never revokes) · OQ-3 **no auto-assignment** (roles exist to assign) ·
  OQ-4 **bare business codes** · OQ-5 **accept the compressed single-role SoD defaults**. OQ-6 left
  as-is (system-role grants stay seed-owned; the additive floor re-heals drift). **Verified:** a
  fresh-DB boot applied exactly 12 roles + 498 grants (per-role counts match), and
  `DefaultRoleBundlesSeededTest` guards the seed statically. **Edge documented:** the role
  `ON CONFLICT (code) DO UPDATE` *adopts* a pre-existing same-code role as a system role and unions the
  floor grants onto it — harmless for a fresh tenant, but a tenant that had pre-created a custom role
  under one of these 12 codes would see it converted to `is_system` with the floor added.
- **Deciders:** Owner (decision) + Solutions Architect (proposal)
- **Effort:** S. **Migration:** one edit to the repeatable `R__seed_permissions.sql` (add 12 `roles`
  rows + their `role_permission` grants). **No versioned `V<n>`, no schema change** (ADR-0043 honoured).
- **Related:** ADR-0001 (IAM — roles are org-wide, `is_system` seeded/undeletable), ADR-0002 (RBAC by
  permission code), ADR-0045/0046 (user_company membership; assignment is per-company), ADR-0047
  (screen-read closure — the bundles are closure-consistent with its manifest), ADR-0043 (schema
  freeze / durable DB — seed-only change), and the [[rbac-no-operational-role-bundles]] finding.

## Context

The product seeds exactly one role — `ORG_ADMIN` (row in `V1__baseline.sql`; its grants filled by
`R__seed_permissions.sql` via a `CROSS JOIN` over the whole permission catalogue, so it self-heals and
holds every code). Every **operational** role (Salesperson, Cashier, Storekeeper, Accountant, …) must
be hand-built by each tenant. Fresh tenants — and the persona-UAT harness — hit 403s on core jobs
because the roles simply do not exist; `app_user.is_root` masks it, since root bypasses every
permission check (so admin/root testing never reveals the gap). The owner has approved shipping
**default operational role bundles** so a fresh tenant gets working roles immediately.

Forces:

1. **Roles are org-wide (ADR-0001 D-A).** `roles` has **no `company_id`**; a user is granted a role
   **per company (and optionally per branch)** via `user_role`. So default roles are seeded **once,
   org-wide** — a fixed set of rows independent of the number of companies — exactly like `ORG_ADMIN`.
2. **Least-privilege is the whole point.** A Cashier must not hold user management; a Salesperson must
   not hold invoice-void or credit-override; an Accountant must not close the fiscal year. Bundles must
   be composed from **exact** permission codes, not module wildcards — the modules do not map to job
   functions (the `sales` module holds both `SALES.QUOTE.CREATE` and `SALES.INVOICE.VOID`/
   `SALES.SETTINGS.MANAGE`/`POS.*`).
3. **Convergent, self-healing reference data.** Whatever we seed must be idempotent and re-runnable
   with no duplicates on a durable DB (ADR-0043) — the `R__seed_permissions.sql` model.
4. **Ordering.** `role_permission` grants FK `permissions(id)`; every referenced code must already
   exist when grants run. In one file this is guaranteed; across two files it depends on Flyway's
   description-ordering of repeatables (fragile).
5. **Segregation of duties vs fresh-tenant usability.** A small/new tenant runs each function with one
   person; strict maker-checker splits create friction on day one. The defaults should be a working
   baseline a tenant can *narrow*, not a compliance ceiling.

## Decision

Ship **12 org-wide `is_system` operational role bundles**, seeded convergently in the existing
repeatable `R__seed_permissions.sql`, composed from **exact permission codes** at least privilege. The
full role list, per-role grant lists, and the illustrative seed SQL live in the design note; this ADR
records the shape and the load-bearing choices.

**D-1 — Org-wide `is_system` roles, seeded once (not per-company).** The 12 roles
(`SALESPERSON`, `CASHIER`, `FIELD_SALES_AGENT`, `STOREKEEPER`, `ACCOUNTANT`, `SALES_MANAGER`,
`BRANCH_MANAGER`, `PROCUREMENT_OFFICER`, `PROCUREMENT_MANAGER`, `HR_PAYROLL_MANAGER`,
`FINANCE_DIRECTOR`, `PRODUCTION_MANAGER`) are seeded exactly like `ORG_ADMIN`: one org-wide row each,
`is_system = true` (undeletable, BR-7), assignable in every company through `user_role`. One catalogue,
shared; per-tenant variation is a **custom (non-system) role**, not a per-company copy of a shipped one.

**D-2 — Least-privilege by explicit code list, not module wildcard.** Each bundle is the exact set of
codes its persona needs (design note §3.2), cross-checked against the ADR-0047 screen-read-closure
manifest so each role holds the required closure-bearing reads of the screens it operates. Officer/
manager and prepare/file/close boundaries are preserved (create ≠ approve, post ≠ close, prepare ≠
file) — that separation is why `SALESPERSON`≠`SALES_MANAGER`, `PROCUREMENT_OFFICER`≠
`PROCUREMENT_MANAGER`, `ACCOUNTANT`≠`FINANCE_DIRECTOR` are distinct roles.

**D-3 — Seed in-place in `R__seed_permissions.sql`; no versioned migration.** Append a "default
operational role bundles" section after the `ORG_ADMIN` grant. Both the `roles` rows and the grants go
in the repeatable seed (upsert), so the frozen versioned schema (ADR-0043) is untouched — the whole
feature is one edit to one file. In-place seeding guarantees the FK ordering (catalogue upserted above,
bundles below, single script) and avoids the fragile cross-file description-ordering a sibling
`R__seed_roles.sql` would depend on (force 4).

**D-4 — Additive floor; new codes require an explicit bundle decision.** Grants are upserted
`ON CONFLICT DO NOTHING` — the seed guarantees the least-privilege **floor** and self-heals a lost row,
matching the `ORG_ADMIN` posture. A newly-added permission does **not** auto-flow into any operational
bundle (only `ORG_ADMIN` absorbs it via its `CROSS JOIN`); flowing a capability into a least-privilege
bundle is a reviewed edit to the pair list. This extends the standing rule "a new gated endpoint must
seed its code" with "…and decide which default bundles, if any, receive it." *(Whether to instead
converge/prune to the exact list — OQ-2 — is deferred to the owner; see Alternatives.)*

**D-5 — No auto-assignment.** The seed makes roles *exist and ready to assign*; provisioning does not
auto-grant any default role to any user (the bootstrap admin is already root/`ORG_ADMIN`). *(Open —
OQ-3.)*

The six open questions (org-wide vs per-company, additive vs convergent, auto-assignment, naming
prefix, SoD compression, system-role editability) are enumerated in the design note §6 and must be
answered by the owner before the migration is authored.

## Consequences

**Easier**
- A fresh tenant gets working Cashier/Salesperson/Accountant/… roles the moment the seed runs;
  operational personas stop 403-ing on their core jobs. The persona-UAT/sim harness can assign shipped
  bundles instead of hand-building `*_190194` roles (retiring keyword-matching role synthesis).
- The defaults are a readable, reviewed, version-controlled least-privilege reference — the "which
  role gets what" knowledge becomes explicit and drift-checkable (a suggested `DefaultRoleBundlesSeededTest`,
  sibling to `PermissionCodesSeededTest`, would assert each bundle holds its declared closure).

**Harder / constrained**
- **New capability ⇒ a bundle decision.** Adding a gated endpoint now means: seed the code (existing
  rule) **and** decide which default bundles receive it (new clause). Correct, but a real discipline —
  omitting it means the new capability is `ORG_ADMIN`/custom-role-only until someone edits the list.
- **Role codes are stable-once-shipped.** Tenants and `user_role` grants reference role `code`; renaming
  a shipped code later is a breaking change. Codes are fixed by owner sign-off before the first migrate.
- **Additive floor does not tighten (D-4/OQ-2).** Removing a code from a bundle's list will *not*
  revoke it on existing durable DBs — a tightening needs an explicit one-off revoke. Accepted as the
  price of the `ORG_ADMIN`-consistent, non-destructive posture (revisited if OQ-2 chooses convergence).

**Delivery cost**
- One edit to `R__seed_permissions.sql`; no `V<n>`, no schema change; converges on next migrate in
  every environment; durable DBs are not wiped (ADR-0043). Web: none (Role screen lists what exists).
  Backend: none to ship; one optional guard test.

## Alternatives considered

1. **Keep only `ORG_ADMIN`; tenants build every role (status quo).** *Rejected:* it is the exact cause
   of the persona-UAT 403s and the "no working roles out of the box" complaint; every tenant re-solves
   the same least-privilege composition, and root-bypass hides mistakes.

2. **Per-company seeded role copies** (a private `SALESPERSON` per company). *Rejected:* breaks the
   "roles are org-wide" invariant (ADR-0001), needs a per-company seed keyed off `companies` that must
   backfill new companies forever, and multiplies the row count for no functional gain — assignment is
   already per-company via `user_role`. Independent per-tenant customization is served by **custom
   (non-system) roles**, which the product already supports. *(Surfaced as OQ-1 for the owner.)*

3. **Module-wildcard grants** ("grant all `sales` permissions to `SALESPERSON`"), self-healing on new
   codes. *Rejected:* modules do not map to job functions — wildcarding `sales` gives a salesperson
   `SALES.INVOICE.VOID`, `SALES.CREDIT.OVERRIDE`, `SALES.SETTINGS.MANAGE`, `TAXRATE.MANAGE`, `POS.*`, a
   gross least-privilege violation. Explicit code lists are the only mechanism that expresses least
   privilege; the trade is that new codes need an explicit bundle decision (D-4) — which is *correct*,
   not a defect.

4. **Sibling `R__seed_roles.sql`** (separate the RBAC catalogue from bundle composition). *Attractive
   for review separation* (engineering adds codes; security/product composes bundles) *but rejected as
   the mechanism:* the grant `INSERT` FKs `permissions`, so it depends on Flyway running `seed
   permissions` before `seed roles` (description order `p` < `r` — true today, fragile to a rename or a
   third file). If the roles seed ever ran first, `JOIN permissions` matches nothing, silently grants
   zero rows, and won't re-run until its own checksum next changes — a latent RBAC hole. In-place
   seeding (D-3) removes the ordering variable; the cost (one file carries two concerns; a bundle tweak
   re-runs the idempotent catalogue upsert) is trivial.

5. **Convergent/prune grants** (`DELETE` grants not in the declared list so the bundle always equals
   the seed). *Deferred, not dead (OQ-2):* it makes least-privilege *tightenings* take effect
   automatically, but forbids tenant edits to system roles (reverted every migrate), puts destructive
   `DELETE` in a seed, and diverges from the additive `ORG_ADMIN` posture. Chosen posture is additive
   floor (D-4); convergence is the owner's call and interacts with system-role editability (OQ-6).

6. **Auto-assign default roles on company/user provisioning.** *Rejected for v1 (OQ-3):* the value is
   in the roles *existing and ready*; who gets which role is a per-user onboarding decision, not a seed
   concern, and the bootstrap admin is already root/`ORG_ADMIN`. Can be layered later without rework.

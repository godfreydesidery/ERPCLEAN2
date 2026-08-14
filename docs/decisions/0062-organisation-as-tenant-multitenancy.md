# 0062 — Organisation-as-tenant multi-tenancy on a shared database

- **Status:** Accepted
- **Date:** 2026-08-14
- **Deciders:** Owner (godfrey.desidery), Claude (architecture, security, operations review)
- **Context source:** [MULTITENANCY-PLAN.md](../../MULTITENANCY-PLAN.md) — the living plan; two
  read-only agent workflows (12-agent gap audit, 43 findings / 37 surviving refutation; 14-agent
  codebase-vs-fork study, both refuters returning `refuted` at high confidence); Stage A measurements
  taken 2026-08-14 against QA and the live Kilimanjaro production install.
- **Supersedes:** **ADR-0001 D-A** (roles are organisation-wide) — correct when there was exactly one
  organisation, false once there are several.
- **Amends:** **ADR-0059** (authority containment) — see [§Decision D-3](#d-3--shipped-role-bundles-stay-global-and-the-conferral-rule-changes)
  and the amendment appended to that ADR.

## Context

The product ships today as **one installation per client**: each customer gets their own deployment
and their own database, containing exactly one `organisations` row. The goal is to add a **shared
multi-tenant instance** where several customer organisations share one deployment and one database,
without disturbing the installations already running.

Production is **live with a paying customer** (Kilimanjaro Supermarket, `orbixerp-api:1.6.1`,
native host Postgres). The database is durable in every environment and is never wiped. Migrations
are frozen and additive-only. Every decision below is therefore constrained by a rule that outranks
elegance: **nothing may break the running estate.**

Two working assumptions from the first assessment were destroyed by the audits, and both had to be
replaced before anything could be designed:

1. **"Company-scoping already implies organisation isolation."** False. The 182 company-scoped tables
   need no new predicate *only* for a non-root principal whose company membership was not forged —
   and both qualifiers do real work. `root ||` short-circuits the company equality at
   `ScopeGuard.java:646`; the principal's company is chosen per request from a caller-supplied
   header; and the membership premise is writable by the attacker before it is read.
2. **"The multi-tenant code is inert on a one-organisation install."** False, and this one nearly
   shipped. Not every principal has a user: `LoginAttemptService.java:94-101` records login failures
   with a null actor, and **seventeen outbox handlers** construct
   `Principal(null, "SYSTEM", false, event.getCompanyId(), …)`. After the organisation is added to
   the principal, those have no organisation — so an organisation predicate applied uniformly would
   hide the entire pre-authentication audit trail and deny every asynchronous GL and stock posting.
   `SalesPostingHandler.java:77-86` catches `Exception` and marks the event processed, so the denial
   would surface as a WARN that never retries — the exact shape of `G14`, which has already cost this
   product a working feature.

**The columns are roughly 10% of the work.** A new column adds no predicate to a `findByUid`.

## Decision

### The target model

| Question | Decision |
|---|---|
| What is a tenant? | An **organisation**. One customer = one `organisations` row. |
| Isolation | **Shared database, shared schema.** |
| Multi-company | **Kept, inside a tenant** — a group customer owns several companies and many branches. |
| Login identity | **Username + password**, one field. No tenant code, no subdomain. |
| Tenant resolution | Derived from `app_users.organisation_id` **after** authentication — never from caller input, and **never by parsing the username string**. |

> **Security invariant.** The `@alias` suffix is a *naming convention*, not an input. Parsing it to
> resolve scope would hand an attacker a tenant selector in the one field they fully control. This is
> the shortcut an implementer is most likely to take.

### One codebase, no fork, no tenancy-mode flag

Single-tenant installations and the shared instance run the **same artefact**. Differences are
carried by **data, permissions and `.env`** — never by a switch read at runtime.

A fork was rejected on mechanics, not taste: the Flyway line is linear `V1…V98` and two lines
authoring `V<n>` collide with nothing to arbitrate; the ArchUnit freeze store is 207 line-anchored
entries that cannot be merged once two stores drift; and the build is a single Maven artefact with
zero `@Profile` uses in the backend.

A deployment-mode flag was rejected because any mode assertion over data requires a `DataSource` and
so runs *after* Flyway — a refusal means "schema migrated, no server" — while `orbixerp.sh` offers
no `psql`, `exec`, `shell` or repair command and its `restore` reinstates the rows that tripped the
check. On a customer-owned box that is an unrecoverable outage. **No deployment-mode flag is ever
read on an authorisation decision.**

### D-1 · New customers only

Existing installations are **never merged**; the shared instance is a new deployment. Migration of an
existing customer is offered later as a priced service.

Merging was rejected because it is not "remap surrogate ids" but remap `id` **and `uid`**, where
`uid` is the *external* identifier: twenty-four migrations mint deterministic uids from `company_id`,
so both installs collide row-for-row on `uq_<table>_uid` for chart of accounts, UoM, tax rates, leave
types, document templates and more; and `uid` appears in URLs, on printed documents, in POS client
state, in ~110 `*_uid` soft-reference columns with no foreign key to find them, and inside four JSONB
columns. Post-merge activity interleaves, so it is irreversible.

### D-2 · Platform operator vs tenant administrator

A **sentinel platform organisation**, a tier-3 `PLATFORM_OPERATOR` role, and `is_root` **re-bounded
rather than removed**, in three stages:

1. **Re-bound `is_root`.** Keep the flag and all 24 `.root()` sites, but apply the organisation
   predicate **unconditionally, root included**. `is_root` then means *"full authority inside the
   organisation I belong to"*, not *"crosses tenants"*.
2. **Add `PLATFORM_OPERATOR`** (tier 3) on the sentinel organisation, carrying explicit `ORG.*` /
   `TENANT.*` codes, excluded from the `ORG_ADMIN` `CROSS JOIN`.
3. **Crossing into a tenant becomes a time-boxed, audited support grant** against one named tenant,
   not a standing capability.

> **P0-3 — the sequencing that must not be treated as soft.** Stage 1 is work items P3-1 and P3-2, and
> both are **prerequisites of P5-1** (tenant provisioning). Whoever writes provisioning will hit
> *"the new admin can't grant any roles"*, and the one-line fix that makes it work is `setRoot(true)`
> — which `BootstrapRunner.java:137` already does today. Once root is organisation-bounded that line
> is harmless, because it makes the new admin powerful inside their own tenant, which is what was
> wanted. **Before those two items land, it silently disables tenant isolation for exactly the users
> who use the system most**, with no error, no failing test, and rows that look native in every
> report because the principal genuinely is in that company.

The sentinel keeps `app_users.organisation_id` `NOT NULL` and every predicate total, avoiding the
null-organisation wildcard that §0.1's finding shows is the fail-open route.

### D-3 · Shipped role bundles stay global, and the conferral rule changes

There are **13** shipped `is_system` roles, not 12: `ORG_ADMIN` (`V1__baseline.sql:289-292`) plus the
twelve ADR-0057 bundles (`R__seed_permissions.sql:288-299`).

They **stay global** (`organisation_id IS NULL`). This is forced, not chosen:
`R__seed_permissions.sql:287` inserts roles without an `organisation_id`, and Postgres checks
`NOT NULL` before the `ON CONFLICT` arbiter, so `roles.organisation_id SET NOT NULL` would break the
repeatable seed on **every boot of every environment**. V100's `uq_role_code_global … WHERE
organisation_id IS NULL` requires those NULLs to persist.

**Being global says who *owns* a role; it does not say who may *confer* it.** Four tiers:

| Tier | Roles | `organisation_id` | Who may confer |
|---|---|---|---|
| **1 · Global, tenant-grantable** | the 12 ADR-0057 bundles | `NULL` | any tenant admin, to a user **in their own organisation** |
| **2 · Global, ceiling-bound** | `ORG_ADMIN` | `NULL` | a tenant admin **who holds it**, within their own organisation |
| **3 · Global, never tenant-grantable** | `PLATFORM_OPERATOR` | `NULL` | **only another platform operator** |
| **4 · Tenant-scoped** | anything a customer authors | `= <org id>` | the owning tenant only |

**The conferral rule** — replacing `AuthorityCeiling.assertCanConferRole`'s blanket
`if (roleIsSystem) throw` for non-root callers:

1. The grantee must be in the **caller's own organisation**, asserted *before* the membership oracle
   is consulted.
2. A **tier-1 or tier-2** role may be conferred by a caller who **holds it themselves**, or holds a
   strict superset of its permissions. **ADR-0059's subset and reserved-floor checks survive
   unchanged.**
3. A **tier-3** role is never conferrable by a tenant caller at any authority level — a flat refusal,
   not a ceiling comparison.
4. Tier-2 and tier-3 grants require MFA on the caller and write a high-severity audit row.

> **Why this does not weaken ADR-0059.** That ADR states plainly (Alternatives, final bullet) that
> blocking `is_system` roles is *"the naive fix"*, kept *"only as a clearer, defence-in-depth failure
> for the `ORG_ADMIN` case"* — the **subset invariant** is what closes both the direct-grant and the
> build-your-own-superrole paths. Rule 2 preserves that invariant exactly. What changes is the
> secondary block, which under multi-tenancy stops being defence-in-depth and starts being the reason
> **a tenant administrator cannot give their own cashier the CASHIER role** — making the product
> unusable on day one and creating the pressure that produces `setRoot(true)`.

Two rules follow from the classification:

> **R-1 · A tenant role code must not collide with a global role code.** V100's two partial indexes
> cover *different partitions*, so once `uq_role_code` drops, `(NULL,'CASHIER')` and `(2,'CASHIER')`
> can coexist — the exact ambiguity that makes `ApprovalEngineImpl:301`'s `findByCode` throw and lets
> `StepApproverResolver:78-84`'s **string** match cross tiers. No pair of partial indexes can express
> this; it needs a service check **and** a trigger, so a seeder cannot bypass it.

> **R-2 · Platform capabilities must never be ordinary permission rows.**
> `R__seed_permissions.sql:267-274` grants `ORG_ADMIN` every row in `permissions` via a `CROSS JOIN`,
> and says so: *"ORG_ADMIN always holds every permission, including ones added above later."* It is a
> **repeatable** migration, so the moment `ORG.*` codes are added, every tenant's `ORG_ADMIN` silently
> gains them. Either exclude a platform module from the join or keep platform capability out of
> `permissions` entirely.
>
> *(Mechanism corrected 2026-08-14: Flyway re-applies a repeatable migration when its **checksum
> changes**, not on every deploy — measured, this seed has run three times ever on the live customer.
> The rule is unaffected, because adding `ORG.*` codes **is** an edit to that file, and the edit is
> what triggers the re-run.)*

### D-7 · Username convention

`<user>@<org-alias>` for tenants provisioned on the shared instance, with a strong password policy.
`organisations.alias` is the uniqueness partition.

**Legacy bare usernames are never rewritten.** Existing users keep `rootadmin`, `jkomba` and so on
indefinitely. This is safe *by construction*: the server always composes new usernames from
`principal.organisationId()` and rejects `@` in the local part, so a new tenant can never mint a bare
name. `findByUsername` stays a **global exact-match lookup** — nothing is parsed, resolved or
disambiguated, so the ambiguity-tolerant login this design rejects is not reintroduced.

This deletes what was the only irreversible, client-visible item in Phase 1: a backfill that would
have broken every saved login on web and on every POS till simultaneously.

### D-9 · `organisation_id` on aggregate roots

Added **nullable in V99 and left unconstrained in Phase 1**, populated forward by application code
from Phase 2 and backfilled by a bounded background pass. Aggregate roots only — never line tables:

```
sales_invoices  purchase_orders  goods_receipts   journal_batches   journal_entries
ar_invoices     ar_receipts      ar_credit_notes  ar_write_offs
ap_payments     ap_debit_notes   supplier_bills   supplier_quotes
stock_movements payroll_runs     products         customers          suppliers
```

**Rationale — corrected 2026-08-14, after measurement.** An earlier draft of this ADR justified the
hedge by claiming that per-tenant extraction is otherwise "a 204-table topological traversal" and that
restore, export and deletion are "permanently bespoke". **That is false and must not be repeated.**
Measured on a restored copy of the live customer's database: of 205 tables, **182 carry `company_id`**
and `companies.organisation_id` is `NOT NULL`, so those 182 are already **one join** from their
organisation. The 23 exceptions are the tenant tree, the global vocabularies and children of
company-scoped parents — none of which is an aggregate root, so the hedge does not cover them anyway.

For extraction, the columns buy **one saved join**. The decision stands on two other grounds:

1. **Row-level security without a correlated subquery** — the material one, and it couples directly to
   the still-open **D-4**. Without a local `organisation_id`, every policy is
   `company_id IN (SELECT id FROM companies WHERE organisation_id = current_setting(...))`, evaluated
   on every row-scan of every protected table.
2. **A partition key**, if tenant-partitioning is ever wanted.

3. **Cost asymmetry, which is what actually decides it.** Adding the columns now is a metadata-only
   `ADD COLUMN` plus a backfill measured at **163 ms** across all 31 tables. Adding them later is an
   `UPDATE` over every row, which rewrites the heap — trivial at today's 38 MB, a real maintenance
   window on a mature database, on a product with no rollback worth the name.

Rejecting the hedge was a legitimate option — **rejecting it by omission was not**, and neither is
keeping it for a reason that does not survive a `SELECT count(*)`.

### D-10 · Email uniqueness scoped to the organisation

`uq_app_users_email` (global, `V69__unique_identifiers.sql:25-27`) becomes
`uq_app_users_org_email ON app_users (organisation_id, email) WHERE email IS NOT NULL`, swapped in
V101 **after** the `SET NOT NULL` — a composite unique would otherwise permit duplicates while the
column is nullable.

A global email unique would stop one person — a shared bookkeeper, an outsourced accountant, a group
IT admin — from holding an account in two tenants, and leaves the 409 on `POST /users` as a
cross-tenant email-existence oracle. **Consequence stated plainly: `app_users` is therefore not
"purely additive"**, which was the headline payoff of the global-username decision.

### Phase 1 ships as ONE release with a self-sufficient V101

V101 carries its own convergent backfill and a temporary column DEFAULT, so it succeeds whether or
not the application reconciler has run. The two-release split was withdrawn because the window
between the releases **manufactures the NULLs its own gate checks for** — `AppUser` has no
organisation field until P2-1, so users created between the releases have none. Additionally,
`cmd_restore` reverts `flyway_schema_history`, so every pre-release-1 backup would become permanently
unrestorable once a box moved to release 2.

> **Standing-rule exception, recorded deliberately.** *Provisioning over data migrations* exists to
> stop brittle backfills that guess. Here the derivation is total and unambiguous — there is exactly
> one legal value — and the alternative is a migration that bricks a customer's machine. The app-side
> reconciler is **not** replaced; it still runs every boot, still owns the alias and the roles, and is
> the only healer after a partial restore.

### Verification obligations

- A **`TenancyScopeEnforcer`** with a single call site, treating a principal whose organisation is
  NULL as **SYSTEM/UNSCOPED and exempt**, proven by an integration test that asserts **the GL journal
  row lands** — not that no exception was thrown, since the handler swallows.
- **Shadow mode covers guards only.** Filter-shaped tightenings return fewer rows silently; those
  need a **row-count parity harness**, not `WOULD_DENY` logging.
- Probes run as a **non-root** tenant administrator. Root passes everything by construction.

### Still open at the time of writing

**D-4** (RLS backstop), **D-5** (per-tenant restore), **D-6** (tenant lifecycle) and **D-8**
(per-tenant fiscalisation — one JVM-wide `erp.fiscal.provider` against per-customer TIN/VRN and TRA
device registration, a legal defect on a shared instance). None gates the schema; **D-5 and D-8 gate
tenant #2.**

## Consequences

- **The live estate keeps receiving the same releases and never acquires a second tenant.** Every
  cross-organisation defect in the register requires two tenants in one database to be exploitable.
- **The customer-visible change is a single moment**, at the client work of Phase 6. Everything
  before it is additive or shadowed. No credential changes, no POS reinstall.
- **`ORG_ADMIN` remains a full-authority role a tenant may delegate**, matching AWS, Azure and Google
  practice, with MFA on privileged grants and a never-zero-admins invariant as the compensating
  controls rather than withholding the capability.
- **`is_root` survives** as a bounded concept rather than being ripped out of 24 call sites on a live
  system — and, once bounded, stops being a tenancy boundary at all.
- **The 207-entry ArchUnit freeze store must be re-triaged** as a Phase 3 gate. Each entry was blessed
  on the premise that a leak stayed inside one customer's installation; that premise dies here.
- **`dist/` becomes permanently dual-track.** Existing customers stay on offline bundles while the
  shared instance is hosted: two deploy pipelines, two backup stories, two support runbooks.
- **Per-tenant recovery does not exist and is gated on D-9's columns arriving.** Until then the honest
  contractual answer is that recovery is whole-instance.

## Alternatives considered

- **Tenant = company.** The smallest change, but collapses the group-customer case the product
  already serves and discards the `organisation` node that already exists.
- **Schema-per-tenant / database-per-tenant.** Strong isolation and trivial per-tenant backup.
  Rejected as the *target*: Flyway would run per schema, breaking the `migration-hygiene` gate and the
  frozen-schema discipline; and database-per-tenant is effectively what ships today, so it does not
  reduce the per-customer operational cost that motivates the work. **Worth revisiting for recovery
  specifically** — that rejection was argued on CI grounds, not on recovery grounds.
- **A fork or long-lived product-line branch.** Scored 0 of 3 in the judge panel; see the mechanics
  above.
- **A `SINGLE | SHARED` deployment-mode flag.** Rejected: unrecoverable boot refusals on
  customer-owned boxes, and a mode knob is the mechanism by which a tenant check ends up skipped in
  one mode with nobody noticing.
- **Tenant code + username + password.** A third thing to distribute, support and mistype, buying
  nothing once usernames are globally unique — and deriving the tenant from the authenticated user is
  strictly stronger than accepting a typed code, because there is no tenant input to spoof.
- **A short-lived bare-username compatibility path.** Rejected: an ambiguity-tolerant login is exactly
  the "temporary" convenience that survives into production and re-opens the multi-match problem the
  alias exists to prevent.
- **Two-release Phase 1 with an operator gate.** Rejected on the evidence above; the gate cannot be
  enforced on a customer-controlled box, and the window itself creates the failure.

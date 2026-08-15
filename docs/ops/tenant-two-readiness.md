# Onboarding a second paying customer — what is actually missing

*Assessed 2026-08-15 against the shipped tree (`backend/`, `infra/`, `dist/`), not against the plan.
Every claim below was checked in code; where the assessment contradicted the plan, the code won.*

The one-line answer: **the tenancy spine is built, and it is not what stands between you and
customer #2.** What stands there is provisioning, identity data, and a deployment story — none of
which needs a schema change, and most of which is small.

## 1 · The decision to make first

**Does one database hold two customers?** Recommendation: **no** — customer #2 gets their own
stack (own database, own JWT keypair, own organisation). Recorded as **D-11** in
[MULTITENANCY-PLAN.md](../../MULTITENANCY-PLAN.md), with the evidence.

It is worth making this decision before the rest, because it decides three others at once (D-4 RLS,
D-5 per-tenant restore, D-6 departure) and it decides how much of the list below matters.

The single most load-bearing fact: **`TenantProvisioningService` sets `setRoot(true)` on every
tenant's administrator, and `PermissionResolver` short-circuits root past every permission check.**
On separate stacks that is contained. On a shared instance, customer #2's own administrator would
satisfy `ORG.SUSPEND` — against customer #1. `PLATFORM_OPERATOR` is currently a name in
`AuthorityCeiling` with no seeded row behind it.

## 2 · Blockers, in the order they bite

| # | Blocker | Schema? | Size |
|---|---|---|---|
| **B1** | **The fresh-install path has never been run at 1.8.x.** Kilimanjaro reached 1.8.3 by in-place `update` from 1.6.1. Customer #2 runs `install.sh` against an **empty** database — V99 expand over zero rows, V102/V103, `R__seed_permissions`, `BootstrapRunner`, `TenancyReconciler`. V103's own post-mortem records that the 1.8.0 crash-loop was missed *because the local rehearsal used a fresh database*. The two paths hide different defects, and the fresh one is the only one customer #2 takes. | no | S |
| **B2** | **No tax identity is ever captured.** `CreateTenantRequest` has no TIN/VRN/legal-name field and `.env.example` never asks. The tenant is born `tax_id = NULL` and prints documents headed "TAX INVOICE" with the TIN line silently omitted — not a valid TZ tax invoice. | no | S |
| **B2b** | **The branding snapshot never re-syncs.** `DocumentBrandingSeeder` copies `company.taxId` once, and only when the row is absent. Filling the TIN on the Company screen fixes reports and leaves **every printed invoice blank forever**. A fallback to `companies.tax_id` when branding is blank self-heals customer #1 too. | no | XS |
| **B2c** | **`document_branding` has no `vrn` column** (V19 has `tax_id` only), so a printed invoice cannot show a VRN however diligent the operator. | **yes** | S |
| **B3** | **No price list is ever seeded** — no `PriceListSeeder`, no migration inserting `price_lists`, absent from the 22-seeder chain. `ProductServiceImpl` throws, the web price dropdown is empty, price import fails every priced row, and **the POS renders everything `NO_PRICE`** — silently. | no | S |
| **B4** | **No walk-in customer.** The POS requires a customer and defaults to `CASH_WALK_IN`; that value exists only as an enum and a CHECK constraint. Nothing seeds a row, so **the till cannot complete one sale** on day one. | no | XS |
| **B5** | **No POS till.** `PosSessionServiceImpl` throws `NotFound` and no migration seeds one. Compounded by the POS generic-400 trap, it reads as a broken app. Seed one per branch, or make it a runbook step — but pick one. | no | S |
| **B6** | **No onboarding runbook, and the nearest doc is wrong.** [`two-tenant-local-stack.md`](two-tenant-local-stack.md) still says "there is no organisation-create endpoint yet — tenant B goes in by SQL". It shipped (P5-2). Following that doc produces a tenant with **zero** company-scoped defaults. | no | S |
| **B7** | **Backups are instruction, not construction.** `install.sh` installs no cron or scheduled task — it only creates `backups/`; `OPERATIONS.md` asks the *customer* to "practise this once". When a restore was actually needed (1.8.0) it was avoided by hand-editing the database. **No restore has ever been drilled**, so the RTO is unknown. | no | S |

## 3 · Fixed on 2026-08-15

Four of the small ones are already done, and two were found independently by this assessment and by
the tenancy work — which is some evidence the list is real:

- **`party_code_sequence` never seeded** — the other half of P5-6. Four kinds left racing, including
  `CUSTOMER` and `SUPPLIER`, which are among the first rows any new tenant creates. *Fixed.*
- **`LeaveTypeSeeder` sat on the tenant path only**, so it covered a tenant's *first* company and
  nothing else; a second company, and every company healed through the re-provision endpoint, opened
  HR → Leave empty. Moved into `provisionDefaults` with the other twenty-two. *Fixed.*
- **The requested base currency never reached the Company row.** `TenantProvisioningService` passed
  it to the currency seeder but never called `setBaseCurrency`, and the entity initialises to `TZS`.
  A tenant provisioned as KES got KES enablement rows and **posted its whole ledger in TZS** — no
  error, just the wrong label on every document. *Fixed.*
- **A stale IT assertion** that had been failing since 2026-08-12, unnoticed because integration
  tests are not in the PR gate. *Fixed.*

## 4 · What this assessment does not claim

It checked the code, not the business. It cannot tell you whether the second customer wants the same
chart of accounts, whether their VFD provider is the same one (**D-8**, unaffected by topology and
now the critical path), or what you have promised them. Treat B1–B7 as the floor, not the ceiling.

Two of its findings were wrong on inspection and are recorded here so the list is not read as
gospel: it reported that a null `baseCurrency` or `enabledCurrencies` would NPE during tenant
creation — both are normalised in `OrganisationServiceImpl.createTenant` before they ever reach the
provisioning service.

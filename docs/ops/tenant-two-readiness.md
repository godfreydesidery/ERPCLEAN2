# Onboarding a second paying customer — what is actually missing

*Assessed 2026-08-15 against the shipped tree (`backend/`, `infra/`, `dist/`), not against the plan.
Every claim below was checked in code; where the assessment contradicted the plan, the code won.*

The one-line answer: **the tenancy spine is built, and it is not what stands between you and
customer #2.** What stands there is provisioning, identity data, and a deployment story — none of
which needs a schema change, and most of which is small.

## 1 · The decision — made 2026-08-15: **SHARED INSTANCE**

**Does one database hold two customers? The owner's answer is yes.** Both customers live in one
database, one application, separated by `organisation_id`. Recorded as **D-11** in
[MULTITENANCY-PLAN.md](../../MULTITENANCY-PLAN.md), together with the own-stack case that was
overruled — kept because the risks it named did not disappear, they became work items.

This section originally recommended separate stacks. That recommendation was not taken, and the
honest consequence is that **the list below gets longer, not shorter**:

| Now required | Why it was not, before |
|---|---|
| **Fix the root/platform tier — first, before anything else** | contained when each customer has their own box |
| **D-5 per-tenant restore** | dropping the database *was* the per-tenant restore |
| **D-4 RLS backstop** (re-decide) | rejected because one database held one tenant |
| **D-6 departure** — per-tenant extract and delete | `pg_dump` and dropping the database |
| **D-8 per-tenant fiscalisation** | a legal gate either way; now a technical one too |
| **Per-tenant mail identity** (P8-5) | one `From` address was one customer's address |

**The item to do first, ahead of everything else on this page.**
`TenantProvisioningService` sets `setRoot(true)` on **every** tenant's administrator, and
`PermissionResolver` short-circuits root past every permission check. On separate stacks that is
contained. **On a shared instance it means customer #2's own administrator satisfies `ORG.SUSPEND` —
against customer #1.** `PLATFORM_OPERATOR` is currently a name in `AuthorityCeiling` with no seeded
row behind it. This is not a decision to weigh; it is a hole to close before customer #2 exists.

**No longer inferred — measured 2026-08-15** on a two-organisation throwaway stack, with both
administrators provisioned the way the product provisions them. Tenant B's admin issued
`POST /api/v1/organisations/uid/<A>/suspend` and received **`HTTP 200`**; organisation A went
`INACTIVE`. A's ordinary staff were then refused login (*"This account is not available at the
moment"*) while A's own root admin could still sign in and resume. Reads are unaffected — all nine
read probes still refuse. Full trace and the operational response:
[`tenant-onboarding.md` §8.2](tenant-onboarding.md#82--the-known-hole--do-not-record-this-as-a-pass).

And one thing no amount of engineering removes: with both customers in one instance, **the next bad
release is a two-customer outage**, and there is no canary — no shipping to one box, watching it, then
shipping to the other. The five gates in
[release-staging-and-rollback.md](release-staging-and-rollback.md) stop being good practice and become
the only defence there is.

## 2 · Blockers, in the order they bite

| # | Blocker | Schema? | Size |
|---|---|---|---|
| **B1** | **The fresh-install path has never been run at 1.8.x.** Kilimanjaro reached 1.8.3 by in-place `update` from 1.6.1. Customer #2 runs `install.sh` against an **empty** database — V99 expand over zero rows, V102/V103, `R__seed_permissions`, `BootstrapRunner`, `TenancyReconciler`. V103's own post-mortem records that the 1.8.0 crash-loop was missed *because the local rehearsal used a fresh database*. The two paths hide different defects, and the fresh one is the only one customer #2 takes. | no | S |
| ~~**B2**~~ | ~~No tax identity is ever captured.~~ **Closed 2026-08-15** — `CreateTenantRequest` now carries `companyLegalName` / `companyTaxId` / `companyVrn`, applied to the Company row before `provisionDefaults` runs. Optional: blank records nothing. | no | done |
| ~~**B2b**~~ | ~~The branding snapshot never re-syncs.~~ **Closed 2026-08-15** — `DocumentModelBuilder` falls back to `companies.legal_name` / `companies.tax_id` when the branding column was **never written**. Read-side only; writes nothing, so it self-heals customer #1 without touching a row. A branding value that is present but *empty* is an administrator's explicit clear and is honoured — see §3 B2b for why that distinction is load-bearing. | no | done |
| **B2c** | **`document_branding` has no `vrn` column** (V19 has `tax_id` only), so a printed invoice cannot show a VRN however diligent the operator. Still open. A VRN captured at tenant creation reaches the Company screen and every standard report header, but no printed transactional document — so a VAT-registered supplier's "TAX INVOICE" is still not compliant. Two shapes: a `vrn` column plus a `BrandingBlock` component and a renderer line (**migration, needs approval**), or the same render-side read of `companies.vrn` that now backs the legal name and TIN (**no migration**, but no per-document override). Owner's call — see §5. | **yes** | S |
| ~~**B3**~~ | ~~No price list is ever seeded.~~ **Closed 2026-08-15** — `priceListCode` + `priceListName` + `priceListIncludesVat` on the create-tenant request build the company's first (and default) price list. Not a seeder: nothing is created on the heal path, and a tenant created without a name gets no price list rather than an invented one. The VAT stance is captured for the same reason the name is — an exclusive list holding VAT-inclusive shelf prices adds 18% to every line, invisibly. | no | done |
| ~~**B4**~~ | ~~No walk-in customer.~~ **Closed 2026-08-15** — `walkInCustomerName` creates one INDIVIDUAL / `CASH_WALK_IN` customer with a generated `CUST-` code. | no | done |
| ~~**B5**~~ | ~~No POS till.~~ **Closed 2026-08-15** — `posTillName` creates one till on the default branch, against the company's default cash account. Degrades (logs and skips) when no cash account exists, rather than aborting the whole tenant on a NOT NULL foreign key. | no | done |
| **B6** | ~~**No onboarding runbook**~~ — **written 2026-08-15: [`tenant-onboarding.md`](tenant-onboarding.md)**, executed end to end against a throwaway two-tenant stack before publication. [`two-tenant-local-stack.md`](two-tenant-local-stack.md) now redirects to it and carries a correction: its probe identity was `is_root = false`, which is **not** what the product provisions. | no | ✅ |
| **B7** | ~~**Backups are instruction, not construction.**~~ **Addressed 2026-08-15.** Both installers now schedule a nightly backup (cron on Linux, a Scheduled Task on Windows, idempotent on both), retention is bounded by age *and* by file count and folder size, and the restore has been **drilled and timed** — see [restore-drill.md](restore-drill.md) for the measured RTO and the caveats on it. Four defects in the shipped backup/restore scripts were found and fixed in the course of it. What is **not** solved: nothing copies backups off the machine, and there is still no per-tenant restore (**D-5**). | no | S |

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

### B2 / B2b / B3 / B4 / B5 — the shape the fix took, and why

The first instinct was to **seed** the four missing things with defaults chosen in Java. That was
rejected, and rightly: a TIN, a price-list name, what the counter customer is called and what is
written on the till are values a human knows. A default here is a wrong answer nobody can tell from
a right one — "Standard" price list, "Walk-in Customer", "Till 1" — and each one then has to be
found and deleted by somebody who does not know it was invented.

So the values **travel on `POST /api/v1/organisations`** and provisioning inserts what it was given.
Blank creates *nothing*: no placeholder, no fallback, no error. The Company screen and the shipped
Price List / Customer / POS Till screens fill in whatever was left out.

The same reasoning is why the price list's **VAT stance** travels on the request. It is not a
preference: an EXCLUSIVE list holding VAT-inclusive shelf prices — the normal case in TZ retail —
has 18% added to every line at invoicing, and `sales_invoice_lines.price_inclusive` is snapshotted
per line, so correcting the flag afterwards fixes nothing already sold. Omitted, the list stays
VAT-exclusive, which is exactly what `POST /api/v1/price-lists` does with the flag omitted
(ADR-0056 D-2) — the default is inherited from the existing service, not invented here.

Four consequences worth knowing:

- **None of it touches the heal path.** `CompanyProvisioningServiceImpl.provisionDefaults` — which
  `POST /api/v1/companies/uid/{uid}/provision-defaults` runs against companies that have traded for
  years — gained no seeder and no parameter. Re-provisioning a live company writes none of these,
  so the "did the idempotency guard hold?" question stops existing rather than being defended.
  `TenantOnlyProvisionersTest` is the build gate that keeps it that way.
- **Tenant one is untouched.** `BootstrapRunner` passes null for every new field, so a fresh install
  produces exactly what it produced before. No new `ERP_BOOTSTRAP_*` key, no `.env.example` change.
  Bootstrap runs only on an empty database, so no environment exercises it before the customer does;
  leaving it alone is worth more than the symmetry.
- **The till degrades, it does not throw.** `pos_tills.cash_bank_account_id` is NOT NULL, and
  `CashBankSeeder` legitimately skips a company with no CASH `gl_config`. Inserting anyway would roll
  back the whole tenant. So the till is skipped and logged at WARN, and the operator adds it later.
  The `201` does not report the skip, which is why *"verify the till was actually created"* is a
  numbered step in [tenant-onboarding.md](tenant-onboarding.md) §7 rather than something to remember.
- **B2b heals a never-set value, not a cleared one.** The branding fallback fires only when
  `document_branding.legal_name` / `tax_id` is `NULL`. An empty string there is not the same thing:
  the Document Branding screen sends `""` for every field left blank, so a stored blank is an
  administrator saying *print no TIN* — and it is their **only** way to keep a superseded
  `companies.tax_id` off a tax document, short of clearing the company row and losing the number from
  the Company screen and all six report headers too. Widening the fallback to "blank" would take that
  away and silently return an old number to the face of every invoice. It is deliberately narrow.

## 4 · What this assessment does not claim

It checked the code, not the business. It cannot tell you whether the second customer wants the same
chart of accounts, whether their VFD provider is the same one (**D-8**, unaffected by topology and
now the critical path), or what you have promised them. Treat B1–B7 as the floor, not the ceiling.

Two of its findings were wrong on inspection and are recorded here so the list is not read as
gospel: it reported that a null `baseCurrency` or `enabledCurrencies` would NPE during tenant
creation — both are normalised in `OrganisationServiceImpl.createTenant` before they ever reach the
provisioning service.

## 5 · One decision left with the owner — the VRN on a printed invoice (B2c)

Everything above closes with no schema change. **B2c does not, and it is the one that still leaves a
VAT-registered tenant printing a document headed "TAX INVOICE" that is not compliant.** The number is
captured, stored and shown on the Company screen and on all six standard report headers; it simply
has no route to the PDF. Two ways to give it one:

1. **A migration.** `ALTER TABLE document_branding ADD COLUMN vrn VARCHAR(40)`, a `vrn` component on
   `BrandingBlock`, one line in `DocumentPdfRenderer`, and the field on the Document Branding screen.
   Consistent with how the TIN and legal name already work, and per-document overridable. Needs
   approval under the standing migration rule.
2. **No migration.** Read `companies.vrn` in `DocumentModelBuilder`, exactly as the legal name and TIN
   fallbacks now do, and print it. Ships immediately; the trade is that the VRN cannot be overridden
   per document — there is one number and it lives on the company row.

Option 2 changes what prints on the live customer's existing documents, so it is a decision and not a
detail. Neither was taken unilaterally.

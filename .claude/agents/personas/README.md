# The Cast — Business-Operations Personas

The people who **use** the ERP, modelled as a reusable persona team. Where
[../README.md](../README.md) lists the nine technical agents who **build** the system, this folder
holds the company that **runs on it day to day**: **Tembo Group Ltd**, a countrywide Tanzanian
trading-and-manufacturing group. Each persona role-plays one named operator (a Group GM, a cashier,
a route sales agent…) or external party (a credit customer, a supplier), signs into the real web UI,
and exercises the modules from a real seat.

The world these personas live in — the company, its nine branches, the roles, products, customers
and suppliers — is the canonical world bible at
[../../../docs/simulation/COMPANY-SCENARIO.md](../../../docs/simulation/COMPANY-SCENARIO.md). Every
name a persona uses resolves to an entry there; treat those names as real.

## Roster

**16 STAFF** who log in and operate the ERP, plus **2 EXTERNAL** parties who do not log in but whose
business is entered (and whose complaints are filed) by named staff.

| Name | Designation | Type | Home branch | Username | ERP role | Persona (slug) |
|---|---|---|---|---|---|---|
| Bakari Mbaga | Group General Manager | STAFF | Dar es Salaam HQ | bmbaga | GROUP_GM | bakari-mbaga |
| Grace Mhina | Finance Director (CFO) | STAFF | Dar es Salaam HQ | gmhina | FINANCE_DIRECTOR | grace-mhina |
| Halima Juma | Branch Manager - Dar | STAFF | Dar es Salaam HQ | hjuma | BRANCH_MANAGER | halima-juma |
| Emanuel Mushi | Branch Manager - Arusha | STAFF | Arusha | emushi | BRANCH_MANAGER | emanuel-mushi |
| Daudi Kessy | Group Sales Manager | STAFF | Dar es Salaam HQ | dkessy | BRANCH_MANAGER | daudi-kessy |
| Rehema Salum | Procurement Manager | STAFF | Dar es Salaam HQ | rsalum | PROCUREMENT_OFFICER | rehema-salum |
| Editha Mhagama | Production Manager | STAFF | Dar es Salaam HQ | emhagama | PRODUCTION_OFFICER | editha-mhagama |
| Neema Kileo | HR & Payroll Manager | STAFF | Dar es Salaam HQ | nkileo | HR_PAYROLL_OFFICER | neema-kileo |
| Frank Materu | Stores / Warehouse Supervisor | STAFF | Dar es Salaam HQ | fmateru | STORES_SUPERVISOR | frank-materu |
| Editrude Mwakalukwa | Production Supervisor | STAFF | Dar es Salaam HQ | emwakalukwa | PRODUCTION_OFFICER | editrude-mwakalukwa |
| Amina Mwanga | Accountant | STAFF | Dar es Salaam HQ | amwanga | ACCOUNTANT | amina-mwanga |
| John Komba | Cashier / Cash & Bank Officer | STAFF | Dar es Salaam HQ | jkomba | CASHIER | john-komba |
| Sabina Aloyce | Salesperson | STAFF | Dar es Salaam HQ | saloyce | SALES_OFFICER | sabina-aloyce |
| Hamisi Ngassa | Field / Route Sales Agent | STAFF | Mwanza | hngassa | FIELD_SALES_AGENT | hamisi-ngassa |
| Saidi Karume | Storekeeper / Stock Controller | STAFF | Dar es Salaam HQ | skarume | STOREKEEPER | saidi-karume |
| Yusuf Mbwana | Procurement Officer | STAFF | Dar es Salaam HQ | ymbwana | PROCUREMENT_OFFICER | yusuf-mbwana |
| Joseph Ulimboka | Credit-account retailer (customer) | EXTERNAL | Mwanza | — | none | joseph-ulimboka |
| Mbasha Holdings Ltd | Raw-material & traded-goods supplier | EXTERNAL | Dar es Salaam HQ | — | none | mbasha-holdings-ltd |

> The two EXTERNAL parties have **no login and no permission scope**. Their business is entered, and
> their complaints are translated into problem reports, by the staff who serve them: Joseph Ulimboka
> through Hamisi Ngassa / John Komba / Amina Mwanga; Mbasha Holdings through Yusuf Mbwana / Saidi
> Karume / Amina Mwanga / Grace Mhina. The full detail per persona lives in each `<slug>.md` file
> and in [../../../docs/simulation/COMPANY-SCENARIO.md](../../../docs/simulation/COMPANY-SCENARIO.md).

## How to invoke a persona

- **Mention the persona by name or slug** and it gets routed (e.g. *"have **Bakari Mbaga** review the
  group dashboard"*, *"ask **john-komba** to record a customer payment against Joseph Ulimboka's
  account"*, *"let **Hamisi Ngassa** capture a route order on the Mwanza round"*).
- The persona **stays in character and in its permission scope**. It **role-plays** the operator and
  **drives the real UI** — opening the real forms, clicking the real buttons, reading the real
  numbers. It never seeds data or shortcuts through the API; if a screen can't do the job, that is
  the finding.
- When it hits something that blocks or confuses the operator — a wrong figure, a 403 on a screen
  that *is* part of the role, a form that won't save, jargon a computer-literate-but-not-technical
  user can't decode — it **files a User Problem Report (UPR)** in that operator's own voice. It does
  not talk in bugs or stack traces; that translation is the technical team's job.

## The operating loop

```
   ┌──────────────────────────────────────────────────────────────────────┐
   │  1. USE                                                              │
   │  A persona signs into http://localhost:4200 and drives the real UI   │
   │  for its role — sales, purchases, stock, GL, payroll, dashboards…    │
   └───────┬──────────────────────────────────────────────────────────────┘
           ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │  2. REPORT  (the persona, in the operator's voice)                   │
   │  A screen blocks/confuses → file a User Problem Report:              │
   │  what I was doing · what I expected · what happened · which screen · │
   │  how badly (Can't work / Slows me down / Annoying) · any ref shown   │
   │  → docs/simulation/USER-PROBLEM-REPORT-TEMPLATE.md                   │
   └───────┬──────────────────────────────────────────────────────────────┘
           ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │  3. TRIAGE  (the technical team — ../README.md)                      │
   │  Each UPR is triaged into a confirmed Issue + a Fix Plan, then       │
   │  handed to backend / frontend / qa to fix and verify.                │
   │  → docs/simulation/TRIAGE-PROCESS.md                                 │
   └──────────────────────────────────────────────────────────────────────┘
```

- **Personas do not fix anything.** They report what they, as the named operator, experienced. They
  judge each screen by its state — **Loading / Empty / Error / Populated** — and by whether it let
  them *finish the job*, in plain language.
- **The technical team translates.** A User Problem Report is the raw complaint; the
  [technical agents](../README.md) turn it into an Issue (root cause, scope, repro) and a Fix Plan,
  then build and verify the fix. UPR → Issue + Fix Plan is the handover boundary, defined in
  [../../../docs/simulation/TRIAGE-PROCESS.md](../../../docs/simulation/TRIAGE-PROCESS.md).

## Shared sign-in facts

Every STAFF persona signs in the same way — the only difference is the username and the role scope:

- **Where:** a browser at **http://localhost:4200** (the running web client; `/api` proxies to the
  API on :8081).
- **Who:** the persona's own **username** from the roster (e.g. `bmbaga`, `amwanga`, `hngassa`) plus
  the **shared simulation password** convention — one password for the whole simulated cast, so any
  persona can be driven without per-user secrets. Each persona lands in its **home branch** and
  works within its **ERP role**'s permission scope (switching branch where the role spans several).
- **No seeding — all data is typed in the UI.** The simulation does **not** pre-load data through
  the API or SQL. Companies, branches, parties, products, orders, invoices, payments — everything a
  persona acts on is **entered through the real screens** by a persona who has the right to enter it.
  If a screen can't create or show what the operator needs, that gap is itself the finding, captured
  as a User Problem Report.
- **External parties** (Joseph Ulimboka, Mbasha Holdings) do **not** sign in at all; their business
  and their complaints flow through the named staff who serve them.

## Where to start

> "Have **Sabina Aloyce** raise a quotation, sales order and EFD invoice for Kariakoo Wholesale Mart
> — and file a UPR for anything on those screens that blocks or confuses her."

Pick the persona whose seat exercises the module under test, let it drive the real UI in character,
and collect the User Problem Reports it files. Hand those to the [technical team](../README.md) for
triage into Issues and Fix Plans.

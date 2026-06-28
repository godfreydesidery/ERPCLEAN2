# ERP Simulation — Tembo Group

> **Status:** Entry point. Read this first, then the world bible
> ([COMPANY-SCENARIO.md](COMPANY-SCENARIO.md)).
>
> *Kila tembo na mzigo wake* — "Every elephant carries its own load."

---

## What this is

A **continuous, role-played simulation** of a real company using this ERP, run to
surface defects the way real users hit them — by *operating the product through the
web UI*, not by reading code or calling the API.

The company is **Tembo Group Ltd**, a countrywide Tanzanian enterprise: a Trading
division that imports and distributes FMCG / hardware / electronics, and a
Manufacturing division that makes edible oil, soap, bottled water, maize flour and
furniture. Nine branches, two divisions, a full cast of customers and suppliers.
The whole world is fixed and named in [COMPANY-SCENARIO.md](COMPANY-SCENARIO.md) —
every branch, party, product, role and person the simulation touches resolves to an
entry there.

The company is operated by a **reusable persona team** — 16 staff who each log into
the ERP web UI as themselves (Amina the accountant, Saidi the storekeeper, Hamisi
the route agent, …) plus 2 external parties whose business is entered by staff. Each
persona:

- signs into the running app at **http://localhost:4200** with their own username,
  lands in their home branch, and drives the **real forms** — typing every field as a
  person at a desk would, never seeding data behind the screen (if the form can't take
  the entry, *that is the finding*);
- works only inside their permission scope and stays in character;
- judges every screen on whether it actually let them finish their job (loading /
  empty / error / populated states, branch clarity, correct numbers & VAT);
- when a screen blocks or confuses them, files a **User Problem Report (UPR)** in
  plain business language — *what I was trying to do, what I expected, what happened,
  which screen, how badly it blocks me, any reference on screen* — and hands it off.

A UPR is **not** a bug report. Personas don't speak in stack traces, status codes or
module names. The **technical team triages** each UPR: reproduces it, decides whether
it's a real defect / works-as-designed / a UX trap / a permission gap, and (for real
defects) promotes it into a proper Issue with a fix plan. This mirrors how problems
actually flow in a deployed product: *user pain in → triaged engineering work out.*

---

## File map

| Path | What it is |
|---|---|
| [README.md](README.md) | **This file** — the entry point and how to run the loop. |
| [COMPANY-SCENARIO.md](COMPANY-SCENARIO.md) | **World bible** — the canonical Tembo Group: company, 9 branches, org chart, ERP roles, product catalog, customers, suppliers, and the full persona roster + detail. Single source of truth; read before role-playing anyone. |
| [USER-PROBLEM-REPORT-TEMPLATE.md](USER-PROBLEM-REPORT-TEMPLATE.md) | The **UPR form** a persona fills in when a screen blocks them (what I was doing / expected / happened / screen / severity / reference). Business language only — no bug terms. |
| [TRIAGE-PROCESS.md](TRIAGE-PROCESS.md) | How the **technical team** triages a UPR: reproduce, classify (real defect / WAD / UX / permission gap), and promote real defects into Issues with fix plans. |
| [`.claude/agents/personas/`](../../.claude/agents/personas/) | The **persona team** — one agent file per staff member (`amina-mwanga.md`, `saidi-karume.md`, …) plus the two external parties (`joseph-ulimboka.md`, `mbasha-holdings-ltd.md`). Each is a reusable, in-character operator scoped to its seat, invokable as a subagent by slug. |
| [`../../e2e/sim/`](../../e2e/sim/) | The **UI-driving harness** (Playwright, no seeding): `sim-data.js` (canon), `sim-lib.js` (login + form helpers + problem capture), `onboard.js` (rootadmin builds the company via the UI), `operate.js` / `run-personas.js` (each persona logs in and works its screens). |
| [UPR-REGISTER.md](UPR-REGISTER.md) | The log of every **User Problem Report** filed in a run — id, persona, screen, severity, status, linked Issue. (Output) |
| [ISSUES-REGISTER.md](ISSUES-REGISTER.md) | The **technical team's triage** of those UPRs into reproducible Issues + Fix Plans, grounded in the code. (Output) |
| [SIM-RUN-REPORT.md](SIM-RUN-REPORT.md) | The end-to-end **run report** — what was built, how it ran, what was found, the systemic finding, how to re-run. |
| [run-2026-06-28/](run-2026-06-28/) | Raw run evidence: `all-problems.json` (29 captured problems), `onboard-summary.json`. |

> The world bible, persona files and harness are the simulation's *inputs*; the UPR
> register, the issues register and the run report are its *outputs*. The 2026-06-28
> run is recorded in [SIM-RUN-REPORT.md](SIM-RUN-REPORT.md).

---

## How to run the loop

The simulation runs against a **live local stack** — personas drive the real web UI,
so the app and its API must be up.

### 1. Bring up the stack

```bash
# from backend/ — Postgres + API (dev profile bootstraps rootadmin / RootPass12345)
docker compose up -d db                                  # Postgres on :5434
mvn spring-boot:run -Dspring-boot.run.profiles=dev       # API on :8081

# from web/ — Angular dev server, /api proxied to :8081
npm install && npm start                                 # web on :4200
```

Confirm the app answers at **http://localhost:4200** before invoking any persona.
(Note: port 4200 may host a different project locally — make sure it's *this* ERP.)

The personas, branches, roles and parties must exist in the DB — all provisioned **through
the UI** (no seeding). Fast path: the harness does it.

```bash
export NODE_PATH=d:/My_Works/ERP/ERPCLEAN2/web/node_modules   # for playwright-core
node e2e/sim/onboard.js        # rootadmin builds 9 branches, 12 roles+perms, 16 accounts via the UI
node e2e/sim/run-personas.js   # all 16 personas log in (non-root) and work their screens → all-problems.json
```

Or provision by hand per [COMPANY-SCENARIO.md](COMPANY-SCENARIO.md) and invoke the persona
agents directly (next step). Sim login password: `Tembo@2026!`.

### 2. Invoke a persona (or several)

Run a persona agent and give it a task drawn from its **primary workflows** in the
world bible — e.g. ask `amina-mwanga` to "raise the supplier invoice for Mbasha
Holdings against their goods-receipt and reconcile VAT," or `saidi-karume` to "receive
the Mbasha Holdings PO into the Dar warehouse with batch/expiry." The persona logs in
as itself, performs the workflow through the UI, and judges each screen.

You can run one persona end-to-end, or fan several out across modules (sales, stock,
purchases, GL, manufacturing, HR/payroll) to exercise the whole company at once — they
share the same world bible, so their data ties together (Yusuf raises the PO → Saidi
receives it → Amina invoices it → John pays it).

### 3. Collect UPRs

Every time a screen blocks or confuses a persona, it files a **UPR** using
[USER-PROBLEM-REPORT-TEMPLATE.md](USER-PROBLEM-REPORT-TEMPLATE.md), in its own voice,
with the on-screen reference (invoice no., GRN, journal no., error code if one shows).
Append each UPR to the session's **`UPR-REGISTER.md`**.

### 4. Triage

The technical team works [UPR-REGISTER.md](UPR-REGISTER.md) per
[TRIAGE-PROCESS.md](TRIAGE-PROCESS.md): reproduce each report **as the reporter's role**
(never root — root masks RBAC), classify it (real defect / works-as-designed / UX trap /
permission gap — note the standing traps: phantom permission codes, route-guard ↔
endpoint parity, error-message hygiene), and promote real defects into
[ISSUES-REGISTER.md](ISSUES-REGISTER.md) with a fix plan. Fixes then follow the normal
branch → PR → `develop` workflow.

### Replaying a persona session through Playwright (optional)

When the technical team needs to reproduce a UPR as an automated run, the persona
sessions follow the existing harness pattern — see `e2e/qa-ui-drive.js` (run with
`NODE_PATH=web/node_modules`) and the other drivers under `e2e/`.

---

*Start here, then read [COMPANY-SCENARIO.md](COMPANY-SCENARIO.md). Every persona,
party, product, role and branch the simulation touches must resolve to that bible.*

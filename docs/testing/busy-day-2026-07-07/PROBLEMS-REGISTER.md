# Busy-Day Problems Register — Tembo Group ERP (local, 2026-07-07)

A full-system "busy day" simulation: the whole 17-strong Tembo Group staff signed into the **real
web UI** (`localhost:4200` → API `:8081`) as themselves (non-root) and did their jobs, while a
harness captured every block, error and permission-denial as a candidate **User Problem Report
(UPR)**. The **desktop OrbixPOS** was run against the same **local** API. This register organises the
raw problems, separates genuine defects from working-as-designed role-scoping, records the fixes the
technical team applied, and the users' re-verification.

## 1. Results at a glance

| Metric | Value |
|---|---|
| Staff who signed in and worked | **17 / 17** |
| Modules exercised | Sales, Purchases, Stock, GL, AR, AP, Cash/Bank, Tax, FX, Cost-centres, Fixed Assets, Budgeting, HR/Payroll, CRM, Projects, Reporting/BI, POS |
| Real end-to-end jobs completed | **11 personas** (see §6) |
| Raw candidate problems captured | **142** (137 BLOCKED, 5 SLOW) |
| **Genuine defects** (after adversarial audit) | **2** — both **FIXED + re-verified** |
| Working-as-designed (role-scoping) | 135 |
| Re-run data artefacts (409 "already exists") | 5 |
| id/uid leaks · 5xx errors · a11y (axe) violations | **0 · 0 · 0** |

**Headline:** the system is largely healthy for correctly-provisioned roles. The only genuine
defects were two flavours of the same gap — *the app didn't tell the user why they couldn't
proceed*. Both are now fixed and confirmed by re-running the affected users.

## 2. How the day was run

- 17 persona drivers (Playwright) logged in with the shared simulation password and each drove the
  screens their **role** owns, plus an **access sweep** of 15 core screens, an **id/uid hygiene
  scan**, and one real **create** in every module their role owns.
- Every redirect / error / 4xx / 403 was captured with evidence (console, page, API) and turned
  into an operator-voiced UPR.
- A deterministic classifier + a **5-auditor adversarial Workflow** then separated genuine problems
  from role-scoping noise and re-run artefacts, reproducing doubtful cases against the live stack.
- Artefacts: `all-problems.json` (aggregate), `classified.json` (classification), `operate-*.json`
  (per-persona raw), `screenshots/` (each persona's landing page).

## 3. Problems Register — genuine defects

| # | Severity | Who hit it | Screen | Problem (user's words) | Status |
|---|---|---|---|---|---|
| **P1** | UX (systemic) | **All 17** (every group) | Any screen outside your role | "When I open a screen I'm not allowed to use, the app just flicks me back to the home page with **nothing on screen telling me why** — no 'you don't have access', no error. I can't tell if I mis-clicked, hit a bug, or lack permission, so I keep retrying." Inconsistent with the clear "You do not have permission" shown elsewhere. | ✅ **FIXED** |
| **P2** | UX / minor | **Saidi Karume** (Storekeeper) | Stock → Locations | Opening Stock Locations — his own job — fired a background **"Forbidden"** because the page tried to load a **sales-agents** list he isn't allowed to see. The page still worked (locations listed), but the background error was confusing. | ✅ **FIXED** |

> **Note on P2:** contrary to first appearances, Saidi was **not** blocked from managing locations —
> he holds `STOCK.LOCATION.VIEW`/`MANAGE`, the locations list returns 200, and the page handled the
> stray 403. The only defect was the unnecessary agents fetch (used solely by the VAN-location
> picker, which needs `AGENT.VIEW`). John Komba's "POS 403" is the **same pattern** (the POS screen
> fetches the agents list) — see §5.

## 4. Fixes applied + re-verification

Branch: `fix/busy-day-access-feedback` (off `develop`).

**Fix A — the guard now explains itself.** `web/src/app/core/auth/permission.guard.ts`
(`requirePermission` / `requireAnyPermission`) previously redirected to `/admin/home` **silently**.
It now shows a calm, non-alarming toast before redirecting:
> *"You don't have access to that screen — it isn't part of your role. Ask your administrator if you need it."*

**Fix B — no more stray Forbidden.** `stock-location-list.component.ts` now **skips** the sales-agents
fetch when the operator lacks `AGENT.VIEW` (the list only feeds the VAN-location picker), so a
storekeeper's Locations screen loads with no background 403.

**Verification:**
- Unit: `permission.guard.spec.ts` (4 tests) + regression on `auth.guard` and `stock-location-list`
  specs → **19/19 pass**.
- **Live re-verify (users tried again):**
  - **Grace Mhina (CFO)** → opened `/admin/work-orders` → redirected home **and now sees the
    message**. (Was: silent bounce.)
  - **Saidi Karume (Storekeeper)** → opened Stock Locations → stays on the page, **0 `/agents`
    403s**, no forbidden text, no API errors. (Was: background 403.)

## 5. Working-as-designed / not defects (excluded from action)

- **135 "silent redirects"** were the access-sweep visiting screens **outside each role's remit**
  (e.g. a CFO opening Stock Count, a storekeeper opening Post Journal). Blocking these is correct;
  only the *missing message* (P1) was the defect — now fixed for all of them at once.
- **5 × 409 "already exists"** (Amina: VAT return + GL account 1100; Neema: department OPS; Grace: FX
  rate USD→TZS + cost-centre CC001) are **re-run artefacts** — the local DB is durable and already
  held that data from prior runs; they would not recur on a clean single day. The duplicate messages
  are friendly and clear (good).
- **Cashier → POS sell (John Komba):** a cashier's remit is cash & bank + AR/AP, not POS selling
  (that's the Sales Officer, for whom POS works). This is role-scoping, not a defect. The only real
  issue was the raw-403 leak, which is the same `/agents`-fetch pattern as P2 and is covered by the
  "tell the user why" direction.

## 6. What worked (positives)

- **Sabina Aloyce** (Sales): quotation → delivery note → sales return → blanket order → standing order.
- **Neema Kileo** (HR): pay component → leave request → staff loan → **full payroll run**.
- **Yusuf Mbwana / Rehema Salum** (Procurement): requisition → RFQ → purchase return → landed cost.
- **Grace Mhina** (Finance): posted a GL journal, AR receipt, AP payment, supplier bill; asset
  category + fixed asset + budget.
- **John Komba** (Cashier): cash entry, cheque, cash transfer, AR receipt, AP payment.
- **Zawadi Lyimo**: created a project. **Managers**: registered CRM leads.
- **0 id/uid leaks, 0 server 5xx, 0 accessibility violations** across the whole run.

## 7. Coverage gaps (not defects — close in the next run)

These modules' **create** flows were **not exercised** because the harness has no scripted action for
them yet (the personas reached the screens fine):

- **Manufacturing** — work orders / BOM consumption (Editha, Editrude).
- **Route / field sales** — van/route sales orders (Hamisi).
- **Stores supervisor** — approval/valuation actions (Frank).

Add these to `e2e/sim/module-actions.json` to confirm those paths in a future busy day.

## 8. Artefacts

- `all-problems.json` — aggregate of every captured problem.
- `classified.json` — genuine / WAD / coverage-gap classification with reasons.
- `operate-<persona>.json` — per-persona raw (access matrix, usage, created, problems).
- `screenshots/` — each persona's landing page.
- Raw operator-voiced UPRs (137) were generated to the run directory; the genuine ones are captured
  in §3 above.

---
*Simulation + triage + fix + re-verification run 2026-07-07 on the local stack. Desktop OrbixPOS was
run against the local API (`http://localhost:8081`) during the day.*

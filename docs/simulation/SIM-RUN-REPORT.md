# Simulation Run Report — 2026-06-28

> The Tembo Group business-operations team used the ERP **through the web UI** and reported the
> problems they hit; the technical team triaged those into engineering Issues + Fix Plans. This is
> the end-to-end record of that loop. Companion docs: [COMPANY-SCENARIO.md](COMPANY-SCENARIO.md)
> (the world), [UPR-REGISTER.md](UPR-REGISTER.md) (what staff filed),
> [ISSUES-REGISTER.md](ISSUES-REGISTER.md) (what the technical team concluded),
> [TRIAGE-PROCESS.md](TRIAGE-PROCESS.md) (how), and the persona team at
> [../../.claude/agents/personas/](../../.claude/agents/personas/).

## What this run was

Two teams, mirrored. The **technical team** (`.claude/agents/*.md`) *builds* the ERP. A new
**business-operations team** (`.claude/agents/personas/*.md`) *uses* it: 18 named personas — 16 staff
who log in (Bakari Mbaga GM, Grace Mhina CFO, Amina Mwanga accountant, Sabina Aloyce salesperson,
Saidi Karume storekeeper, …) plus 2 external parties (Joseph Ulimboka customer, Mbasha Holdings
supplier). They operate a simulated countrywide Tanzanian trading **and** manufacturing company,
Tembo Group Ltd.

**Ground rule honoured:** every value was **typed into the real web UI** (no DB seeding, no API
shortcuts), exactly as real staff would. The harness that drives the browser as each persona is in
[../../e2e/sim/](../../e2e/sim/).

## How it ran

1. **Onboarding (rootadmin, via UI).** Created the company through the actual admin screens:
   **9 branches**, **12 roles** with scoped permissions (each role granted a keyword-scoped slice of
   the 230 seeded permissions, e.g. GROUP_GM 146/230, ACCOUNTANT 39/230, CASHIER 12/230), and **16
   user accounts** — each given a company membership, a default home branch, and its role grant. The
   user-detail prerequisite chain (company membership → branch → role; ADR-0046) was followed. **0
   problems** at the admin layer.
2. **Operations (16 personas, via UI, non-root).** Each persona logged in as itself and worked its
   own screens. Master data was typed in through real forms — procurement registered suppliers and
   sourced products, production registered manufactured products, sales registered customers, the
   route agent registered Joseph Ulimboka. **29 problems** were captured live (with HTTP/console
   evidence).
3. **Reporting.** Each affected persona filed a **User Problem Report** in its own business voice — 6
   UPRs, see [UPR-REGISTER.md](UPR-REGISTER.md).
4. **Triage.** The technical team (security-engineer / backend-engineer agents) reproduced each as
   the reporter's role, root-caused it **in the actual code**, and produced an Issue + Fix Plan — see
   [ISSUES-REGISTER.md](ISSUES-REGISTER.md).

## What the run found

Master-data entry succeeded across roles (suppliers, sourced + manufactured products, customers all
typed in). The blocking problems all share one shape and are **invisible to root** (root bypasses
RBAC) — which is exactly why a non-root persona run surfaces them:

| UPR | Reporter (role) | Screen(s) blocked | Failing call → required permission |
|---|---|---|---|
| UPR-001 | Sabina Aloyce (SALES_OFFICER) | POS / counter sale | `GET /api/v1/branches` → **BRANCH.VIEW** |
| UPR-002 | Editha Mhagama (PRODUCTION_OFFICER) | Work Orders, BOMs, Products | `GET /api/v1/products` → **PRODUCT.VIEW**; `…/branches` → BRANCH.VIEW |
| UPR-003 | Frank Materu (STORES_SUPERVISOR) | Stock on-hand, Stock count, Transfer | `GET /api/v1/branches` → **BRANCH.VIEW**; `…/products` → PRODUCT.VIEW |
| UPR-004 | Grace Mhina (FINANCE_DIRECTOR) | Record Receipt, Record Payment | `GET /api/v1/wht/types` → **WHT.VIEW/MANAGE** |
| UPR-005 | Amina Mwanga (ACCOUNTANT) | Enter Supplier Bill | `GET /api/v1/purchase-orders` → **PURCHASE.ORDER.VIEW** |
| UPR-006 | Sabina Aloyce (SALES_OFFICER) | Price lists > New | `POST /api/v1/price-lists` → **409** (RETAIL already exists) |

Also-reported-by (same root cause): Editrude Mwakalukwa (production), Saidi Karume (storekeeper),
John Komba (cashier).

### Systemic finding (the payoff)

**Transaction screens hard-depend on supporting reference-data list endpoints, and hard-403 the
*whole screen* when the user's role lacks that one VIEW permission — even though the screen is core
to the role.** A branch picker needs `BRANCH.VIEW`; a product picker needs `PRODUCT.VIEW`; the
supplier-bill matcher needs `PURCHASE.ORDER.VIEW`; the receipt screen needs `WHT.VIEW`. Confirmed in
code: `BranchController.list` `@PreAuthorize("@perm.scoped(#companyUid,'company','BRANCH.VIEW')")`,
`ProductController` `@perm.has('PRODUCT.VIEW')`, `PurchaseOrderController.list`
`@perm.has('PURCHASE.ORDER.VIEW')`, `WhtTypeController` `WHT.VIEW`/`WHT.MANAGE`.

This is the same family as the project's [phantom-permission](../../) and route-guard↔endpoint-parity
lessons: a role can pass the screen's own guard yet be blocked by a *dependency* call. The fix space
(decided per-Issue in the register): **(a)** compose every operational/finance role to include the
foundational VIEW permissions its screens read (BRANCH.VIEW, PRODUCT.VIEW, …); **(b)** relax these
ubiquitous reference reads to any authenticated company member; or **(c)** have screens degrade
gracefully (empty picker + inline notice) instead of a hard 403 that blanks the page.

> **Honest caveat.** The sim granted each role a *keyword-scoped* permission bundle, so part of this is
> a role-composition gap rather than a pure product defect — but that is itself the finding: building
> these roles by hand hits exactly this wall, root never sees it, and the hard-403-blanks-the-screen
> behaviour (vs. a graceful empty state) is a real UX/RBAC design call worth making deliberately.
> UPR-006 is duplicate reference data (a RETAIL price list already existed) — the real defect there is
> the absent friendly message (a bare 409), per [error-message-hygiene](../../).

## How to re-run

Stack up (`docker compose up -d db`; backend dev profile :8081; `npm start` in web/ :4200), then:

```bash
export NODE_PATH=d:/My_Works/ERP/ERPCLEAN2/web/node_modules
node e2e/sim/onboard.js          # rootadmin builds the company via the UI (idempotent)
node e2e/sim/run-personas.js     # all 16 personas log in (non-root) and work their screens
```

Raw evidence for this run: [run-2026-06-28/all-problems.json](run-2026-06-28/all-problems.json).
Personas are also invokable as subagents by slug (e.g. `sabina-aloyce`, `amina-mwanga`) in a fresh
session, to role-play and re-test a fix in their own voice.

---

## Deep transactional run (continuation, 2026-06-28)

With the access blockers cleared, the harness was extended past *opening* screens to the **real
transactional work** (`DEEP=1`). Evidence: [run-2026-06-28/deep-run.json](run-2026-06-28/deep-run.json).

**What works end-to-end (typed through the UI):**
- **Procure-to-pay** — Yusuf raises a PO to Mbasha Holdings, **adds a line, and places** the order.
- **GL** — Amina and Grace **post balanced manual journals**.
- **Cash/AR** — John **records a customer receipt** for Joseph Ulimboka.
- **Stock** — Saidi **records an opening balance**.
- **Master data** — procurement registers suppliers + sourced/manufactured products.

**What the deeper layer surfaced (all role-spec, same F22 family — no gate relaxation):**
- The transactional screens have a **read-dependency closure** that peels open one layer at a time:
  Record-Receipt needs `CUSTOMER.VIEW` (picker) **and** `AR.VIEW` (open-items to allocate). A cashier
  legitimately needs both, so the cash/AR role was composed with them (security finding **F22**:
  customer/supplier/AR are sensitive — the gate stays; the role gains the read). Confirmed by re-run:
  John then records a receipt with 0 problems.
- This is exactly what **ISSUE-008** argues for: there is no guard that a role's grants are *closed*
  over the reads its screens fire, so each composition gap ships invisibly until a non-root user hits
  it. The durable fix is that guard/tooling, not whack-a-mole grants.

**Full order-to-cash, UI-only (closed):** the sales-order line gap was a data mismatch (the driver
searched an unregistered product), not a product defect. Once corrected, the line is correctly stopped
by a friendly, working-as-designed 400 — *"Product has no price configured for this company."* — the
real upstream prerequisite. With a persona doing that step in the UI too (procurement prices the product
on the product-detail **Set price** form, `PRODUCT.MANAGE`), the chain completes end to end:
**register product → price it → create customer → sales order → priced line** — every step typed into
the UI. Verified: Yusuf `price product`, Sabina `create SO + line`.

**Residual (non-blocking, correct-by-design):** duplicate "RETAIL price list" and duplicate "Cement"
opening balance both surface a **friendly 409 message** (working-as-designed duplicate data).

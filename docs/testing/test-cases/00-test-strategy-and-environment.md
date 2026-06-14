# 00 — Test Strategy & Environment Matrix (Master Front-Matter)

This is the **master front-matter** for the ERPCLEAN2 whole-system test-case suite. It is **not** a
per-feature case file — it defines the test approach, the canonical environment matrix (user types,
branch/company contexts, every entity-type enum), the convention test charter (C1–C9), the ID scheme,
the priority definitions, and a coverage index pointing at every per-domain case document.

> Read this first. Every per-domain doc (`01-*` … `NN-*`) assumes the matrix, conventions, priorities,
> and seeding approach defined here, and references them by name instead of repeating them.

**Modules/submodules covered:** ALL — this is the strategy umbrella over the entire system
(115 REST controllers under `backend/src/main/java/com/erp/api/*Controller.java`, ~114 admin nav items,
the route table in `web/src/app/features/admin/admin.routes.ts`, nav in
`web/src/app/layout/shell/shell.component.ts`).

**Permission codes in scope:** the full permission catalogue (every `@PreAuthorize` code on the 115
controllers). Per-domain docs cite the exact codes for their slice; this doc documents the *permission
model* (how codes are checked) rather than enumerating all codes.

---

## 1. Purpose & Scope

1.1 **Goal.** Provide an exhaustive, accurate, executable test-case library for the whole ERP:
authentication & IAM, parties & products, the sales O2C cycle, purchasing P2P, stock/inventory,
GL & financial reporting, AR/AP, cash & bank, tax (VAT/WHT), approvals, documents, notifications,
costing/dimensions, fixed assets, CRM, HR & payroll, projects, budgeting, manufacturing, FX,
analytics, and POS.

1.2 **Method.** **Automated-first (Playwright e2e against the deployed QA SPA), with Manual fallback**
for cases that are awkward to automate (visual PDF rendering, axe spot-audits requiring human judgement,
multi-day/scheduled jobs, true multi-tenant isolation that needs a second seeded tenant). Every case is
marked `Automated (Playwright) | Manual | Both`.

1.3 **What "whole-system, UI-driven" means here.** Cases drive the **real browser UI** by route
(`/admin/...`), interact by accessible role/label (not uid), and assert visible state + the resource's
backend effect. They complement — they do **not** replace — the authoritative unit/integration tests in
`backend/src/test` (JUnit / ArchUnit / Testcontainers) and `web/src/**/*.spec.ts` (Vitest). See
`e2e/README.md`: this harness exists to catch full-stack defects unit tests miss (e.g. the outbox
dispatcher TX bug). The system is verified **by permission code**, never by role name (RBAC is by
permission — `PROJECT-CONVENTIONS.md` §3.4).

1.4 **Out of scope for this suite.** Pure unit logic already covered by JUnit/Vitest; load/perf testing;
penetration testing (a security review is a separate exercise). Tenant isolation IS in scope (C7) but is
mostly **Manual/seed-dependent** because the QA box is single-tenant by default.

---

## 2. Test-Case ID Scheme & Domain Codes

**ID format:** `TC-<DOMAIN>-<NNN>` — `DOMAIN` is the 2–6 letter code below; `NNN` is a zero-padded
sequence number, **restarting at 001 within each domain doc**. This doc uses domain code **`STRAT`**
(it carries the convention/environment meta-cases, §6/§7).

| Code | Domain | Doc (suggested) |
|---|---|---|
| `STRAT` | Test strategy, environment, conventions (this doc) | `00-test-strategy-and-environment.md` |
| `AUTH` | Authentication, session, branch-switch, /me, /my-branches | `01-auth-session.md` |
| `IAM` | Organisation, Company, Branch, User, Role, UserRole, UserBranch, Audit | `02-iam.md` |
| `PARTY` | Customer, Supplier, Agent, Other-Party (+ party branch assignment) | `03-parties.md` |
| `PROD` | Product, Price List, Unit of Measure, Tax Rate, Pricing Rules | `04-products-pricing.md` |
| `SALES` | Quotation, Sales Order, Delivery, Sales Invoice, Sales Return, Blanket/Standing orders, Routes | `05-sales-o2c.md` |
| `POS` | POS Till, Session, Sale | `06-pos.md` |
| `PURCH` | Purchase Requisition, RFQ, Supplier Quote, Purchase Order, Goods Receipt, Purchase Return, Landed Cost, Bill Match, Purchase Settings | `07-purchasing-p2p.md` |
| `STOCK` | Stock on-hand, Locations, Batches, Serials, Transfers, Counts, Valuation, Issue-to-project | `08-stock-inventory.md` |
| `GL` | Chart of Accounts, Journals, Trial Balance, Fiscal Periods, GL Config, Year-End Close | `09-general-ledger.md` |
| `AR` | AR Invoice, Receipt, Credit Note, Write-Off, Opening Balance, Statement, Ageing | `10-accounts-receivable.md` |
| `AP` | Supplier Bill, AP Payment, Debit Note, Opening Balance, Statement | `11-accounts-payable.md` |
| `CASH` | Cash/Bank Account, Cash Transfer, Direct Entry, Cheque, Bank Reconciliation, Statement | `12-cash-bank.md` |
| `TAX` | VAT Return, VAT Adjustment, WHT Type, WHT Register | `13-tax-vat-wht.md` |
| `RPT` | Income Statement, Balance Sheet, Cash-Flow, Account Ledger (Reporting) | `14-financial-reporting.md` |
| `APPR` | Approval Policy, Approval Request, inbox | `15-approvals.md` |
| `DOC` | Document, Document Template, Document Branding (PDF) | `16-documents.md` |
| `NOTIF` | Notification inbox, Preferences, Admin (types/deliveries) | `17-notifications.md` |
| `COST` | Dimensions, Dimension Values, Costing Report | `18-costing-dimensions.md` |
| `FA` | Asset Category, Fixed Asset, Depreciation Run, FA reconciliation | `19-fixed-assets.md` |
| `CRM` | Lead, Opportunity, Pipeline, Pipeline Stage, CRM Activity | `20-crm.md` |
| `HR` | Employee, Department, Contract, Pay Component, Payroll Run, Leave, Loan, Statutory | `21-hr-payroll.md` |
| `PROJ` | Project, Task, Timesheet, Costing/WIP | `22-projects.md` |
| `BUD` | Budget, Budget Version, Variance/Departmental reports | `23-budgeting.md` |
| `MFG` | Work Order, BOM, WIP reconciliation, manufacturing reports | `24-manufacturing.md` |
| `FX` | Currency / FX rates, Revaluation runs | `25-fx-multicurrency.md` |
| `BI` | Analytics dashboard | `26-analytics.md` |

(Doc filenames are the recommended layout; the authority is the **domain code** in each `TC-` id.)

---

## 3. Canonical Environment Matrix

All values below are **verified** against the codebase (enum files under
`backend/src/main/java/com/erp/modules/**/domain/enums/`, `platform/common/domain/MasterStatus.java`,
controller `@PreAuthorize`, and the route/nav files). Do **not** invent additional values.

### 3.1 User types (RBAC is by permission code, never role name)

| User type | What it represents | Use in tests |
|---|---|---|
| `rootadmin` | Bootstrap **SUPERUSER**; bypasses all permission checks **and** cross-tenant scope (`SessionStore.hasPermission` → always true for root). | Seeding, positive "everything visible" smoke. **Never** use for negative-auth / forbidden assertions. |
| `ORG_ADMIN` | Org/company administrator (manages IAM, companies, branches, masters). | Admin/setup positive cases. |
| `SALES_MANAGER` | Owns the sell side (quotes→orders→delivery→invoice; confirm/approve). | Sales O2C positive + approve/confirm transitions. |
| `SALES_REP` | Creates quotes/orders but lacks confirm/post powers. | Sales create cases; **denied** on confirm/post/cancel. |
| `ACCOUNTANT` | GL/AR/AP/cash/tax/reporting posting & viewing. | Finance positive cases. |
| `STOREKEEPER` | Stock receipts, transfers, counts, locations. | Stock/goods-receipt positive; **denied** on finance posting. |
| `PURCHASE_OFFICER` | Requisition→RFQ→PO→receive→bill. | Purchasing positive; **denied** on sales/finance posting. |
| `CUSTOM` role | Admin-defined **subset** of permissions (e.g. VIEW-only on one module). | Proves gating granularity: one allowed action + adjacent denied action. |
| `NO-PERMISSION` user | A login with **zero** functional permissions. | Empty/hidden nav; `forbidden` state on every screen; 403 on every API. |

> RBAC enforcement (verified): controllers gate with SpEL helpers — `@perm.has('CODE')` (presence of the
> permission) and `@perm.scoped(#uid,'<resourceType>','CODE')` (permission **plus** company/branch scope
> for the referenced resource). A lacking user gets **403**; the SPA shows the screen as `forbidden` (C4)
> and the nav item is hidden when `session.hasPermission(item.permission)` is false (shell §6 of
> `CONVENTIONS.md`). Negative-auth cases MUST run as the role that lacks the exact `@PreAuthorize` code,
> never as root.

### 3.2 Branch / Company contexts (NO "branch type" enum)

Branch = `code` + `name` + `timeZone` + `isDefault` (bool) + `status` (`MasterStatus`). The active branch
is carried in the **`X-Branch-Uid`** request header (verified in `web/.../core/api/http.interceptors.ts`),
switchable without re-login via `AuthService` / `GET /api/v1/auth/my-branches`.

| Context variation | What to vary | Why |
|---|---|---|
| Default vs non-default branch | `isDefault=true` vs `false` | Login lands in default; switching exercises the override header. |
| Single-branch vs multi-branch company | 1 branch vs ≥2 | Company `<select>`/branch switcher hidden when single (CONVENTIONS §3). |
| User assigned ONE branch | `user_branch` ⊇ {B1} | Sees only B1's branch-scoped data. |
| User assigned MANY branches | `user_branch` ⊇ {B1,B2} | Can switch; sees union of assigned branches. |
| User assigned ALL branches | `user_role.branch_id = NULL` (all-branch scope) | Company-wide visibility. |
| **Switch the active branch** | change `X-Branch-Uid` to B2 | Data set updates to B2; no re-login. |
| **Act in an UNASSIGNED branch** | set `X-Branch-Uid` to a branch with no `user_branch` | MUST be **denied** (403 / forbidden) — `@perm.scoped` fails. |
| Multi-**company** isolation | Org→Company A vs Company B | Tenant A cannot see Tenant B's records (C7). |
| Company/branch `status` | `ACTIVE` vs `INACTIVE`/`ARCHIVED` | Inactive company/branch not selectable; soft-delete (C9). |

### 3.3 Entity-type enums (EXACT values — assert behaviour changes per value)

| Entity / dimension | Enum (file) | Values |
|---|---|---|
| Customer party type | `PartyType` (parties) | `INDIVIDUAL`, `BUSINESS` |
| Customer kind | `CustomerKind` (parties) | `CASH_WALK_IN`, `CREDIT_ACCOUNT` |
| Supplier kind | `SupplierKind` (parties) | `GOODS`, `SERVICE` |
| Agent kind | `AgentKind` (parties) | `INTERNAL`, `EXTERNAL` |
| Product type | `ProductType` (products) | `GOODS` (stockable), `SERVICE` (NOT stockable — `chk_product_service_stockable`) |
| Stock location type | `LocationType` (stock) | `WAREHOUSE`, `STORE`, `VAN`, `QUARANTINE`, `OTHER` |
| POS/receipt tender | `TenderType` (sales) | `CASH`, `MOBILE_MONEY` |
| GL account type | `AccountType` (gl) | `ASSET`, `LIABILITY`, `EQUITY`, `INCOME`, `EXPENSE` |
| Master record status | `MasterStatus` (platform/common) | `ACTIVE`, `INACTIVE`, `ARCHIVED` |
| HR contract type | `ContractType` (hr) | `PERMANENT`, `FIXED_TERM`, `CASUAL`, `PROBATION` |
| HR employment status | `EmploymentStatus` (hr) | `ACTIVE`, `ON_LEAVE`, `SUSPENDED`, `TERMINATED` |
| HR pay frequency | `PayFrequency` (hr) | `MONTHLY` |
| Currency | (base) | base = **TZS**; foreign currencies + FX rates for multi-currency |

**Behaviour-changing pairings to cover:**
- Customer `BUSINESS`×`CREDIT_ACCOUNT` (credit terms / AR ageing) vs `INDIVIDUAL`×`CASH_WALK_IN` (POS, no credit).
- Product `GOODS` creates stock moves / valuation; `SERVICE` does **not** (and rejects stock-location assignment).
- Supplier `GOODS` (goods receipt → stock) vs `SERVICE` (bill only, no GRN/stock).
- Agent `INTERNAL` vs `EXTERNAL` (commission/assignment differences per module).

### 3.4 Status-lifecycle enums (transition + ILLEGAL-transition cases live in each domain doc)

Each per-domain doc MUST read its own lifecycle enum file and write a case **per legal transition** and a
**reject case per illegal transition**. Verified examples (read the enum file for the full set & guards):

| Enum (file) | Verified states |
|---|---|
| `QuotationStatus` (sales) | `DRAFT`, `SENT`, `ACCEPTED`, `EXPIRED`, `REJECTED` |
| `SalesOrderStatus` (sales) | `DRAFT`, `CONFIRMED`, `PARTIALLY_FULFILLED`, `FULFILLED`, `PARTIALLY_INVOICED`, `INVOICED`, `CLOSED`, `CANCELLED` |
| `PurchaseOrderStatus` (purchases) | `DRAFT`, `ORDERED`, `PARTIALLY_RECEIVED`, `RECEIVED`, `CLOSED`, `VOID` |
| `StockTransferStatus` (stock) | `DRAFT`, `DISPATCHED`, `RECEIVED`, `COMPLETED`, `CANCELLED` |
| `PosSessionStatus` (sales) | `OPEN`, `CLOSED`, `RECONCILED` |
| (others) | `GoodsReceiptStatus`, `DeliveryStatus`, `SalesReturnStatus`, `PurchaseReturnStatus`, `RfqStatus`, `RequisitionStatus`, `SupplierBillStatus`, `BillMatchStatus`, `ArInvoiceStatus`, `ArReceiptStatus`, `VatReturnStatus`, `WorkOrderStatus`, `BomStatus`, `ProjectStatus`, `PayrollRunStatus`, `LeaveRequestStatus`, `LoanStatus`, `DepreciationRunStatus`, `FixedAssetStatus`, `ApprovalRequestStatus`, `OpportunityStatus`, `LeadStatus`, `BlanketStatus`, `StandingStatus`, `StockCountStatus`, `LandedCostStatus`, `ChequeStatus`, `ReconciliationStatus`, `FxRevaluationRunStatus`, `PeriodStatus`, `BudgetVersionStatus`, … (≈100 enums under `**/domain/enums/`) |

### 3.5 Supplier / Agent / Product / Location / Tender / Contract — quick reference

Already in §3.3; restated here as the "pickable type" cheat-sheet that per-domain seeders draw from:
`SupplierKind{GOODS,SERVICE}` · `AgentKind{INTERNAL,EXTERNAL}` · `ProductType{GOODS,SERVICE}` ·
`LocationType{WAREHOUSE,STORE,VAN,QUARANTINE,OTHER}` · `TenderType{CASH,MOBILE_MONEY}` ·
`ContractType{PERMANENT,FIXED_TERM,CASUAL,PROBATION}` · `EmploymentStatus{ACTIVE,ON_LEAVE,SUSPENDED,TERMINATED}`.

---

## 4. How Playwright Consumes These Cases

4.1 **Target.** Deployed QA SPA at **http://16.170.11.41/** (single origin; the API is served behind
`/api/v1` so there is no CORS). Login `rootadmin` for seeding; seed the other role-users (§5) for
RBAC cases. (Local alternative: `e2e/static-proxy-server.js` serving the built SPA, per `e2e/README.md`.)

4.2 **Login per role.** A reusable login fixture POSTs `/api/v1/auth/login`, stores the JWT, then drives
the UI as that user. The active branch is sent as **`X-Branch-Uid`**; switching branch = change the
header / use the in-app branch switcher (no re-login). Negative-auth cases log in as the role lacking the
permission — **never** as root.

4.3 **Navigate by ROUTE.** Go to `/admin/<route>` (e.g. `/admin/sales-orders`,
`/admin/sales-orders/uid/:uid`, `/admin/goods-receipts/create`). Routes are the contract in
`web/src/app/features/admin/admin.routes.ts`. **Never** type or assert a uid in a URL by hand in a way a
user would — uids appear only in the path, chosen under the hood by a picker.

4.4 **Interact by accessible role/label.** Use `getByRole`, `getByLabel`, `getByPlaceholder`,
`getByText`. Pick another resource via the shared **`<app-uid-picker>`** by **human name/code** (the uid
is stored under the hood). Paginate via the shared **`<app-paginator>`** (first / previous / numbers /
next / last). Forms are `[(ngModel)]` two-way; submit triggers imperative validation → `role="alert"`.

4.5 **Assert.** (a) the **four states** distinctly — loading (spinner + `aria-live`), empty
(empty message), error (`role="alert"`), forbidden ("no permission"); (b) **pagination** controls present
on multi-page lists and **self-hidden** at one page; (c) **picker present** + **no raw uid on screen**
(C1); (d) the **resulting record/state** + the API envelope/HTTP where relevant; (e) for `Automated`
cases, an **axe** scan with no new serious/critical violations (C6).

4.6 **Money & dates.** Assert money as the formatted string `"CUR 1,234.56"` (string on the wire), dates
as ISO `yyyy-MM-dd` (C8).

---

## 5. Data-Seeding Approach

5.1 **Primary seeders (reuse the existing harness — do not rebuild):**
- `e2e/seed-and-flow.js` — rootadmin bootstraps → creates an operator role + 100 branch-assigned,
  role-granted users → a **non-root** operator bulk-creates 1000 customers / 50 suppliers / 50 products /
  20 agents / 10 routes → runs PO→goods-receipt (stock in) and finalised sales (stock out) → **asserts**
  counts, stock math, invoice-number uniqueness; logs failures to `issues.json` (never aborts). Use this
  to populate pagination/list/scope cases at scale.
- `e2e/full-coverage-drive.js` — the broad coverage driver (exercises the full endpoint surface).
- `e2e/seed-and-flow.js` + `e2e/qa-ui-drive.js` — `qa-ui-drive.js` does **100% typed UI** entry (no API
  seeding) when a case must prove the real forms; counts are env-tunable (`N_CUSTOMERS`, …).
- `e2e/static-proxy-server.js` / `e2e/ui-smoke.js` — serve SPA + smoke a browser flow.

5.2 **Config (env, sensible defaults — see `e2e/README.md`):** `API_BASE`
(`http://127.0.0.1:8088/api/v1` local; point at the QA `/api/v1` for QA), `WEB_BASE`
(`http://16.170.11.41` for QA), `ROOT_USER`/`ROOT_PASS` (`rootadmin`/`RootPass12345`). Run with
`playwright-core` on `NODE_PATH` for the browser scripts.

5.3 **Known seeding gotchas (verified in `e2e/README.md`) — bake into seeders/cases:**
- Parties (`/customers`, `/suppliers`, `/agents`) create DTOs take **`companyId` (Long)**; newer masters
  (`/products`, `/price-lists`, `/routes`, `/units`) take **`companyUid` (String)** — handle both.
- `GET /companies` requires `?organisationUid=` or returns a clean **400**.
- Long/BigDecimal ids are **JSON strings** on the wire (id survives JS); type everything as `string`.

5.4 **Seed hygiene.** QA is shared and data-preserved — seeders must be **idempotent** (skip-if-exists by
code/name) and tag test data (recognisable prefixes). Tenant-isolation (C7) cases need a **second
company/tenant**; if QA has only one, mark those cases **Manual** and seed a scratch tenant via API.

---

## 6. Convention Test Charter (C1–C9)

These are cross-cutting assertions woven into every applicable per-domain case. The `STRAT` cases below
(§6.x) are the canonical, reusable definitions; per-domain docs reference them by tag.

| Tag | Convention | The assertion to make | Source |
|---|---|---|---|
| **C1** | Identity / picker | uid appears **only** in the URL path; **never** shown in tables/labels/detail; resource refs chosen via `<app-uid-picker>` by **name/code**; no hand-typed uid; no numeric DB id in any URL. | CONVENTIONS §2/§7; GROUND TRUTH C1 |
| **C2** | API envelope | responses are `ApiResponse<T>` `{data,errors,meta}`, auto-unwrapped; paginated lists keep `meta {page,size,totalElements,totalPages,hasNext}`. | CONVENTIONS §2; PROJECT-CONV §3.1 |
| **C3** | RBAC | every action gated by its `@PreAuthorize` code; lacking user → nav hidden / screen `forbidden` / API **403**; allowed role passes. | PROJECT-CONV §3.4; `@perm.has`/`@perm.scoped` |
| **C4** | Four states | list/detail handle **loading / empty / error / forbidden** distinctly. | CONVENTIONS §3 |
| **C5** | Pagination | shared `<app-paginator>`: first / previous / page-numbers / next / last; self-hidden at 1 page. | CONVENTIONS §3/§7 |
| **C6** | A11y | WCAG 2.1 AA — axe-clean (no new serious/critical), keyboard-operable, aria labels, table `<caption>` + `scope`. | PROJECT-CONV §3.8; CONVENTIONS §3 |
| **C7** | Multi-tenancy / scope | data is company- + branch-scoped; user sees only their company + assigned branches; cross-tenant/branch denied. | PROJECT-CONV §3.2 |
| **C8** | Money / date | money is a string formatted `"CUR 1,234.56"`; dates ISO `yyyy-MM-dd`. | CONVENTIONS §7 |
| **C9** | Soft-delete / append-only | masters deactivate/archive (`MasterStatus`), never hard-delete; financial postings are append-only (reversals, never edits). | PROJECT-CONV §3.6 |

### Type/role variations exercised (this strategy doc)

| Dimension | Variations exercised in STRAT meta-cases |
|---|---|
| User type | `rootadmin` (positive smoke), one functional role (positive), `NO-PERMISSION` (forbidden/empty-nav), `CUSTOM` (granular gating) |
| Branch/company | default vs non-default; single vs multi-branch; assigned vs **unassigned** (denied); switch active branch; cross-company isolation |
| Entity-type enums | representative one per family to prove C1/C8 formatting (e.g. Customer `BUSINESS`×`CREDIT_ACCOUNT`, Product `GOODS`/`SERVICE`) |
| Screen states | loading / empty / error / forbidden (C4) on a representative list |

---

### TC-STRAT-001 — uid is never shown; resource references use a name picker (C1)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cross-cutting convention (any list+detail; canonical: Sales Orders `/admin/sales-orders` · `/api/v1/sales-orders`)
- **Permission / Role:** `SALES.ORDER.VIEW` / `SALES.ORDER.CREATE` — runs as `SALES_MANAGER` (has both); also as `NO-PERMISSION` → expect forbidden
- **Variation:** customer = `BUSINESS`+`CREDIT_ACCOUNT`; product = `GOODS`
- **Preconditions / Seed:** ≥1 customer + ≥1 product seeded (via `seed-and-flow.js`); an existing sales order
- **Steps:**
  1. Login `SALES_MANAGER`; navigate to `/admin/sales-orders`.
  2. Inspect the list table: read every visible cell + the detail link.
  3. Open `/admin/sales-orders/uid/:uid` (link click); read all visible text.
  4. Start a create flow; open the **customer** picker (`<app-uid-picker>`) and choose by **name**; open the **product** picker and choose by **name/code**.
- **Test Data:** customer "Acme Distributors Ltd" (BUSINESS, CREDIT_ACCOUNT); product "Widget A" (GOODS)
- **Expected Result:** no raw uid string is visible anywhere in tables/labels/detail text; the uid appears **only** inside the URL path of the detail link; resource references are selected via a name/code picker, with the uid stored under the hood (never typed).
- **Convention Assertions:** **C1** (uid-not-shown; picker-used-by-name; no numeric id in URL); **C3** (NO-PERMISSION → forbidden); **C6** (axe scan on list + detail).
- **Negative / Edge:** a free-text uid/id `<input>` anywhere on a create/edit form is a **failure**; a uid rendered in a visible table cell is a **failure**.

### TC-STRAT-002 — Every list/detail handles the four states distinctly (C4)
- **Type:** Both (loading/empty/error automatable; some error injection Manual)
- **Priority:** P1
- **Module / Submodule:** Cross-cutting (canonical: Customers `/admin/customers` · `/api/v1/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — runs as `ORG_ADMIN`; also as `NO-PERMISSION` → forbidden
- **Preconditions / Seed:** an empty company (for empty state) and a populated one (for default state)
- **Steps:**
  1. As `ORG_ADMIN` on a slow/throttled connection, load `/admin/customers` → observe **loading** (spinner + `aria-live`).
  2. Switch to a company with **no** customers → observe **empty** message (not an error).
  3. Force an API error (offline / 5xx) → observe **error** `role="alert"`.
  4. As `NO-PERMISSION`, load `/admin/customers` → observe **forbidden** ("no permission").
- **Test Data:** company "Empty Co" (0 customers); company "Acme Org / Acme Co" (N customers)
- **Expected Result:** four visually + semantically distinct states; empty ≠ error; forbidden never shows the table.
- **Convention Assertions:** **C4** (four states); **C3** (forbidden = 403 under the hood); **C6** (axe each state).
- **Negative / Edge:** empty rendered as an error, or error rendered as empty, is a failure; forbidden leaking partial data is a failure.

### TC-STRAT-003 — Shared paginator: first/prev/numbers/next/last + self-hide (C5)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cross-cutting (canonical: Customers list, which can hold 1000 seeded rows)
- **Permission / Role:** `CUSTOMER.VIEW` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** ≥ (2 × page size) customers via `seed-and-flow.js`; also a company with ≤ 1 page
- **Steps:**
  1. Load `/admin/customers` with many rows → assert the `<app-paginator>` shows **first / previous / page-numbers / next / last**.
  2. Click next, last, a page number, previous, first → assert rows + `meta.page` update; `aria-current` on the active page.
  3. Switch to a company with ≤ 1 page of customers → assert the paginator renders **nothing**.
- **Test Data:** ≥ 2 pages of customers; a small company (≤ size customers)
- **Expected Result:** every control present and functional; `meta {page,size,totalElements,totalPages,hasNext}` consistent; control self-hides at `totalPages <= 1`.
- **Convention Assertions:** **C5** (all five controls + self-hide); **C2** (paginated `meta` preserved); **C6** (axe; paginator is a `<nav>` with `aria-current`).
- **Negative / Edge:** bespoke prev/next buttons instead of `<app-paginator>` is a failure; next enabled past last page is a failure.

### TC-STRAT-004 — RBAC denial as the role lacking the permission (C3) — not root
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Cross-cutting (canonical: Sales Order confirm `/api/v1/sales-orders` `SALES.ORDER.CONFIRM`)
- **Permission / Role:** `SALES.ORDER.CONFIRM` — runs as `SALES_MANAGER` (allowed) and as `SALES_REP` (lacks confirm → denied)
- **Preconditions / Seed:** a `DRAFT`/`CONFIRMED`-eligible sales order
- **Steps:**
  1. As `SALES_REP`, open the sales order → the **Confirm** action is hidden/disabled; calling the confirm endpoint returns **403**.
  2. As `SALES_MANAGER`, open the same order → **Confirm** is available and succeeds (status advances).
- **Test Data:** sales order in `DRAFT`
- **Expected Result:** permission gating is granular per action and enforced at the API (403), not only hidden in the UI.
- **Convention Assertions:** **C3** (per-action gating; 403; nav/button hidden for the lacking role); **C9** (status change is an append-only transition, not a destructive edit).
- **Negative / Edge:** running this as `rootadmin` (which bypasses checks) would invalidate the test — MUST use the non-root lacking role.

### TC-STRAT-005 — Branch scope: assigned vs unassigned branch; switch without re-login (C7/branch model)
- **Type:** Both (switch automatable; unassigned-branch denial may need header injection = Manual/API)
- **Priority:** P1
- **Module / Submodule:** Auth/branch context (`/api/v1/auth/my-branches`; `X-Branch-Uid` header) over any branch-scoped list (canonical: Stock on-hand `/admin/stock` · `/api/v1/stock`)
- **Permission / Role:** `STOCK.VIEW` (+ branch scope) — runs as a user assigned to branches B1,B2 (not B3)
- **Variation:** default branch B1; non-default B2; **unassigned** B3
- **Preconditions / Seed:** company with branches B1 (default), B2, B3; user with `user_branch` {B1,B2}; distinct stock per branch
- **Steps:**
  1. Login → land in **default** B1; `/admin/stock` shows B1 stock.
  2. Switch active branch to **B2** (branch switcher; `X-Branch-Uid`=B2) — no re-login → list shows **B2** stock.
  3. Attempt to act in **B3** (set `X-Branch-Uid`=B3, a branch with no `user_branch`) → **denied** (403 / forbidden) because `@perm.scoped` fails.
- **Test Data:** B1="Main (default)", B2="Depot", B3="Unassigned Depot"
- **Expected Result:** data set follows the active assigned branch; switching needs no re-login; an unassigned branch is rejected.
- **Convention Assertions:** **C7** (branch scoping + denial); **C1** (branch chosen by name in the switcher, uid under the hood); **C3** (403 on unassigned).
- **Negative / Edge:** seeing B3 data, or being allowed to post in B3, is a failure; switching requiring re-login is a failure.

### TC-STRAT-006 — Cross-company tenant isolation (C7)
- **Type:** Manual (needs a second seeded tenant) — automate if QA has 2 companies
- **Priority:** P1
- **Module / Submodule:** Cross-cutting multi-tenancy (any company-scoped list)
- **Permission / Role:** any VIEW code — runs as a Company-A user
- **Preconditions / Seed:** Organisation with Company A and Company B; the user belongs to A only; each has distinct masters
- **Steps:**
  1. As a Company-A user, list customers/products/orders → only **A**'s records appear.
  2. Attempt to open a Company-B record by its uid in the URL → **404/forbidden** (not found in A's scope).
- **Test Data:** Company A "Acme Co"; Company B "Globex Co" with their own data
- **Expected Result:** no Company-B data leaks into A's lists or detail; direct-uid access to B is denied.
- **Convention Assertions:** **C7** (tenant isolation); **C2** (envelope errors are user-safe).
- **Negative / Edge:** any B record visible to an A user is a failure; a finder bypassing the scoped base repo is a tenant-isolation bug (PROJECT-CONV §3.2).

### TC-STRAT-007 — Money/date formatting & API envelope on the wire (C2/C8)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Cross-cutting (canonical: a Sales Invoice detail `/admin/sales-invoices/uid/:uid` · `/api/v1/sales-invoices`)
- **Permission / Role:** `SALES.INVOICE.VIEW` — runs as `ACCOUNTANT`
- **Preconditions / Seed:** a posted sales invoice with a known total
- **Steps:**
  1. Open the invoice detail; read displayed money + dates.
  2. Capture the underlying API response.
- **Test Data:** invoice total 1,234.56 in TZS, date 2026-06-14
- **Expected Result:** money shown as `"TZS 1,234.56"` (string on wire); date shown as `2026-06-14`; response is `ApiResponse<T>` `{data,errors,meta}` and the SPA shows unwrapped data only.
- **Convention Assertions:** **C8** (money string + ISO date); **C2** (envelope auto-unwrap; ids are JSON strings).
- **Negative / Edge:** money rendered as a raw number/float, or a date in a non-ISO locale format, is a failure.

### TC-STRAT-008 — Soft-delete / append-only posting (C9)
- **Type:** Both (deactivate automatable; append-only reversal partly Manual)
- **Priority:** P2
- **Module / Submodule:** Cross-cutting (canonical: a master e.g. Product `/admin/products` `MasterStatus`; a posting e.g. GL Journal `/admin/gl/journals`)
- **Permission / Role:** `PRODUCT.MANAGE` and `GL.POST` — runs as `ORG_ADMIN` / `ACCOUNTANT`
- **Preconditions / Seed:** an ACTIVE product; a posted journal
- **Steps:**
  1. Deactivate/archive the product → status becomes `INACTIVE`/`ARCHIVED`; the record persists (not hard-deleted) and is excluded from active pickers.
  2. Attempt to "edit" a posted journal → not allowed; correction is a new **reversal** posting.
- **Test Data:** product "Obsolete Widget"; posted journal JNL-0001
- **Expected Result:** masters soft-delete via `MasterStatus`; financial postings are append-only (reversal, never edit).
- **Convention Assertions:** **C9** (soft-delete + append-only); **C1** (status badge; no uid shown); **C3** (manage/post gated).
- **Negative / Edge:** a hard-delete of a master, or an in-place edit of a posted journal, is a failure.

### TC-STRAT-009 — No-permission user sees an empty nav and no forbidden screens it shouldn't reach
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Shell navigation (`web/.../layout/shell/shell.component.ts`)
- **Permission / Role:** none — runs as `NO-PERMISSION`
- **Preconditions / Seed:** a login with zero functional permissions
- **Steps:**
  1. Login `NO-PERMISSION`; inspect the shell nav.
  2. Attempt to navigate directly to a permissioned route (e.g. `/admin/customers`).
- **Test Data:** user "noperm.user"
- **Expected Result:** nav shows only items whose `permission` the user holds (i.e. effectively none beyond neutral home); direct navigation to a permissioned route shows **forbidden** / redirects to admin home (route guard `requirePermission`).
- **Convention Assertions:** **C3** (nav filtered by `session.hasPermission`; route guard denies); **C4** (forbidden state).
- **Negative / Edge:** any permissioned nav item visible, or a permissioned screen rendering data, is a failure.

### TC-STRAT-010 — Custom role proves granular per-permission gating
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** IAM role + cross-cutting (canonical: Customer VIEW vs MANAGE)
- **Permission / Role:** `CUSTOMER.VIEW` granted, `CUSTOMER.MANAGE` **not** granted — runs as a `CUSTOM` role
- **Preconditions / Seed:** a custom role with **only** `CUSTOMER.VIEW`; a user holding it
- **Steps:**
  1. As the custom-role user, open `/admin/customers` → list renders (VIEW allowed).
  2. Attempt to create/edit a customer → action hidden/disabled; the manage endpoint returns **403**.
- **Test Data:** role "Customer Viewer" = {`CUSTOMER.VIEW`}
- **Expected Result:** a permission subset grants exactly the gated capability and nothing more.
- **Convention Assertions:** **C3** (granular gating; 403 on the ungranted action); **C1** (no uid shown).
- **Negative / Edge:** the create/manage action available to a VIEW-only role is a failure.

---

## 7. Priority Definitions

| Priority | Meaning | Examples |
|---|---|---|
| **P1** | Critical path / correctness-or-compliance / security-or-money. Must pass before release; run every cycle. | login & RBAC denial (C3), branch/tenant scope (C7), financial posting & append-only (C9), core O2C/P2P happy path, status-transition correctness incl. illegal-transition rejection. |
| **P2** | Important coverage: secondary flows, validation/required-field, four-state (C4), pagination (C5), money/date formatting (C8), entity-type-enum behaviour branches. | empty/error states, required-field rejection, soft-delete, custom-role gating, multi-currency formatting. |
| **P3** | Edge / cosmetic / low-frequency: boundary values, rare enum variants, a11y polish beyond axe-clean, large-list performance feel. | extreme pagination boundaries, rarely-used location types, optional-field permutations. |

---

## 8. Coverage Index (every domain doc + its module count)

Module count = the number of REST controllers (`com.erp.api.*Controller`) primarily owned by the domain.
115 controllers total (`HealthController` excluded from functional counts). Each domain doc owns the
exhaustive per-endpoint, per-transition, per-enum, per-role, four-state, pagination, and convention cases
for its slice; this index is the map.

| Doc | Domain | Module count | Key controllers (base path) |
|---|---|---|---|
| `01-auth-session.md` | AUTH | 1 | AuthController (`/api/v1/auth`: login/refresh/logout/me/my-branches) |
| `02-iam.md` | IAM | 9 | Organisation, Company, Branch, User, Role, UserRole, UserBranch, Audit (+ Health, non-functional) |
| `03-parties.md` | PARTY | 4 | Customer (`/customers`), Supplier (`/suppliers`), Agent (`/agents`), OtherParty (`/other-parties`) |
| `04-products-pricing.md` | PROD | 5 | Product (`/products`), PriceList (`/price-lists`), UnitOfMeasure (`/units`), TaxRate (`/tax-rates`), PricingRule (`/pricing-rules`) |
| `05-sales-o2c.md` | SALES | 8 | Quotation, SalesOrder (`/sales-orders`), Delivery, SalesInvoice, SalesReturn, BlanketOrder, StandingOrder, Route |
| `06-pos.md` | POS | 3 | PosTill, PosSession, PosSale |
| `07-purchasing-p2p.md` | PURCH | 9 | PurchaseRequisition, Rfq, SupplierQuote, PurchaseOrder, GoodsReceipt, PurchaseReturn, LandedCost, BillMatch, PurchaseSettings |
| `08-stock-inventory.md` | STOCK | 8 | Stock, StockLocation, StockBatch, StockSerial, StockTransfer, StockCount, StockValuation, IssueToProject |
| `09-general-ledger.md` | GL | 6 | ChartOfAccount, Journal, TrialBalance, FiscalPeriod, GlConfig, YearEndClose |
| `10-accounts-receivable.md` | AR | 6 | ArInvoice, ArReceipt, ArCreditNote, ArWriteOff, ArOpeningBalance, ArStatement (+ ageing endpoint on ArStatement) |
| `11-accounts-payable.md` | AP | 5 | SupplierBill, ApPayment, ApDebitNote, ApOpeningBalance, ApStatement |
| `12-cash-bank.md` | CASH | 6 | CashBankAccount, CashTransfer, CashDirectEntry, Cheque, BankReconciliation, CashAccountStatement |
| `13-tax-vat-wht.md` | TAX | 4 | VatReturn, VatAdjustment, WhtType, WhtRegister |
| `14-financial-reporting.md` | RPT | 1 | ReportingController (income statement / balance sheet / cash-flow / account ledger) |
| `15-approvals.md` | APPR | 2 | ApprovalPolicy, ApprovalRequest |
| `16-documents.md` | DOC | 3 | Document, DocumentTemplate, DocumentBranding |
| `17-notifications.md` | NOTIF | 3 | Notification, NotificationPreference, NotificationAdmin |
| `18-costing-dimensions.md` | COST | 3 | Dimension, DimensionValue, DimensionReport |
| `19-fixed-assets.md` | FA | 3 | AssetCategory, FixedAsset, DepreciationRun |
| `20-crm.md` | CRM | 5 | Lead, Opportunity, Pipeline, PipelineStage, Activity |
| `21-hr-payroll.md` | HR | 8 | HrEmployee, HrDepartment, HrContract, HrPayComponent, HrPayroll, HrLeave, HrLoan, HrStatutory |
| `22-projects.md` | PROJ | 4 | Project, ProjectTask, ProjectTimesheet, ProjectCosting |
| `23-budgeting.md` | BUD | 3 | Budget, BudgetVersion, BudgetReport |
| `24-manufacturing.md` | MFG | 3 | WorkOrder, Bom, ManufacturingReport |
| `25-fx-multicurrency.md` | FX | 2 | Currency, FxRevaluationRun |
| `26-analytics.md` | BI | 1 | BiDashboard |
| **Total** | — | **≈115** | (HealthController is infrastructure, not a functional domain) |

> Counts are by primary ownership; a few controllers serve two domains (e.g. `Route` is referenced by both
> Sales and logistics) — the per-domain doc states any shared ownership. The authoritative endpoint/
> permission/enum facts live in each domain doc, read from the source files per the ACCURACY RULES.

---

## 9. Authoring Rules for the Per-Domain Docs (recap)

1. Start each doc with H1 title + 2–3 line scope + "Modules/submodules covered" (cite real controllers +
   base paths + frontend routes) + "Permission codes in scope" (exact `@PreAuthorize` codes).
2. Add a "Type/role variations exercised" table for that domain (drawn from §3).
3. Write cases in the exact `TC-<DOMAIN>-NNN` structure (Type, Priority, Module/Submodule, Permission/Role,
   Variation, Preconditions/Seed, Steps, Test Data, Expected Result, Convention Assertions, Negative/Edge).
4. **Coverage bar:** every endpoint/action; every status transition **and** its illegal transitions; each
   behaviour-changing enum value; each allowed + denied role; the four states; pagination + search/filter;
   create/edit/validation/required-field; and the C1–C9 checks.
5. **Accuracy:** cite only endpoints/codes/enum-values/routes you verified by reading the source. Never
   invent. Where a capability is backend-only or embedded in another screen, say so explicitly.

# RBAC / Authorization Matrix — Cross-Cutting Test Suite

Cross-cutting authorization tests for the ERP. Verifies that every action is gated by the
exact permission code on its controller `@PreAuthorize`, that the seeded roles + custom +
no-permission users see only what their codes allow, that company/branch scope is enforced,
and that `rootadmin` bypasses both permission and scope checks. This is a **special doc**: it
spans all modules but exercises only the *authorization* dimension (allowed vs denied vs
scoped), not each module's business logic.

## How RBAC actually works here (verified ground truth)

Read from `backend/src/main/java/com/erp/platform/security/` and the SQL seeders. These
facts drive every case below; cite them rather than assuming generic behaviour.

- **Gate mechanism.** Every protected endpoint carries `@PreAuthorize("@perm.has('<CODE>')")`
  or `@PreAuthorize("@perm.scoped(#uid,'<targetType>','<CODE>')")`. The `perm` bean is
  `com.erp.platform.security.PermissionChecks`. `has` checks the principal holds the code in
  their **active scope**; `scoped` additionally checks the target resource lives in the
  principal's active company (via `ScopeGuard.canActOn`).
- **Permissions are resolved per (user, company, branch) at request time** — `PermissionResolver`
  (ADR-0001 D-E). The JWT does **not** carry the permission set; switching the active branch via
  the `X-Branch-Uid` header re-resolves the effective set without re-login. A `user_role` grant
  with `branch_id = NULL` = all branches in the company; a grant with a specific `branch_id` =
  only that branch.
- **rootadmin** = `app_users.is_root = true`. `PermissionResolver.hasPermission` short-circuits
  to `true` for root (never hits the DB), and `ScopeGuard.canActOn`/`canActIn` short-circuit to
  `true` for root. So root passes **every** `@perm.has` and `@perm.scoped` and acts cross-company.
  When root acts **outside** its active company, `ScopeGuard.assertCanActIn` writes a
  `ROOT.BYPASS` audit row (`AuditActions.ROOT_BYPASS`, target_type `companies`).
- **403 / 401 envelope.** Security-filter denials are written by `SecurityErrorResponder` as the
  standard `ApiResponse` envelope (C2). 403 body message = exactly
  `"You do not have permission to perform this action."`; 401 (no/invalid token) =
  `"Authentication is required."` Messages are **generic by design** — they never name the
  missing permission (no enumeration). This is itself a test target.
- **Frontend nav gating.** `web/src/app/layout/shell/shell.component.ts` hides a nav item when
  the user lacks its `permission` code; `SessionStore.hasPermission` returns `true` for root or
  when the code is in the session's `permissions` (loaded from `/auth/me` / login `TokenResponse`).
- **Frontend route guard.** `web/src/app/core/auth/permission.guard.ts` `requirePermission(code)`:
  a user lacking the code is **redirected to `/admin/home`** (an `UrlTree`) — there is **no**
  dedicated `/admin/forbidden` screen. So "direct navigation to a forbidden route" = a redirect
  to home, NOT a 403 page. The 403 is observed only at the API layer. (This nuances C4: the
  "forbidden" state on the FE manifests as nav-hidden + redirect-to-home, not a forbidden panel.)

## Seeded roles & where their permissions come from (verified)

The migrations seed exactly **one** role with permissions: `ORG_ADMIN`
(`V1__baseline.sql` line 255). V1 grants it **every `module='iam'` permission**; each later
module migration adds an *additive* `INSERT … SELECT … WHERE r.code='ORG_ADMIN' AND p.module=…`
(or an explicit code list) — so `ORG_ADMIN` ends up holding effectively **all** permissions across
modules. The other operational roles (`SALES_MANAGER`, `SALES_REP`, `ACCOUNTANT`, `STOREKEEPER`,
`PURCHASE_OFFICER`) are **referenced** only in `V25__notifications.sql` (granted
`NOTIFICATION.VIEW` + `NOTIFICATION.PREFERENCE.MANAGE` *if they exist*); they are **not created by
migrations**. They are seed/demo roles defined per the data-model "Seed data" and created on the
QA deployment as **permission bundles an admin assembles** (via `POST /api/v1/roles` +
`PUT /api/v1/roles/uid/{uid}/permissions`). Therefore the role→permission rows below are the
**intended bundle per role** (what the QA seed must grant for these tests); the test runner must
either rely on the QA-seeded bundles or seed them via the Roles API as a precondition (see
TC-RBAC-001/002). RBAC is enforced **by permission code, never by role name** — no controller
checks a role.

> **Authoritative permission catalogue:** 185 codes across modules `iam, parties, products,
> sales, stock, purchases, routes, gl, ar, ap, cashbank, tax, reporting, approvals, documents,
> costing, fixedassets, hr, crm, notifications, projects, budgeting, manufacturing, bi, fx`.
> All codes cited below were extracted from `backend/src/main/resources/db/migration/*.sql`
> and cross-checked against each controller's `@PreAuthorize`.

## Modules / submodules covered (controllers · base paths · FE routes)

This suite samples representative endpoints from **all** controllers under
`backend/src/main/java/com/erp/api/`. Primary anchors (base path · key FE route):

- IAM: `CompanyController` `/api/v1/companies` · `/admin/companies`;
  `BranchController` `/api/v1/branches` · `/admin/companies/:companyUid/branches`;
  `UserController` `/api/v1/users` · `/admin/users`;
  `RoleController` `/api/v1/roles` · `/admin/roles`;
  `UserRoleController` `/api/v1/user-roles`; `UserBranchController` `/api/v1/user-branches`;
  `AuditController` `/api/v1/audit` · `/admin/audit`;
  `OrganisationController` `/api/v1/organisations`; `AuthController` `/api/v1/auth`.
- Parties: `CustomerController` `/api/v1/customers` · `/admin/customers`;
  `SupplierController` `/api/v1/suppliers` · `/admin/suppliers`;
  `AgentController`, `OtherPartyController`, `RouteController`.
- Products/Stock: `ProductController` `/api/v1/products` · `/admin/products`;
  `StockController` `/api/v1/stock` · `/admin/stock`; `StockTransferController`
  `/api/v1/stock-transfers` · `/admin/stock-transfers`.
- Sales: `SalesOrderController` `/api/v1/sales-orders` · `/admin/sales-orders`;
  `QuotationController`, `SalesInvoiceController`, `PosSessionController`/`PosSaleController`/
  `PosTillController` `/admin/pos/*`.
- Purchases: `PurchaseOrderController` `/api/v1/purchase-orders` · `/admin/purchase-orders`;
  `GoodsReceiptController`.
- Finance: `JournalController` `/api/v1/gl/journals`; `ArReceiptController` `/api/v1/ar/receipts`;
  `ApPaymentController` `/api/v1/ap/payments`; `YearEndCloseController`
  `/api/v1/gl/periods/fiscal-years`; `ReportingController` `/api/v1/reports`.
- HR: `HrEmployeeController` `/api/v1/hr/employees`; `HrPayrollController` `/api/v1/hr/payroll-runs`.
- CRM: `LeadController` `/api/v1/crm/leads`. Fixed Assets: `FixedAssetController`
  `/api/v1/fixed-assets`. Notifications: `NotificationController`.

## Permission codes in scope (exact `@PreAuthorize` codes — primary set)

`COMPANY.VIEW`, `COMPANY.MANAGE`, `BRANCH.VIEW`, `BRANCH.MANAGE`, `BRANCH.ASSIGN`,
`USER.VIEW`, `USER.MANAGE`, `ROLE.VIEW`, `ROLE.MANAGE`, `PERMISSION.VIEW`, `AUDIT.VIEW`,
`CUSTOMER.VIEW`, `CUSTOMER.MANAGE`, `PARTY.BRANCH.ASSIGN`, `SUPPLIER.VIEW`, `SUPPLIER.MANAGE`,
`AGENT.VIEW`, `ROUTE.VIEW`, `PRODUCT.VIEW`, `PRODUCT.MANAGE`, `STOCK.VIEW`, `STOCK.ADJUST`,
`STOCK.TRANSFER.VIEW`, `STOCK.TRANSFER.CREATE`, `STOCK.TRANSFER.RECEIVE`,
`SALES.ORDER.VIEW`, `SALES.ORDER.CREATE`, `SALES.ORDER.CONFIRM`, `SALES.ORDER.CANCEL`,
`SALES.INVOICE.VIEW`, `PURCHASE.ORDER.VIEW`, `PURCHASE.ORDER.CREATE`, `PURCHASE.ORDER.APPROVE`,
`PURCHASE.ORDER.VOID`, `PURCHASE.RECEIVE`, `GL.VIEW`, `GL.POST`, `GL.YEAR.CLOSE`,
`AR.VIEW`, `AR.RECEIPT.RECORD`, `AP.VIEW`, `AP.PAYMENT.RUN`, `REPORT.PL.VIEW`,
`HR.EMPLOYEE.VIEW`, `HR.EMPLOYEE.MANAGE`, `HR.PAYROLL.VIEW`, `HR.PAYROLL.RUN`,
`HR.PAYROLL.APPROVE`, `HR.PAYROLL.POST`, `CRM.LEAD.VIEW`, `CRM.LEAD.MANAGE`, `FA.VIEW`,
`FA.REGISTER.MANAGE`, `FA.DISPOSE`, `NOTIFICATION.VIEW`, `NOTIFICATION.ADMIN`.
Plus the **POS** codes the controllers actually require — `POS.SESSION.OPEN`,
`POS.SESSION.VIEW`, `POS.SESSION.CLOSE`, `POS.SESSION.RECONCILE`, `POS.SALE.CREATE`,
`POS.TILL.VIEW`, `POS.TILL.MANAGE` — which (per the defect below) are **not** the seeded
`SALES.POS.*` codes.

---

## Role → permission coverage table (intended seed bundle)

`✓` = role holds the code (and so its gated actions are **allowed**, subject to scope).
Blank = role lacks it (action **denied**: nav hidden + route→home + API 403). `ORG_ADMIN`
holds **all** codes (V1 + additive per-module grants). `rootadmin` is not a role — it bypasses
the table entirely. `NO-PERM` user holds **nothing**. `CUSTOM` = an admin-defined subset
(this suite uses a custom role granting exactly `{CUSTOMER.VIEW, PRODUCT.VIEW}` — see
TC-RBAC-020). The operational columns are the **intended** bundles the QA seed assigns; the
runner must confirm them via `/auth/me` before asserting (TC-RBAC-002).

| Permission code (action gated) | ORG_ADMIN | SALES_MANAGER | SALES_REP | ACCOUNTANT | STOREKEEPER | PURCHASE_OFFICER | NO-PERM |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| COMPANY.VIEW / COMPANY.MANAGE | ✓ | | | | | | |
| BRANCH.VIEW | ✓ | ✓ | | ✓ | ✓ | ✓ | |
| BRANCH.MANAGE / BRANCH.ASSIGN | ✓ | | | | | | |
| USER.VIEW / USER.MANAGE | ✓ | | | | | | |
| ROLE.VIEW / ROLE.MANAGE / PERMISSION.VIEW | ✓ | | | | | | |
| AUDIT.VIEW | ✓ | | | ✓ | | | |
| CUSTOMER.VIEW | ✓ | ✓ | ✓ | ✓ | | | |
| CUSTOMER.MANAGE / PARTY.BRANCH.ASSIGN | ✓ | ✓ | | | | | |
| SUPPLIER.VIEW | ✓ | | | ✓ | ✓ | ✓ | |
| SUPPLIER.MANAGE | ✓ | | | | | ✓ | |
| AGENT.VIEW / ROUTE.VIEW | ✓ | ✓ | ✓ | | | | |
| PRODUCT.VIEW | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| PRODUCT.MANAGE | ✓ | | | | ✓ | | |
| STOCK.VIEW | ✓ | ✓ | | ✓ | ✓ | ✓ | |
| STOCK.ADJUST / STOCK.OPENING | ✓ | | | | ✓ | | |
| STOCK.TRANSFER.VIEW | ✓ | | | | ✓ | | |
| STOCK.TRANSFER.CREATE / .RECEIVE | ✓ | | | | ✓ | | |
| SALES.ORDER.VIEW | ✓ | ✓ | ✓ | ✓ | | | |
| SALES.ORDER.CREATE | ✓ | ✓ | ✓ | | | | |
| SALES.ORDER.CONFIRM / .CANCEL | ✓ | ✓ | | | | | |
| SALES.INVOICE.VIEW | ✓ | ✓ | ✓ | ✓ | | | |
| SALES.CREDIT.OVERRIDE | ✓ | ✓ | | | | | |
| PURCHASE.ORDER.VIEW | ✓ | | | ✓ | ✓ | ✓ | |
| PURCHASE.ORDER.CREATE | ✓ | | | | | ✓ | |
| PURCHASE.ORDER.APPROVE / .VOID | ✓ | | | | | | |
| PURCHASE.RECEIVE | ✓ | | | | ✓ | | |
| GL.VIEW | ✓ | | | ✓ | | | |
| GL.POST / GL.PERIOD.CLOSE | ✓ | | | ✓ | | | |
| GL.YEAR.CLOSE | ✓ | | | | | | |
| AR.VIEW / AR.RECEIPT.RECORD | ✓ | | | ✓ | | | |
| AP.VIEW / AP.PAYMENT.RUN | ✓ | | | ✓ | | | |
| REPORT.* (PL/BS/CASHFLOW/LEDGER) | ✓ | | | ✓ | | | |
| HR.EMPLOYEE.VIEW / .MANAGE | ✓ | | | | | | |
| HR.PAYROLL.RUN / .APPROVE / .POST | ✓ | | | ✓¹ | | | |
| CRM.LEAD.VIEW / .MANAGE | ✓ | ✓ | ✓ | | | | |
| FA.VIEW | ✓ | | | ✓ | | | |
| FA.REGISTER.MANAGE / FA.DISPOSE | ✓ | | | ✓ | | | |
| NOTIFICATION.VIEW / .PREFERENCE.MANAGE | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| NOTIFICATION.ADMIN | ✓ | | | | | | |

¹ `ACCOUNTANT` holds payroll *posting/approval* in the intended bundle (it posts to GL); the
*authoritative* split between HR-admin and Accountant is a QA-seed decision — TC-RBAC-002
prints `/auth/me` so the test asserts against the **actually granted** set, not this table.
The matrix is the design target; any divergence the runner finds is itself a finding to log.

## Type/role variations exercised

| Dimension | Values exercised |
|---|---|
| User type | `rootadmin` (bypass); `ORG_ADMIN`; each operational role; a `CUSTOM` role (subset); a `NO-PERM` user |
| Permission shape | `@perm.has` (list/create, no target) vs `@perm.scoped` (path-uid / body-companyUid, adds company-scope) |
| Company context | single-company; two tenants A & B (cross-tenant denial, C7) |
| Branch context | default vs non-default; user assigned to ONE branch vs ALL (`branch_id NULL`); acting in an **unassigned** branch via `X-Branch-Uid` (denied) |
| Resource selection | always via `<app-uid-picker>` by NAME; uid only in URL path (C1) |
| Screen states | loading / empty / error / **forbidden-as-redirect-to-home** (C4 nuance) |
| Defect coverage | POS code mismatch (`POS.*` gated vs `SALES.POS.*` seeded) — TC-RBAC-040..042 |

---

# TEST CASES

## A. Setup / matrix-truth preconditions

### TC-RBAC-001 — Seed the role bundles and test users the matrix needs
- **Type:** Automated (Playwright) | Manual
- **Priority:** P1
- **Module / Submodule:** IAM Roles & Users (`/admin/roles`, `/admin/users` · `/api/v1/roles`, `/api/v1/users`, `/api/v1/user-roles`, `/api/v1/user-branches`)
- **Permission / Role:** `ROLE.MANAGE` + `USER.MANAGE` + `ROLE.VIEW` — runs as `rootadmin` (bootstrap, always allowed)
- **Preconditions / Seed:** A bootstrapped org with at least one company (default) and one non-default branch. If the QA deployment already seeds the operational roles, verify+reuse them instead of recreating.
- **Steps:**
  1. Log in as `rootadmin`.
  2. For each role in {ORG_ADMIN(exists), SALES_MANAGER, SALES_REP, ACCOUNTANT, STOREKEEPER, PURCHASE_OFFICER}, ensure it exists: `GET /api/v1/roles`; if missing, `POST /api/v1/roles {code,name}` then `PUT /api/v1/roles/uid/{uid}/permissions {permissionCodes:[…]}` with that role's bundle from the matrix.
  3. Create the **CUSTOM** role granting exactly `{CUSTOMER.VIEW, PRODUCT.VIEW}`.
  4. Create one test user per role + one **NO-PERM** user (no role grant). Set passwords via `PUT /api/v1/users/uid/{uid}/password`.
  5. Grant each user its role in the default company via `POST /api/v1/user-roles {userUid, roleUid, companyUid}` (branch_id NULL = all branches). Assign branches via `POST /api/v1/user-branches` as needed for branch-scoping cases.
- **Test Data:** usernames `qa.orgadmin`, `qa.salesmgr`, `qa.salesrep`, `qa.accountant`, `qa.storekeeper`, `qa.purchaser`, `qa.custom`, `qa.noperm`; password per QA convention.
- **Expected Result:** Roles created (201), permission sets applied, users created (201), grants created (201). `GET /api/v1/roles/uid/{uid}` shows the intended codes.
- **Convention Assertions:** C1 (roles/users chosen by name in pickers when granting; uid only in URL). C2 (envelope on all). C9 (no hard-delete; archive only).
- **Negative / Edge:** Creating a role with a duplicate `code` → 4xx (uq_role_code). Granting a role to a user in a company the actor can't act in → 403.

### TC-RBAC-002 — `/auth/me` returns the correct effective permission set per role
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Auth (`/api/v1/auth/me`)
- **Permission / Role:** `isAuthenticated()` — runs as each seeded user in turn
- **Preconditions / Seed:** TC-RBAC-001.
- **Steps:** For each test user: log in, call `GET /api/v1/auth/me`, capture `permissions[]` and `isRoot`.
- **Test Data:** all eight users.
- **Expected Result:** `ORG_ADMIN`'s set is a superset covering every module; each operational user's set equals its matrix bundle; `NO-PERM` set is empty; `rootadmin` has `isRoot=true` (and the UI treats root as holding everything regardless of the list).
- **Convention Assertions:** C2 envelope; C3 (the set drives nav + guards).
- **Negative / Edge:** If an operational user's `/auth/me` set diverges from the matrix, **log it as a finding** (the QA seed bundle is wrong) and continue — the per-action cases below assert against the *granted* set.

---

## B. Allowed vs denied per action (representative, per role)

> Pattern for every "denied" case: (1) the **nav item is hidden** in the shell sidebar;
> (2) **direct navigation** to the route **redirects to `/admin/home`** (no forbidden screen);
> (3) the **API returns 403** with body `"You do not have permission to perform this action."`
> in the `ApiResponse` envelope. Cite all three. For "allowed" cases assert the screen loads
> with its four states and the action succeeds.

### TC-RBAC-010 — ORG_ADMIN can administer IAM (companies/users/roles)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** IAM (`/admin/companies`, `/admin/users`, `/admin/roles`)
- **Permission / Role:** `COMPANY.VIEW`/`COMPANY.MANAGE`, `USER.*`, `ROLE.*` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** TC-RBAC-001.
- **Steps:** Log in as `qa.orgadmin`. Navigate `/admin/companies`, `/admin/users`, `/admin/roles`. Open create on each.
- **Expected Result:** All three nav items visible; lists load (C4/C5); create forms reachable; `POST` returns 201.
- **Convention Assertions:** C1 (no raw uid on screen; create uses pickers for company/branch). C3. C4 (loading→loaded). C5 (paginator on lists). C6 (axe-clean).
- **Negative / Edge:** none (this is the positive baseline).

### TC-RBAC-011 — SALES_MANAGER allowed sales, denied finance & IAM-admin
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sales (`/admin/sales-orders` · `/api/v1/sales-orders`) vs GL (`/admin/gl/journals`) vs Users (`/admin/users`)
- **Permission / Role:** allowed `SALES.ORDER.VIEW/CREATE/CONFIRM/CANCEL`, `CUSTOMER.MANAGE`; denied `GL.VIEW`, `USER.VIEW`
- **Preconditions / Seed:** TC-RBAC-001; ≥1 customer + product seeded.
- **Steps:** Log in as `qa.salesmgr`. (a) Navigate `/admin/sales-orders`; create an order (pick customer + product by NAME). (b) Confirm the order. (c) Navigate `/admin/gl/journals` directly. (d) Navigate `/admin/users` directly. (e) Call `GET /api/v1/gl/journals` and `GET /api/v1/users` directly.
- **Test Data:** order for customer "Acme Traders", product "Sugar 1kg", qty 5.
- **Expected Result:** (a)(b) succeed (201 / confirm transition). (c)(d) **redirect to `/admin/home`**; the GL and Users nav items are **absent** from the sidebar. (e) both API calls return **403** with the generic message.
- **Convention Assertions:** C1 (customer/product via picker; uid only in `/uid/:uid`). C3 (gate by code, not role name). C4 (denied = redirect, not panel). C7 (order scoped to active company).
- **Negative / Edge:** Attempting `POST /api/v1/gl/journals` → 403 even though the user can reach the sales screens.

### TC-RBAC-012 — SALES_REP can create but NOT confirm/cancel sales orders
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sales Orders (`/admin/sales-orders` · `/api/v1/sales-orders`)
- **Permission / Role:** holds `SALES.ORDER.CREATE`/`SALES.ORDER.VIEW`; lacks `SALES.ORDER.CONFIRM`/`SALES.ORDER.CANCEL` (verify against `SalesOrderController`: confirm gated `@perm.scoped(#uid,'salesorder','SALES.ORDER.CONFIRM')`, cancel `…CANCEL`)
- **Preconditions / Seed:** TC-RBAC-001; a DRAFT sales order created by the rep.
- **Steps:** Log in as `qa.salesrep`. Create an order (succeeds). On the order detail, look for **Confirm**/**Cancel** controls. Call `PUT /api/v1/sales-orders/uid/{uid}/confirm` directly.
- **Expected Result:** Create succeeds (201). Confirm/Cancel buttons **not rendered** (action-level gating). Direct confirm API → **403**.
- **Convention Assertions:** C1 (order referenced by uid in URL only). C3 (action-level perm, distinct from view). C9 (status change is a transition, not edit).
- **Negative / Edge:** Rep tries to cancel another rep's order → 403 (lacks CANCEL regardless of ownership).

### TC-RBAC-013 — ACCOUNTANT allowed GL/AR/AP/reports, denied sales-write & IAM
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** GL (`/admin/gl/journals`), AR (`/api/v1/ar/receipts`), AP (`/api/v1/ap/payments`), Reports vs Sales create / Users
- **Permission / Role:** allowed `GL.VIEW`,`GL.POST`,`AR.VIEW`,`AR.RECEIPT.RECORD`,`AP.VIEW`,`AP.PAYMENT.RUN`,`REPORT.PL.VIEW`,`AUDIT.VIEW`; denied `SALES.ORDER.CREATE`,`USER.VIEW`,`COMPANY.MANAGE`
- **Preconditions / Seed:** TC-RBAC-001; an open fiscal period; an AR invoice + AP bill to settle.
- **Steps:** Log in as `qa.accountant`. (a) `/admin/gl/journals` → post a balanced journal (pick accounts by NAME). (b) Record an AR receipt; run an AP payment. (c) Open a P&L report. (d) Navigate `/admin/sales-orders` create and `/admin/users` directly. (e) `POST /api/v1/sales-orders` directly.
- **Expected Result:** (a)(b)(c) succeed. (d) sales-create + users routes redirect to home / nav hidden. (e) 403.
- **Convention Assertions:** C1 (accounts/customers via picker; money formatted "CUR 1,234.56" C8; dates ISO C8). C3. C7 (postings company-scoped). C9 (journal is append-only; reversal not edit).
- **Negative / Edge:** Accountant attempts `GL.YEAR.CLOSE` (lacks it) → year-end action 403 (see TC-RBAC-016).

### TC-RBAC-014 — STOREKEEPER allowed stock & receiving, denied sales/finance
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Stock (`/admin/stock` · `/api/v1/stock`), Stock Transfers (`/api/v1/stock-transfers`), Goods Receipts vs GL/Sales
- **Permission / Role:** allowed `STOCK.VIEW`,`STOCK.ADJUST`,`STOCK.TRANSFER.VIEW/CREATE/RECEIVE`,`PRODUCT.MANAGE`,`PURCHASE.RECEIVE`; denied `GL.VIEW`,`SALES.ORDER.VIEW`,`AP.PAYMENT.RUN`
- **Preconditions / Seed:** TC-RBAC-001; ≥1 product (GOODS, stockable), ≥2 stock locations.
- **Steps:** Log in as `qa.storekeeper`. (a) `/admin/stock` → adjust on-hand (pick product by NAME). (b) `/admin/stock-transfers` → create a transfer (pick from/to location by NAME), then receive it. (c) Navigate `/admin/gl/journals` and `/admin/sales-orders` directly. (d) `GET /api/v1/gl/journals` directly.
- **Expected Result:** (a)(b) succeed. (c) redirect/hidden. (d) 403.
- **Convention Assertions:** C1 (product + locations via picker; uid only in URL; LocationType enum surfaced). C3. C4/C5 on stock list. C7 (stock scoped to active company+branch).
- **Negative / Edge:** Storekeeper tries `STOCK.ADJUST` on a SERVICE product → blocked (service not stockable, `chk_product_service_stockable`) — this is a business rule overlaying the perm.

### TC-RBAC-015 — PURCHASE_OFFICER allowed PO create, denied PO approve/void
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Purchasing (`/admin/purchase-orders` · `/api/v1/purchase-orders`)
- **Permission / Role:** holds `PURCHASE.ORDER.VIEW`,`PURCHASE.ORDER.CREATE`,`SUPPLIER.MANAGE`; lacks `PURCHASE.ORDER.APPROVE`,`PURCHASE.ORDER.VOID` (verify: approve `@perm.scoped(#uid,'purchaseorder','PURCHASE.ORDER.APPROVE')`, void `…VOID`)
- **Preconditions / Seed:** TC-RBAC-001; ≥1 supplier (GOODS).
- **Steps:** Log in as `qa.purchaser`. Create a PO (pick supplier by NAME). On the PO detail look for **Approve**/**Void**. Call `POST /api/v1/purchase-orders/uid/{uid}/approve` directly.
- **Expected Result:** Create succeeds (201). Approve/Void controls **not rendered**. Direct approve API → **403**.
- **Convention Assertions:** C1 (supplier via picker). C3 (action-level perm distinct from create). C9 (void is a state transition, not delete).
- **Negative / Edge:** Officer creates PO then tries to approve own PO → still 403 (segregation of duties is enforced by perm, not ownership).

### TC-RBAC-016 — GL.YEAR.CLOSE restricted to ORG_ADMIN (denied to ACCOUNTANT)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Year-End Close (`YearEndCloseController` · `/admin/...year-end`)
- **Permission / Role:** `GL.YEAR.CLOSE` — runs as `ORG_ADMIN` (allowed) and `ACCOUNTANT` (denied)
- **Preconditions / Seed:** TC-RBAC-001; a fiscal year eligible to close.
- **Steps:** As `ORG_ADMIN`: open year-end-close, run the close (`@perm.scoped(#uid,'fiscalyear','GL.YEAR.CLOSE')`). As `ACCOUNTANT`: attempt the same route + API.
- **Expected Result:** ORG_ADMIN succeeds; ACCOUNTANT → nav hidden / redirect + API 403.
- **Convention Assertions:** C3. C7 (fiscal year scoped to company). C9 (close is append-only posting).
- **Negative / Edge:** Closing an already-closed / illegal-state year → business 4xx (distinct from 403).

### TC-RBAC-017 — HR payroll: RUN vs APPROVE vs POST are separate gates
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** HR Payroll (`HrPayrollController` · `/api/v1/hr/payroll-runs`)
- **Permission / Role:** verify on controller — run `@perm.has('HR.PAYROLL.RUN')`; approve `@perm.scoped(#uid,'payrollrun','HR.PAYROLL.APPROVE')`; post `…HR.PAYROLL.POST`; disburse `…DISBURSE`; reverse `…REVERSE`; view `HR.PAYROLL.VIEW`
- **Permission / Role (run-as):** a user holding only `HR.PAYROLL.RUN`+`HR.PAYROLL.VIEW`; expect approve/post/disburse/reverse → 403
- **Preconditions / Seed:** TC-RBAC-001 + a custom HR-clerk role granting only RUN+VIEW; a payroll run created.
- **Steps:** Log in as the HR-clerk. Run a payroll (succeeds). Attempt approve, post, disburse, reverse via API on the run uid.
- **Expected Result:** RUN ok; each of APPROVE/POST/DISBURSE/REVERSE → 403.
- **Convention Assertions:** C3 (one action = one code). C7 (run scoped to company). C8 (money strings). C9 (reverse, not edit).
- **Negative / Edge:** posting a run that wasn't approved → business 4xx (state guard), separate from the 403 gate.

### TC-RBAC-018 — Fixed-asset DISPOSE is a distinct gate from REGISTER.MANAGE
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Fixed Assets (`FixedAssetController` · `/api/v1/fixed-assets`)
- **Permission / Role:** holds `FA.VIEW`,`FA.REGISTER.MANAGE`; lacks `FA.DISPOSE` (verify: dispose `@perm.scoped(#uid,'fixedasset','FA.DISPOSE')`)
- **Preconditions / Seed:** TC-RBAC-001 + a custom role FA.VIEW+FA.REGISTER.MANAGE; an active asset.
- **Steps:** Edit an asset (succeeds). Attempt dispose via API.
- **Expected Result:** Edit ok; dispose → 403; Dispose control not rendered.
- **Convention Assertions:** C3. C9 (dispose is a posting/transition).
- **Negative / Edge:** dispose on already-disposed asset → business 4xx.

### TC-RBAC-019 — NOTIFICATION.ADMIN restricted to ORG_ADMIN; all roles get NOTIFICATION.VIEW
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Notifications (`NotificationController`/`NotificationAdminController`)
- **Permission / Role:** `NOTIFICATION.VIEW` (all operational roles per V25), `NOTIFICATION.PREFERENCE.MANAGE` (all), `NOTIFICATION.ADMIN` (ORG_ADMIN only)
- **Preconditions / Seed:** TC-RBAC-001.
- **Steps:** As any operational user: view notifications + manage own preferences (succeed). Attempt the admin type-catalogue endpoint. As ORG_ADMIN: open the admin catalogue (succeeds).
- **Expected Result:** operational users: view/prefs ok, admin → 403; ORG_ADMIN: all ok.
- **Convention Assertions:** C3. C7 (notifications company-scoped).
- **Negative / Edge:** NO-PERM user → even NOTIFICATION.VIEW 403 (no role granted at all).

### TC-RBAC-020 — CUSTOM role sees only its two screens; everything else hidden/403
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers`), Products (`/admin/products`) vs all others
- **Permission / Role:** CUSTOM role = exactly `{CUSTOMER.VIEW, PRODUCT.VIEW}`
- **Preconditions / Seed:** TC-RBAC-001 (custom role + user).
- **Steps:** Log in as `qa.custom`. Enumerate the sidebar; navigate `/admin/customers` and `/admin/products` (load). Navigate `/admin/suppliers`, `/admin/sales-orders`, `/admin/gl/journals` directly. Attempt `POST /api/v1/customers` (lacks MANAGE) and `GET /api/v1/suppliers`.
- **Expected Result:** Sidebar shows **only** Customers + Products (plus always-on items without a `permission` key, e.g. Notifications if granted — here none). The two screens load **read-only** (no Create button: lacks `CUSTOMER.MANAGE`/`PRODUCT.MANAGE`). Other routes redirect to home. `POST /api/v1/customers` → 403; `GET /api/v1/suppliers` → 403.
- **Convention Assertions:** C1 (uid never shown). C3 (view-only because MANAGE absent → create controls suppressed). C4 (forbidden = redirect). C5 (paginator on the two lists). C6 (axe).
- **Negative / Edge:** Granting the custom role `CUSTOMER.MANAGE` mid-session does **not** take effect until cache TTL/`/auth/me` refresh — note the 30s `PermissionResolver` TTL backstop and that role-permission edits call `invalidate()` (so the flip is near-immediate after re-login or next request).

### TC-RBAC-021 — NO-PERM user: empty nav, every screen redirects, every API 403
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** all
- **Permission / Role:** none (user has no role grant)
- **Preconditions / Seed:** TC-RBAC-001 (`qa.noperm`).
- **Steps:** Log in as `qa.noperm`. Inspect the sidebar. Navigate `/admin/customers`, `/admin/users`, `/admin/gl/journals` directly. Call `GET /api/v1/customers`, `GET /api/v1/users`.
- **Expected Result:** Sidebar shows **no** permissioned items (only any unconditional items + `/admin/home`). Every protected route redirects to `/admin/home`. Every protected API → **403** with the generic message. `/auth/me` returns `permissions: []`. `/auth/login` itself succeeds (auth ≠ authorization) and `/admin/home` renders.
- **Convention Assertions:** C3 (no code = nothing). C4 (denied state = redirect; home is the safe landing). C2 (403 envelope).
- **Negative / Edge:** `GET /api/v1/auth/me` and `/auth/my-branches` still succeed (gated `isAuthenticated()`), proving the boundary is *authorization*, not *authentication*.

---

## C. Scoped vs unscoped gates (`@perm.has` vs `@perm.scoped`)

### TC-RBAC-030 — Cross-tenant denial: ORG_ADMIN of Tenant A cannot touch Tenant B
- **Type:** Automated (Playwright) | Manual
- **Priority:** P1
- **Module / Submodule:** Customers (`@perm.scoped(#uid,'customer','CUSTOMER.MANAGE')`), Sales Orders, Branches
- **Permission / Role:** `CUSTOMER.MANAGE` etc. held in Tenant A; target lives in Tenant B (C7)
- **Preconditions / Seed:** Two tenants A & B, each with a company + a customer. A's `qa.orgadmin` is granted ORG_ADMIN in **company A only**.
- **Steps:** Log in as A's admin (active company = A). Obtain B's customer uid out of band. Call `PUT /api/v1/customers/uid/{B_customer_uid}` and `GET /api/v1/customers/uid/{B_customer_uid}`.
- **Test Data:** B customer "Bravo Ltd".
- **Expected Result:** Both → **403** (the user holds `CUSTOMER.MANAGE` but `ScopeGuard.canActOn` resolves the target's company = B ≠ active A → deny). In the UI, B's customer never appears in A's list (list is company-filtered), so it can't even be picked.
- **Convention Assertions:** C7 (tenant isolation). C1 (B's customer not pickable in A). C3 (has-perm but scope-denied is still 403). C2 envelope.
- **Negative / Edge:** A's admin switches no company → cannot reach B at all. Note: an **unresolvable** target uid also denies (`companyIdOf` empty → `canActOn` false → 403) — not a 404 leak.

### TC-RBAC-031 — `@perm.scoped` on body companyUid: create denied when companyUid ≠ active company
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sales Orders (`@perm.scoped(#request.companyUid(),'company','SALES.ORDER.CREATE')`), Journals (`@perm.scoped(#req.companyUid,'company','GL.POST')`), AR receipts (`@perm.scoped(#req.companyUid,'company','AR.RECEIPT.RECORD')`)
- **Permission / Role:** holds the create/post perm in company A; request body names company B
- **Preconditions / Seed:** Two companies A & B in the same org; a user with the perm scoped to A.
- **Steps:** Log in (active company A). `POST /api/v1/sales-orders` with `companyUid = B`. Repeat for `POST /api/v1/gl/journals` with `companyUid = B`.
- **Expected Result:** Both → **403** (body-scoped guard: holds perm but B ≠ active A). UI: the company is implied by active scope / picker only offers A, so the FE never sends B.
- **Convention Assertions:** C7. C1 (company via active scope/picker, not typed). C3.
- **Negative / Edge:** Same call with `companyUid = A` → succeeds (201), proving the gate is scope, not the perm.

### TC-RBAC-032 — Branch-scoped grant: user assigned to ONE branch is denied in another
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Branch scope via `X-Branch-Uid` (JwtRequestContextFilter) + any branch-scoped read (e.g. Stock `/api/v1/stock`)
- **Permission / Role:** `STOCK.VIEW` granted in company A but the `user_role` row is bound to **branch 1** only (branch_id = branch1)
- **Preconditions / Seed:** Company A with default branch1 + non-default branch2; storekeeper granted `STOCK.VIEW`/`STOCK.ADJUST` scoped to **branch1**; on-hand rows in both branches.
- **Steps:** Log in (default branch1). View stock (sees branch1 data). Switch active branch to **branch2** (branch selector → sets `X-Branch-Uid` = branch2) and re-list stock. Also send a raw request with `X-Branch-Uid: branch2`.
- **Expected Result:** In branch1: stock loads. After switching to branch2: the permission set re-resolves to **empty** for branch2 (no grant there) → `STOCK.VIEW` no longer held → list → **403** / nav reflects loss; the override to an **unassigned** branch is rejected. (`PermissionResolver.resolve` keys on (user,company,branch); a branch with no grant yields no codes.)
- **Convention Assertions:** C7 (branch scoping). C3 (effective set is per-branch). C1 (branch chosen by NAME in the selector, uid under the hood).
- **Negative / Edge:** A bogus/foreign `X-Branch-Uid` (branch of another company) → the filter **rejects the override** (scope build fails) → 401/403; never silently falls back to the JWT default.

### TC-RBAC-033 — All-branches grant (branch_id NULL) works across branches
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Branch scope (`X-Branch-Uid`) + Stock
- **Permission / Role:** `STOCK.VIEW` granted with `branch_id = NULL` (all branches in company A)
- **Preconditions / Seed:** As TC-RBAC-032 but the grant is company-wide (NULL branch).
- **Steps:** Log in; view stock in branch1; switch to branch2; view stock again.
- **Expected Result:** Stock loads in **both** branches (NULL branch_id = effective in every branch of the company). Data differs per branch (C7) but the permission holds in both.
- **Convention Assertions:** C7 (data still branch-filtered even when perm is company-wide). C5 (paginator per branch dataset).
- **Negative / Edge:** Switching to a branch of a **different** company → denied (cross-tenant), even with a NULL-branch grant in company A.

### TC-RBAC-034 — Acting in a branch the user is NOT assigned to is denied
- **Type:** Automated (Playwright) | Manual
- **Priority:** P1
- **Module / Submodule:** Branch selector / `X-Branch-Uid` (JwtRequestContextFilter line 53 `BRANCH_OVERRIDE_HEADER`)
- **Permission / Role:** any; the point is branch membership, not the action perm
- **Preconditions / Seed:** User assigned (UserBranch) to branch1 only; branch2 exists in same company.
- **Steps:** Log in. Open the branch selector (driven by `GET /api/v1/auth/my-branches`). Confirm branch2 is **not** offered. Then forge a request with `X-Branch-Uid: branch2`.
- **Expected Result:** Selector lists **only** branch1 (the user's switchable branches). The forged override to branch2 is **rejected** by the filter (no effective scope there) → 403; the UI cannot reach branch2 at all.
- **Convention Assertions:** C7. C1 (branches by name). C3.
- **Negative / Edge:** Removing the user's branch1 assignment mid-session → next request can't resolve scope → effective set empty → 403 across the app until re-assigned.

---

## D. rootadmin bypass

### TC-RBAC-035 — rootadmin passes every `@perm.has` gate with no grants
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** all (sample: companies, users, GL, sales, stock, HR)
- **Permission / Role:** none required — `is_root` short-circuits `PermissionResolver.hasPermission`
- **Preconditions / Seed:** the bootstrap `rootadmin`.
- **Steps:** Log in as `rootadmin`. Enumerate the sidebar; navigate a sample of screens across modules; perform one allowed action each (create company, create user, post a journal).
- **Expected Result:** **Every** nav item is visible (`SessionStore.hasPermission` returns true for root). Every screen loads; every action succeeds — even codes never granted to any role.
- **Convention Assertions:** C3 (root bypass). C1/C2 still apply (uid hidden, envelope).
- **Negative / Edge:** Despite bypass, business-rule validations still apply (e.g. unbalanced journal rejected) — bypass is *authorization*, not data validation.

### TC-RBAC-036 — rootadmin bypasses tenant scope and writes a ROOT.BYPASS audit row
- **Type:** Automated (Playwright) | Manual
- **Priority:** P1
- **Module / Submodule:** cross-tenant action + Audit (`AUDIT.VIEW` · `/admin/audit`)
- **Permission / Role:** root bypass of `ScopeGuard`; `ROOT.BYPASS` audited via `assertCanActIn`
- **Preconditions / Seed:** Two tenants A & B; root's active company = A.
- **Steps:** Log in as `rootadmin` (active company A). Act on a Tenant B resource (e.g. view/edit B's customer, or run a B-scoped report). Then open `/admin/audit` and filter the trail.
- **Expected Result:** The cross-company action **succeeds** (root bypass). A `ROOT.BYPASS` audit row exists (action `ROOT.BYPASS`, target_type `companies`, detail with `activeCompanyId=A`, `targetCompanyId=B`). Root acting **within** A does **not** produce a bypass row (only the action's own audit row).
- **Convention Assertions:** C7 (root is the documented exception). C9 (audit append-only). C2 envelope on audit list.
- **Negative / Edge:** Root cross-company **read** in a read-only transaction still records the bypass (uses `recordIndependent`/REQUIRES_NEW — must not 500; ISSUES-REGISTER #11).

### TC-RBAC-037 — rootadmin must NOT be used for negative-auth assertions
- **Type:** Manual (guardrail / review checklist)
- **Priority:** P2
- **Module / Submodule:** test-suite hygiene
- **Permission / Role:** n/a
- **Steps:** Review the suite: confirm no "expect 403/denied" case is executed as `rootadmin` (root never gets 403 from the perm/scope layer). Negative-auth cases run as operational/custom/no-perm users only.
- **Expected Result:** All negative cases use non-root users; root is used only for setup and positive bypass cases.
- **Convention Assertions:** C3 (correct interpretation of bypass).
- **Negative / Edge:** A 403 observed while logged in as root would indicate a non-auth failure (e.g. 404/500 misreported) — investigate, do not record as an auth denial.

---

## E. Security-contract & defect cases

### TC-RBAC-038 — 401 vs 403 contract and non-enumeration of the missing permission
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** SecurityErrorResponder (all protected endpoints)
- **Permission / Role:** none / insufficient
- **Steps:** (a) Call a protected endpoint with **no** bearer token. (b) Call it with a valid token but **insufficient** permission (use `qa.noperm`).
- **Expected Result:** (a) **401**, body `ApiResponse.error("Authentication is required.")`. (b) **403**, body `ApiResponse.error("You do not have permission to perform this action.")`. Neither message names the required permission code, the resource, or whether it exists (no enumeration).
- **Convention Assertions:** C2 (envelope on filter-level errors). C3.
- **Negative / Edge:** Expired token → 401 (not 403). Valid token + correct perm but cross-tenant target → 403 (not 404 — avoids existence leak).

### TC-RBAC-039 — Nav hidden ⇒ route guarded ⇒ API gated are consistent for the SAME code
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** consistency across shell nav ↔ `admin.routes.ts` ↔ controller
- **Permission / Role:** parameterised over a sample: `COMPANY.VIEW`(companies), `STOCK.VIEW`(stock), `SALES.ORDER.VIEW`(sales-orders), `GL.VIEW`(gl/journals), `PURCHASE.ORDER.VIEW`(purchase-orders)
- **Preconditions / Seed:** a user lacking the code under test.
- **Steps:** For each code: confirm (1) the shell nav item with that `permission` is **absent**; (2) `requirePermission(code)` on the route → **redirect to /admin/home**; (3) the controller endpoint → **403**.
- **Expected Result:** All three agree per code (the FE `permission` key == route guard code == controller `@PreAuthorize` code).
- **Convention Assertions:** C3 (single source of truth = the code). C4 (FE forbidden = redirect).
- **Negative / Edge:** **Mismatch finding** — flag any nav/route/controller triple where the codes differ (the POS defect TC-RBAC-040 is the known instance).

### TC-RBAC-040 — DEFECT: POS session gates use `POS.SESSION.*` but only `SALES.POS.*` is seeded
- **Type:** Automated (Playwright) | Manual
- **Priority:** P1
- **Module / Submodule:** POS Sessions (`PosSessionController` · `/admin/pos/sessions`)
- **Permission / Role:** controller requires `POS.SESSION.OPEN` / `POS.SESSION.VIEW` / `POS.SESSION.CLOSE` / `POS.SESSION.RECONCILE`; the seeded catalogue (V43) defines `SALES.POS.SESSION.OPEN`/`.CLOSE`/`.RECONCILE`/`SALES.POS.VIEW` and grants those to ORG_ADMIN. The `POS.SESSION.*` codes **do not exist** in `permissions` and are granted to **no role**.
- **Preconditions / Seed:** TC-RBAC-001; even an ORG_ADMIN user (granted `SALES.POS.*`).
- **Steps:** Log in as `qa.orgadmin`. Navigate `/admin/pos/sessions`. Attempt to **open** a POS session (UI button gated by FE `POS.SESSION.VIEW`/`POS.SESSION.OPEN`). Call `POST /api/v1/pos/sessions` (open) directly. Repeat as a SALES_MANAGER granted `SALES.POS.*`.
- **Expected Result (current/expected-failure):** **No non-root user can open/view/close/reconcile a POS session** — even ORG_ADMIN gets **403**, because the held `SALES.POS.*` codes don't match the required `POS.SESSION.*`. The POS nav items (gated on FE by `POS.SESSION.VIEW`/`POS.TILL.VIEW`/`POS.SALE.CREATE`) are **hidden** for everyone except root. Only `rootadmin` (bypass) can use POS. **This is a defect** — record it.
- **Convention Assertions:** C3 (the gate code must match a seeded, grantable code). C7.
- **Negative / Edge:** As `rootadmin`, POS open/close/reconcile **succeed** (bypass), masking the defect — so the test must assert the **non-root** path. Expected fix: align controller codes to `SALES.POS.SESSION.*` and FE to `SALES.POS.*` (or seed the `POS.*` codes + grant them).

### TC-RBAC-041 — DEFECT: POS sale gate `POS.SALE.CREATE` is unseeded/ungranted
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** POS Sale (`PosSaleController` `@perm.has('POS.SALE.CREATE')` · `/admin/pos/sell`)
- **Permission / Role:** required `POS.SALE.CREATE`; seeded equivalent is `SALES.POS.SELL`
- **Preconditions / Seed:** TC-RBAC-001; an ORG_ADMIN and a SALES_MANAGER (granted `SALES.POS.SELL`).
- **Steps:** As each non-root user, navigate `/admin/pos/sell` and attempt to ring a sale; call `POST /api/v1/pos/sales` directly.
- **Expected Result (expected-failure):** Non-root users **cannot** ring a POS sale (403); nav item hidden. Only root succeeds. **Defect** — `POS.SALE.CREATE` is not in the permission catalogue (V43 seeds `SALES.POS.SELL`).
- **Convention Assertions:** C3. C7.
- **Negative / Edge:** root path succeeds (bypass) — assert the non-root denial is the failure.

### TC-RBAC-042 — DEFECT: POS till gates `POS.TILL.MANAGE` / `POS.TILL.VIEW` are unseeded/ungranted
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** POS Tills (`PosTillController` · `/admin/pos/tills`)
- **Permission / Role:** required `POS.TILL.MANAGE` (create/manage) + `POS.TILL.VIEW` (list/get); seeded equivalent is `SALES.POS.TILL.MANAGE` (+ list via `SALES.POS.VIEW`)
- **Preconditions / Seed:** TC-RBAC-001; ORG_ADMIN (granted `SALES.POS.TILL.MANAGE`,`SALES.POS.VIEW`).
- **Steps:** As `qa.orgadmin`, navigate `/admin/pos/tills`; attempt to create a till; call `GET /api/v1/pos/tills` and `POST /api/v1/pos/tills`.
- **Expected Result (expected-failure):** Non-root users get **403** on list/create (held `SALES.POS.*` ≠ required `POS.TILL.*`); nav hidden. Only root works. **Defect** — record alongside TC-RBAC-040/041; same root cause (POS module gate codes diverge from the seeded `SALES.POS.*` namespace).
- **Convention Assertions:** C3. C7.
- **Negative / Edge:** root succeeds (bypass).

### TC-RBAC-043 — Effective-permission cache flips promptly on grant/revoke
- **Type:** Automated (Playwright) | Manual
- **Priority:** P2
- **Module / Submodule:** PermissionResolver cache (`invalidate()` on access-changing writes) + UserRole grant/revoke
- **Permission / Role:** `ROLE.MANAGE` (actor) granting/revoking a target user's role
- **Preconditions / Seed:** TC-RBAC-001; target user currently holding a role with `CUSTOMER.VIEW`.
- **Steps:** As ORG_ADMIN, revoke the target's role (`DELETE /api/v1/user-roles/uid/{uid}`). Immediately, as the target (existing session/new request), call `GET /api/v1/customers`. Then re-grant and retry.
- **Expected Result:** After revoke, the target's `GET /api/v1/customers` → **403** promptly (`PermissionResolver.invalidate()` clears the cache on grant/revoke; the 30s TTL is only a backstop). After re-grant → 200. `/auth/me` reflects the change.
- **Convention Assertions:** C3 (live re-resolution; no JWT-embedded perms). C7.
- **Negative / Edge:** If a stale allow persists beyond ~30s after a revoke, that's a finding (missed `invalidate()`); if a brief stale allow within the TTL window appears for an *edge race*, note it against the documented TTL backstop.

### TC-RBAC-044 — Branch switch re-resolves perms without re-login (X-Branch-Uid)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Branch selector → `X-Branch-Uid` → PermissionResolver
- **Permission / Role:** a user with different effective grants per branch (e.g. STOCK.VIEW in branch1 only)
- **Preconditions / Seed:** user with a branch1-scoped STOCK.VIEW grant and no grant in branch2.
- **Steps:** Log in (branch1). `GET /api/v1/stock` → 200. Switch active branch to branch2 (FE sets `X-Branch-Uid`); `GET /api/v1/stock` → expect change. Switch back to branch1.
- **Expected Result:** branch1: 200; branch2: 403 (no grant there); branch1 again: 200 — all **without** re-login, proving per-(user,company,branch) resolution.
- **Convention Assertions:** C3. C7. C1 (branch by name in selector).
- **Negative / Edge:** No `X-Branch-Uid` header → uses the JWT default branch's effective set.

---

## Coverage notes & gaps (accuracy)

- **Operational role bundles are design targets, not migration-enforced.** Only `ORG_ADMIN` is
  migration-seeded with permissions; `SALES_MANAGER/SALES_REP/ACCOUNTANT/STOREKEEPER/PURCHASE_OFFICER`
  are referenced only by `V25__notifications.sql` and otherwise must be seeded by the QA/demo
  process or via the Roles API (TC-RBAC-001). The role→permission matrix is therefore the
  **intended** assignment; TC-RBAC-002 asserts against the **actually granted** set and any
  divergence is a finding.
- **FE "forbidden" is a redirect, not a panel.** `permission.guard.ts` redirects to `/admin/home`;
  there is no `/admin/forbidden` route. C4's "forbidden" state is realised as nav-hidden +
  redirect on the FE and 403 at the API. Cases assert all three.
- **POS permission-code mismatch (TC-RBAC-040..042)** is a verified latent RBAC defect: the
  `PosSession/PosSale/PosTill` controllers gate on `POS.*` codes that are **not** in the seeded
  permission catalogue (V43 seeds the `SALES.POS.*` namespace and grants those to ORG_ADMIN).
  Net effect: only `rootadmin` (bypass) can operate POS; every non-root user — including
  ORG_ADMIN — is 403. Tests are written as **expected-failure**, asserting the **non-root** path.
- **Per-action gating verified** for the sampled controllers (Sales Order confirm/cancel,
  Purchase Order approve/void, HR payroll run/approve/post/disburse/reverse, FA dispose vs manage,
  Year-End close) — each distinct action carries its own code; these are exercised as separate
  allow/deny cases rather than one coarse "module access" case.
- **`@perm.has` vs `@perm.scoped`** distinction drives Section C: unscoped gates (list/create
  where there's no target) only check the code; scoped gates additionally enforce
  company-of-target == active-company (path-uid) or companyUid-in-body == active-company.
  Cross-tenant/branch denial (C7) is tested against both forms.

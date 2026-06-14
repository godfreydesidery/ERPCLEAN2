# ERPCLEAN2 — System Test Cases

Comprehensive test-case suite covering the **whole ERP** — every module/submodule, every
user/branch/customer/supplier/agent/product/location/tender/contract type, and the system
conventions. Authored from the real codebase (controllers, routes, DTOs, enums, migrations) and
adversarially verified for accuracy — every cited endpoint, permission code, enum value and route was
grep-confirmed against the code.

- **Primary purpose:** drive **automated Playwright e2e** (cases are written UI-first: navigate by route,
  pick resources by name via the picker — never by uid, assert four-state + pagination + axe + RBAC).
- **Secondary:** the same cases serve as **manual** test scripts.
- **Next step:** run these to identify issues. Authoring already surfaced several real defects (see below).

Start with **[00 — Test Strategy & Environment](00-test-strategy-and-environment.md)** — it holds the
environment matrix (all user/entity types), the ID scheme, the Playwright consumption model, and the
convention charter (C1–C9).

## Conventions under test (C1–C9)
- **C1 Identity:** a uid appears ONLY in the URL path; never shown in the UI, never hand-typed; resources
  are chosen via `<app-uid-picker>` by name; a numeric id is never in a URL.
- **C2** ApiResponse envelope · **C3** RBAC by permission code · **C4** four-state screens
  (loading/empty/error/forbidden) · **C5** pagination (first/prev/numbers/next/last) ·
  **C6** WCAG 2.1 AA / axe · **C7** multi-company + multi-branch isolation · **C8** money/date formatting ·
  **C9** soft-delete/archive + append-only postings.

## Environment matrix (summary — full detail in doc 00)
- **User types:** `rootadmin` (superuser, bypasses RBAC) · seeded roles `ORG_ADMIN`, `SALES_MANAGER`,
  `SALES_REP`, `ACCOUNTANT`, `STOREKEEPER`, `PURCHASE_OFFICER` · custom role · no-permission user.
- **Branch/company:** default vs non-default branch; single- vs multi-branch; user assigned to one/many/all
  branches; multi-company tenant isolation.
- **Customer:** PartyType {INDIVIDUAL, BUSINESS} × CustomerKind {CASH_WALK_IN, CREDIT_ACCOUNT} ·
  **Supplier** {GOODS, SERVICE} · **Agent** {INTERNAL, EXTERNAL} · **Product** {GOODS, SERVICE} ·
  **Location** {WAREHOUSE, STORE, VAN, QUARANTINE, OTHER} · **Tender** {CASH, MOBILE_MONEY} ·
  **Contract** {PERMANENT, FIXED_TERM, CASUAL, PROBATION} · plus each module's status lifecycle.

## Catalogue (1,150 test cases across 25 documents)

| # | Document | Cases | Scope |
|---|---|---:|---|
| 00 | [Test Strategy & Environment](00-test-strategy-and-environment.md) | 10 | Strategy, environment matrix, ID scheme, convention charter |
| 01 | [IAM & Access](01-iam-access.md) | 57 | Auth, users, roles, user-roles, user-branch, org/company/branch |
| 02 | [RBAC Authorization Matrix](02-rbac-authorization-matrix.md) | 29 | Role × allowed/denied; negative-auth; cross-tenant/branch denial |
| 03 | [Party Masters](03-masters-parties.md) | 50 | Customers, suppliers, other-parties, agents (all kinds) |
| 04 | [Catalog Masters](04-masters-catalog.md) | 56 | Products, UoM, price-lists, currencies, tax-rates, routes |
| 05 | [Sales / Order-to-Cash](05-sales-order-to-cash.md) | 39 | Quotation→SO→delivery→invoice→return |
| 06 | [Sales Advanced & Pricing](06-sales-advanced-pricing.md) | 56 | Blanket orders, standing orders, pricing rules |
| 07 | [Point of Sale](07-pos.md) | 35 | Till, session (open/close/reconcile), checkout |
| 08 | [Procurement / P2P](08-procurement-p2p.md) | 55 | Requisition→RFQ→quote→PO→GRN→landed-cost→bill→return |
| 09 | [Inventory](09-inventory.md) | 56 | On-hand, transfer, count, locations, batches, serials, valuation |
| 10 | [General Ledger](10-finance-gl.md) | 80 | Journals, CoA, periods, trial balance, year-end, dimensions |
| 11 | [Accounts Receivable](11-accounts-receivable.md) | 62 | Invoices, receipts, credit notes, write-offs, statements |
| 12 | [Accounts Payable](12-accounts-payable.md) | 36 | Payments, debit notes, openings, statements |
| 13 | [Cash & Bank](13-cash-bank.md) | 50 | Accounts, transfers, entries, reconciliation, cheques |
| 14 | [Tax (VAT + WHT)](14-tax-vat-wht.md) | 53 | VAT return, adjustments, WHT register/types |
| 15 | [FX / Multi-currency](15-fx-multicurrency.md) | 40 | Rates, foreign posting, revaluation |
| 16 | [Reporting & BI](16-reporting-bi.md) | 45 | P&L, BS, cash-flow, ledger, BI dashboard |
| 17 | [Fixed Assets](17-fixed-assets.md) | 41 | Categories, register, depreciation, disposal |
| 18 | [HR & Payroll](18-hr-payroll.md) | 54 | Employees, departments, contracts, leave, loans, payroll, statutory |
| 19 | [CRM](19-crm.md) | 40 | Leads, opportunities, pipeline, stages, activities |
| 20 | [Projects](20-projects.md) | 35 | Projects, tasks, timesheets, costing, issue-to-project |
| 21 | [Manufacturing](21-manufacturing.md) | 50 | BOM, work orders, WIP report |
| 22 | [Budgeting](22-budgeting.md) | 37 | Budgets, versions, variance/actuals reports |
| 23 | [Platform Services](23-platform-services.md) | 46 | Documents, notifications, approvals, audit |
| 24 | [Conventions (cross-cutting)](24-conventions-cross-cutting.md) | 38 | C1–C9 as first-class cases |

## Defects already surfaced during authoring (verify these when running the suite)
Authoring read the real code and flagged concrete issues — to confirm/triage when the suite runs:
- **DEFECT-POS-PERM (P1)** — controllers/FE check `POS.*` permission codes but migration V43 seeds
  `SALES.POS.*`; `PermissionResolver` does exact match (no prefix normalisation) → all POS endpoints/nav
  are denied for every non-root user. (`07-pos.md`, `PermissionResolver.java:78`.)
- **DEFECT-POS-TENDER (P2)** — `PosSaleServiceImpl.processSale` hardcodes `TenderType.CASH`; MOBILE_MONEY
  unreachable via POS.
- **DEFECT-POS-AGENT (P2)** — `PosSaleRequest.agentId` is `@NotNull` but the checkout UI treats agent as
  optional → 400 when omitted.
- **CONVENTION-POS-UID (P3, C1)** — POS sessions list shows raw session uid; tills list shows raw branchId.
- Plus the **7 e2e-found defects** (see `docs/testing/` notes): pos.till/stock-count create 500, crm.activity
  NOTE/TASK 500, supplier-quote 400, purchase-return confirm 500, stock-transfer in-transit 409, ar/ageing
  on-load param. Many docs also embed expected-failure (negative) cases — those are intended, not defects.

> Accuracy note: each document was authored from the code, then a second agent grep-verified every endpoint,
> permission, enum and route and corrected any inaccuracy in place. All 25 passed the accuracy gate.

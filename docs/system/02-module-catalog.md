# Module Catalog

A reference to every module in ERPCLEAN2: its purpose, key entities, the controllers and base paths it exposes, its permission family, and the governing ADR. The system has ~116 REST controllers grouped into the modules below. All paths are under `/api/v1`. All controllers are flat in `com.erp.api`; domain logic lives in `com.erp.modules.<name>` (business modules) or `com.erp.platform.*` (the spine). Permission codes are dot-separated and gate endpoints via `@PreAuthorize`.

The catalog is organised by area. Within each area, the controller list is the verified set from the shipped code.

## Platform spine

These are not business modules — they are the shared infrastructure every module rests on.

| Concern | Package | Role |
|---|---|---|
| Security / RBAC | `platform.security` | JWT (RS256) issue/verify, `RequestContext`, `@perm` permission resolver, `ScopeGuard` (uid → scope) |
| IAM | `platform.iam` | App users, roles, permissions, assignments |
| Company / tenancy | `platform.company` | Organisation → Company → Branch |
| Document numbering | `platform.sequence` | `code_sequence` row-locked allocation (`JB-####`, invoice numbers, …) |
| Outbox / events | `platform.events` | `domain_events` + `processed_events` + `@Scheduled` dispatcher (ADR-0009) |
| Audit | `platform.audit` | Audit aspect → append-only `audit_log` (ADR-0004) |
| Common | `platform.common` | `ApiResponse<T>`, `Money`, `UidEntity`, error model, validation |

The outbox has no controller, no REST surface, and no permission — it is internal plumbing.

## IAM — Identity & Access (ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0046)

- **Purpose.** The security spine: authentication, the org/company/branch tenant tree, users, roles, permissions, company/branch assignment, and the audit trail. Built first; everything hangs off it.
- **Key entities.** `organisation`, `company`, `branch`, `app_user`, `permission`, `role`, `role_permission`, `user_role`, `user_branch`, `user_company`, `refresh_token`, `audit_log`.
- **Company membership (assign-company-first, ADR-0046).** `user_company` is an **authoritative** write-path prerequisite (supersedes the non-authoritative phase of ADR-0045): a user must hold an active company membership before any role can be granted or branch assigned in that company (else 409) — the prior auto-create on grant/assign is removed, and a membership cannot be removed while roles/branches remain there. The read/isolation oracle stays additive (role OR branch OR membership). Root bypasses tenant scope but not the membership gate; bootstrap/seeders write directly and an every-boot `UserCompanyBackfill` reconcile guarantees coverage (no migration).
- **Permission family.** `USER.*` (incl. `USER.COMPANY.MANAGE`), `ROLE.*`, `PERMISSION.*`, `COMPANY.*`, `BRANCH.*`, `AUDIT.*`.

| Controller | Base path | Notes |
|---|---|---|
| `AuthController` | `/api/v1/auth` | login / refresh / logout (no permission — public/auth) |
| `OrganisationController` | `/api/v1/organisations` | org tree root |
| `CompanyController` | `/api/v1/companies` | legal entities; base currency. The list returns only the caller's companies (root sees all) — same assigned-or-root filter as `/companies/accessible`, not enumerable cross-tenant |
| `BranchController` | `/api/v1/branches` | locations; set-default |
| `UserController` | `/api/v1/users` | user CRUD, set-password, unlock |
| `UserCompanyController` | `/api/v1/user-companies` | assign/remove company membership (`USER.COMPANY.MANAGE`); prerequisite for role/branch assignment |
| `UserBranchController` | `/api/v1/user-branches` | assign branches, set default — requires prior company membership |
| `UserRoleController` | `/api/v1/user-roles` | grant/revoke roles, scoped to company/branch — requires prior company membership |
| `RoleController` | `/api/v1/roles` | role + permission-bundle management |
| `AuditController` | `/api/v1/audit` | read-only audit trail |

## Masters — Parties (ADR-0006)

- **Purpose.** The trading-partner master data: who the company sells to, buys from, and deals with.
- **Key entities.** `customers`, `suppliers`, `agents` (sales agents), `other_parties`, party code sequences, branch associations.
- **Permission family.** `CUSTOMER.*`, `SUPPLIER.*`, `AGENT.*`, `OTHERPARTY.*`, `PARTY.*`.

| Controller | Base path |
|---|---|
| `CustomerController` | `/api/v1/customers` |
| `SupplierController` | `/api/v1/suppliers` |
| `AgentController` | `/api/v1/agents` |
| `OtherPartyController` | `/api/v1/other-parties` |

## Masters — Catalog / Products (ADR-0007, ADR-0026)

- **Purpose.** The product/service catalogue and its supporting reference data: products (GOODS=stockable / SERVICE=non-stockable), barcodes, bulk packs, prices, recipe components; units of measure with conversions; price lists; tax rates; distribution routes.
- **Key entities.** `products` (+ branches/barcodes/bulk-packs/prices/components sub-resources), `units_of_measure`, `price_lists`, `tax_rates`, `routes`. Bills of Materials extend products (ADR-0026; see Manufacturing).
- **Permission family.** `PRODUCT.*`, `UOM.*`, `PRICELIST.*`, `TAXRATE.*`, `ROUTE.*`, `BOM.*`.

| Controller | Base path |
|---|---|
| `ProductController` | `/api/v1/products` (+ `/products/uid/{uid}/...` sub-resources, `/products/barcode-lookup`) |
| `UnitOfMeasureController` | `/api/v1/units` |
| `PriceListController` | `/api/v1/price-lists` |
| `TaxRateController` | `/api/v1/tax-rates` |
| `RouteController` | `/api/v1/routes` |

## Sales — Order-to-Cash (ADR-0008, ADR-0021, ADR-0029)

- **Purpose.** The full O2C chain: Quotation → Sales Order (reserves stock) → Delivery (full/partial/backorder) → Sales Invoice (per-delivery or DIRECT walk-in) → Sales Return (RMA). Finalising an invoice publishes `SALE.FINALISED` over the outbox, driving stock deduction and GL posting.
- **Key entities.** `quotations`, `sales_orders`, `deliveries`, `sales_invoices` (with `tax_summary` JSONB), `sales_returns`, and their lines.
- **Permission family.** `SALES.*` (e.g. `SALES.INVOICE.CREATE`, `SALES.INVOICE.VOID`, `SALES.ORDER.*`, `SALES.QUOTE.*`, `SALES.DELIVERY.*`, `SALES.RETURN.*`).

| Controller | Base path |
|---|---|
| `QuotationController` | `/api/v1/quotations` |
| `SalesOrderController` | `/api/v1/sales-orders` |
| `DeliveryController` | `/api/v1/deliveries` |
| `SalesInvoiceController` | `/api/v1/sales-invoices` |
| `SalesReturnController` | `/api/v1/sales-returns` |

## Sales — Advanced Pricing (ADR-0029)

- **Purpose.** Price lists and rule-driven pricing applied across the sales chain.
- **Key entities.** `pricing_rules` (+ price lists, shared with catalog).
- **Permission family.** `PRICELIST.*`, `SALES.*` pricing codes.

| Controller | Base path |
|---|---|
| `PricingRuleController` | `/api/v1/pricing-rules` |

## Point of Sale (ADR — POS, V43/V82/V83)

- **Purpose.** Retail POS: till setup; session lifecycle (open float → sell → payout → X-read → close[count cash] → reconcile[Z-read, variance, GL]); quick checkout (session + customer + agent + line items + tender + change).
- **Key entities.** `pos_tills`, `pos_sessions`, `pos_sales` (+ lines, tenders).
- **Permission family.** `POS.*`.

| Controller | Base path |
|---|---|
| `PosTillController` | `/api/v1/pos/tills` |
| `PosSessionController` | `/api/v1/pos/sessions` |
| `PosSaleController` | `/api/v1/pos/sales` |

## Procurement — Procure-to-Pay (ADR-0011, ADR-0027)

- **Purpose.** The full P2P chain: requisition → submit → approve → convert → RFQ → send → supplier quote → award (creates PO) → PO place/approve → goods receipt → landed cost → supplier bill → 3-way bill match → purchase return. A goods receipt publishes `STOCK.RECEIVED` over the outbox.
- **Key entities.** `purchase_requisitions`, `rfqs`, `supplier_quotes`, `purchase_orders`, `goods_receipts`, `landed_costs`, `purchase_returns`, `purchase_settings`. (Supplier bill + 3-way match live in the AP module — the procurement→bill bridge.)
- **Permission family.** `PURCHASE.*`, `SUPPLIER.*` (supplier-quote codes).

| Controller | Base path |
|---|---|
| `PurchaseRequisitionController` | `/api/v1/purchase-requisitions` |
| `RfqController` | `/api/v1/rfqs` |
| `SupplierQuoteController` | `/api/v1/supplier-quotes` |
| `PurchaseOrderController` | `/api/v1/purchase-orders` |
| `GoodsReceiptController` | `/api/v1/goods-receipts` |
| `LandedCostController` | `/api/v1/landed-costs` |
| `PurchaseReturnController` | `/api/v1/purchase-returns` |
| `PurchaseSettingsController` | `/api/v1/purchase-settings` |

## Inventory / Stock (ADR-0010, ADR-0020, ADR-0028)

- **Purpose.** Inventory of record: on-hand by location/product, movements, adjustments, opening balances, reorder levels, inter-location transfers, physical/cycle counts, batches/lots, serials, and valuation (moving-average, perpetual). The first outbox **consumer** — it applies `SALE.FINALISED`/`SALE.VOIDED`/`STOCK.RECEIVED` idempotently, stamping `source_event_uid` on each movement. Movements are append-only.
- **Key entities.** `stock_movements` (append-only ledger), `stock_on_hand`, `stock_locations`, `stock_transfers`, `stock_counts`, `stock_batches`, `stock_serials`, valuation tables.
- **Permission family.** `STOCK.*`, `INVENTORY.*`.

| Controller | Base path |
|---|---|
| `StockController` | `/api/v1/stock` (on-hand, movements, adjustments, opening, by-location, by-product) |
| `StockLocationController` | `/api/v1/stock-locations` |
| `StockTransferController` | `/api/v1/stock-transfers` |
| `StockCountController` | `/api/v1/stock-counts` |
| `StockBatchController` | `/api/v1/stock-batches` |
| `StockSerialController` | `/api/v1/stock-serials` |
| `StockValuationController` | `/api/v1/stock/valuation` |

## Finance — General Ledger (ADR-0013, ADR-0019)

- **Purpose.** The double-entry posting engine and books of record: chart of accounts, manual journals (balanced-or-rejected, no draft), fiscal calendar (years + periods, open/close), trial balance, GL posting-account config (`gl_configs`), and year-end close. Consumes sales events to auto-post; every other financial module posts into it.
- **Key entities.** `chart_of_accounts`, `fiscal_years`, `fiscal_periods`, `journal_batches`, `journal_entries`, `journal_lines`, `gl_configs`.
- **Permission family.** `GL.*` (e.g. `GL.JOURNAL.POST`, `GL.ACCOUNT.*`, `GL.PERIOD.CLOSE`, `GL.CONFIG.*`).

| Controller | Base path |
|---|---|
| `ChartOfAccountController` | `/api/v1/gl/accounts` |
| `JournalController` | `/api/v1/gl/journals` |
| `FiscalPeriodController` | `/api/v1/gl/periods` (+ `/gl/periods/fiscal-years`) |
| `GlConfigController` | `/api/v1/gl/configs` |
| `TrialBalanceController` | `/api/v1/gl/trial-balance` |
| `YearEndCloseController` | (year-end close, ADR-0019) |

## Cost Centre / Dimensions (ADR-0025)

- **Purpose.** Analytical tagging over GL postings: dimension types, dimension values, mandatory-dimension enforcement, and dimension-sliced trial balance.
- **Key entities.** `dimensions` (types), `dimension_values`, document-default mappings.
- **Permission family.** `COSTING.*` (and dimension codes).

| Controller | Base path |
|---|---|
| `DimensionController` | `/api/v1/dimensions` |
| `DimensionValueController` | `/api/v1/dimension-values` |
| `DimensionReportController` | `/api/v1/costing/reports` |

## Accounts Receivable (ADR-0014)

- **Purpose.** Customer open items: AR invoice view, record receipt (tender + allocation + optional WHT leg), credit notes, write-offs, opening balances, statement / ageing / balance. Posts to the GL AR control account.
- **Key entities.** AR open items (over `sales_invoices`), `ar_receipts` (+ allocations), `ar_credit_notes`, `ar_write_offs`, `ar_opening_balances`.
- **Permission family.** `AR.*`.

| Controller | Base path |
|---|---|
| `ArInvoiceController` | `/api/v1/ar/invoices` |
| `ArReceiptController` | `/api/v1/ar/receipts` |
| `ArCreditNoteController` | `/api/v1/ar/credit-notes` |
| `ArWriteOffController` | `/api/v1/ar/write-offs` |
| `ArOpeningBalanceController` | `/api/v1/ar/opening-balances` |
| `ArStatementController` | `/api/v1/ar` (balance / ageing / statement) |

## Accounts Payable (ADR-0015)

- **Purpose.** Supplier obligations: supplier-bill entry + 3-way match (the procurement→bill bridge), single-bill payment + payment run (with WHT-on-payment), debit notes, opening balances, and supplier statement (balance / ageing / reconciliation). Posts to the GL AP control account.
- **Key entities.** `supplier_bills` (+ match), `ap_payments` (single + run), `ap_debit_notes`, `ap_opening_balances`.
- **Permission family.** `AP.*` (+ `PURCHASE.*` where the bill bridges procurement).

| Controller | Base path |
|---|---|
| `SupplierBillController` | `/api/v1/ap/supplier-bills` |
| `BillMatchController` | `/api/v1/ap/supplier-bills/uid/{billUid}/match` |
| `ApPaymentController` | `/api/v1/ap/payments` |
| `ApDebitNoteController` | `/api/v1/ap/debit-notes` |
| `ApOpeningBalanceController` | `/api/v1/ap/opening-balance` |
| `ApStatementController` | `/api/v1/ap/statement` |

## Cash & Bank (ADR-0016)

- **Purpose.** Treasury: cash/bank account CRUD, inter-account transfers, direct entries, account balance/statement + GL reconciliation, bank reconciliation lifecycle, and the cheque register.
- **Key entities.** `cash_bank_accounts`, `cash_transfers`, `cash_direct_entries`, `bank_reconciliations`, `cheques`.
- **Permission family.** `CASH.*`, `CHEQUE.*`.

| Controller | Base path |
|---|---|
| `CashBankAccountController` | `/api/v1/cash/accounts` |
| `CashTransferController` | `/api/v1/cash/transfers` |
| `CashDirectEntryController` | `/api/v1/cash/entries` |
| `CashAccountStatementController` | `/api/v1/cash/statements` |
| `BankReconciliationController` | `/api/v1/cash/reconciliations` |
| `ChequeController` | `/api/v1/cash/cheques` |

## Tax — VAT & WHT (ADR-0017)

- **Purpose.** VAT return lifecycle (open → recompute → file) with add/remove adjustments; withholding-tax types (rate master) and the WHT register / certificate view.
- **Key entities.** `vat_returns` (+ adjustments), `wht_types`, WHT register.
- **Permission family.** `VAT.*`, `WHT.*`.

| Controller | Base path |
|---|---|
| `VatReturnController` | `/api/v1/vat/returns` |
| `VatAdjustmentController` | `/api/v1/vat/returns/uid/{returnUid}/adjustments` |
| `WhtTypeController` | `/api/v1/wht/types` |
| `WhtRegisterController` | `/api/v1/wht/register` |

## FX / Multi-currency (ADR-0005, ADR-0036)

- **Purpose.** Currency master, effective-dated exchange-rate maintenance, foreign-currency document posting against the base (TZS) ledger, realized FX on settlement, and the period-end **unrealized** FX revaluation run (preview → post → reverse).
- **Key entities.** `currencies` (global reference), `currency_rates` (effective-dated), document base-triple columns, `fx_revaluation_runs`.
- **Permission family.** `FX.*`, `CURRENCY.*`.

| Controller | Base path |
|---|---|
| `CurrencyController` | `/api/v1/fx` (`/currencies`, `/rates`) |
| `FxRevaluationRunController` | `/api/v1/fx/revaluation-runs` |

## Reporting & BI (ADR-0018, ADR-0037)

- **Purpose.** Read-only over the GL: P&L, Balance Sheet, Cash Flow (indirect), trial balance, account-ledger drill-through, server-side PDF/XLSX export; a composite BI analytics dashboard (per-panel RBAC, drill, export); analytical reports (budget variance, departmental actuals, dimension-sliced TB, project WIP/P&L, manufacturing WIP). Posts nothing, owns no business table.
- **Key entities.** None of its own — pure queries over `journal_lines` and the source ledgers.
- **Permission family.** `REPORT.*`, `BI.*`.

| Controller | Base path |
|---|---|
| `ReportingController` | `/api/v1/reports` (income-statement, balance-sheet, cash-flow, account-ledger; `/export`) |
| `BiDashboardController` | `/api/v1/bi` |

## Fixed Assets (ADR-0030)

- **Purpose.** Asset categories, the fixed-asset register (register / acquire-from-bill / place-in-service / transfer), depreciation runs (preview / post), revaluation, disposal & write-off, and the FA→GL reconciliation report.
- **Key entities.** `asset_categories`, `fixed_assets`, `depreciation_runs`, disposals/revaluations.
- **Permission family.** `FA.*`.

| Controller | Base path |
|---|---|
| `AssetCategoryController` | `/api/v1/fixed-assets/categories` |
| `FixedAssetController` | `/api/v1/fixed-assets` |
| `DepreciationRunController` | `/api/v1/fixed-assets/depreciation-runs` |

## HR & Payroll (ADR-0032)

- **Purpose.** Departments, employees (employment-status lifecycle), employment contracts (types + terminate), leave requests (lifecycle + accrual), employee loans, pay components, the payroll run lifecycle (calculate → approve → post → disburse → reverse, with GL + Cash & Bank effects), and statutory setup (PAYE band sets + statutory rate sets).
- **Key entities.** `hr_departments`, `hr_employees`, `hr_contracts`, `hr_leave_requests`, `hr_loans`, `hr_pay_components`, `hr_payroll_runs`, statutory tables.
- **Permission family.** `HR.*`.

| Controller | Base path |
|---|---|
| `HrDepartmentController` | `/api/v1/hr/departments` |
| `HrEmployeeController` | `/api/v1/hr/employees` |
| `HrContractController` | `/api/v1/hr/contracts` |
| `HrLeaveController` | `/api/v1/hr/leave-requests` |
| `HrLoanController` | `/api/v1/hr/loans` |
| `HrPayComponentController` | `/api/v1/hr/pay-components` |
| `HrPayrollController` | `/api/v1/hr/payroll-runs` |
| `HrStatutoryController` | `/api/v1/hr/statutory` |

## CRM (ADR-0031)

- **Purpose.** Lead capture and lifecycle (NEW → CONTACTED → QUALIFIED → CONVERTED/DISQUALIFIED); opportunities (OPEN → WON/LOST, advance-stage, lines, convert to Quotation/Sales Order); pipeline analytics (board, forecast, KPIs); pipeline-stage CRUD; activities (CALL/EMAIL/MEETING/NOTE/TASK on a lead or opportunity).
- **Key entities.** `crm_leads`, `crm_opportunities` (+ lines), `crm_pipeline_stages`, `crm_activities`.
- **Permission family.** `CRM.*`.

| Controller | Base path |
|---|---|
| `LeadController` | `/api/v1/crm/leads` |
| `OpportunityController` | `/api/v1/crm/opportunities` |
| `PipelineController` | `/api/v1/crm/pipeline` |
| `PipelineStageController` | `/api/v1/crm/pipeline-stages` |
| `ActivityController` | `/api/v1/crm/activities` |

## Projects — Job Costing (ADR-0033)

- **Purpose.** Project CRUD + lifecycle, tasks, timesheets, issue-materials-to-project (COGS at moving-average, tagged to the project), and the costing read models (per-project P&L, cross-project WIP).
- **Key entities.** `projects`, `project_tasks`, `project_timesheets`, project material issues, project cost tags on AP/sales/stock.
- **Permission family.** `PROJECTS.*`.

| Controller | Base path |
|---|---|
| `ProjectController` | `/api/v1/projects` |
| `ProjectTaskController` | `/api/v1/project-tasks` |
| `ProjectTimesheetController` | `/api/v1/project-timesheets` |
| `IssueToProjectController` | `/api/v1/project-issues` |
| `ProjectCostingController` | `/api/v1/project-costing` |

## Manufacturing (ADR-0026, ADR-0035)

- **Purpose.** Multi-level Bill of Materials authoring + lifecycle (explode, where-used, cost roll-up); Work Order execution (release → issue → apply-cost → complete → close, plus cancel reversal); per-order cost report; company-level WIP reconciliation.
- **Key entities.** `boms` (+ components), `work_orders` (+ operations), WIP accounts.
- **Permission family.** `BOM.*`, `WORKORDER.*`, `MANUFACTURING.*`.

| Controller | Base path |
|---|---|
| `BomController` | `/api/v1/boms` |
| `WorkOrderController` | `/api/v1/work-orders` |
| `ManufacturingReportController` | `/api/v1/manufacturing` |

## Budgeting (ADR-0034)

- **Purpose.** Budget headers + version lifecycle (DRAFT → SUBMITTED → APPROVED/REJECTED/SUPERSEDED, recall to DRAFT), line entry in three modes (DIRECT, ANNUAL_SPREAD, SEED-from-prior), new-version re-plan, and the two budget reports (budget-vs-actual variance, departmental actuals). Posts **nothing** to GL — read against GL actuals at report time only.
- **Key entities.** `budgets`, `budget_versions`, budget lines.
- **Permission family.** `BUDGETING.*`.

| Controller | Base path |
|---|---|
| `BudgetController` | `/api/v1/budgets` |
| `BudgetVersionController` | `/api/v1/budget-versions` |
| `BudgetReportController` | `/api/v1/budgeting` |

## Approvals engine (ADR-0022)

- **Purpose.** A generic, document-agnostic governance spine: amount-threshold + branch-scoped multi-step approval **policies** (per-company master with an ordered chain of IAM approver roles), runtime **approval requests** that freeze a policy-step snapshot at submit, append-only decisions, deterministic policy-match (branch beats company-wide; no match → auto-approve). Exposes `submitForApproval` (idempotent per type+uid) + `getApprovalState` (synchronous gate) and publishes `APPROVAL.RESOLVED`. Posts nothing to the books — it gates.
- **Key entities.** `approval_policies` (+ steps), `approval_requests` (+ frozen steps, decisions).
- **Permission family.** `APPROVALS.*`.

| Controller | Base path |
|---|---|
| `ApprovalPolicyController` | `/api/v1/approvals/policies` |
| `ApprovalRequestController` | `/api/v1/approvals/requests` |

## Documents (ADR-0023)

- **Purpose.** Server-side document generation: PDF render with a template registry and per-company branding; a generated-documents log.
- **Key entities.** generated documents, `document_templates`, document branding.
- **Permission family.** `DOCUMENT.*`.

| Controller | Base path |
|---|---|
| `DocumentController` | `/api/v1/documents` |
| `DocumentTemplateController` | `/api/v1/documents/templates` |
| `DocumentBrandingController` | `/api/v1/documents/branding` |

## Notifications (ADR-0024)

- **Purpose.** In-app notification inbox, per-user preferences, and an admin type-catalogue toggle + delivery log.
- **Key entities.** notifications, notification preferences, notification type catalogue + delivery log.
- **Permission family.** `NOTIFICATION.*`.

| Controller | Base path |
|---|---|
| `NotificationController` | `/api/v1/notifications` |
| `NotificationPreferenceController` | `/api/v1/notification-preferences` |
| `NotificationAdminController` | `/api/v1/admin/notifications` |

## Sales — Blanket / Standing Orders (ADR-0029)

- **Purpose.** Recurring and committed-volume sales arrangements that drive scheduled releases.
- **Key entities.** `blanket_orders`, `standing_orders`.
- **Permission family.** `SALES.*`.

| Controller | Base path |
|---|---|
| `BlanketOrderController` | `/api/v1/blanket-orders` |
| `StandingOrderController` | `/api/v1/standing-orders` |

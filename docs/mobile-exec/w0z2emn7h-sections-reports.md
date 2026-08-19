## Reports & Dashboards

Everything below is grounded in the shipped tree at `d:/My_Works/ERP/ERPCLEAN2` (branch `develop`, `53343e96`). Endpoint paths, permission codes, DTO field lists and file:line references were re-verified against the tree; anything marked **(PROPOSED)** does not exist and is specified here for build, and anything I could not verify from source is marked **(UNVERIFIED)**.

Two facts shape this whole section:

1. **No group-level (cross-company) read exists anywhere.** `ScopeGuard.assertCanActIn` (`backend/src/main/java/com/erp/platform/security/ScopeGuard.java:708`, in a 727-line file) admits a single `companyId`, and there is no `X-Company-Uid` header — the only scope header is `X-Branch-Uid` (`JwtRequestContextFilter.java:56`; a repo-wide grep for `X-Company-Uid` returns zero hits).
2. **Permission gating uses `@perm.has('CODE')` / `@perm.scoped(#uid,'type','CODE')`**, not Spring's `hasPermission(...)`. Any new handler must use the same beans, and `EndpointAuthorizationTest` (`src/test/java/com/erp/architecture/EndpointAuthorizationTest.java`) fails the build on a `com.erp.api` handler without a `@PreAuthorize`.

**Corrected counts.** There are **14** `@GetMapping(".../export")` handlers under `com.erp.api`, of which **12 are report/statement/dashboard exports** (the other two are `BulkImportController:60 /{key}/export` and `HrPayrollController:115 /uid/{uid}/eft-export`, an EFT payment file gated on `HR.PAYROLL.DISBURSE`). The earlier draft's "18 `/export` siblings" and its "54 report/dashboard read endpoints" are both **unverified inflations** — the second figure depends on where you draw the line around "report", so it is dropped rather than restated.

---

### 1. The Morning Brief — the one home screen **(PROPOSED screen)**

**Contract (PROPOSED targets, not measured):** one screen, one scroll, **one HTTP call**, ≤ 8 KB gzipped, p95 ≤ 400 ms server-side. Every figure carries its own scope label and the server's `generatedAt`, never the device clock. Pull-to-refresh. Nothing on this screen links into a 40-column desktop grid.

**Header strip (not a tile):** organisation name · company chip (rendered only when the accessible-company count > 1, from `GET /api/v1/companies/accessible?organisationUid=…` — `CompanyController.java:50-53`, gated `isAuthenticated()` deliberately so a non-admin does not need `COMPANY.VIEW` to load a picker) · freshness pill.

| # | Tile | The exact number(s) shown | Secondary line | Backing endpoint TODAY | Backing endpoint TARGET |
|---|---|---|---|---|---|
| **T1** | **Sales — hero** | `TZS 48.2M` (yesterday, or today after 12:00) at 36–40 sp | `▲ 12% vs Thu · MTD 612M (▲ 4%)` · `9 branches reporting` | ❌ **nothing gives a daily figure.** `GET /api/v1/bi/sales-by-branch?companyId&branchId&from&to` (`BiDashboardController.java:126-136`) summed client-side, per company; `/bi/revenue-trend` (`:114-118`) is **fiscal-period granularity only** (`DashboardServiceImpl.java:379-407`, `MAX_TREND_PERIODS = 12` at `:94`) | **N1 (PROPOSED)** `group.salesToday`, `salesSameDayLastWeek`, `salesMtd`, `salesMtdPriorMonth` |
| **T2** | **Gross margin** | `21.4%` | `▼ 0.8 pts vs last week` | ⚠ **Do not derive from `/bi/finance-summary`.** `FinanceSummaryDto` is `{netProfitPeriod, revenue, opex, netProfit, tbTies, tbTotalDebit, tbTotalCredit, cash}` — `grossMarginPct` was **removed** because it had been hard-coded to 100% (`DashboardServiceImpl.java:80-84`). The only true margin shipped is `SalesReportTotalsDto.margin` on `GET /api/v1/reports/sales` — an unbounded per-SKU report | **N1 (PROPOSED)** `group.marginPct` |
| **T3** | **Cash on hand** | `TZS 154M` | tie badge: `Cash agrees with the ledger` / `Out by TZS 42,000` | `GET /api/v1/bi/finance-summary?companyId&from&to` (`BiDashboardController.java:79-88`) → `CashPositionDto{total, accounts[], cashTies, cashGlDifference}` — **it ships the complete account list to show one number** | **N1 (PROPOSED)** `group.cashOnHand` + one health chip |
| **T4** | **Customers owe us ≥ 90 days** | `TZS 31.4M` | `of TZS 88M total owed` | `GET /api/v1/ar/ageing?companyId&customerId?&asAt` (`ArStatementController.java:55-57`) → exactly 5 `ArAgeingRowDto{bucket, amount, currency}` rows. ⚠ **The bucket enum's last value is `D90_PLUS`, not `D91_PLUS`** (`modules/ar/domain/enums/AgeingBucket.java`; the boundary is `daysOverdue > 90` at `ArAgeingQuery.java:189-190`) | **N1 (PROPOSED)** `group.ar90Plus`, `arOutstanding` |
| **T5** | **Waiting for you** | `3 approvals · oldest 2 days` | chevron → Approvals tab; count must equal the tab badge | `GET /api/v1/approvals/requests/inbox` (`ApprovalRequestController.java:38-44`, `APPROVALS.DECIDE`, `Pageable`) — **active company only** (`ApprovalDecisionServiceImpl.java:279-304`, scoped to `principal.companyId()`) | **N2 (PROPOSED)** cross-company inbox count |
| **T6** | **Book health** | `All 5 checks passed` **or** `2 checks need attention ›` | expands to the 5 named chips | `DashboardDto.health[]` = `List<HealthIndicatorDto{label, ties, difference}>`, labels `"TB"`, `"Cash vs GL"`, `"AR vs GL 1200"`, `"AP vs GL 2100"`, `"Stock vs GL 1300"` (`DashboardServiceImpl.java:471, 473, 485, 486, 497`) — obtainable only via a full `/bi/dashboard` call | **N7 (PROPOSED)** health board (1 call) / **N1** `health[]` |
| **T7** | **Branches — top 3** | `Dar es Salaam HQ · TZS 18.4M · ▲9%` ×3 + `See all 9 ›` | CSS bar, no chart library (ADR-0037:20 confirms none is in `package.json`) | `GET /api/v1/bi/sales-by-branch` — **already sorted total-desc** (`DashboardServiceImpl.java:452`), returns `SalesByBranchDto{currency, grandTotal, invoiceCount, rows[]}` with `BranchSalesRowDto{branchId, branchCode, branchName, total, count}`. **The single best executive endpoint shipped.** | **N1 (PROPOSED)** `topBranches[]` (capped 10, cross-company) |

**Day-1 fallback, before N1 lands.** The brief is buildable *today* for **one company** with 4 calls — `/bi/sales-by-branch` (T1 partial, T7), `/bi/finance-summary` (T3), `/ar/ageing` (T4), `/approvals/requests/inbox` (T5) — and the header must then name **the company, not the group**. Shipping a group label over one company's numbers is the same class of defect as `/bi/dashboard` stamping a branch label on company-wide figures: `DashboardServiceImpl.java:189-195` passes `branchId` to **only** the CRM and sales-by-branch panels (finance, working-capital, inventory and both trends never see it) while `:200-206` stamps `branchScope.label()` on the header regardless. **Hard rule for the app: never send `branchId` to `/bi/dashboard`, and never call `/bi/dashboard` at all from the phone** — one tap fans out into every panel plus the health block, including **three inception-to-date `journal_lines` scan families** (trial balance inside `safeFinance`, plus the stock and AR/AP recons). *(The earlier draft's "58 + 4N SQL statements" is **UNVERIFIED** — the fan-out is real and large, the exact statement count is not something I measured.)*

**Tile states, all seven tiles:** *populated* / *empty* (`No sales posted yet` — never a `0`) / *stale* (banner above the numbers) / *forbidden* (tile is **replaced**, not hidden: `You don't have access to margin figures. Ask your administrator for BI.FINANCE.VIEW.`). The server's per-panel-nullable contract (`DashboardServiceImpl.java:185-195` — `canFinance`/`canOps`/`canCrm` gate each panel, `safeFinance`/`safeCrm`/`safeSalesByBranch` return `null` on forbidden-or-failed) must be preserved in every new DTO: **null ≠ 0**.

---

### 2. Full report catalogue

Consolidation: **Group** = across companies in the organisation · **Company** = all branches of one company · **Branch** = filterable to one branch · **Entity** = one customer/supplier/project/account.
Status: **READY** = callable from the phone as-is · **DTO** = endpoint exists, payload/shape unfit for a phone · **NEW (PROPOSED)** = no endpoint exists.

All seed line numbers below are `backend/src/main/resources/db/migration/R__seed_permissions.sql` (922 lines) and were individually grepped.

#### 2.1 Sales & revenue

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Group sales today / yesterday / MTD | one hero figure + 3 comparisons | ❌ none (no daily series anywhere) | `EXEC.BRIEF.VIEW` — **confirmed ABSENT from the seed** | Group | **NEW — N1 (PROPOSED)** |
| Company sales for a window | total + invoice count | `GET /api/v1/bi/sales-by-branch?companyId&branchId?&from&to` `BiDashboardController.java:126` | `BI.FINANCE.VIEW` (seed:40) | Company (+per-branch rows) | **READY** |
| Revenue trend / net-profit trend, 12 periods | sparkline, 12 points | `GET /api/v1/bi/revenue-trend?companyId` `:114`; `GET /api/v1/bi/net-profit-trend?companyId` `:120` → `TrendDto{metricLabel, currency, points[]}`, `TrendPointDto{periodLabel, periodStart, periodEnd, value}` | `BI.FINANCE.VIEW` | Company | **READY** (relabel `P3 2026` → `Mar 2026` — the accountant-speak label is built at `DashboardServiceImpl.java:405`; `periodStart` is on the DTO) |
| Sales report (per-SKU, agent, route) | qty, discount, VAT, margin, amount per product | `GET /api/v1/reports/sales?fromDate&toDate&agentUid&routeUid&supplierUid&branchUid` `SalesReportController.java:48-59` — **no `companyId` param; taken from `RequestContext.get().companyId()`** (`:57`) | `SALES.INVOICE.VIEW` (seed:208) | Company + Branch ⚠ | **DESKTOP ONLY** — `SalesReportQuery` (310 lines) contains **no `LIMIT` and no `Pageable`** anywhere; and **`SalesReportDto` carries `supplierName`/`agentName`/`routeName` but no `branchName`**, so a reader filtering by branch cannot tell the scope from the payload |
| POS till expenses | total + by category | `GET /api/v1/pos/sessions/expenses?companyId&from&to` `PosSessionController.java:144-146` → `TillExpenseReportDto{companyId, fromDate, toDate, totalAmount, count, byCategory[], rows[]}` | `POS.EXPENSE.VIEW` (seed:136) | Company | **DTO** (`rows[]` is unbounded `PosPayoutDto`; the exec wants `totalAmount` + `byCategory[]`, which are already there — the phone can use it if it discards `rows`) |
| Till day-close / variance | sessions open, cash counted vs expected, variance | `GET /api/v1/pos/sessions/uid/{uid}/z-read` `PosSessionController.java:219-221` — **one session per call**. A paged session list exists (`GET /api/v1/pos/sessions?companyId&status` `:66-68`) but carries no cash-variance roll-up | `POS.SESSION.VIEW` (seed:143) | Entity | **NEW — N9 (PROPOSED)**; today it is N+1 over `/z-read` |

#### 2.2 Gross margin

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Group / company margin % | `21.4%` + delta | ❌ **no correct source.** `FinanceSummaryDto` cannot yield it (no `grossMarginPct`; `opex` excludes COGS — `DashboardServiceImpl.java:80-84`) | `EXEC.BRIEF.VIEW` (PROPOSED) / `SALES.INVOICE.VIEW` | Group / Company | **NEW — N1 + N3 (PROPOSED)** |
| Margin by product / customer | top & bottom 10 by margin | only inside the unbounded `/reports/sales` (`SalesReportRowDto.margin`) | `SALES.INVOICE.VIEW` (seed:208) | Company + Branch | **NEW — N3 (PROPOSED)** (`metric=MARGIN`) |
| Potential margin on stock | cost value vs sale value | `GET /api/v1/stock/reports/stock-value?branchUid&supplierUid` `ProductStockReportController.java:74-78` → `ProductStockReportDto{company, branchName, supplierName, currency, priceListName, priceIncludesVat, rows[], totals, generatedAt}` with `ProductStockTotalsDto{totalQuantity, totalCostValue, totalSaleValue, potentialMargin, unvaluedItems, unpricedItems, discontinuedItems}` | `INVENTORY.VALUATION.VIEW` (seed:124) | Company + Branch ✅ (**the only report query that checks `user_branch`** — `ProductStockReportQuery.java:118`, helper at `:390`) | **DTO — N4 (PROPOSED)** (totals arrive *after* every SKU row; ✅ note this DTO **does** echo `branchName` — the pattern to copy) |

#### 2.3 Cash & bank position

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Cash on hand, total | one figure + tie badge | `GET /api/v1/bi/finance-summary?companyId&from&to` `BiDashboardController.java:79` → `cash.total`, `cash.cashTies`, `cash.cashGlDifference` | `BI.FINANCE.VIEW` | Company | **DTO** (ships every account to display one number) |
| Cash & bank account balances | list: account name + balance | `GET /api/v1/cash/statements/balances?companyId` `CashAccountStatementController.java:49-51` → `List<CashAccountBalanceDto{cashBankAccountId, cashBankAccountUid, accountCode, accountName, bookBalance, currency}>` | `CASH.VIEW` (seed:60) | Company | **READY** (typically < 20 rows; drop `cashBankAccountId` on the wire) |
| Cash vs GL reconciliation | per-account difference | `GET /api/v1/cash/statements/gl-reconciliation?companyId` `:56-58` → `List<CashGlReconciliationDto{cashBankAccountUid, accountName, linkedGlAccountId, linkedGlAccountCode, bookBalance, linkedGlBalance, difference}>`; single-account sibling at `:63-65` | `CASH.VIEW` | Company / Entity | **READY** → fold into **N7** |
| One account's statement | running transactions | `GET /api/v1/cash/statements/accounts/uid/{uid}/statement` `:42-44` (`@perm.scoped`) | `CASH.VIEW` (scoped) | Entity | **READY**, drill-down only |

#### 2.4 Receivables ageing

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| AR ageing buckets | 5 bars: `CURRENT / D1_30 / D31_60 / D61_90 / D90_PLUS` | `GET /api/v1/ar/ageing?companyId&customerId?&asAt` `ArStatementController.java:55` → `List<ArAgeingRowDto{bucket, amount, currency}>` | `AR.STATEMENT.VIEW` (seed:34) | Company — **no branch dimension exists** | **READY** (5 rows) |
| Worst 10 debtors | name + 90+ + total | `GET /api/v1/ar/ageing/by-customer?companyId&asAt` `:71-73` → `List<ArCustomerAgeingRowDto{customerId, customerCode, customerName, current, days1to30, days31to60, days61to90, days91Plus, total, currency}>` — 10 columns, **unpaged**. ⚠ note the DTO field is `days91Plus` while the enum value is `D90_PLUS`; they are the same bucket | `AR.STATEMENT.VIEW` | Company | **DTO — N6 (PROPOSED)** |
| One customer's balance | single figure | `GET /api/v1/ar/balance?companyId&customerId` `:84-86` — gated on **`AR.VIEW`** (seed:35), not `AR.STATEMENT.VIEW` | `AR.VIEW` | Entity | **READY** — the only AR figure a `BRANCH_MANAGER` can reach (see the seed gap below) |
| AR vs GL 1200 tie | badge + difference | `GET /api/v1/bi/working-capital?companyId` `BiDashboardController.java:90-93` → `WorkingCapitalDto{arOutstanding, arTies, arDifference, apOutstanding, apTies, apDifference}` | `BI.FINANCE.VIEW` | Company | **READY** — 6 scalars, **the single best-fit endpoint in the product** |
| Customer statement | open items + receipts | `GET /api/v1/ar/statement?companyId&customerId&asAt` `:39-43` | `AR.STATEMENT.VIEW` | Entity | **DESKTOP / PDF** (`DocumentType.AR_STATEMENT` renders) |

⚠ **`BRANCH_MANAGER` does not hold `AR.STATEMENT.VIEW`** — it holds `AR.VIEW` (`:695`) and `AP.VIEW` (`:696`) only. A branch manager 403s on both ageing screens and on the customer statement. Seed gap, not an app bug.

#### 2.5 Payables & commitments

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| We owe suppliers (total) | one figure + tie badge | `GET /api/v1/bi/working-capital?companyId` → `apOutstanding`, `apTies`, `apDifference` | `BI.FINANCE.VIEW` | Company | **READY** |
| AP vs GL 2100 reconciliation | sub-ledger vs control | `GET /api/v1/ap/statement/reconciliation?companyId` `ApStatementController.java:57-59` → `ApReconciliationDto{companyId, subLedgerTotal, glControlBalance, difference, currency}` | `AP.VIEW` (seed:22) | Company | **READY** → **N7** |
| AP ageing | buckets | `GET /api/v1/ap/statement/ageing?companyId&supplierId&asAt` `:47-52` — **`supplierId` and `asAt` are both REQUIRED** | `AP.VIEW` | Entity only | **NEW — N6-mirror (PROPOSED)**: there is **no company-wide AP ageing and no by-supplier AP ageing** (the AR side has both) |
| Bills awaiting payment | count + value by status | `GET /api/v1/ap/supplier-bills?companyId&supplierId?&supplierUid?&status&uncomparedOnly` `SupplierBillController.java:63-71` — paged `SupplierBillDto`. ⚠ **the base path is `/api/v1/ap/supplier-bills`, not `/api/v1/supplier-bills`** | `AP.VIEW` | Company | **DTO** (a list where the exec wants a total by status) |
| **Open purchase commitments (LPO)** | `TZS 210M ordered, not yet received` | ❌ nothing aggregates it. `GET /api/v1/purchase-orders?companyId&q&includeDirectReceipts` `PurchaseOrderController.java:95-101` returns a paged `PurchaseOrderDto` list; the exec would have to page and sum | `PURCHASE.ORDER.VIEW` (seed:169) — or `AP.BILL.ENTER` / `PURCHASE.RECEIVE`, the list gate is an `or` of three (`:96`) | Company + Branch | **NEW — N11 (PROPOSED)** |

#### 2.6 Statutory statements — P&L, balance sheet, cash flow

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| P&L, section subtotals | `sections[]` subtotals, `grossProfit`, `netProfit`, current vs comparative | `GET /api/v1/reports/income-statement?companyId&fromDate&toDate&cmpFrom&cmpTo` `ReportingController.java:52-61` → `IncomeStatementDto{header, sections[], grossProfit, netProfit, reconciliation}` | `REPORT.PL.VIEW` (seed:186) | Company (**GL is not branch-dimensional in any report** — every aggregate is `WHERE l.companyId = :companyId`, `AccountMovementQuery.java:57, 81, 107, 139`) | **DTO — N5 (PROPOSED)** |
| **Branch P&L** | the number a branch manager most wants | ❌ **impossible today.** `journal_lines.branch_id` exists (`V10__general_ledger.sql:225`, FK `:247`) and is indexed `(company_id, branch_id)` (`:331`), but no reporting query reads it — ADR-0037:136 records this as deliberate ("a nullable analysis tag … no GL aggregate filters on it") | `REPORT.PL.VIEW` + a branch-authorisation decision | Branch | **NEW — N10 (PROPOSED), and it needs its own ADR** — ADR-0037:212 fences it explicitly and names the ADR requirement |
| Balance sheet, totals | `totalAssets` / `totalLiabilities` / `totalEquity` + `reconciliation` | `GET /api/v1/reports/balance-sheet?companyId&asAtDate&compareAsAt` `:83-90` | `REPORT.BS.VIEW` (seed:182) | Company | **DTO — N5**; ⚠ **never on a foreground tap** — `BalanceSheetBuilder.java:63-64` issues **two** `cumulativeByAccountAsAt` inception-to-date scans |
| Cash flow | `openingCash`, `netChangeInCash`, `closingCash` + sections | `GET /api/v1/reports/cash-flow?companyId&fromDate&toDate&cmpFrom&cmpTo` `:107-116` | `REPORT.CASHFLOW.VIEW` (seed:183) | Company | **DTO — N5**; the heaviest report shipped — `CashFlowStatementBuilder.java:67-70` issues **four** inception-to-date scans |
| Trial balance | every account | `GET /api/v1/gl/trial-balance?companyId` `TrialBalanceController.java:50-52` / `/period?companyId&periodId` `:57-60` → `TrialBalanceDto{companyId, company, baseCurrency, periodLabel, rows[], totalDebits, totalCredits, generatedAt}` | `GL.VIEW` (seed:101) | Company | **DESKTOP ONLY** — no pagination, no `LIMIT` in `TrialBalanceQuery`. The phone shows only the derived tie — note **`tbTies` lives on `FinanceSummaryDto`, not on `TrialBalanceDto`** (which exposes `totalDebits`/`totalCredits` for the caller to compare) |
| Account ledger drill-down | raw postings | `GET /api/v1/reports/account-ledger?companyId&accountUid&fromDate&toDate&page&size` `:135-145` (paged, default 50) | `REPORT.LEDGER.VIEW` (seed:185) | Company + Entity | **DESKTOP ONLY** (also 2 inception-to-date scans, `AccountLedgerQuery.java:75, 80`) |
| Dimension-sliced TB (cost centre) | widest grid in the product | `GET /api/v1/costing/reports/sliced-trial-balance?companyId&slot&valueUid&rollUp&periodId` `DimensionReportController.java:36-43` | `COSTING.VIEW` (seed:66) **and** `GL.VIEW` | Company | **DESKTOP ONLY** |

#### 2.7 Stock value & slow movers

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Stock value, one figure | `TZS 412M` + GL tie | `GET /api/v1/bi/inventory?companyId` `BiDashboardController.java:96-99` → `InventorySummaryDto{stockValue, stockTies, stockDifference}` — 3 scalars on the wire, but the server materialises every product row to produce them | `BI.OPS.VIEW` (seed:41) | Company | **READY on the wire / DTO on the server — N4** |
| Stock value by branch | value + totals | `GET /api/v1/stock/reports/stock-value?branchUid&supplierUid` `ProductStockReportController.java:74` (companyId from `RequestContext`) | `INVENTORY.VALUATION.VIEW` | Company + Branch ✅ guarded | **DTO — N4** |
| Product list / catalogue | per-SKU cost & sale value | `GET /api/v1/stock/reports/product-list?branchUid&supplierUid` `:54-58` (`totals` is null on this one, by design) | `INVENTORY.VALUATION.VIEW` | Company + Branch ✅ | **DESKTOP ONLY** — unbounded |
| Stock register | on-hand by product | `GET /api/v1/stock/report?branchUid` `StockReportController.java:51-55` | `INVENTORY.VALUATION.VIEW` | Company + Branch ⚠ **no `user_branch` check** (`StockReportQuery.java:67-73`) | **DESKTOP ONLY** |
| Stock valuation + GL recon | `totalValue` + `StockValuationReconDto` | `GET /api/v1/stock/valuation/report?asOf` `StockValuationController.java:97-104` → `StockValuationReportDto{companyId, company, rows[], totalValue, recon, currency, generatedAt}` | `INVENTORY.VALUATION.VIEW` | Company (from principal) | **DESKTOP ONLY** — unbounded per-product. ⚠ **Correction to the earlier draft: `asOf` is NOT silently discarded.** `assertReportableDate` (`:151-164`) rejects any past or future date with a user-safe message ("Stock valuation is only available for the current position… use the Stock Movement report") and only then runs the current-position query. The limitation is real; the endpoint is honest about it |
| Stock movement, SUMMARY | opening / in / out / closing per product | `GET /api/v1/reports/stock-movement?fromDate&toDate&mode=SUMMARY&branchUid&productUid&page&size` `StockMovementReportController.java:68-77` — **paged**, `MAX_PAGE_SIZE = 500` (`StockMovementReportQuery.java:61`, clamped at `:104`) | `INVENTORY.VALUATION.VIEW` | Company + Branch ⚠ **no `user_branch` check** (`:128-152`) | **DTO** (acceptable with a top-N sort) |
| **Slow movers / dead stock** | `47 SKUs, TZS 38M, no sale in 90 days` | ❌ **nothing.** A case-insensitive grep for `slowMov|lastSold|last_sold|daysSinceLastSale|deadStock` across `backend/src/main`, the migrations and `web/src` returns **zero hits**. The data exists but no query derives it | `INVENTORY.VALUATION.VIEW` (seed:124) | Company + Branch | **NEW — N14 (PROPOSED)** |

⚠ **`StockValuationReconDto` has no `glAccountNotMapped` field.** Its actual shape is `{label, computed, expected, difference, exactDifference, ties, status, message}` with `enum Status { TIED, OUT_OF_BALANCE, GL_ACCOUNT_NOT_CONFIGURED }`; `glAccountNotMapped(...)` is a **static factory method** (`:102`, called at `StockValuationQuery.java:161`). The three-outcome contract is real and must be preserved — but the phone reads `status`, not a boolean.

#### 2.8 Purchases / LPO

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Open LPO commitment | ordered-not-received value | ❌ (see 2.5) | `PURCHASE.ORDER.VIEW` (seed:169) | Company + Branch | **NEW — N11 (PROPOSED)** |
| POs awaiting my approval | card list | `GET /api/v1/approvals/requests/inbox` — `PURCHASE_ORDER` (`PoApprovalGate.java:34`) and `SALES_ORDER` (`SalesApprovalGate.java:34`) are the **only two** document types wired to the engine; `document_type` is an opaque `VARCHAR(60)` (`V18__approvals_engine.sql:12, 76`) | `APPROVALS.DECIDE` (seed:24) | Company (one) | **READY** for one company / **N2 (PROPOSED)** for group |
| PO detail behind an approval | supplier, lines, amount | `GET /api/v1/purchase-orders/uid/{uid}` `:66-69` + `/uid/{uid}/lines` `:147-150` | `PURCHASE.ORDER.VIEW` — **independent of `APPROVALS.DECIDE`** | Entity | **READY** ⚠ an approver holding only `APPROVALS.DECIDE` approves blind off `summary`, a `VARCHAR(500)` (`V18:85`) built as `"PO {orderNumber} — {supplierName}"` (`PoApprovalGate.java:168-170`) |
| Purchases by supplier, top 10 | supplier + spend | ❌ nothing | `PURCHASE.ORDER.VIEW` | Company | **NEW — N3 (PROPOSED)** (`dimension=SUPPLIER`) |

#### 2.9 Budget vs actual

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Variance, worst 10 lines | account, budget, actual, variance | `GET /api/v1/budgeting/variance?companyId&fiscalYearUid&fromPeriodNo&toPeriodNo&costCentreValueUid&accountType` `BudgetReportController.java:41-49` → `VarianceReportDto{header, rows[], totalsBudgetByType, totalsActualByType, totalsVarianceByType}` where the three totals are **`Map<AccountType, BigDecimal>`** and `header` is a nested `HeaderDto{companyId, fiscalYearUid, fiscalYearCode, fromPeriodNo, toPeriodNo, costCentreValueUid, costCentreValueName, noApprovedBudget}` | `BUDGETING.REPORT.VIEW` (seed:53) | Company | **DTO — N8 (PROPOSED)** (account × cost centre; `VarianceReportQuery` has no `LIMIT`) |
| Departmental actuals | cost centre × account | `GET /api/v1/budgeting/departmental-actuals?companyId&fiscalYearUid&fromPeriodNo&toPeriodNo` `:59-65` | `BUDGETING.REPORT.VIEW` | Company | **DESKTOP ONLY** |

#### 2.10 Production & projects

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| WIP vs GL tie | badge + difference | `GET /api/v1/manufacturing/wip-reconciliation?companyId` `ManufacturingReportController.java:40-42` → `WipReconciliationDto{label, computed, expected, ties}` | `MANUFACTURING.VIEW` (seed:125) | Company | **READY** → **N7** |
| Work orders in progress | count by status + WIP value | `GET /api/v1/work-orders?companyId&status&page&size` `WorkOrderController.java:81-88` — paged list, no roll-up | `MANUFACTURING.VIEW` | Company | **NEW — N13 (PROPOSED)** |
| One work order's cost | planned vs good vs scrap, unit cost, variance | `GET /api/v1/work-orders/uid/{uid}/cost-report` `:166-167` → `WorkOrderCostReportDto{woNumber, …, computedUnitCost, varianceAmount, incompleteCost, components[], operations[]}` | `MANUFACTURING.VIEW` (scoped) | Entity | **DESKTOP ONLY** — nesting depth 3 |
| Project WIP / project P&L | WIP by project; margin per project | `GET /api/v1/project-costing/wip?companyId` `ProjectCostingController.java:35-37` → `List<ProjectWipRowDto{projectUid, projectNumber, name, costIncurred, billed, wip, currency}>`; `GET /api/v1/project-costing/projects/uid/{projectUid}/pnl` `:29-31` → `ProjectPnlDto{…, costByType[], recon}` | `PROJECTS.COSTING.VIEW` (seed:154) | Company / Entity | **DTO** (WIP list, 7 columns) / **DESKTOP ONLY** (P&L) |

#### 2.11 HR / payroll cost

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Payroll cost this month | `Gross 84.2M · Net 61.0M · Employer cost 92.7M` | `GET /api/v1/hr/payroll-runs?companyId&page&size` `HrPayrollController.java:46-52` → paged `PayrollRunDto` — **the totals (`grossTotal`, `deductionTotal`, `netTotal`, `employerCostTotal`) are already on the run header**, no per-employee fan-out needed | `HR.PAYROLL.VIEW` (seed:116) | Company (`PayrollRunDto.branchId` exists; no branch filter is exposed) | **READY for the latest run** — take `content[0]`; **NEW — N12 (PROPOSED)** for a trend or branch split. Note `PayrollRunDto` carries **no `employeeCount`** |
| Payroll approval pending | run awaiting sign-off | `POST /api/v1/hr/payroll-runs/uid/{uid}/approve` `:86-90`, `@perm.scoped(#uid,'payrollrun','HR.PAYROLL.APPROVE')` — **bypasses the approvals engine entirely** (only `PURCHASE_ORDER` and `SALES_ORDER` are wired), so it never appears in the inbox | `HR.PAYROLL.APPROVE` (seed:111) | Entity | **NEW routing decision** (see the approvals workstream) |
| Headcount / leave liability | — | ❌ nothing aggregates it | — | — | **NOT IN SCOPE for v1** |

#### 2.12 Branch league table

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Branch league, one company | ranked rows: branch, total, invoice count, bar | `GET /api/v1/bi/sales-by-branch?companyId&branchId?&from&to` — pre-sorted desc (`DashboardServiceImpl.java:452`) | `BI.FINANCE.VIEW` | Company, per-branch rows | **READY — best-in-class as-is** |
| Branch league, group | same, across companies | ❌ nothing | `EXEC.BRIEF.VIEW` (PROPOSED) | Group | **NEW — N1 `topBranches[]` (PROPOSED)** |
| Branch drill-down (my branch) | branch sales + stock + tills + debtors | mixed: `/bi/sales-by-branch` ✅, `/stock/reports/stock-value?branchUid` ✅ guarded, `/pos/sessions/**/z-read` N+1, `/ar/ageing` ❌ no branch dimension, branch P&L ❌ impossible | mixed | Branch | **PARTIAL** — needs **N4 / N6 / N9 / N10** |

#### 2.13 Top customers / top products

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Top 10 products by revenue / margin / qty | ranked list + share % | ❌ **explicitly excluded from BI v1** — ADR-0037:108: *"Explicitly NOT in v1 …: top customers / top products by revenue or outstanding; company-wide bucketed AR/AP ageing; branch-scoped GL KPIs."* ADR-0037:276 (OQ-BI-02) already flags top-N as a candidate fast-follow, defaulted to deferred | `SALES.INVOICE.VIEW` | Company + Branch | **NEW — N3 (PROPOSED)** |
| Top 10 customers by revenue | same | ❌ same | `SALES.INVOICE.VIEW` | Company + Branch | **NEW — N3 (PROPOSED)** |
| Top 10 customers by amount owed | name + 90+ + total | `GET /api/v1/ar/ageing/by-customer` (unpaged, 10 columns) | `AR.STATEMENT.VIEW` | Company | **DTO — N6 (PROPOSED)** |
| Top agents / routes | ranked | `/reports/sales?agentUid&routeUid` filters *to one*, never ranks | `SALES.INVOICE.VIEW` | Company | **NEW — N3 (PROPOSED)** (`dimension=AGENT\|ROUTE`) |

#### 2.14 CRM pipeline

| Report | What the exec sees | Backing endpoint(s) | Permission code | Consolidation | Status |
|---|---|---|---|---|---|
| Pipeline value + weighted forecast | 2 figures | `GET /api/v1/bi/crm-summary?companyId&branchId&from&to` `BiDashboardController.java:102-112` → `CrmSnapshotDto{pipeline, kpis, forecast}`; `ForecastDto{periodStart, periodEnd, weightedValueAmount, openCount, currency}` | `BI.CRM.VIEW` (seed:38) | Company + **Branch (a real filter here)** | **DTO** — exec wants `forecast.weightedValueAmount` + `kpis.winRatePercent` |
| Win rate / avg cycle days | `62% · 24 days` | same → `CrmKpiDto{periodStart, periodEnd, wonCount, lostCount, winRatePercent, avgCycleDays}` — ⚠ **`avgCycleDays` is a `BigDecimal`, not an `Integer`** | `BI.CRM.VIEW` | Company + Branch | **READY** (small) |
| Pipeline by stage / forecast / KPIs (CRM module) | stage bars | `GET /api/v1/crm/pipeline?companyId&branchId` `PipelineController.java:34-37`; `/forecast` `:45-50`; `/kpis` `:58-63` — **`branchId` is REQUIRED on all three** | `CRM.PIPELINE.VIEW` (seed:75) | Branch (mandatory) | **READY**, branch-only |

#### 2.15 Cross-cutting scope defects that gate this catalogue

| # | Defect | Evidence | Effect on the app |
|---|---|---|---|
| **G1** | No group (cross-company) number exists anywhere | `ScopeGuard.java:708`; no `X-Company-Uid`; no `GROUP BY company_id` aggregate in the tree | The "Group GM" persona is unbuildable without **N1**; today it costs 4+ round trips × N companies |
| **G2** | `/bi/dashboard` stamps a branch label on company-wide figures | `DashboardServiceImpl.java:189-195` (only CRM + sales-by-branch receive `branchId`) vs `:200-206` (header stamps `branchScope.label()`); ADR-0037:136 documents `branchId` as "an analysis filter, not an auth boundary" | **Never call `/bi/dashboard` from the phone.** A card reading `Mwanza Branch — Net Profit 41.2M` is a wrong number presented as a right one |
| **G3** | **The branch hole** — 3 of the 4 branch-filtered report queries do not check `user_branch` | `grep -rn "assertMayReadBranch"` returns **one** production file: `ProductStockReportQuery.java:118` (helper `:390`). Missing in `SalesReportQuery.java:54-72`, `StockReportQuery.java:67-73`, `StockMovementReportQuery.java:128-152` — each does `assertCanActIn` (company) and then resolves the branch by uid within that company, with no assignment check. The header path *does* refuse an unassigned branch (`JwtRequestContextFilter.java:230-235`) | A phone app *invites* a branch picker as primary navigation. **Lift `assertMayReadBranch` into a shared helper before shipping any branch picker.** Blocking |
| **G4** | Currency: approval policies hard-code `"TZS"` (`ApprovalPolicyServiceImpl.java:76`) and no report FX-translates; `Company.getBaseCurrency()` is per company (`DashboardServiceImpl.java:173`, defaulting to `"TZS"`) | as cited | Any group total must carry `mixedCurrency: true` + `presentationCurrency` rather than silently summing |
| **G5** | No `GENERAL_MANAGER` / `CEO` / `OWNER` role bundle | **12** bundles seeded, `R__seed_permissions.sql:309-321` (SALESPERSON, CASHIER, FIELD_SALES_AGENT, STOREKEEPER, ACCOUNTANT, SALES_MANAGER, BRANCH_MANAGER, PROCUREMENT_OFFICER, PROCUREMENT_MANAGER, HR_PAYROLL_MANAGER, FINANCE_DIRECTOR, PRODUCTION_MANAGER) | The primary persona is root (bypasses RBAC, masking every gap) or hand-assembled |
| **G6** | **The `FINANCE_DIRECTOR` hole is far wider than "manufacturing and projects"** | Grep of the 83-permission `FINANCE_DIRECTOR` block (`:812-895`): it holds **none** of `SALES.INVOICE.VIEW`, `INVENTORY.VALUATION.VIEW`, `PURCHASE.ORDER.VIEW`, `POS.SESSION.VIEW`, `POS.EXPENSE.VIEW`, `HR.PAYROLL.VIEW`, `MANUFACTURING.VIEW`, `PROJECTS.COSTING.VIEW`, `CRM.PIPELINE.VIEW`, `BI.EXPORT` | The CFO persona 403s on §2.1 (sales report, till expenses), §2.2 (both margin rows), §2.7 (every stock report), §2.8, §2.10 and §2.11 — most of this catalogue |
| **G7** | `BRANCH_MANAGER` (54 perms, `:657-716`) holds none of `AR.STATEMENT.VIEW`, `GL.VIEW`, `REPORT.CASHFLOW.VIEW`, `REPORT.LEDGER.VIEW`, `BUDGETING.REPORT.VIEW`, `BI.CRM.VIEW`, `CRM.PIPELINE.VIEW`, `MANUFACTURING.VIEW`, `PROJECTS.COSTING.VIEW`, `HR.PAYROLL.VIEW` | as cited | The branch-drill-down screen must degrade per tile, not per screen |
| **G8** | **`BI.EXPORT` (seed:39) is granted to zero seeded roles** | `grep "'BI.EXPORT'"` returns only the definition at `:39`; no `(role,'BI.EXPORT')` grant row exists. The one handler using it is `BiDashboardController.java:147` | The BI dashboard export is unreachable by every non-root user today. **The app must not render that share button** until a grant is seeded |
| **G9** | **`BUDGETING.REPORT.EXPORT` (seed:52) is a dead permission** | `grep -rn "BUDGETING.REPORT.EXPORT" src/main/java src/test/java` returns **zero** hits — it is granted to `FINANCE_DIRECTOR` (`:882`) but no endpoint checks it, because **no budgeting export endpoint exists** | §5's export table must not list budgeting exports as shipped (the earlier draft did) |

**G5–G9 together mean one thing: smoke-test every screen as a non-root `FINANCE_DIRECTOR` and a non-root `BRANCH_MANAGER` before any build sign-off.** Root testing will show a working app that no real user can use.

---

### 3. Drill-down rules — how deep the phone goes

**The rule in one line: the phone renders one number, its comparison, and at most one ranked list of ten. The eleventh row, the sixth column, and the raw posting all live on the desktop.**

| Level | What it is | Rule | Example |
|---|---|---|---|
| **L0 — Brief** | 7 tiles, one call | Always cached, always readable offline with a staleness stamp | Group sales `TZS 48.2M` |
| **L1 — Dimension list** | one ranked list, ≤ 10 rows, ≤ 3 columns | Server-side `ORDER BY … LIMIT`, never client-side truncation of an unbounded payload | Branch league (`/bi/sales-by-branch`); Top 10 products (**N3**); Worst 10 debtors (**N6**) |
| **L2 — Entity summary** | one entity's headline figures, ≤ 12 scalars | Fetched on tap, never prefetched with the list | Branch brief; approval detail (`ApprovalRequestDto` is already card-shaped); a customer's ageing row |
| **L3 — Document** | one document's header + lines | **Only for the approvals path**, and only on explicit tap (`See the document ›`) | `GET /api/v1/purchase-orders/uid/{uid}` + `/lines`; the sales-order equivalent |
| **L4 — STOP** | statements, ledgers, per-SKU grids, per-posting logs | The app shows a **stop card**, not a spinner | Trial balance, account ledger, full P&L lines, `/reports/sales`, product list, stock register, stock valuation report, stock-movement DETAIL, sliced TB, departmental actuals, work-order cost report, project P&L |

**The stop card is a first-class screen, not an error.** It states what the report is, the last computed headline figure if one is available, and offers exactly two actions: **`Share the PDF`** (§5) and **`Open on desktop`** (which copies the deep link). It never says "not supported on mobile".

**Hard thresholds that trigger L4:**

1. **> 25 rows** in a single list, or **> 4 columns** per row.
2. **Unpaged endpoint.** Verified to contain no `LIMIT` and no `Pageable`: `/reports/sales` (`SalesReportQuery`, 310 lines), `/stock/report`, `/stock/reports/product-list`, `/stock/reports/stock-value`, `/stock/valuation/report`, `/gl/trial-balance` (+`/period`), `/ar/ageing/by-customer`, `/budgeting/variance`, `/budgeting/departmental-actuals`, `/costing/reports/sliced-trial-balance`. **Exception: `/reports/stock-movement` IS paged** (`MAX_PAGE_SIZE = 500`) and `/reports/account-ledger` IS paged (default 50) — they are L4 for width and depth, not for boundlessness.
3. **Inception-to-date scans** — `/reports/balance-sheet` (2 × `cumulativeByAccountAsAt`, `BalanceSheetBuilder.java:63-64`), `/reports/cash-flow` (4 ×, `CashFlowStatementBuilder.java:67-70`), `/reports/account-ledger` (2 ×, `AccountLedgerQuery.java:75, 80`), `/gl/trial-balance`. These are **never** on a foreground tap; they sit behind an explicit "Prepare full statement" action with a progress state, and the preferred output is the PDF, not JSON.
4. **Nesting depth ≥ 3** — `WorkOrderCostReportDto{components[], operations[]}`, `ProjectPnlDto{costByType[], recon}`, `IncomeStatementDto{sections[] → lines[] → amounts}`, `VarianceReportDto{rows[] + 3 × Map<AccountType,BigDecimal>}`.
5. **Any screen that would show a raw `uid`, a numeric `id`, or an enum name.** `PENDING` → "Waiting for you"; `SALES_ORDER` → "Sales order"; `D90_PLUS` → "Over 90 days"; `GL_ACCOUNT_NOT_CONFIGURED` → "No inventory account is set up, so we couldn't check"; `P3 2026` → "Mar 2026" (derived from `TrendPointDto.periodStart`, because `buildTrend` emits the accountant label at `DashboardServiceImpl.java:405`).

**One exception, and only one:** the **approval path** is allowed to reach L3, because an approver holding only `APPROVALS.DECIDE` otherwise decides off a `VARCHAR(500)` summary reading `"PO 000123 — Kilimanjaro Traders Ltd"` (`PoApprovalGate.java:168-170`; column at `V18__approvals_engine.sql:85`). Loading PO lines requires `PURCHASE.ORDER.VIEW`, a permission independent of `APPROVALS.DECIDE` — so the app must degrade honestly: *"You can decide this request but not open the order. Ask your administrator for PURCHASE.ORDER.VIEW."*

---

### 4. New backend endpoints required — all **PROPOSED**

All are **read-only, additive, no schema change, no Flyway `V<n>` migration**. The single exception is the permission seed for `EXEC.BRIEF.VIEW`, an edit to the repeatable `R__seed_permissions.sql`, which **still requires explicit owner approval** under the standing rule. All live under a new `com.erp.api.MobileExecController` (flat under `com.erp.api`, per the controller convention), each handler carrying a `@perm.has(...)` / `@perm.scoped(...)` gate — `EndpointAuthorizationTest` fails the build otherwise.

| # | Proposed path | Permission code | In the seed today? | Consolidation | Replaces / fixes |
|---|---|---|---|---|---|
| **N1** | `GET /api/v1/mobile/exec/brief` | `EXEC.BRIEF.VIEW` | ❌ **NO — new code, seed edit + owner approval** | **Group** | many calls → 1 |
| **N2** | `GET /api/v1/mobile/exec/approvals/inbox` | `APPROVALS.DECIDE` | ✅ line 24 | **Group** | `ApprovalDecisionServiceImpl.java:279-304` single-company inbox |
| **N3** | `GET /api/v1/mobile/exec/top` | `SALES.INVOICE.VIEW` | ✅ line 208 | Company + Branch | the ADR-0037:108 v1 exclusion; the unbounded `/reports/sales` |
| **N4** | `GET /api/v1/mobile/exec/stock-summary` | `INVENTORY.VALUATION.VIEW` | ✅ line 124 | Company + Branch | full catalogue → ~12 scalars; also fixes `/bi/inventory`'s server cost |
| **N5** | `GET /api/v1/mobile/exec/statements/summary` | `REPORT.PL.VIEW` / `REPORT.BS.VIEW` / `REPORT.CASHFLOW.VIEW`, **per block** | ✅ lines 186 / 182 / 183 | Company | 3 wide statements → 1 small call |
| **N6** | `GET /api/v1/mobile/exec/receivables` · `…/payables` | `AR.STATEMENT.VIEW` · `AP.VIEW` | ✅ lines 34 · 22 | Company + **Branch (new)** | unpaged by-customer ageing; **no company-wide AP ageing exists at all** |
| **N7** | `GET /api/v1/mobile/exec/health` | `BI.VIEW` | ✅ line 42 | Company | 5+ calls → 1; avoids `/bi/dashboard`'s full fan-out |
| **N8** | `GET /api/v1/mobile/exec/budget-variance` | `BUDGETING.REPORT.VIEW` | ✅ line 53 | Company | account × cost-centre grid → worst-10 |
| **N9** | `GET /api/v1/mobile/exec/till-summary` | `POS.SESSION.VIEW` | ✅ line 143 | Company + Branch | N+1 over `/z-read` |
| **N10** | `GET /api/v1/mobile/exec/branch-pnl` | `REPORT.PL.VIEW` + a `user_branch` check | ✅ line 186 | **Branch** | the impossible report; ⚠ **needs its own ADR** (ADR-0037:212) |
| **N11** | `GET /api/v1/mobile/exec/purchase-commitments` | `PURCHASE.ORDER.VIEW` | ✅ line 169 | Company + Branch | paging + summing `PurchaseOrderDto` client-side |
| **N12** | `GET /api/v1/mobile/exec/payroll-summary` | `HR.PAYROLL.VIEW` | ✅ line 116 | Company + Branch | a trend/branch split over `PayrollRunDto` totals |
| **N13** | `GET /api/v1/mobile/exec/production-summary` | `MANUFACTURING.VIEW` | ✅ line 125 | Company | count-by-status roll-up + WIP tie |
| **N14** | `GET /api/v1/mobile/exec/slow-movers` | `INVENTORY.VALUATION.VIEW` | ✅ line 124 | Company + Branch | zero prior art — no `lastSold` anywhere in the tree |
| **N15** | `GET /api/v1/mobile/exec/crm-summary` | `BI.CRM.VIEW` | ✅ line 38 | Company + Branch | slims `CrmSnapshotDto` to 4 scalars |

**Note on N3/N4/N9/N11/N12/N13/N14/N15:** the permission code exists, but **`FINANCE_DIRECTOR` holds none of `SALES.INVOICE.VIEW`, `INVENTORY.VALUATION.VIEW`, `PURCHASE.ORDER.VIEW`, `POS.SESSION.VIEW`, `HR.PAYROLL.VIEW`, `MANUFACTURING.VIEW`, `CRM.PIPELINE.VIEW`** (G6). Reusing a seeded code does *not* mean the CFO can call it. Either the executive persona gets a purpose-built role bundle (a seed edit → owner approval) or these screens are dark for the intended audience.

#### Response DTOs — all **PROPOSED** (Java records, `*Dto` suffix, per the coding standard)

```java
// ── N1 · Group Morning Brief — the anchor of the whole app (PROPOSED) ───────
// GET /api/v1/mobile/exec/brief?organisationUid={uid}&asOf={date}&presentationCurrency={ccy}
// Internally still assertCanActIn per company: a user with 2 of 4 companies gets 2, NAMED.
public record ExecBriefDto(
        String       organisationUid, String organisationName,
        LocalDate    asOf, String presentationCurrency,
        boolean      mixedCurrency,            // companies differ and no FX rate applied
        List<String> excludedCompanyUids,      // companies the caller may not read — named, never dropped
        GroupTotalsDto       group,
        List<CompanyLineDto> companies,
        List<BranchLineDto>  topBranches,      // capped at 10, sorted desc
        List<HealthChipDto>  health,
        int          approvalsPending,
        Instant      generatedAt) {}

public record GroupTotalsDto(
        BigDecimal salesToday, BigDecimal salesYesterday, BigDecimal salesSameDayLastWeek,
        BigDecimal salesMtd,   BigDecimal salesMtdPriorMonth,
        BigDecimal marginAmountMtd, BigDecimal marginPct,   // from sales invoice lines, NOT FinanceSummaryDto
        BigDecimal cashOnHand, BigDecimal arOutstanding, BigDecimal ar90Plus,   // D90_PLUS, per AgeingBucket
        BigDecimal apOutstanding, BigDecimal openPoCommitment, BigDecimal stockValue,
        BigDecimal netProfitMtd, long invoiceCountToday) {}

public record CompanyLineDto(String companyUid, String companyName, String baseCurrency,
                             BigDecimal salesToday, BigDecimal salesMtd,
                             BigDecimal netProfitMtd, BigDecimal cashOnHand,
                             boolean allChecksTie) {}

public record BranchLineDto(String branchUid, String branchCode, String branchName,
                            String companyUid, BigDecimal salesToday,
                            long invoiceCount, BigDecimal deltaVsLastWeekPct) {}

public record HealthChipDto(String key, String label, boolean ties,
                            BigDecimal difference, String severity, String companyUid) {}
```
*Implementation intent:* one `SUM … GROUP BY company_id, branch_id` over the sales-invoice tables for the day/MTD windows; one `GROUP BY company_id` over `journal_lines` (the `AccountMovementQuery` shape widened from `l.companyId = :companyId` to `IN (:companyIds)` — see `:57, 81, 107, 139`); one over the AR open items; one over stock on hand. **A small fixed number of queries, one round trip.** It must **never** call `TrialBalanceQuery` or `StockValuationQuery.report`, and it must **never** reach across the module boundary to another module's entity or service — the roll-up belongs behind a BI-module query returning `..domain.dto..` types (`ModuleBoundaryTest` enforces this).

```java
// ── N2 · Cross-company approvals inbox (PROPOSED) ───────────────────────────
// GET /api/v1/mobile/exec/approvals/inbox?organisationUid={uid}&page&size
// Approve/reject stay on the existing uid-scoped POST endpoints — no change there.
public record ExecInboxRowDto(
        String companyUid, String companyName,
        String uid, String requestNumber, String documentType, String documentUid,
        BigDecimal amount, String currency,
        String branchName, String branchCode,          // both already on ApprovalRequestDto
        String summary, String submittedByName, Instant submittedAt,
        int waitingDays,                               // computed server-side; no age field exists today
        String openStepRoleName, int openStepSequence, int totalSteps) {}
```
⚠ `ApprovalRequestDto.currentStepSequence` **is always null in practice**: the field is declared on the entity (`ApprovalRequest.java:66`) and read into the DTO (`ApprovalEngineImpl.java:260`), but `grep -rn "setCurrentStepSequence"` returns **zero writers**. N2 must derive the open step from `steps[]` (lowest-sequence `PENDING`) server-side, not trust that field.

```java
// ── N3 · Top-N leaderboards (PROPOSED) ─────────────────────────────────────
// GET /api/v1/mobile/exec/top?companyId&branchUid?&from&to
//     &dimension=PRODUCT|CUSTOMER|SUPPLIER|AGENT|ROUTE|BRANCH&metric=REVENUE|MARGIN|QTY&limit=10
public record TopNDto(String dimension, String metric, String currency,
                      LocalDate fromDate, LocalDate toDate,
                      String branchLabel,        // ALWAYS echoed — SalesReportDto's omission
                      BigDecimal grandTotal, List<TopNRowDto> rows) {}
public record TopNRowDto(String uid, String code, String label,
                         BigDecimal value, BigDecimal sharePct, long docCount) {}
```
*`ORDER BY … DESC LIMIT :limit` in SQL. **Must ship with the shared `assertMayReadBranch` guard from G3** — today the only implementation is private to `ProductStockReportQuery`.*

```java
// ── N4 · Totals-only stock (PROPOSED) ──────────────────────────────────────
// GET /api/v1/mobile/exec/stock-summary?companyId&branchUid?
public record StockSummaryDto(String companyUid, String branchLabel, String currency,
                              BigDecimal totalQuantity, BigDecimal totalCostValue,
                              BigDecimal totalSaleValue, BigDecimal potentialMargin,
                              int unvaluedItems, int unpricedItems, int discontinuedItems,
                              int skuCount,
                              // Echo the SHIPPED three-outcome contract, not a boolean:
                              StockValuationReconDto.Status glStatus,   // TIED | OUT_OF_BALANCE
                                                                        // | GL_ACCOUNT_NOT_CONFIGURED
                              BigDecimal glDifference, String glMessage,
                              List<TopNRowDto> topByValue) {}           // limit 10

// ── N5 · Statement summaries, each block nullable when its perm is absent (PROPOSED)
// GET /api/v1/mobile/exec/statements/summary?companyId&fromDate&toDate&compareAsAt?
public record StatementSummaryDto(
        StatementHeaderDto header,                     // reuse the shipped reporting DTO
        List<SectionTotalDto> plSections,              // REVENUE / COST_OF_SALES / OPERATING_EXPENSES
        AmountPairDto grossProfit, AmountPairDto netProfit,
        List<SectionTotalDto> bsSections,              // CURRENT_ASSETS / NON_CURRENT_ASSETS /
                                                       // CURRENT_LIABILITIES / NON_CURRENT_LIABILITIES / EQUITY
        AmountPairDto totalAssets, AmountPairDto totalLiabilities, AmountPairDto totalEquity,
        List<SectionTotalDto> cfSections,              // OPERATING / INVESTING / FINANCING
        AmountPairDto openingCash, AmountPairDto netChangeInCash, AmountPairDto closingCash,
        boolean plTies, boolean bsTies, boolean cfTies) {}
public record SectionTotalDto(StatementSection sectionKey, String title, AmountPairDto subtotal) {}
// StatementSection is the SHIPPED enum with exactly those 11 values.

// ── N6 · Receivables / payables, branch-aware, top-N (PROPOSED) ────────────
// GET /api/v1/mobile/exec/receivables?companyId&branchUid?&asAt&limit=10
public record ReceivablesSummaryDto(String companyUid, String branchLabel, LocalDate asAt,
                                    String currency, BigDecimal total,
                                    List<ArAgeingRowDto> buckets,        // reuse, 5 rows, D90_PLUS last
                                    List<ArCustomerAgeingRowDto> worst,  // reuse, LIMIT :limit
                                    int customersWithBalance,
                                    BigDecimal glControlBalance, boolean ties) {}
// Mirror: PayablesSummaryDto — this is NEW capability, not a projection. No company-wide AP ageing
// and no by-supplier AP ageing exists (ApStatementController.java:47 requires supplierId AND asAt).

// ── N7 · One health board (PROPOSED) ───────────────────────────────────────
// GET /api/v1/mobile/exec/health?companyId
public record HealthBoardDto(String companyUid, List<HealthChipDto> chips,
                             boolean allTie, Instant generatedAt) {}
// The 5 shipped checks, by their existing labels: "TB", "Cash vs GL", "AR vs GL 1200",
// "AP vs GL 2100", "Stock vs GL 1300" — plus, optionally, the WIP tie from
// /manufacturing/wip-reconciliation, gated separately on MANUFACTURING.VIEW.

// ── N8 · Budget variance, worst-N (PROPOSED) ───────────────────────────────
// GET /api/v1/mobile/exec/budget-variance?companyId&fiscalYearUid&fromPeriodNo&toPeriodNo
//     &limit=10&sort=ABS_VARIANCE
public record BudgetVarianceSummaryDto(
        VarianceReportDto.HeaderDto header,        // reuse the SHIPPED nested header,
                                                   // incl. its noApprovedBudget flag
        Map<AccountType, BigDecimal> totalsBudgetByType,   // keyed by AccountType, as shipped
        Map<AccountType, BigDecimal> totalsActualByType,
        Map<AccountType, BigDecimal> totalsVarianceByType,
        List<WorstVarianceRowDto> worst) {}
public record WorstVarianceRowDto(String accountCode, String accountName,
                                  String costCentreValueName, BigDecimal budgetAmount,
                                  BigDecimal actualAmount, BigDecimal variancePct) {}

// ── N9 · Branch day-close roll-up (PROPOSED) ───────────────────────────────
// GET /api/v1/mobile/exec/till-summary?companyId&branchUid?&date
public record TillDaySummaryDto(String branchLabel, LocalDate date,
                                BigDecimal totalSales, BigDecimal totalCashExpected,
                                BigDecimal totalCashCounted, BigDecimal totalVariance,
                                int sessionsOpen, int sessionsClosed, int sessionsUnreconciled,
                                List<TillLineDto> tills) {}
public record TillLineDto(String sessionUid, String tillName, String cashierName,
                          String status, BigDecimal totalSalesAmount, BigDecimal varianceAmount) {}

// ── N10 · Branch P&L  ⚠ NEEDS ITS OWN ADR (PROPOSED) ───────────────────────
// GET /api/v1/mobile/exec/branch-pnl?companyId&branchUid&fromDate&toDate
// Adds a branchId predicate to AccountMovementQuery.periodMovementByAccountType (:96-127).
// The index journal_lines(company_id, branch_id) — V10__general_ledger.sql:331 — already supports it.
public record BranchPnlDto(String branchUid, String branchName, StatementHeaderDto header,
                           List<SectionTotalDto> sections,
                           AmountPairDto grossProfit, AmountPairDto netProfit,
                           BigDecimal unbranchedExcludedAmount,   // MUST be shown, not hidden
                           String scopeNote) {}
```
⚠ The ADR must state honestly what a branch P&L **excludes**: year-end close posts its journal with `branchId = null` deliberately (`YearEndCloseServiceImpl.java:178`, commented "branchId null — company-level journal"), and `journal_lines.branch_id` is nullable throughout (`V10:225`). Every unbranched line therefore vanishes from the sum of branch P&Ls. `unbranchedExcludedAmount` is not optional — without it the branch P&Ls will not add up to the company P&L and someone will call it a bug.

```java
// ── N11 · Open purchase commitments (LPO) (PROPOSED) ───────────────────────
// GET /api/v1/mobile/exec/purchase-commitments?companyId&branchUid?&limit=10
public record PurchaseCommitmentDto(String companyUid, String branchLabel, String currency,
                                    BigDecimal orderedNotReceived, BigDecimal receivedNotBilled,
                                    int openOrderCount, int overdueCount,
                                    BigDecimal awaitingApprovalValue, int awaitingApprovalCount,
                                    List<TopNRowDto> topSuppliers) {}    // by open value

// ── N12 · Payroll cost (PROPOSED) ──────────────────────────────────────────
// GET /api/v1/mobile/exec/payroll-summary?companyId&branchUid?&months=6
public record PayrollSummaryDto(String companyUid, String branchLabel, String currency,
                                String latestRunNumber, short periodYear, short periodMonth,
                                String status, LocalDate payDate,
                                BigDecimal grossTotal, BigDecimal deductionTotal,
                                BigDecimal netTotal, BigDecimal employerCostTotal,
                                BigDecimal deltaVsPriorMonthPct,
                                int employeeCount,                 // NOT on PayrollRunDto — derived
                                List<TrendPointDto> employerCostTrend) {}  // reuse the BI TrendPointDto
                                                                           // {periodLabel, periodStart,
                                                                           //  periodEnd, value}

// ── N13 · Production (PROPOSED) ────────────────────────────────────────────
// GET /api/v1/mobile/exec/production-summary?companyId
public record ProductionSummaryDto(String companyUid, String currency,
                                   Map<String, Integer> workOrdersByStatus,
                                   BigDecimal wipComputed, BigDecimal wipExpected, boolean wipTies,
                                   int overdueWorkOrders,
                                   List<TopNRowDto> topOpenByValue) {}

// ── N14 · Slow movers / dead stock — NO prior art, new query (PROPOSED) ────
// GET /api/v1/mobile/exec/slow-movers?companyId&branchUid?&days=90&limit=20
public record SlowMoverSummaryDto(String companyUid, String branchLabel, String currency,
                                  int thresholdDays, int skuCount, BigDecimal tiedUpValue,
                                  BigDecimal pctOfTotalStockValue,
                                  List<SlowMoverRowDto> rows) {}
public record SlowMoverRowDto(String productUid, String productCode, String productName,
                              BigDecimal quantityOnHand, BigDecimal costValue,
                              LocalDate lastSoldOn, Integer daysSinceLastSale,
                              boolean neverSold, boolean discontinued) {}

// ── N15 · CRM, slimmed (PROPOSED) ──────────────────────────────────────────
// GET /api/v1/mobile/exec/crm-summary?companyId&branchUid?&from&to
public record CrmMobileDto(String branchLabel, String currency,
                           BigDecimal openPipelineValue, BigDecimal weightedForecastValue,
                           long openCount,                 // ForecastDto.openCount is a long
                           BigDecimal winRatePercent,
                           BigDecimal avgCycleDays) {}     // BigDecimal on CrmKpiDto, not Integer
```

**Rules binding every DTO above.**
- **Null ≠ 0.** Keep the server's existing graceful-degrade contract (`DashboardServiceImpl.java:185-195`): a null block means *forbidden or failed*. Rendering a forbidden panel as `0` is the mobile version of a wrong number.
- **Wire types.** `Long` ids serialise as JSON **strings** (global Jackson config, CLAUDE.md invariant 3); `BigDecimal` money serialises as JSON **numbers**. In Dart, typing money as `int` crashes at runtime — type ids `String`, money `num`. Better still: **drop numeric ids from mobile DTOs entirely** and address by `uid` (note `BranchSalesRowDto.branchId` and `CashAccountBalanceDto.cashBankAccountId` are numeric ids on shipped DTOs — do not copy that).
- **Every list takes `limit`, capped server-side.** No new unbounded endpoint ships. Ever.
- **Every DTO echoes its scope** — `branchLabel` on all of them, including when it is `"All branches"` (the shipped constant at `DashboardServiceImpl.java:97`; `"Unknown branch"` at `:108` for a foreign id). `ProductStockReportDto.branchName` is the good precedent; `SalesReportDto`'s missing `branchName` is the mistake not to repeat.
- **`generatedAt` is the truth of the figure, not of the response.** If a cache is added in front of these, `generatedAt` must be the cache entry's creation time, not `Instant.now()` — otherwise the honest staleness UI becomes a lie.
- **No cross-module imports.** These DTOs reuse `ArAgeingRowDto`, `TrendPointDto`, `StatementSection`, `VarianceReportDto.HeaderDto` and friends — all of which live under `..domain.dto..` / `..domain.enums..` and are therefore legal cross-module surface. Reaching for an entity or a service from another module fails `ModuleBoundaryTest`.

---

### 5. Export & share from the phone

#### 5.1 What exists — 12 report/dashboard exports, verified handler by handler

Every one is gated on **its own view permission AND an export permission**. That double gate is deliberate and documented in the code — `ReportingController.java:63-65`: *"A download discloses strictly more than one on-screen page, so it must never be reachable by a caller the screen itself refuses."* Same reasoning at `BiDashboardController.java:143-145` and `SalesReportController.java:61-64`.

| Export | Path | Gate |
|---|---|---|
| BI dashboard | `GET /api/v1/bi/dashboard/export?companyId&from&to&branchId&format` `BiDashboardController.java:146-153` | `BI.VIEW` **and** `BI.EXPORT` — ⚠ **`BI.EXPORT` is granted to no seeded role (G8)** |
| Income statement | `GET /api/v1/reports/income-statement/export` `ReportingController.java:66-74` | `REPORT.PL.VIEW` **and** `REPORT.EXPORT` (seed:184) |
| Balance sheet | `…/balance-sheet/export` `:92-98` | `REPORT.BS.VIEW` **and** `REPORT.EXPORT` |
| Cash flow | `…/cash-flow/export` `:118-126` | `REPORT.CASHFLOW.VIEW` **and** `REPORT.EXPORT` |
| Account ledger | `…/account-ledger/export` `:147-154` — **capped at `LEDGER_EXPORT_MAX_ROWS = 10_000`** (`:158, 163`, NFR-REP-02) | `REPORT.LEDGER.VIEW` **and** `REPORT.EXPORT` |
| Sales report | `GET /api/v1/reports/sales/export` `SalesReportController.java:66-75` (companyId from `RequestContext`) | `SALES.INVOICE.VIEW` **and** `REPORT.EXPORT` |
| Product list | `GET /api/v1/stock/reports/product-list/export` `ProductStockReportController.java:62-67` | `INVENTORY.VALUATION.VIEW` **and** `REPORT.EXPORT` |
| Stock value | `GET /api/v1/stock/reports/stock-value/export` `:82-87` | same |
| Stock register | `GET /api/v1/stock/report/export` `StockReportController.java:64-69` | same |
| Stock movement | `GET /api/v1/reports/stock-movement/export` `StockMovementReportController.java:92-100` | same |
| Stock valuation | `GET /api/v1/stock/valuation/report/export` `StockValuationController.java:120-124` (same `asOf` guard) | same |
| Trial balance | `GET /api/v1/gl/trial-balance/export` `TrialBalanceController.java:71-76` | `GL.VIEW` **and** `REPORT.EXPORT` |

- **Formats:** `enum ExportFormat { PDF, XLSX, CSV }` (`modules/reporting/domain/enums/ExportFormat.java`), `?format=PDF` by default on every handler.
- **Transport:** `ResponseEntity<byte[]>` with `Content-Disposition: attachment; filename="…"` (`ReportingController.java:167-173`) — and, critically for a Dart client, these **pass through `ApiResponseAdvice` unwrapped**. The exec app's `ApiClient` must have a `SKIP_UNWRAP` equivalent: the POS's `_send` (`pos_app/lib/core/api/api_client.dart:124-127`) calls `unwrapData(res.data)` unconditionally and would corrupt a byte stream.
- **Branded PDFs of *documents*** (not reports) exist separately: `GET /api/v1/documents/render?type=&source=&params=` (`DocumentController.java:54-70`, `DOCUMENT.RENDER`, seed:84) and `GET /api/v1/documents/uid/{uid}/download` (`:77-88`, `@perm.scoped`). Renderable set: `INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE, QUOTATION`; `PAYSLIP` and `DEBIT_NOTE` are declared but reserved (`modules/documents/domain/enums/DocumentType.java:5-6, 50, 53`).

#### 5.2 What the phone does with that

**Rule: an export is a share-sheet action, never an in-app view.** No PDF viewer, no XLSX preview.

| Flow | Behaviour |
|---|---|
| Tap `Share this report` | Confirm sheet naming the cost — `This will use about 400 KB` **(size estimate is UNVERIFIED; measure per report before wording it)** — then `GET …/export?format=PDF` with a long timeout and a visible progress state |
| On bytes received | Write to the app's cache dir, hand the path to `share_plus` → the OS share sheet → WhatsApp, Gmail, Drive, Files |
| WhatsApp | Works **with zero backend work** — it is just the OS share sheet. Highest value, lowest cost item in this section |
| Email | Same path, via the OS mail client, **from the executive's own account** |
| L4 stop card | Its `Share the PDF` button is exactly this flow |

**Guard rails.** (a) The app must check `REPORT.EXPORT` (and `BI.EXPORT` — which today **nobody holds**, G8) in its permission set before rendering the button; a share button that 403s reads as "the app is broken". (b) The generated file is written to the **cache** directory, not documents, and deleted after the share intent returns — a consolidated P&L must not persist in a folder that lands in a device backup. (c) `FLAG_SECURE` on Android suppresses screenshots on the P&L and payroll screens but has **no iOS equivalent**; the share sheet is the sanctioned egress and should be the only one. (d) The client must **not** unwrap `ApiResponse` on these responses.

#### 5.3 What is missing

| Gap | Evidence | Consequence |
|---|---|---|
| **No budgeting export exists at all.** `BudgetReportController` has two `@GetMapping`s (`/variance` `:41`, `/departmental-actuals` `:59`) and no export handler; `BUDGETING.REPORT.EXPORT` (seed:52, granted to `FINANCE_DIRECTOR` at `:882`) is referenced by **zero** Java files | grep, as cited | The earlier draft listed budgeting exports as shipped. They are not. Budget variance is `Open on desktop` only, or wait for **N8** |
| **`BI.EXPORT` is unreachable.** Seeded at `:39`, used by one handler, granted to no role | grep | The dashboard export 403s for every non-root user until a grant is seeded (owner-approval item) |
| **No server-side email of a report.** The only mail path is `EmailSender`, constructed **non-multipart**: `new MimeMessageHelper(msg, false, "UTF-8")` (`modules/notifications/service/EmailSender.java:60`), body set with `helper.setText(notification.getBody(), false)` (`:63`) — **attachments are structurally impossible** | as cited | "Email this to my accountant every Monday" cannot be built without making `EmailSender` multipart **and** adding a scheduled-report concept. Neither exists. Out of scope for v1; say so to the owner |
| **No scheduled / subscribed reports.** No subscription table, no scheduler | grep | The `APPROVAL_PENDING` notification (`ApprovalSubmittedNotificationHandler.java:18-35`) is the only proactive channel, and it has no push sender — `NotificationChannel.PUSH` is `/** Reserved — not sent in v1 */` (`modules/notifications/domain/enums/NotificationChannel.java`), alongside `SMS` and `WEBHOOK` |
| **No export for the majority of report endpoints** — AR ageing, AR by-customer, AR statement, AP ageing/recon, cash balances, cash-GL recon, WIP recon, project WIP/P&L, CRM pipeline/forecast/KPIs, work-order cost report, sliced trial balance, budgeting (both) | no `/export` sibling in those controllers | Their stop cards can offer **only** `Open on desktop`. Adding exports is mechanical (`TabularExporter` / `StatementModelFlattener` + the existing flatteners) but is real work, and each new handler needs its own `@PreAuthorize` naming a **seeded** code |
| **No group-level export.** Every export takes one `companyId` (or reads one from `RequestContext`) | `ScopeGuard.java:708` | A group P&L pack cannot be produced from the phone or anywhere else. **N1** gives group *numbers*; a group *document* is a separate, larger piece of work and should not be promised in v1 |
| **The sales and stock exports are unbounded** — the same missing `LIMIT` as their JSON siblings | `SalesReportQuery` (no `LIMIT`, no `Pageable`); `StockValuationQuery`, `ProductStockReportQuery`, `StockReportQuery`, `TrialBalanceQuery` likewise | An export tapped over 3G at supermarket volume is a multi-MB download and a long server hold. Cap them or put a row-count warning in the confirm sheet, the way the ledger export is already capped at 10 000 rows |
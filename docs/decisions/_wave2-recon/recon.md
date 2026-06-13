# Wave 2 (BI + FE-polish) — codebase recon (drives ADR-0037)


## reporting-export-infra

## How a read-only report is built (no business tables â€” reads over GL journal_lines / accounts)

The reporting module (`com.erp.modules.reporting`, ADR-0018, FR-REP-01..08) is strictly read-only: it owns NO business table and posts nothing (BR-REP-08). Every statement is computed on demand from the GL.

**Layered structure (controller -> orchestrating service -> builders/queries):**
- `ReportingController` (`com.erp.api.ReportingController`, `@RequestMapping("/api/v1/reports")`) â€” 4 read endpoints + 4 `/export` siblings (income-statement, balance-sheet, cash-flow, account-ledger). Thin: delegates to `ReportingService`, then for exports calls `flattener.flatten(dto)` -> `exporter.export(model, format)` -> `download(...)`.
- `ReportingService` / `ReportingServiceImpl` (`com.erp.modules.reporting.service`) â€” `@Service @Transactional(readOnly = true)`. Orchestrates only: calls `scopeGuard.assertCanActIn(...)` FIRST on every method (before any data access), resolves the comparative window via `ComparativeWindowResolver` (default = prior period of equal length, D-8), resolves company name via `CompanyRepository`, then delegates SQL work to the builders/query.
- Builders: `IncomeStatementBuilder`, `BalanceSheetBuilder`, `CashFlowStatementBuilder` (all `@Component`); drill-down query `AccountLedgerQuery`; shared low-level reads `AccountMovementQuery`. Plus pure helpers `StatementClassifier`, `CashEquivalentAccountResolver`, `ComparativeWindowResolver`.

**The GL read pattern (the core reuse target):** `AccountMovementQuery` (`@Component @Transactional(readOnly=true)`) is the single low-level read surface. It uses `EntityManager` JPQL that ALWAYS aggregates in SQL (`GROUP BY`), never summing raw lines in Java (NFR-REP-02). It reads over GL entities `JournalLine l JOIN JournalEntry e ON e.id = l.entryId` (and `JOIN ChartOfAccount a` for type rollups), always filtered `WHERE l.companyId = :companyId` and a posting-date window. Methods: `periodMovementByAccount` (Map<accountId,[sumDebit,sumCredit]> over [from,to]), `cumulativeByAccountAsAt` (inception->asAt), `periodMovementByAccountType` / `cumulativeByAccountTypeAsAt` (Map<AccountType,[d,c]>), `netIncomeForPeriod` (equity-fold), and `accountMapForCompany` (pre-fetch ChartOfAccount once to avoid N+1). It re-calls `scopeGuard.assertCanActIn` at the top of every method (defense in depth). Reading GL repositories from reporting is the documented cross-module read allowance (D-12, same stance as `CashGlReconciliationQuery`).

`IncomeStatementBuilder.build(...)` shows the assembly idiom: fetch account map + period movements + type-level aggregates; classify each INCOME/EXPENSE account into a `StatementSection` via `StatementClassifier`; compute presented amounts honoring `normalBalance` (CREDIT-normal: credit-debit; DEBIT-normal: debit-credit); build sections/subtotals/grossProfit/netProfit; then attach a self-check `ReconciliationDto`.

`AccountLedgerQuery.query(...)` is the one inherently row-by-row read â€” it is paginated (page/size, `setFirstResult`/`setMaxResults`) to bound memory (NFR-REP-02), resolves `accountUid` via `accountRepo.findByUid` then enforces `account.getCompanyId().equals(companyId)` (throws `NotFoundException` if cross-tenant â€” avoids leaking existence), computes opening/closing balance via `cumulativeByAccountAsAt`, and walks the page accumulating running balance.

**Self-reconciliation:** every statement DTO carries a `ReconciliationDto` (`label, computed, expected, difference, ties`). `ReconciliationDto.of(label, computed, expected)` does BigDecimal-exact `compareTo == 0` to set `ties`. Never plugged â€” a `ties=false` is surfaced as a data-integrity alarm, not corrected. P&L check: netProfit == period INCOME - EXPENSE movement (BR-REP-03).

## How scope/tenant (assertCanActIn) is enforced

- Single home: `ScopeGuard` (`com.erp.platform.security.ScopeGuard`, `@Component`). `assertCanActIn(RequestContext.Principal, Long companyId)` throws `ForbiddenException.notPermitted()` (403) unless `canActIn` is true: `principal.root() || companyId.equals(principal.companyId())`. Root acting OUTSIDE its active company emits an independent `ROOT_BYPASS` audit row via `audit.recordIndependent(...)` (REQUIRES_NEW so it commits even inside a readOnly tx â€” ISSUES-REGISTER #11).
- Principal comes from `RequestContext.get()` â€” a request-scoped `ThreadLocal<Principal>` (userId, username, root, companyId, branchId, ip) populated by RequestContextFilter from the JWT.
- Enforcement is layered: (1) `@PreAuthorize("@perm.has('REPORT.PL.VIEW')")` etc. on each controller method (the `@perm` bean is `PermissionChecks`); export endpoints all gate on `REPORT.EXPORT`; (2) `ReportingServiceImpl` calls `assertCanActIn` first on every method; (3) `AccountMovementQuery` / `AccountLedgerQuery` re-assert at every query. companyId is an explicit `@RequestParam` on every endpoint, not inferred.

## How export (CSV / XLSX / PDF) is wired and reused

Two-stage pipeline, statement-agnostic â€” this is the key reuse seam for a new BI module:
1. **Flatten:** `StatementModelFlattener` (`@Component`) converts each typed statement DTO into a single generic `StatementRenderModel` (record: title, companyName, currency, periodLabel, comparativeLabel, generatedAt, `List<Row>`). `Row` (nested record) has `RowType` enum { SECTION_HEADER, LINE, SUBTOTAL, TOTAL, RECONCILIATION }, label, current/comparative BigDecimals, plus reconciliationBar/ties flags. Factory helpers: `Row.line/sectionHeader/subtotal/total/reconciliation`. It has one overload per DTO type (`flatten(IncomeStatementDto)`, `flatten(BalanceSheetDto)`, etc.).
2. **Export:** `ReportExporter` (`@Component`) â€” facade `export(StatementRenderModel, ExportFormat) -> ExportResult`. Switches on the `ExportFormat` enum { PDF, XLSX, CSV } (`com.erp.modules.reporting.domain.enums.ExportFormat`) and delegates to one of three renderers, deriving a slug filename from the title and stamping the MIME type. `ExportResult` is a record `{ byte[] content, String contentType, String filename }`.

**The three renderers (each `@Component`, all consume the generic `StatementRenderModel`):**
- `CsvStatementRenderer` â€” no external dep; UTF-8; CSV-escape + CSV/Excel formula-injection guard (`'`-prefix on cells starting with `= + - @ \t \r`) on TEXT cells only; numeric amounts via `toPlainString()` (keeps leading `-`).
- `XlsxStatementRenderer` â€” Apache POI (`XSSFWorkbook`); amounts written as real numbers; header rows + frozen pane at the column-heading row; section headers shaded, subtotals/totals bold, reconciliation green/rose.
- `PdfStatementRenderer` â€” OpenPDF (`com.lowagie.text`); A4, 3-column table (Description/current/comparative), styled per RowType, reconciliation row green `[OK]` / red `[!]`.

**HTTP wiring:** export endpoints return `ResponseEntity<byte[]>` built by the controller's private `download(ExportResult)` helper, which sets `Content-Type` (from `result.contentType()`) and `Content-Disposition: attachment; filename="..."`. Account-ledger export caps rows at `LEDGER_EXPORT_MAX_ROWS = 10_000` (NOT Integer.MAX_VALUE) to avoid OOM (NFR-REP-02).

`ApiResponseAdvice` (`@RestControllerAdvice(basePackages="com.erp.api")`) wraps controller returns into `ApiResponse`, but explicitly PASSES THROUGH `byte[]` and `Resource` (and `String`) un-wrapped (lines 43-45) so binary downloads keep their own headers â€” a BI module reusing this pipeline gets that pass-through for free.

## ApiResponse / DTO shape convention

- `ApiResponse<T>` (`com.erp.platform.common.api.ApiResponse`, record `{ T data, List<String> errors, Object meta }`) is the project response envelope (PROJECT-CONVENTIONS Â§3.1). Controllers normally return the raw payload and `ApiResponseAdvice` wraps it.
- NOTE the reporting controller is INCONSISTENT with the rest of `com.erp.api`: `ReportingController` read methods return the bare DTO (e.g. `IncomeStatementDto`) and let the advice wrap it into `ApiResponse` â€” whereas peer report controllers like `DimensionReportController` explicitly call `ApiResponse.ok(...)`. The web `reporting.service.ts` comment confirms the client expects the DTO to arrive (its interceptor unwraps the envelope by detecting the `errors` array). A new BI controller should follow the `ApiResponse.ok(...)` style (DimensionReportController) for consistency; either way the advice handles it.
- All statement DTOs are immutable Java records under `com.erp.modules.reporting.domain.dto`: every one carries `StatementHeaderDto` (companyId, companyName, currency, periodLabel, comparativeLabel, fromDate, toDate, asAtDate, generatedAt) + sections + totals + a `ReconciliationDto`. Money is `BigDecimal`, base-currency only (BR-REP-09), and every figure is an `AmountPairDto` (current, comparative) with null-safe `of`/`zero` factories. `StatementSectionDto` (sectionKey, title, lines, subtotal) holds `StatementLineDto` (accountId/uid/code/name + AmountPairDto; synthetic lines carry null account fields).
- Dates are `LocalDate` `@RequestParam @DateTimeFormat(iso = ISO.DATE)`; comparative params are `required = false` (service fills defaults). `ExportFormat format` is a `@RequestParam(defaultValue = "PDF")`.

## Web (Angular) reuse pattern
`web/src/app/features/admin/reporting/` has one component per statement + `reporting.service.ts`. Reads call `http.get<Dto>(...)`; exports call `http.get(..., { responseType: 'blob' })` and the blob triggers a browser download. A BI module's frontend should mirror this (blob download for exports).

### Touch points
- REUSE AS-IS for BI exports: ReportExporter.export(StatementRenderModel, ExportFormat) + the three renderers (CsvStatementRenderer, XlsxStatementRenderer, PdfStatementRenderer) + ExportResult + ExportFormat enum â€” a BI module should build a StatementRenderModel (or extend the model/flattener with new flatten(...) overloads for analytics DTOs) and call exporter.export(...) rather than writing its own PDF/XLSX/CSV code (ADR-0018 D-9).
- REUSE the StatementRenderModel/Row generic flat model: it is statement-agnostic (header + ordered typed rows). BI tables/KPI grids can be flattened into Rows (SECTION_HEADER/LINE/SUBTOTAL/TOTAL). If BI needs richer shapes (e.g. multi-column pivots, charts), this is where the model must be extended â€” current Row supports exactly current+comparative numeric columns.
- REUSE the GL read idiom in AccountMovementQuery: SQL GROUP BY aggregation over JournalLine JOIN JournalEntry (+ ChartOfAccount), companyId-filtered, posting-date windowed, returning Map<key,[debit,credit]>. A BI/analytics query layer should follow this exact pattern (EntityManager JPQL, aggregate in SQL, never sum raw lines in Java â€” NFR-REP-02) and the cross-module GL read allowance (D-12).
- ENFORCE tenant scope identically: call scopeGuard.assertCanActIn(RequestContext.get(), companyId) FIRST in every BI service method (before data access) and at every query method; gate controllers with @PreAuthorize("@perm.has('...')") and take companyId as an explicit @RequestParam. Add a BI.* permission family (mirroring REPORT.*.VIEW / REPORT.EXPORT) for view + export.
- FOLLOW the controller download() pattern: BI export endpoints return ResponseEntity<byte[]> with Content-Type + Content-Disposition; ApiResponseAdvice already passes byte[]/Resource through un-wrapped. Apply a row cap (cf. LEDGER_EXPORT_MAX_ROWS=10_000) on any unbounded BI export to bound memory.
- FOLLOW DTO conventions: immutable records under a domain/dto package, StatementHeaderDto-style header, BigDecimal money (base currency), AmountPairDto for dual-period figures, and a ReconciliationDto self-check where a BI aggregate can be tied back to GL (ties via BigDecimal compareTo==0; never plug). Prefer ApiResponse.ok(...) in the BI controller (DimensionReportController style) for envelope consistency.
- FOLLOW the web pattern: per-report Angular components + a service using http.get<Dto> for reads and responseType:'blob' for exports (web/src/app/features/admin/reporting/).
- a11y NOTE: PDF/XLSX renderers encode meaning via color (recon green/rose) plus a text prefix ([OK]/[!]) â€” preserve the text-prefix + non-color cue when extending renderers so accessibility is not color-only.

### Files of interest
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\api\ReportingController.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\service\ReportingService.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\service\ReportingServiceImpl.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\service\AccountMovementQuery.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\service\AccountLedgerQuery.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\service\IncomeStatementBuilder.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\service\StatementClassifier.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\export\ReportExporter.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\export\StatementRenderModel.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\export\StatementModelFlattener.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\export\ExportResult.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\export\CsvStatementRenderer.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\export\XlsxStatementRenderer.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\export\PdfStatementRenderer.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\domain\enums\ExportFormat.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\domain\dto\StatementHeaderDto.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\domain\dto\AmountPairDto.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\domain\dto\ReconciliationDto.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\domain\dto\StatementSectionDto.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\modules\reporting\domain\dto\StatementLineDto.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\ScopeGuard.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\security\RequestContext.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\common\api\ApiResponse.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\platform\common\api\ApiResponseAdvice.java
- D:\My_Works\ERP\ERPCLEAN2\backend\src\main\java\com\erp\api\DimensionReportController.java
- D:\My_Works\ERP\ERPCLEAN2\web\src\app\features\admin\reporting\reporting.service.ts

## gl-subledger-read-primitives

RECON SUMMARY: ERPCLEAN2 already has a rich, consistent set of on-demand read aggregates. There is NO existing BI/analytics/dashboard module (backend `com.erp.modules.{bi,analytics,dashboard}` absent; no `web/src/app/**/dashboard*`). All reads are computed on demand from the GL/sub-ledgers â€” nothing is materialized. BI must reuse these query beans, not re-query journal_lines.

=== THE CORE GL READ PRIMITIVE: JournalLineRepository ===
`com.erp.modules.gl.repository.JournalLineRepository` (extends JpaRepository<JournalLine,Long>) is the single GL aggregate hub. Methods (all company-scoped via WHERE l.companyId = :companyId):
- `List<Object[]> trialBalanceSums(companyId)` â€” GROUP BY accountId, SUM(debit)/SUM(credit). Object[] = [accountId, totalDebit, totalCredit]. Hits ix_journal_lines_company_account.
- `List<Object[]> trialBalanceSumsByPeriod(companyId, periodId)` â€” same, JOIN JournalEntry on e.fiscalPeriodId = :periodId. THE time-series hook for per-period TB.
- `Object[] grandTotals(companyId)` â€” [SUM(debit), SUM(credit)] for TB-nets-to-zero assertion.
- `BigDecimal accountBalance(companyId, accountId)` â€” SUM(debit) - SUM(credit) for ONE account; returns null when no lines (callers coalesce to ZERO). This is the cross-module leaf reader used by every recon (Cash, Stock, WIP, FixedAsset).
- `List<Object[]> periodMovementByAccount(companyId, fromDate, toDate)` â€” JOIN JournalEntry, WHERE e.postingDate BETWEEN :from AND :to, GROUP BY accountId â†’ [accountId, sumDebit, sumCredit]. Date-windowed movement (used by Year-End Close).
JournalLine entity (`gl.domain.entity.JournalLine`, table journal_lines, append-only) carries the dimension columns BI can slice on: company_id, branch_id (nullable analysis tag), account_id, debit_amount/credit_amount (precision 19 scale 4), cost_centre_value_id, department_value_id, dimension3_value_id, dimension4_value_id (all nullable FKâ†’dimension_values), project_id, project_task_id, project_cost_type. JournalEntry holds posting_date, fiscal_period_id, source_type, source_ref, entry_no.

=== THE REPORTING ENGINE (richest reuse target): AccountMovementQuery ===
`com.erp.modules.reporting.service.AccountMovementQuery` â€” the dedicated SQL-aggregate engine for all financial statements (ADR-0018 D-3). Uses EntityManager JPQL, NEVER sums raw lines in Java (NFR-REP-02). assertCanActIn on EVERY method. Methods:
- `Map<Long,BigDecimal[]> periodMovementByAccount(companyId, from, to)` â€” [debit,credit] per accountId in a window.
- `Map<Long,BigDecimal[]> cumulativeByAccountAsAt(companyId, asAtDate)` â€” inceptionâ†’asAt cumulative per account (balance-sheet stock figures + opening-balance computation).
- `Map<AccountType,BigDecimal[]> periodMovementByAccountType(companyId, from, to)` â€” P&L type-level rollup (the equity fold / net-income source). AccountType enum is ASSET/LIABILITY/EQUITY/INCOME/EXPENSE.
- `Map<AccountType,BigDecimal[]> cumulativeByAccountTypeAsAt(companyId, asAt)` â€” inception-to-date by type.
- `BigDecimal netIncomeForPeriod(companyId, from, to)` â€” INCOME net âˆ’ EXPENSE net (the single net-profit KPI primitive; INCOME=creditâˆ’debit, EXPENSE=debitâˆ’credit).
- `Map<Long,ChartOfAccount> accountMapForCompany(companyId)` â€” pre-fetch to avoid N+1 (mirrors TrialBalanceQuery).
- static `toBD(Object)` helper for null-safe numeric coercion.

`AccountLedgerQuery` (reporting.service) â€” the ONE inherently row-by-row read: paginated running-balance ledger for one accountUid over [from,to]; opening balance = cumulativeByAccountAsAt(fromâˆ’1). Page/size, totalCount. The drill-down target for any BI metric.

`ReportingService` / `ReportingServiceImpl` orchestrates: incomeStatement(companyId, from, to, cmpFrom, cmpTo), balanceSheet(companyId, asAt, compareAsAt), cashFlow(...), accountLedger(...). Builders: `IncomeStatementBuilder` (REVENUEâ†’COST_OF_SALESâ†’grossâ†’OPERATING_EXPENSESâ†’net; self-check BR-REP-03 netProfit==INCOMEâˆ’EXPENSE), `BalanceSheetBuilder` (inception-to-date equity fold split into prior-year retained vs current-year earnings; self-check ASSET==LIAB+EQUITY), `CashFlowStatementBuilder`, `StatementClassifier` (AccountType+accountCodeâ†’StatementSection), `ComparativeWindowResolver` (default comparative windows â€” prior period of equal length for flows, fromDateâˆ’1 for stock). Output DTOs: IncomeStatementDto, BalanceSheetDto, CashFlowStatementDto, AccountLedgerDto, with reusable StatementSectionDto/StatementLineDto/AmountPairDto(current,comparative)/ReconciliationDto(ties flag). Export pipeline (ReportExporter/CSV/PDF/XLSX renderers + StatementModelFlattener) BI can reuse for export.

=== TRIAL BALANCE: TrialBalanceQuery ===
`com.erp.modules.gl.service.TrialBalanceQuery` â€” compute(companyId) (all periods) + computeForPeriod(companyId, periodId). Returns TrialBalanceDto{companyId, List<TrialBalanceRowDto>, totalDebit, totalCredit}. TrialBalanceRowDto = (id, uid, accountCode, name, accountType, normalBalance, debit, credit, net). assertCanActIn first. AR/AP recon read GL control account net out of this DTO (filter by accountCode "1200"/"2100").

=== AR / AP SUB-LEDGER READS ===
- `ar.service.ArAgeingQuery`: ageing(companyId, customerId, asAt) â†’ List<ArAgeingRowDto>; statement(...) â†’ ArStatementDto; statementByCustomerUid(...). Buckets via shared enum `ar.domain.enums.AgeingBucket` {CURRENT, D1_30, D31_60, D61_90, D90_PLUS}. Source: ArInvoiceRepository.findOpenForStatement (status IN OPEN,PARTIAL); classify by ChronoUnit.DAYS(dueDate, asAt).
- `ar.service.ArReconciliationQuery`: reconcile(companyId) â†’ ArReconciliationDto{subLedger, glControl(acct 1200), difference, currency}. subLedger = ArInvoiceRepository.sumOutstandingByCompany âˆ’ ArReceiptRepository.sumUnallocatedByCompany.
- `ap.service.ApAgeingQuery`: ageing(companyId, supplierId, asAt) â†’ List<ApAgeingRowDto>; reuses AR's AgeingBucket. Source: SupplierBillRepository.findOpenForStatement (status IN MATCHED,APPROVED,PARTIALLY_PAID).
- `ap.service.ApReconciliationQuery`: reconcile(companyId) â†’ ApReconciliationDto{subLedger, glControl(acct 2100, .negate() since credit-normal), difference}. subLedger = SupplierBillRepository.sumOutstandingByCompany.
Repo aggregate methods (COALESCE(SUM,0)): ArInvoiceRepository.sumOutstandingByCompany / sumOutstandingByCompanyAndCustomer / findOverdueByCompany; SupplierBillRepository.sumOutstandingByCompany / sumOutstandingBySupplier.

=== CASH & BANK READS ===
- `cashbank.service.CashAccountStatementQuery`: getBalance(accountUid)â†’CashAccountBalanceDto; getStatement(accountUid)â†’CashAccountStatementDto (running balance); listBalances(companyId)â†’List<CashAccountBalanceDto>. Book balance from `CashTransactionRepository.bookBalance(accountId)` = SUM(CASE direction IN then +amount else âˆ’amount).
- `cashbank.service.CashGlReconciliationQuery`: reconcileAll(companyId)/reconcileOne(accountUid) â†’ CashGlReconciliationDto{bookBal, glBal(via JournalLineRepository.accountBalance on account.glAccountId), diff}.

=== STOCK / INVENTORY READS ===
- `stock.service.StockValuationQuery`: report(companyId) â†’ StockValuationReportDto{rows: List<StockValuationRowDto>(productId,uid,code,name,qty,avgCost,value,currency), totalValue, recon vs GL 1300}. Uses JdbcTemplate native SQL on stock_on_hand LEFT JOIN products, GROUP BY product, SUM(quantity)/SUM(on_hand_value), avg_cost = SUM(value)/NULLIF(SUM(qty),0). GL side via GLConfigResolver.resolve(companyId, GlConfigKey.INVENTORY) + JournalLineRepository.accountBalance. Currency hardcoded "TZS".
- `stock.service.LocationOnHandQuery`: queryForBranch(companyId, branchId, pageable)â†’Page<LocationOnHandRowDto>; queryForProduct(companyId, productId). This is the ONLY read primitive already accepting branchId (joins stock_locations; product enrichment deferred to caller).

=== COSTING / DIMENSION SLICING (key BI multi-dim engine): DimensionSlicedReportQuery ===
`costing.service.DimensionSlicedReportQueryImpl` (ADR-0025 D-8) â€” native SQL on journal_lines Ã— chart_of_accounts Ã— dimension_values, using V27 partial indexes. DimensionSlot enum {COST_CENTRE, DEPARTMENT, DIMENSION_3, DIMENSION_4} maps to columns cost_centre_value_id/department_value_id/dimension3_value_id/dimension4_value_id. Methods:
- `slicedTrialBalance(companyId, slot, valueUid, rollUp, periodId)` â†’ DimensionSlicedTbDto (per value_id+account_id SUM debit/credit; rollUp does BFS over dimension_value parent tree). A dimension slice need NOT net to zero (BR-CC-01).
- `actualsByAccountValuePeriod(companyId, slot, fromDate, toDate)` â†’ List<DimensionActualsRowDto>(accountId, valueId, periodNo, debit, credit, net). JOIN fiscal_periods for period_no attribution. THIS is the per-dimension, per-period time-series source consumed by budgeting/projects.

=== BUDGET VARIANCE: VarianceReportQuery ===
`budgeting.service.VarianceReportQueryImpl` (ADR-0034 D-8). run(VarianceQuery)â†’VarianceReportDto; departmentalActuals(companyId, fyUid, fromPeriodNo, toPeriodNo)â†’DepartmentalActualsDto. VarianceQuery record = (companyId, fiscalYearUid, fromPeriodNo 1..12, toPeriodNo, costCentreValueUid nullable, accountType nullable). Actuals consumed from DimensionSlicedReportQuery.actualsByAccountValuePeriod (NOT querying journal_lines directly â€” D-8 contract). Budget side from BudgetLineRepository.findByVersionAndPeriods on the APPROVED BudgetVersion. variance = actual âˆ’ budget; variancePct; totals by AccountType. Period range driven by FiscalPeriod.periodNo. VarianceRowDto carries budget/actual/variance/variancePct + costCentre context.

=== PROJECT P&L / WIP: ProjectCostingQuery ===
`projects.service.ProjectCostingQueryImpl` (ADR-0033) + `projects.repository.ProjectCostingQueryRepository` (JdbcTemplate, NOT JPA). projectPnl(projectUid, principal)â†’ProjectPnlDto{revenue, cost, costByType: List<ProjectCostingRowDto> by ProjectCostType, margin, marginPct, budget, budgetVariance, wip, recon}; wipReport(companyId, principal)â†’List<ProjectWipRowDto>. SQL aggregates over journal_lines.project_id Ã— chart_of_accounts: revenue=Î£(creditâˆ’debit) WHERE INCOME, cost=Î£(debitâˆ’credit) WHERE EXPENSE; costByType GROUP BY project_cost_type; WIP=max(0, costâˆ’billed). BRANCH-ISOLATED: passes principal.branchId into SQL (p.branch_id filter) â€” the one read path enforcing branch-level isolation beyond company.

=== MANUFACTURING / FIXED-ASSET RECON ===
- `manufacturing.service.WipReconQuery`: reconcile(companyId)â†’WipReconciliationDto. computed = WorkOrderRepository.sumOpenWip (native, status IN RELEASED/IN_PROGRESS/COMPLETED) vs GL WIP_INVENTORY (GLConfigResolver + JournalLineRepository.accountBalance). NOTE: this bean does NOT call assertCanActIn (companyId comes from controller path); BI must scope-guard before calling.
- `fixedassets.service.FixedAssetReconQuery`: reconcile(companyId)â†’FixedAssetReconciliationDto{registerCost, glCost(FIXED_ASSETS), costTies; registerAccum, glAccum(ACCUMULATED_DEPRECIATION .negate()), accumDepTies}. Register sums via FixedAssetRepository.sumCarryingCostInService / sumAccumulatedDepreciationInService.

=== CRM KPIs: PipelineQuery (the only true "KPI" bean) ===
`crm.service.PipelineQuery` (ADR-0031 D-9), takes (companyId, branchId). Methods:
- `pipeline(companyId, branchId)`â†’PipelineSummaryDto (open opps grouped by stage, Î£value + Î£weighted). Source: OpportunityRepository.pipelineSummaryRaw â€” JPQL JOIN PipelineStage, GROUP BY stage, SUM(estimatedValueAmount), SUM(estimatedValueAmount*winProbability/100), status='OPEN'.
- `forecast(companyId, branchId, from, to)`â†’ForecastDto (Î£weighted of OPEN closing in window). Source: OpportunityRepository.forecastRaw.
- `kpis(companyId, branchId, from, to)`â†’CrmKpiDto{periodStart, periodEnd, wonCount, lostCount, winRatePercent, avgCycleDays}. Source: OpportunityRepository.kpiRaw â€” NATIVE SQL, COUNT + AVG(EXTRACT(EPOCH FROM won_atâˆ’created_at)/86400) GROUP BY opportunity_status, filtered by won_at/lost_at in [from,to] Instants. This is the closest existing pattern to a BI KPI card and is the only one that is genuinely branch-aware AND time-bounded.

=== FISCAL-PERIOD MODEL (time-series spine) ===
`gl.domain.entity.FiscalYear` (table fiscal_years): company_id, year_code, start_month (default 1), start_date, end_date, status (PeriodStatus OPEN/CLOSED), year-end-close fields (closed_at, closing_journal_uid). `gl.domain.entity.FiscalPeriod` (table fiscal_periods): company_id (denormalized), fiscal_year_id, period_no (1..12 monthly), start_date, end_date, status (the posting gate). Repos: FiscalYearRepository.findByUid; FiscalPeriodRepository.findByFiscalYearIdOrderByPeriodNo, findByCompanyIdOrderByStartDateAsc, findCompanyIdByUid. Time-series for BI = either (a) per-period TB via trialBalanceSumsByPeriod(companyId, periodId) iterated over periods, or (b) date-windowed periodMovementByAccount over each period's [start_date,end_date], or (c) DimensionActualsRowDto.periodNo from actualsByAccountValuePeriod. There is NO pre-aggregated period_balances / snapshot table â€” all period series are computed live.

=== COMPANY / BRANCH SCOPE (the #1 anti-regression guard) ===
`platform.security.ScopeGuard.assertCanActIn(RequestContext.Principal, companyId)` is THE scope enforcement â€” called first on every read path (TrialBalanceQuery, AccountMovementQuery every method, all Ageing/Recon/Pipeline/StockValuation/Costing queries). Rule: allow iff principal.root() OR companyId == principal.companyId(); fails closed (ForbiddenException). Root acting cross-company emits an independent (REQUIRES_NEW) audit row â€” this is why it works under @Transactional(readOnly=true). `RequestContext.Principal` = record(userId, username, root, companyId, branchId, ip) held in ThreadLocal. KEY FINDING: company scope is uniformly enforced; BRANCH scope is almost never applied at the query level â€” only PipelineQuery (branchId param, but not validated against principal) and ProjectCostingQuery/LocationOnHandQuery actually filter by branch. journal_lines.branch_id exists but is a nullable analysis tag and NO GL aggregate filters on it. BI branch-level dashboards will need to add branch predicates to the GL aggregates (new repo methods) and decide branch-authorization semantics (assertCanActIn only checks company, not branch).

=== HTTP SURFACE (controllers in com.erp.api) ===
- `ReportingController` @ /api/v1/reports: GET income-statement, balance-sheet, cash-flow, account-ledger (+ /export each, ExportFormat PDF/CSV/XLSX). Perms REPORT.PL.VIEW / REPORT.BS.VIEW / REPORT.CASHFLOW.VIEW / REPORT.LEDGER.VIEW / REPORT.EXPORT.
- `TrialBalanceController` @ /api/v1/gl/trial-balance: GET (companyId), GET /period (companyId, periodId). Perm GL.VIEW.
- `PipelineController` @ /api/v1/crm/pipeline: GET (companyId, branchId), GET /forecast, GET /kpis. Perm CRM.PIPELINE.VIEW.
- `StockValuationController`, `ManufacturingReportController`, `DimensionReportController`, `BudgetReportController`, `ProjectCostingController`, `CashAccountStatementController`, `ArStatementController`, `ApStatementController` expose the remaining queries. All controllers take companyId as @RequestParam and rely on the service's internal assertCanActIn.

CONVENTIONS BI MUST FOLLOW (so it reuses, not duplicates): (1) call ScopeGuard.assertCanActIn(RequestContext.get(), companyId) before any read; (2) aggregate in SQL (JPQL/native GROUP BY), never row-by-row in Java (NFR-REP-02); (3) reuse AccountMovementQuery for any GL-derived metric (it already gives windowed + cumulative + by-type + net-income); reuse DimensionSlicedReportQuery for dimension/department/cost-centre/project breakdowns; reuse PipelineQuery for CRM KPIs; (4) cross-module GL reads go through JournalLineRepository.accountBalance or AccountMovementQuery only (documented ADR D-12 allowance), never importing other modules' entities; (5) currency is base-currency per company (Company.getBaseCurrency()), several queries hardcode "TZS" â€” BI should resolve via CompanyRepository.

### Touch points
- AccountMovementQuery (reporting.service) â€” the primary reuse engine: windowed + cumulative + by-type movements; BI KPI cards/time-series should call these, not re-query journal_lines. Add branch-aware overloads here if branch dashboards are needed.
- JournalLineRepository (gl.repository) â€” add new SUM/GROUP-BY methods here for any aggregate not yet covered (e.g. GROUP BY branch_id, per-period series, monthly buckets). It is the single sanctioned GL aggregate hub.
- DimensionSlicedReportQuery.actualsByAccountValuePeriod (costing.service) â€” reuse for any cost-centre/department/dimension/period time-series; already returns periodNo-attributed net per account+value.
- PipelineQuery + CrmKpiDto (crm.service / crm.domain.dto) â€” the existing KPI projection pattern; extend or mirror its win-rate/avg-cycle/forecast shape for new BI KPI DTOs.
- TrialBalanceQuery.computeForPeriod / trialBalanceSumsByPeriod â€” per-fiscal-period TB is the natural time-series primitive; iterate over FiscalPeriodRepository periods for trend lines.
- ScopeGuard.assertCanActIn + RequestContext.Principal (platform.security) â€” every BI read path MUST start here; note branch_id on the principal is NOT enforced by assertCanActIn, so branch-scoped BI needs explicit branch predicates + an authorization decision.
- FiscalYear / FiscalPeriod + FiscalPeriodRepository (gl) â€” the time-series spine; no pre-aggregated snapshot table exists, so BI computes period series live (period_no 1..12, start/end dates).
- Recon DTOs/queries (ArReconciliationQuery, ApReconciliationQuery, CashGlReconciliationQuery, StockValuationQuery, WipReconQuery, FixedAssetReconQuery) â€” surface their ties/difference flags as BI 'health' indicators; note WipReconQuery lacks assertCanActIn so guard before reuse.
- com.erp.api controllers (ReportingController, TrialBalanceController, PipelineController, StockValuationController, etc.) â€” the existing HTTP surface a BI dashboard can call directly, or model new /api/v1/bi endpoints after (companyId @RequestParam + @PreAuthorize perm + service-internal scope guard).
- Reporting export pipeline (ReportExporter, StatementModelFlattener, CSV/PDF/XLSX renderers) â€” reuse for BI export rather than building a new exporter.

### Files of interest
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/repository/JournalLineRepository.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/service/TrialBalanceQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/entity/JournalLine.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/entity/FiscalPeriod.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/gl/domain/entity/FiscalYear.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/reporting/service/AccountMovementQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/reporting/service/AccountLedgerQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/reporting/service/ReportingService.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/reporting/service/IncomeStatementBuilder.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/reporting/service/BalanceSheetBuilder.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/reporting/service/ComparativeWindowResolver.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/service/ArAgeingQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ar/service/ArReconciliationQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/service/ApAgeingQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/ap/service/ApReconciliationQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/cashbank/service/CashAccountStatementQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/cashbank/service/CashGlReconciliationQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/stock/service/StockValuationQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/stock/service/LocationOnHandQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/costing/service/DimensionSlicedReportQueryImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/budgeting/service/VarianceReportQueryImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/budgeting/domain/dto/VarianceQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/projects/service/ProjectCostingQueryImpl.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/projects/repository/ProjectCostingQueryRepository.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/manufacturing/service/WipReconQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/fixedassets/service/FixedAssetReconQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/crm/service/PipelineQuery.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/crm/domain/dto/CrmKpiDto.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/modules/crm/repository/OpportunityRepository.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/ScopeGuard.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/platform/security/RequestContext.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/api/ReportingController.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/api/PipelineController.java
- d:/My_Works/ERP/ERPCLEAN2/backend/src/main/java/com/erp/api/TrialBalanceController.java

## dashboard-home-frontend

## What /admin/home currently shows

`AdminHomeComponent` (web/src/app/features/admin/home/admin-home.component.ts) is a neutral, permission-filtered LAUNCHER, not an analytics dashboard. It is the default landing for every authenticated user.

- It is a standalone component, `selector: 'app-admin-home'`, imports only `RouterLink`. Injects `SessionStore` as `protected readonly session`.
- State: `displayName = computed(() => session.user()?.displayName ?? '')`; a static `private readonly allCards: readonly HomeCard[]`; and `readonly cards = computed(() => allCards.filter(c => session.hasPermission(c.permission)))`.
- `HomeCard = { label; description; route; icon; permission }`. Seven hardcoded cards: Companies (COMPANY.VIEW), Users (USER.VIEW), Roles (ROLE.VIEW), Audit (AUDIT.VIEW), Customers (CUSTOMER.VIEW), Suppliers (SUPPLIER.VIEW), Sales Agents (AGENT.VIEW). NOTE: the card list is stale vs. the nav â€” it lacks the dozen-plus modules (Sales, Inventory, Accounting, CRM, HR, etc.) that exist in the shell.
- Template (admin-home.component.html): `<section class="admin-page">` + `.page-head` header ("Welcome, {name}"), then a Bootstrap `row g-3` of `col-sm-6 col-lg-4` cards using `@for (card of cards(); track card.route)`. Each card is an `<a class="home-card card card-body" [routerLink]="card.route">` with a `bi`-icon span. `@else` branch shows a calm "no admin access" empty state. No KPIs, no numbers, no HTTP calls â€” purely static navigation.
- SCSS (admin-home.component.scss): only `.home-card` hover/focus styling + a `prefers-reduced-motion` block. Relies on global `.admin-page`/`.page-head`.

## Routing / landing chain
- app.routes.ts: `'' â†’ 'admin'`; under `admin`, admin.routes.ts has `{ path:'', redirectTo:'home' }` (the LAST entry, line 828) and `{ path:'home', loadComponent: ...AdminHomeComponent }`.
- Login (`login.component.ts`) navigates to `/admin` on success â†’ resolves to `/admin/home`.
- `requirePermission` guard (core/auth/permission.guard.ts) redirects FORBIDDEN routes back to `/admin/home` â€” so home must remain permission-safe.

## Charting dependency â€” NONE exists; one MUST be added for any real charts
- package.json deps: @angular/* 21.2, bootstrap ^5.3.8, bootstrap-icons ^1.13.1, rxjs, tslib. Dev: @angular/build, vitest, prettier, jsdom. NO chart.js / ng2-charts / echarts / ngx-echarts / apexcharts / d3 / highcharts / plotly / chartist â€” confirmed absent in both package.json and package-lock.json.
- grep for `chart`/`graph` across web/ only matches Bootstrap Icon class names (`bi-bar-chart-line`, `bi-graph-up-arrow`, `bi-bar-chart-steps`) and the literal "Chart of Accounts" feature. The only `<svg>` in the app is the alert-host icon. No `<canvas>`, `baseChart`, `new Chart()`, or `EChartsOption` anywhere.
- Every existing "report"/"dashboard"/"KPI" surface renders with plain HTML: `<table class="table ... grid">`, definition lists (`<dl class="row">`), Bootstrap badges, and `| number:'1.2-2'`. So a BI dashboard can ship a v1 with zero new deps (table + stat-card + CSS bars). To add real charts, a lib must be added to package.json and to angular.json/app.config as needed (ng2-charts + chart.js is the lightest Angular-idiomatic fit; ngx-echarts is the alternative).

## Closest existing analog â€” the CRM Pipeline Dashboard (the template to mirror for BI)
`PipelineDashboardComponent` (web/src/app/features/admin/crm/pipeline-dashboard.component.ts) is the de-facto "dashboard" pattern and the best exemplar for a BI home:
- Standalone, imports `[FormsModule, DecimalPipe]`. Injects feature service (`CrmService`), `CompanyService`, `OrganisationService`, `BranchService`, and `SessionStore`.
- Per-widget signal trios: each report has `data = signal<Dto|null>(null)` + `state = signal<'loading'|'idle'|'error'|'forbidden'>(...)`. There is no single page state â€” each panel reports its own load/empty/error/forbidden independently.
- Company/branch context bootstrapped in the constructor (NO ngOnInit): `loadCompanies()` â†’ `OrganisationService.current()` â†’ `CompanyService.list(org.uid)` â†’ auto-select first â†’ `loadBranches(uid)` via `BranchService.list` â†’ auto-select first â†’ `runAllReports()`. Company `<select>` hidden when `companies().length <= 1`. Date-range signals (`fromDate`/`toDate`) drive forecast/KPI reloads via `applyDates()`.
- Error mapping convention: `(err) => state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error')`.
- `readonly canView = computed(() => session.hasPermission('CRM.PIPELINE.VIEW'))`.
- Template uses `@switch (state())` per panel: loading=spinner+`aria-live="polite"`, error=`role="alert"`, forbidden="no permission", default â†’ table/`<dl>` or empty message. Money rendered `{{ currency }} {{ +amount | number:'1.2-2' }}`. KPIs shown as a `<dl class="row">` of stat pairs (Won/Lost/Win Rate/Avg Cycle).

## HTTP-service + signals pattern a dashboard component follows
Per docs/frontend/CONVENTIONS.md (the authoritative contract) and exemplars:
- Service: one per feature folder, `@Injectable({ providedIn: 'root' })`, `private readonly base = \`${environment.apiBaseUrl}/<resource>\`` (apiBaseUrl = `/api/v1`). Inject `HttpClient`. Report/aggregate endpoints return BARE DTOs via the auto-unwrap interceptor (`apiResponseInterceptor` strips `{data,errors}`), so `http.get<Dto>(url, { params })` is already unwrapped. Query params via `new HttpParams().set('companyId', ...).set('branchId', ...)` (and from/to dates). Paginated lists opt OUT with `SKIP_UNWRAP` (HttpContext token) and map to `{ rows, meta }` â€” dashboards typically do NOT need this. Auth header + `X-Branch-Uid` are added automatically by `authHeaderInterceptor`. All Long/BigDecimal are `string` on the wire; coerce with `+value` for `number` pipe. See CrmService.getPipelineSummary/getForecast/getKpis as the report-endpoint exemplar.
- Component: signals are the state primitive â€” `signal<T>()` for fields, `computed()` for derived (permission gates, isEmpty). Load in the constructor (no ngOnInit); RxJS only for the HTTP `.subscribe()`. Four-state `@switch (state())` is mandatory. Forms use `FormsModule` + `[ngModel]`/`(ngModelChange)` two-way to signals (NO ReactiveForms). Control flow is `@if`/`@for (track â€¦)`/`@switch` (NO `*ngIf`/`*ngFor`). Toast/alert hosts are mounted once in the shell â€” feature components do not mount them.

## How a dedicated BI dashboard screen would be added (3 append points)
1. Create `web/src/app/features/admin/<dashboard>/` with `<x>-dashboard.component.{ts,html,scss}`, a `<x>.service.ts`, and `models/<x>.model.ts` (mirror backend DTOs, all numbers as `string`). Mirror PipelineDashboardComponent.
2. ROUTE (admin.routes.ts): append a banner-commented block BEFORE the final `{ path:'', redirectTo:'home' }` (line 828): `{ path:'<dashboard>', canActivate:[requirePermission('<BI.VIEW>')], loadComponent: () => import('./<dashboard>/...').then(m => m.XComponent) }`. Use the EXACT permission code from the backend `@PreAuthorize`.
3. NAV (shell.component.ts): append a `NavItem` `{ label, route:'/admin/<dashboard>', icon:'bi-â€¦', available:true, permission:'<BI.VIEW>' }` into the right `allNav` NavGroup (or a new "Analytics/BI" group). APPEND-ONLY, never reorder â€” this is the high-conflict file. `nav()` computed already filters by `session.hasPermission`.
4. Optionally, since /admin/home is the universal landing, the BI surface could instead/also be folded INTO AdminHomeComponent (add KPI rows above the cards) â€” but note home must stay permission-safe (it's the forbidden-redirect target), so any KPI fetch there must degrade gracefully and be gated.
5. Build gate: `npm run build` zero errors + `npm test` (Vitest). New list/report specs use `vi.useFakeTimers()` and cover load-once, isEmpty, 403â†’'forbidden'.

### Touch points
- web/src/app/features/admin/home/admin-home.component.ts â€” the universal landing; BI KPI rows could be folded in here (must stay permission-safe, it is the forbidden-redirect target), or home cards extended to include the BI route
- web/src/app/features/admin/admin.routes.ts â€” append BI dashboard route block before the final { path:'', redirectTo:'home' } at line 828, guarded by requirePermission('<BI.VIEW>')
- web/src/app/layout/shell/shell.component.ts â€” append NavItem (or a new 'Analytics'/'BI' NavGroup) into the static allNav array; append-only, permission-gated via the existing nav() computed
- web/package.json + package-lock.json + angular.json â€” add a charting dependency (none exists today; ng2-charts+chart.js or ngx-echarts) if real charts are required; v1 can ship chart-free with tables/stat-cards/CSS bars
- New feature folder web/src/app/features/admin/<dashboard>/ â€” service (providedIn:'root', report endpoints via auto-unwrap http.get<Dto>) + dashboard component mirroring PipelineDashboardComponent's per-widget signal trios + four-state @switch
- docs/frontend/CONVENTIONS.md â€” the binding contract (HTTP layer, signals, four-state, append points, new-feature checklist) the BI surface must follow

### Files of interest
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/home/admin-home.component.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/home/admin-home.component.html
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/home/admin-home.component.scss
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/layout/shell/shell.component.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/admin.routes.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/app.routes.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/core/auth/permission.guard.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/auth/login/login.component.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/crm/pipeline-dashboard.component.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/crm/pipeline-dashboard.component.html
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/crm/crm.service.ts
- d:/My_Works/ERP/ERPCLEAN2/web/package.json
- d:/My_Works/ERP/ERPCLEAN2/docs/frontend/CONVENTIONS.md

## frontend-test-a11y-setup

## Unit-test runner (current)

The web app (`web/`) is Angular 21 (standalone, signals-based, zoneless-style components). `ng test` resolves to the modern first-party Angular vitest integration, NOT karma:
- `web/angular.json` -> `projects.web.architect.test.builder = "@angular/build:unit-test"` (the only `test` config; no `options` block, so it uses the builder defaults â€” it auto-discovers `src/**/*.spec.ts` from `tsconfig.spec.json`).
- `web/package.json` script `"test": "ng test"`. devDependencies include `vitest ^4.0.8` and `jsdom ^28.0.0` (jsdom is the test DOM environment). There is NO `karma`, NO `jasmine`, NO `@types/jasmine`.
- `web/tsconfig.spec.json` sets `"types": ["vitest/globals"]` and `include: ["src/**/*.d.ts", "src/**/*.spec.ts"]`. `web/tsconfig.json` references both `tsconfig.app.json` and `tsconfig.spec.json`.
- There is NO standalone config file: no `vitest.config.ts`, no `karma.conf.js`, no `test-setup.ts`/`test.ts`, no `web/src/test-setup.ts`. All test config is implicit via the `@angular/build:unit-test` builder + `tsconfig.spec.json`. (Note: vitest 4 pulls a transitive optional dep `@vitest/browser-playwright@4.1.8` seen in `package-lock.json` â€” it is NOT a declared devDep and NOT used; tests run in jsdom, not a real browser.)

## How specs are written

Globals style (Vitest `describe/it/expect/vi`, enabled by `vitest/globals` types â€” no explicit imports of these). Two patterns:
1. Service specs (e.g. `web/src/app/features/admin/branch/branch.service.spec.ts`): `TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] })`, inject service + `HttpTestingController`, assert request URL/method/body, `httpMock.verify()` in `afterEach`.
2. Component (list) specs (e.g. `web/src/app/features/admin/products/product-list.component.spec.ts`, `customer-list.component.spec.ts`): standalone component imported directly; providers stub `provideHttpClient`, `provideHttpClientTesting`, `provideRouter([])`, the feature service (`useValue` with `vi.fn(() => of(...))`), `OrganisationService`, `CompanyService`, `AlertService`, and a hand-rolled `SessionStore` fake (`hasPermission: vi.fn`, signal-backed `isAuthenticated`/`user`/`permissions`/`activeBranchUid`). Heavy use of `vi.useFakeTimers()` + `vi.runAllTimersAsync()` / `vi.advanceTimersByTimeAsync()` to drive the 300ms live-search debounce, `distinctUntilChanged`, switchMap race-safety, and 403->"forbidden" state. Assertions are on component signals/methods (`comp.rows()`, `comp.state()`, spy `.mock.calls`), rarely on rendered DOM (`app.spec.ts` is the exception: queries `router-outlet`).

## Per-module spec coverage

41 project `*.spec.ts` (excluding node_modules). Coverage is thin and breadth-first: roughly ONE spec per module, almost always the list (or a primary create/post) component, plus a handful of core/service specs. Inventory: 4 core (`app.spec.ts`, `core/api/http.interceptors.spec.ts`, `core/auth/session.store.spec.ts`, `core/auth/auth.service.spec.ts`); admin services (branch/role/user/audit `.service.spec.ts`); then one component spec per feature area â€” parties (agent, customer), products (product, units-of-measure), sales (sales-invoice, tax-rate, quotation, sales-order, sales-return), purchases (PO list, goods-receipt-create), stock, routes (list+detail), gl (chart-of-accounts, post-journal, trial-balance), ar (record-receipt), ap (enter-bill, record-payment, supplier-statement), cashbank (bank-reconciliation, cash-account-statement, record-transfer), tax (vat-returns, wht-types), reporting, inventory-valuation, approvals (policy-list), cost-centre (dimension-value), documents, notifications (inbox), projects, fx (rate-list). Many sizeable modules (HR/payroll, CRM, fixed-assets, budgeting, manufacturing, most detail/report screens) have NO spec. There is currently no rendered-DOM / a11y assertion anywhere, and no full app-render integration test beyond app.spec.ts.

## Playwright / axe-core presence

NEITHER is a real dependency. Confirmed:
- No `axe-core`, `@axe-core/*`, `@playwright/test`, or `playwright` in `web/package.json` (deps or devDeps) or anywhere in `web/src`.
- The only `playwright` token in `web/package-lock.json` is the transitive optional `@vitest/browser-playwright@4.1.8` (unused; vitest browser-mode is not configured). The only `axe` substring in the lockfile is `saxes` (a sax XML parser, unrelated).
- A separate ad-hoc browser harness DOES exist at repo root `e2e/` (Node scripts, NOT in the web build/CI): `ui-smoke.js`, `qa-ui-drive.js`, `sales-o2c-ui-drive.js`, `gl-ui-drive.js` use `require('playwright-core')` resolved from an external NODE_PATH scratch dir (no local install), auto-discovering a system Chromium under `%LOCALAPPDATA%/ms-playwright`. `e2e/README.md` explicitly states these are manual/on-demand operator tools, "not part of the Maven/CI build". So Playwright is used informally but is not a managed devDep and has no test-runner wiring.

## CI wiring

NONE at project level. No `.github/`, `.gitlab-ci.yml`, `azure-pipelines.yml`, `Jenkinsfile`, or `*.yml` workflows exist in the repo root (the only `.github/workflows` matches are inside `web/node_modules/*`). Frontend tests are run manually via `npm test`. Adding a11y/e2e gates will require creating CI from scratch (or just npm scripts) â€” there is no existing pipeline to hook into.

## Cleanest way to ADD (a) axe-core a11y gate and (b) Playwright e2e â€” matching project style

(a) axe-core accessibility gate over key screens â€” TWO viable approaches:
- Lightweight, in-vitest (best fit for current style, no new browser infra): add devDeps `axe-core` + `jest-axe` (or call `axe.run()` directly against `fixture.nativeElement`). Render each key standalone component via TestBed (exactly like the existing list specs, reusing the same SessionStore/service `useValue` fakes + `vi.fn(() => of(...))` stubs), then `await axe.run(fixture.nativeElement)` and assert zero violations. Place as new `*.a11y.spec.ts` files so they are auto-picked-up by the existing `@angular/build:unit-test` builder (include glob already `src/**/*.spec.ts`) and run under `npm test` with no angular.json change. Caveat: jsdom has no real layout, so color-contrast/visibility rules are unreliable â€” disable those rules and treat this as a structural-a11y gate (labels, roles, names, aria, duplicate ids).
- Real-browser (higher fidelity, pairs with Playwright): `@axe-core/playwright` injected into the Playwright e2e flow to scan each rendered route. Better contrast coverage; needs the e2e infra below.

(b) Playwright e2e â€” formalize the existing `e2e/` harness into a managed suite:
- Add devDeps `@playwright/test` (+ `@axe-core/playwright` if combining with a11y) and a `web/playwright.config.ts` with `webServer` running `ng serve` (proxy.conf.json already forwards `/api`->`http://localhost:8081`) and `baseURL` http://localhost:4200, projects=[chromium].
- Add npm scripts in `web/package.json`: `"e2e": "playwright test"`, `"e2e:install": "playwright install chromium"`, and (if combining) `"a11y": "playwright test --grep @a11y"`. Keep the vitest `"test"` script untouched so unit and e2e stay separate runners.
- Seed specs from the existing `e2e/ui-smoke.js` flow (login -> iterate key routes -> screenshot/assert no crash). Login selectors are already accessible and stable: `#username`, `#password`, `button[type="submit"]` (form has `aria-label="Sign in"`, inputs have `<label for>`); the smoke script uses these exact selectors. Reuse its route list (`/admin/stock`, `/admin/purchase-orders`, `/admin/goods-receipts`, `/admin/routes`) plus high-traffic list screens from `admin.routes.ts`.
- Key-screen target set (from `web/src/app/features/admin/admin.routes.ts`) for both gates: `/login`, `/admin/home`, and one representative per area â€” `/admin/companies`, `/admin/users`, `/admin/customers`, `/admin/products`, `/admin/stock`, `/admin/sales-orders`, `/admin/purchase-orders`, `/admin/gl/accounts`, `/admin/gl/trial-balance`, `/admin/ar/invoices`, `/admin/ap/supplier-bills`, `/admin/approvals/inbox`, `/admin/reporting/balance-sheet`. Note every `/admin/*` route is permission-guarded (`requirePermission(...)` in admin.routes.ts) and behind `authGuard` (app.routes.ts) on the `ShellComponent`, so an e2e a11y crawl must log in as a fully-permissioned root user (the e2e scripts use `rootadmin`/`RootPass12345`) or it will be redirected to `/admin/home`.
- CI: since none exists, the matching-style move is a new `.github/workflows/web-ci.yml` (or extend whatever backend CI is later added) running `npm ci && npm test` (unit + a11y vitest) and a separate job `npm run e2e` after `playwright install --with-deps chromium`. e2e/a11y-browser should be its own job because it needs a running API+SPA, whereas the vitest a11y gate runs hermetically with the unit tests.

### Touch points
- web/package.json â€” add devDeps (axe-core/jest-axe for in-vitest a11y; @playwright/test + @axe-core/playwright for browser e2e) and npm scripts (e2e, e2e:install, a11y); keep test=ng test unchanged
- web/tsconfig.spec.json â€” already globs src/**/*.spec.ts, so new *.a11y.spec.ts files are auto-included with no config change; may add axe/jest-axe to types[]
- web/angular.json â€” projects.web.architect.test (@angular/build:unit-test) runs the vitest a11y specs unchanged; no edit needed for in-vitest a11y
- New web/playwright.config.ts â€” webServer=ng serve (proxy.conf.json forwards /api -> :8081), baseURL :4200, chromium project
- New web/e2e/ (or web/tests/) Playwright specs â€” seed from repo-root e2e/ui-smoke.js login+route-crawl flow
- Login flow selectors for e2e: #username, #password, button[type=submit], form aria-label="Sign in" (web/src/app/features/auth/login/login.component.html)
- Route list for a11y/e2e targets: web/src/app/features/admin/admin.routes.ts (all permission-guarded) + web/src/app/app.routes.ts (authGuard on ShellComponent)
- Reusable spec scaffolding: SessionStore fake + service useValue/vi.fn(of(...)) stubs from product-list.component.spec.ts / customer-list.component.spec.ts for rendering components in a11y specs
- CI: no .github/ exists â€” add new .github/workflows for npm ci && npm test (unit+a11y) and a separate browser e2e job (playwright install + npm run e2e)
- Existing manual harness e2e/ui-smoke.js + e2e/README.md â€” basis to formalize into managed Playwright suite (currently playwright-core via external NODE_PATH, not a devDep)

### Files of interest
- d:/My_Works/ERP/ERPCLEAN2/web/package.json
- d:/My_Works/ERP/ERPCLEAN2/web/angular.json
- d:/My_Works/ERP/ERPCLEAN2/web/tsconfig.json
- d:/My_Works/ERP/ERPCLEAN2/web/tsconfig.spec.json
- d:/My_Works/ERP/ERPCLEAN2/web/tsconfig.app.json
- d:/My_Works/ERP/ERPCLEAN2/web/proxy.conf.json
- d:/My_Works/ERP/ERPCLEAN2/web/src/environments/environment.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/app.routes.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/admin.routes.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/app.spec.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/products/product-list.component.spec.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/admin/branch/branch.service.spec.ts
- d:/My_Works/ERP/ERPCLEAN2/web/src/app/features/auth/login/login.component.html
- d:/My_Works/ERP/ERPCLEAN2/e2e/ui-smoke.js
- d:/My_Works/ERP/ERPCLEAN2/e2e/README.md
- d:/My_Works/ERP/ERPCLEAN2/web/package-lock.json

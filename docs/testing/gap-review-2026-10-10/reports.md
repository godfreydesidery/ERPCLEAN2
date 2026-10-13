# Reports, dashboards, exports — findings (static review, develop @ f4de0c94)

Saved by the orchestrator (the reviewer agent could not write files). BE = backend/src/main/java/com/erp, WEB = web/src/app/features/admin, HQ = mobile_exec/lib.

| ID | Title | Sev | Type | Evidence | Fix direction (no schema) | Effort |
|---|---|---|---|---|---|---|
| RPT-01 | Sales Report + Profitability sum `quantity` in mixed units (crates + bottles) | High | Bug | SalesReportQuery.java:174; ProfitabilityReportQuery.java:157 (Summary uses qty_in_base :39,176); HQ dashboard_screen.dart:256 | SUM(qty_in_base) + base unit label | S |
| RPT-02 | Cost of sales double-counted when a product is renamed within the period | High | Bug | SalesReportQuery.java:202,229-231; ProfitabilityReportQuery.java:185,212-221 | Group by product id only | S |
| RPT-03 | No company-wide creditors ageing | High | Missing | ApStatementController.java:84-97,158-163 ("Choose a supplier.") | All-supplier ageing + screen mirroring AR | M |
| RPT-04 | No daily cash position per branch (POS cash never reaches cash book; payment summary excludes AR receipts/payouts) | High | Missing | CashTransactionRecorder.java:9-12; PaymentSummaryReportQuery.java:39-41 | Read-only Daily Cash Report from existing tables | M-L |
| RPT-05 | OrbixHQ labels company-wide sales with active branch name; PDFs don't state branch scope | High | Bug | HQ sales_service.dart:121-126; dashboard_screen.dart:88-91; SalesReportController ~108-116 | Send branch / print true scope | S |
| RPT-06 | Branch-restricted users see all branches by clearing the filter | High | Bug (design) | BranchReadGuard.java:47-53,160-163 | Treat "no branch" as "my branches" for non-company-wide users — OWNER RULING | M |
| RPT-07 | Sales reports ignore returns/credit notes → don't match P&L | High | Bug | SalesSummaryReportQuery.java:36-38 | Returns/credits + Net columns | M |
| RPT-08 | Web Sales Report has no branch picker | Med | Missing | sales-report.component.ts:128-170 | Add picker | S |
| RPT-09 | BI sales-by-branch uses UTC days | Med | Bug | DashboardServiceImpl.java:439-440,183-184 | Company TZ | S |
| RPT-10 | No voids / discounts report; Z-read lacks voids, discounts, VAT, returns | Med | Missing | SalesInvoice.java:124-133; SalesInvoiceLine.java:125-143; ZReadDto | New endpoints (response shapes frozen) | M |
| RPT-11 | No over/short report across till sessions | Med | Missing | PosSessionController.java:66-72 | Sessions variance report | S-M |
| RPT-12 | No stock transfer register | Med | Missing | StockTransferController.java:140-145; ServiceImpl:384-389 | Register with filters + export | M |
| RPT-13 | Adjustments/write-offs can't be isolated or valued | Med | Friction | StockMovementReportQuery.java:26-43,79-85 | Type/reason filters + value | S-M |
| RPT-14 | VAT return has no per-invoice schedules | Med | Missing | VatReturnDto.java | Export schedules | M |
| RPT-15 | Till-expense report built, no screen | Med | Missing UI | PosSessionController.java:144-153 | Web screen | S |
| RPT-16 | Discount column omits doc-level discounts | Med | Bug | InvoiceTotalsCalculator.java:100-125; SalesReportQuery.java:176 | list×qty − net | S |
| RPT-17 | OrbixHQ lacks Z-reads, variances, profit, debtors, creditors, expenses | Med | Missing | HQ reports_screen.dart; operations_service.dart:194-204 | Screens on existing endpoints | M |
| RPT-18 | OrbixHQ low-stock card truncated/mislabelled | Med | Bug | HQ stock_service.dart:185-189; dashboard_screen.dart:26-30,107-112 | Use /reports/reorder | S |
| RPT-19 | Cash statement screen: no date range, loads all history | Med | Perf | CashAccountStatementController.java:71-75 | Dates + paging | S |
| RPT-20 | PDFs A4 portrait only, no repeated headers/page numbers | Low | Friction | TabularPdfRenderer.java:34,48-61 | Landscape + headers + footer | S |
| RPT-21 | Web date defaults in UTC | Low | Bug | dashboard.component.ts:239-247 etc. | Local date helper | S |
| RPT-22 | AR/AP ageing "as at" past date uses today's items | Low | Bug | ArAgeingQuery.java:85-95; ApAgeingQuery.java:52-59 | Rebuild as-at | M |
| RPT-23 | No usable price-change history | Low | Missing | ProductServiceImpl.java:779-783 | Old price in audit + report | S |
| RPT-24 | Payment Summary: no branch / credit-sales column | Low | Friction | PaymentSummaryRowDto | Add columns | S |
| RPT-25 | Reports scattered; 4 overlapping stock-value screens | Low | Friction | shell.component.ts:190-191,218-237,324-329 | Reports hub | S |
| RPT-26 | Sales Report pickers capped at 200 | Low | Bug | sales-report.component.ts:103-115 | Server search | S |
| RPT-27 | No sales by hour, fast movers, month-by-month P&L | Low | Missing | SalesSummaryGroupBy.java | HOUR grouping, top-N, 12-col P&L | M |
| RPT-28 | No stock valuation as at past date | Low | Missing | StockValuationController.java:85-103 | NEEDS-SCHEMA (out of scope) | L |
| RPT-29 | Sales Report accepts end < start | Low | Bug | SalesReportQuery.java:62-81 | Add check | S |

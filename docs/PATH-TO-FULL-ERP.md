# ERPCLEAN2 — Path to a Full ERP

**The definitive master plan** to take ERPCLEAN2 from its current state (shipped V1–V12, ADR-0001–0015) to a **complete, full-featured ERP**.

> **This document supersedes the "remaining" / forward-looking sections of `docs/ROADMAP.md` as the detailed, sub-feature-level backlog.** ROADMAP.md remains the high-level tier/sequence narrative and the authority on the *shipped* core + guiding principles (additive migrations, outbox reuse, DTO-only module boundaries, requirements→ADR→build→verify→PR). This file goes deeper: every ERP area is broken to the sub-feature, each marked done / partial / remaining, with effort and the integration seam. Keep ROADMAP's tier language; this is the tracking sheet beneath it.

Grounded in the shipped code: migrations `V1__baseline … V12__accounts_payable`, ADRs `0001–0015`, and the ten shipped modules under `com.erp.modules.{iam, parties, products, sales, stock, purchases, routes, gl, ar, ap}` plus the platform outbox.

Effort key: **S** ≈ days · **M** ≈ 1–2 weeks · **L** ≈ 2–4 weeks · **XL** ≈ 4–8 weeks · **XXL** = multi-increment programme. Status: `[x]` done · `[~]` partial · `[ ]` remaining.

---

## 1. Executive summary

ERPCLEAN2 today is a **working, double-entry accounting system on top of a complete event-wired trading core**. The operational spine — IAM (org/company/branch + RBAC + audit), Parties, Products (catalogue/UoM/price-lists/single-level recipes), Sales (invoice channel, credit-aware), Stock (quantity-only, single location), Purchases (PO→goods-receipt), Routes — is shipped and idempotently event-coupled through a transactional outbox. On top of it, the **financial spine is built**: General Ledger (chart of accounts, manual + auto journals, fiscal periods, trial balance, sales auto-posting), Accounts Receivable (open items, receipts + allocation, ageing, statements, write-offs, credit notes, opening balances, credit-limit enforcement), and Accounts Payable (supplier bills, 3-way match, payment runs, debit notes, opening balances). Books balance; AR/AP sub-ledgers reconcile to their GL control accounts; sales post revenue + VAT automatically. **The headline of what remains** is four-fold: (1) **finish Tier-1 finance** — Cash & Bank (the cash-leg home for AR/AP settlement + reconciliation), the periodic VAT return, and year-end close automation; (2) **reporting & financial statements** — the system can produce a trial balance but not yet a P&L, balance sheet, cash-flow statement, or operational analytics; (3) **operational depth** across sales (orders/POS/returns), procurement (requisitions/RFQ/approvals/landed cost), and inventory (the big one: **valuation + COGS**, plus multi-location, counts, batch/serial); then (4) the **extension modules** — Fixed Assets, Manufacturing, HR/Payroll, CRM, Projects, Budgeting — layered on a set of **cross-cutting enablers** (document/PDF generation, notifications, approvals, multicurrency FX, cost-centre dimensions) and a **production-hardening** pass (observability, secrets, CI/CD, K8s, OpenAPI, a11y, backup/DR). The critical insight from the build so far holds: everything financial gates on GL (shipped), and several big modules (Manufacturing WIP costing, true P&L, ABC analysis) gate on **inventory valuation/COGS**, which is the single highest-leverage remaining operational piece.

---

## 2. Status snapshot

**Overall completion ≈ 41%** (effort-weighted) as of 2026-06-11 (Sales Order-to-Cash shipped — area 6 30%→65%; Inventory valuation/COGS area 8 30%→70%). See the roll-up below the table.

| # | ERP area | Status | Effort·wt | % done | Note |
|---|----------|--------|-----------|--------|------|
| 1 | **IAM / platform foundation** (org, RBAC, audit, outbox, Money, numbering) | **DONE** | —·10 | **100%** | Shipped V1/V6; the spine everything reuses |
| 2 | **Parties** (customers/suppliers/agents/other) | **DONE** | —·3 | **100%** | Shipped V2; segmentation/contracts are depth items under other areas |
| 3 | **Products / catalogue** (products, UoM, price lists, barcodes, single-level recipes) | **PARTIAL** | M·3 | **75%** | Core done; multi-level BOM/versions feed Manufacturing |
| 4 | **Tier-1 Accounting** (GL / AR / AP / Cash&Bank / VAT / FX / year-end) | **PARTIAL** | XXL·12 | **90%** | GL/AR/AP/Cash&Bank/**VAT+WHT/Reporting-statements/Year-End DONE**; only **FX remaining** |
| 5 | **Reporting, Financial Statements & BI** | **PARTIAL** | L·5 | **45%** | TB + AR/AP ageing + **P&L/BS/Cash-Flow/ledger/export DONE**; analytics, dashboards, custom report builder remaining |
| 6 | **Sales / Order-to-Cash depth** | **PARTIAL** | XL·8 | **65%** | Invoice channel + credit sales + **full O2C (quote→SO→reserve→deliver/backorder→COGS@delivery→partial-invoice→returns/RMA) + order/line discounts DONE (ADR-0021/V18-V19)**; POS, advanced pricing/promotions, drop-ship, blanket orders remaining |
| 7 | **Procurement / Purchase-to-Pay depth** | **PARTIAL** | XL·8 | **45%** | PO→GR→bill→3-way-match→pay done; requisitions, RFQ, approvals, landed cost, returns remaining |
| 8 | **Inventory / Warehouse depth** | **PARTIAL** | L·5 | **70%** | Qty-only + **moving-avg valuation/COGS, perpetual via GRNI (ADR-0020/V17) DONE**; multi-location, counts, batch/serial remaining |
| 9 | **Manufacturing / Production** | **NOT_STARTED** | XL·8 | **0%** | Greenfield; gated on multi-level BOM + valuation/COGS |
| 10 | **HR & Payroll (Tanzania)** | **NOT_STARTED** | XL·8 | **0%** | Greenfield; posts to GL, cash via Cash&Bank; TZ statutory detail |
| 11 | **Fixed Assets** | **NOT_STARTED** | L·5 | **0%** | Greenfield; depreciation runs post to GL; fiscal-period gated |
| 12 | **CRM** | **NOT_STARTED** | L·5 | **0%** | Greenfield; reads Parties, feeds Sales Orders |
| 13 | **Projects / Job Costing** | **NOT_STARTED** | L·5 | **0%** | Greenfield; project-tags costs/revenue across GL/AR/AP/stock |
| 14 | **Budgeting & Management Accounting** | **NOT_STARTED** | XL·8 | **0%** | Greenfield; needs cost-centre dimension framework on GL |
| 15 | **Cross-cutting platform capabilities** | **PARTIAL** | XL·8 | **35%** | Outbox/audit/Money done; documents/PDF, notifications, import/export, approvals, FX, search remaining |
| 16 | **Non-functional / production-hardening** | **PARTIAL** | XXL·12 | **30%** | Audit/RBAC/pagination/migrations solid; logging, secrets, CI/CD, K8s, OpenAPI, a11y, backup/DR remaining |

*(Areas 1–2 fold into "shipped core"; the backlog below details areas 3–16.)*

### Completion roll-up (recompute when an area's % changes)

**Method:** effort-weight per area (`M·3 · L·5 · XL·8 · XXL·12`; IAM foundation ·10), then `Σ(weight × %) ÷ Σweight`. Total weight = **113**; weighted-done ≈ **47** → **≈ 41% complete** (effort-weighted). (Sales area 6 30%→65% = +2.8 points; Inventory area 8 30%→70% = +2.0 — since the last two bumps.)

**Two lenses:**
- **By raw remaining effort: ≈37%** — the long tail is large (six greenfield modules at 0% + a full production-hardening pass).
- **By value / risk-retired: ≈50%** — the hardest, highest-risk spine (the double-entry **financial core** GL/AR/AP/Cash/VAT/statements/year-end + the **trading core** sales/purchases/stock) is **done**; what remains is mostly *additive modules on a proven platform*, not foundational risk.

**How to keep this honest:** when an increment lands, bump that area's **% done** in the table and re-run the method above (it's just a weighted average — no tooling needed). E.g. when Inventory valuation/COGS ships, area 8 goes 30% → ~70% (+~2 points overall ≈ 39%).

---

## 3. The complete remaining backlog, by area

### 3.1 Tier-1 Accounting — GL / AR / AP / Cash&Bank / VAT / FX / Year-end  *(PARTIAL · area XXL)*

> **GL/AR/AP are fully shipped (V10/V11/V12, ADR-0013/0014/0015).** Books balance, sub-ledgers reconcile to control accounts, sales auto-post. Remaining work finishes the finance tier (Cash&Bank, VAT, year-end) and the deferred depth (FX, recurring journals, accruals, intercompany).

**Shipped (GL):**
- [x] Chart of Accounts master (create/edit/deactivate; seeded TZ CoA) — **XL** · V10 — *account hierarchy `parent_id` reserved, null in v1*
- [x] Fiscal Year & Period master (create/open/close; seeds current FY + 12 periods) — **L** · V10
- [x] Manual journal entry (compose/balance/validate/post; append-only) — **L** · V10 · perm `GL.POST`
- [x] Opening-balance journals (manual, `source_type=OPENING_BALANCE`) — **S** · V10
- [x] `gl_configs` posting-role→account mapping (SALES_REVENUE, VAT_PAYABLE, AR, AP, CASH, INVENTORY, COGS, +BAD_DEBT, OPENING_BALANCE_EQUITY, PURCHASES) — **S** · V10/V11/V12
- [x] Sales auto-posting engine (`SalesPostingHandler` ← SALE.FINALISED; `SaleVoidingHandler` ← SALE.VOIDED) — **XL** · V10 — *future: per-category revenue (OQ-GL-04)*
- [x] Trial Balance read + API (`TrialBalanceQuery` / `/gl/trial-balance`) — **S** · V10 — *PDF/Excel export deferred to X.1*
- [x] GL scope enforcement (`ScopeGuard` case `account`) + RBAC (GL.VIEW/MANAGE/POST/PERIOD.CLOSE) — **S** · V10
- [x] AR↔GL control-account reconciliation (sub-ledger total == GL 1200) — **S** · views `v_ar_reconciliation_*`
- [x] AP↔GL control-account reconciliation (sub-ledger total == GL 2100) — **S** · `ApReconciliationQuery`

**Shipped (AR — V11, ADR-0014):**
- [x] AR auto-post on credit sale (`ArSalePostedHandler`; cash sales create no AR) — **L**
- [x] AR receipt recording + GL cash-leg (DR cash / CR AR), auto/manual allocation, on-account receipts — **M** · `RCT-####`
- [x] AR ageing (5 buckets) + customer statements — **M** — *email delivery deferred to X.2, PDF to X.1*
- [x] AR write-offs (DR bad-debt 5500 / CR AR) — **M**
- [x] AR credit notes (standalone + SALE_VOID origin) — **M**
- [x] AR opening balances (`source=OPENING_BALANCE`) — **S**
- [x] AR RBAC (8 perms incl. SALES.CREDIT.OVERRIDE) — **S**

**Shipped (AP — V12, ADR-0015):**
- [x] Supplier bill entry + **3-way match** (bill ↔ PO ↔ GR, qty+price tolerance, HELD variances) — **L**
- [x] AP bill→GL posting (`ApBillPostedHandler`; DR Inventory 1300 / Purchases 5150 / CR AP 2100; bill-driven, no GRN accrual) — **M**
- [x] Payment runs (`PAYRUN-####`) + single-bill payment (DR AP / CR Cash) — **L** — *bank-account selection depth deferred (OQ-AP-03)*
- [x] AP debit notes (supplier credits) — **M**
- [x] AP opening balances — **S**
- [x] AP RBAC (6 perms) — **S**

**Shipped (Sales↔finance breadcrumb):**
- [x] Credit-limit enforcement at sales finalise (reads `customers.credit_limit_amount` + AR balance; SALES.CREDIT.OVERRIDE) — **S**

**Remaining:**
- [ ] **Cash & Bank module (V13, ADR-0016)** — **L** — `cash_bank_accounts`, `cash_transactions`, `bank_reconciliations`; receipt/payment flows; reconciliation matching. *Settles AR/AP allocations and replaces the hard-coded Cash 1000 lookup in AR/AP posting; emits CASH.RECEIPT.RECORDED / BANK.PAYMENT.MADE; perms CASH.RECEIPT.RECORD / BANK.PAYMENT.RUN / BANK.RECONCILE.* **Next critical dependency** — unblocks cash-flow statement + true cash position.
- [x] **Tax / VAT return (V14, ADR-0017)** — **DONE** — `com.erp.modules.tax`: `vat_returns` + `vat_return_bands`, `vat_adjustments`, `wht_types`, `wht_transactions`. Monthly accrual-basis return: output (Σ sales `vat_total_amount` by band) − input (Σ `supplier_bills.vat_amount`) + adjustments − opening credit = net; DRAFT→FILED posts a synchronous GL settlement (DR `VAT_PAYABLE` 2200 / CR `VAT_INPUT` 1400 / ±`VAT_DUE` 2300) + credit carry-forward. Input VAT now booked to a new `VAT_INPUT` control account at AP bill-match (D-7). **Withholding tax** included (WHT register + certificates; WHT leg rides the AR-receipt / AP-payment entry → `WHT_PAYABLE`/`WHT_RECEIVABLE`). 525 backend tests (incl. BR-VAT-08 reconciliation + WHT legs); 6 web screens. *TRA EFD/VFD fiscalisation deferred (OQ-SALES-03).*
- [x] **Year-end close & reopen** — **DONE** (ADR-0019 / V16) — `YearEndCloseService.closeFiscalYear` posts the closing journal (zero each P&L → Retained Earnings 3900), auto-closes periods, marks the year CLOSED; `reopenFiscalYear` reverses it (most-recently-closed only). `RETAINED_EARNINGS` config key + `YEAR_END_CLOSE` source type + `GL.YEAR.CLOSE` perm. *Opening carry-forward is N/A for a continuous ledger — balance-sheet balances roll naturally; only P&L is closed to RE. Opening the next FY is the existing `openFiscalYear`.* (OQ-GL-03 resolved.)
- [~] **Period-close posting policy** — **S** — closed-period posting is rejected; the policy for an *event* landing in a closed period (strict fail-and-retry vs lenient next-open) is a config/logging enhancement, not schema (OQ-GL-01; recommend strict).
- [~] **Multicurrency at GL: FX rates, revaluation, realised/unrealised gain-loss** — **M** — Money pairs amount+currency, `companies.base_currency` seeded TZS; **no FX ops**. Needs `currency_master`, `fx_rates`, `RevaluationService` (period-end unrealised gain/loss), cross-currency settlement (realised gain/loss). *Base-currency-only v1 accepted (BR-GL-06). Shared with X.6 cross-cutting.*
- [ ] **Recurring journals & scheduled posting** — **M** — `recurring_journals` + scheduler (rent accrual, depreciation once FA lands). Depends on GL posting + period validation.
- [ ] **Accruals / prepayments / deferrals automation** — **M** — manual journals work today; automation = amortization schedules + period-close split posting.
- [ ] **Intercompany & multi-company consolidation** — **L** — intercompany GL accounts, intercompany posting, elimination entries, group reporting currency (OQ-CUR-01). Not in Tier-1.
- [ ] **Bank / cash-flow forecasting** — **M** — project inflows (AR ageing) vs outflows (AP ageing); `cash_forecast_scenarios`. Depends on AR/AP + Cash&Bank.
- [ ] **Petty cash management** — **S** — `petty_cash_floats` (imprests) + `petty_cash_vouchers`; lightweight add-on to Cash&Bank.

---

### 3.2 Reporting, Financial Statements & BI  *(PARTIAL · area L)*

> No `com.erp.modules.reporting` module exists yet (ADR-0013 D-7 flags this). All reports read GL `journal_lines`, AR/AP sub-ledgers, and transactional tables, scoped by `RequestContext` company/branch + RBAC.

- [x] Trial Balance read + API — **S** · V10
- [x] AR ageing + customer statements — **S** · V11
- [x] AP ageing + supplier statements — **S** · V12
- [ ] **Profit & Loss / Income Statement** — **M** — filter `journal_lines` to INCOME+EXPENSE account types, group by type. *Account type is placement authority (ADR-0013 D-2). Ships together with BS.*
- [ ] **Balance Sheet** — **M** — filter to ASSET+LIABILITY+EQUITY; includes retained-earnings carry (needs year-end close, §3.1).
- [ ] **Cash Flow Statement** — **L** — depends on Cash&Bank (V13) + `cash_transactions`.
- [ ] **Statement of Changes in Equity** — **M** — driven by EQUITY accounts + opening balances + closing entries (needs year-end automation).
- [ ] **GL account ledger / drill-down** — **M** — list `journal_lines` per account; `JournalLineReadController` not yet exposed.
- [ ] **Sales analytics** (by customer/product/agent/route/period) — **M** — DB views `v_sales_by_*` over `sales_invoices`/lines.
- [ ] **Purchase analytics** (by supplier/product/period; PO-vs-GR variance) — **M** — views over `supplier_bills`/`po_lines`/`goods_receipt_lines`.
- [~] **Stock valuation / movement / ageing reports** — **M** — movements exist; valuation + aged inventory need T2.2 valuation (§3.8).
- [ ] **Tax / VAT reports** — **M** — wait for VAT return (V14, §3.1).
- [ ] **Dashboards / KPI visualisations (role-based)** — **L** — aggregation queries scoped by context + RBAC (REPORTING.VIEW/EXPORT, FINANCIAL.VIEW).
- [ ] **Comparative / period-over-period reporting** — **M** — fiscal periods shipped; extend TB/P&L/BS to side-by-side.
- [ ] **Drill-down / hyperlink to detail transactions** — **M** — TB→lines, P&L/BS→ledger, analytics→invoices.
- [ ] **Export (PDF / Excel / CSV)** — **L** — cross-cutting X.1; no PDF lib in pom yet (CSV via Jackson is easy; PDF/Excel need iText/POI).
- [ ] **Scheduled / emailed reports** — **M** — cross-cutting X.2; `@Scheduled` + generation + dispatch.
- [ ] **Reporting read-model / module infrastructure** — **S** — create `com.erp.modules.reporting` (`ReportingController`, base service, views folder).
- [ ] **Reporting snapshots / cached views** — **M** — materialised snapshots + invalidation on journal post; perf for large GLs (not MVP).
- [ ] **Custom report builder** — **XL** — ad-hoc query/saved reports; deferred to extension phase.

---

### 3.3 Sales / Order-to-Cash depth  *(PARTIAL · area XL)*

> Invoice channel (V5) is fully functional and credit-aware; GL+AR enable credit sales end-to-end. Remaining work adds order capture, POS, returns, fulfilment docs, and pricing depth.

- [x] Sales Invoices — core channel (header/lines/payments, draft→finalised→void, per-product VAT, tax-exclusive entry, mandatory agent, `INV-####`, cash/mobile-money, credit-limit check) — **XL** · V5/V11
- [x] Credit sales & AR integration (CASH_WALK_IN vs CREDIT_ACCOUNT; DR Cash vs DR AR; AR open-item auto-create) — **L** · V5/V10/V11
- [x] VAT on sales (per-product status, `tax_rates`, per-line VAT, `tax_summary` JSONB, VAT-invoice printout) — **L** · V5 — *TRA EFD deferred*
- [x] Document numbering (`INV-####` at finalise; receipts `RCT-####`) — **S** — *per-branch/channel series additive*
- [x] Stock coupling on sale (SALE.FINALISED → `SaleIssueStockHandler`; recipe explosion) — **L** · V5/V7 — *COGS deferred to T2.2*
- [x] Audit & compliance trail — **S**
- [x] Sales RBAC (VIEW/CREATE/SETTLE/OVERRIDE/VOID + CREDIT.OVERRIDE) — **S**
- [~] **Sales returns + credit notes** — **M** — void (full reversal) built; partial returns, restocking, refund tenders, goods-return docs deferred. *Returns feed AR as negative open items; AR credit-note recording exists.*
- [~] **Pricing engine depth** — **M** — price lists + line/document discounts + override approval done; volume/tier discounts, promotions/campaigns, contract pricing, seasonal pricing, price-rule engine with effective dates, bundles remaining.
- [~] **Commission recording & tracking** — **L** — mandatory agent attachment + capture done; commission *calculation* (rates/tiers/frequency, OQ-PARTY-03), accrual, commission runs, agent payment, multi-agent split, reversal-on-void remaining.
- [~] **Quotation→Order→Delivery→Invoice workflow** — **L** — invoice spine + AR done; SO creation, quote lifecycle, SO→Invoice conversion, backorder/partial-fulfilment, delivery-date driven remaining.
- [~] **Payment methods depth** — **M** — cash + mobile-money + split + change done; card/EFTPOS, gateways, cheque, bank transfer, layaway/deposits, loyalty/gift-card, bank reconciliation (Cash&Bank) remaining.
- [~] **Multi-currency sales** — **M** — Money model + document currency done; FX rates/revaluation, realised/unrealised gain-loss, multi-currency ageing remaining (shared with X.6).
- [ ] **Sales Orders (SO)** — pre-invoice capture, quote→order→delivery→invoice, reservation/back-order — **L** · T2.1
- [ ] **Point-of-Sale (POS)** — till devices, cashier sessions + float + cash-drawer reconciliation, X/Z reports, offline buffering — **L** · T2.1
- [ ] **Delivery notes / dispatch documents** — picking slip, dispatch confirmation, packing — **M**
- [ ] **Pro-forma invoices** (non-binding estimates) — **S**
- [ ] **Quotations / estimates** (distinct doc, expiry, conversion, acceptance) — **M**
- [ ] **Recurring / subscription billing** (standing orders, renewals, proration) — **M**
- [ ] **Dropshipping / third-party fulfilment** — **M**
- [ ] **Multi-channel / e-commerce integration** (order intake, inventory sync, payment gateways, marketplace pull) — **XL**
- [ ] **Customer portal & self-service** (view invoices/AR/statements, reorder, pay, quote requests) — **M**

---

### 3.4 Procurement / Purchase-to-Pay depth  *(PARTIAL · area XL)*

> Core buy→receive→match→pay loop is shipped (V8 PO/GR, V12 AP bills/match/payment/debit-notes). Remaining work adds upstream demand management, governance, compliance, and analytics — all additive around the core loop; none blocks it.

- [x] Purchase Orders + Goods Receipts (PO→GR, partial receipts, outstanding qty, STOCK.RECEIVED event) — **L** · V8
- [x] AP supplier bill + 3-way match + posting + payment runs + debit notes + opening balances — *(see §3.1 AP block)* · V12
- [~] **PO numbering variants** — **S** — single `PO-####` per company done; per-branch/per-supplier series remaining.
- [~] **Input/import VAT on purchase** — **M** — `supplier_bill.vat_amount` captured; input-VAT deduction tracking remaining (ties to VAT return V14).
- [ ] **Purchase Requisitions (PR) + req→PO flow** — **L** — `purchase_requisitions` + REQUESTER role + approval gate (OQ-PURCH-01).
- [ ] **Multi-step PO approval workflow (thresholds)** — **M** — `po_approval_flows` + `po_approvals`; blocks PO ORDERED transition. *Shares X.5 approvals.*
- [ ] **RFQ + quotation comparison** — **L** — `rfqs`/`rfq_recipients`/`quotations`; precedes PO; depends on notifications (X.2).
- [ ] **Supplier price lists / contract pricing** — **M** — `supplier_price_lists` + tiered items; PO line cost defaults.
- [ ] **Blanket / framework POs** — **M** — `po_type` (STANDARD/BLANKET), cumulative limit + expiry.
- [ ] **Goods return to supplier (RMA)** — **M** — `purchase_returns` + lines; emits STOCK.RETURNED (OQ-PURCH-06).
- [ ] **Landed cost allocation** (freight/duty/insurance → inventory cost) — **L** — `purchase_landed_costs`; depends on valuation (T2.2).
- [ ] **Service / expense (non-stock) purchasing** — **L** — `po_type` (GOODS/SERVICE); service POs skip GR, bill posts to expense (OQ-PURCH-08).
- [ ] **GRNI accrual / Goods-Received-Not-Invoiced journal** — **L** — `GrniHandler` on STOCK.RECEIVED; optional toggle (BR-AP-01 accepted gap in v1).
- [ ] **Multi-currency purchase ops** — **M** — relax base-only currency; FX gain/loss on payment (X.6).
- [ ] **PO line phased delivery / ASNs** — **M** — `po_line_delivery_schedules`.
- [ ] **JIT auto-release / PO consumption** — **L** — auto-GR when stock < reorder; needs blanket POs + reorder config.
- [ ] **Spend analysis / purchase analytics** — **M** — `v_purchase_spend_by_supplier`, variance views (T2.3).
- [ ] **PO change orders (amendment after placement)** — **M** — `po_change_orders` + approval gate.
- [ ] **Supplier contract / terms master** (net days, discount terms) — **M** — feeds AP due-date + payment-run selection.
- [ ] **Supplier categorisation / segmentation** — **S** — `supplier_category` enum.
- [ ] **Supplier performance / scorecards** — **M** — `supplier_scorecards` (OTD, quality, price).
- [ ] **Supplier dispute / claim management** — **M** — `supplier_disputes`.
- [ ] **Supplier compliance / certifications** — **M** — `supplier_certifications`; gates PO if expired.
- [ ] **Purchase commitment / encumbrance tracking** — **M** — needs Budgeting (§3.14).
- [ ] **Approval delegation (deputy/proxy)** — **S** — `approval_delegations` (IAM depth).
- [ ] **Bulk PO import (CSV/Excel)** — **M** — X.4.
- [ ] **PO notifications** (buyer/supplier/stakeholders) — **M** — X.2.
- [ ] **PO PDF / document export** — **M** — X.1; needed to send POs to suppliers.
- [ ] **Import / customs (Tanzania: HS codes, import VAT, EAC docs)** — **M**
- [ ] **Procurement / P-card integration** — **L** — card-statement import + matching; needs Cash&Bank.
- [ ] **Supplier portal / self-service** — **XL** — standalone app + SSO + OCR.
- [ ] **PO expiry / obsolescence tracking** — **S**

---

### 3.5 Inventory / Warehouse depth  *(PARTIAL · area L)*

> Stock (V7) is **quantity-only, single-location** and fully event-driven. The single biggest ERP-completeness gap lives here: **valuation + COGS** (blocks true P&L, balance-sheet inventory value, margin analysis, and all manufacturing costing).

- [x] Stock-on-hand per (product, branch) with optional reorder level + negative/low flags — **S** · V7
- [x] Append-only stock movement ledger (6 types) with idempotency — **S** · V7
- [x] Goods receipt (event-driven IN) + GR reversal — **M** · V7/V8
- [x] Sale issue (event-driven OUT) + sale reversal — **M** · V5/V7
- [x] Recipe explosion on sale (composed → component deductions) — **M** · V3/V7
- [x] Manual adjustment (± with mandatory reason) — **M** · perm STOCK.ADJUST
- [x] Opening balance seed (rejected if prior movement) — **M** · perm STOCK.OPENING
- [x] Reorder-level indicator (no auto-reorder) — **M**
- [x] View on-hand + movement-history drill-down — **M**
- [x] Multi-branch scope + RBAC + audit trail — **M**/**S**
- [ ] **Inventory valuation (FIFO / weighted-avg / standard)** — **L** — `stock_cost_layers` + per-movement cost capture (T2.2). *The critical gap; model accepts cost columns additively (NFR-STOCK-06).*
- [ ] **COGS posting to GL on sale** (DR COGS / CR inventory) — **L** — needs valuation + GL config (COGS/INVENTORY accounts shipped); write the COGS posting handler. *The dependency knot for true P&L.*
- [ ] **Multi-location / multi-warehouse** (warehouse/bin/location hierarchy) — **L**
- [ ] **Inter-branch / inter-warehouse transfers** (TRANSFER_OUT/IN, in-transit) — **M** — types reserved in enum (OQ-STOCK-08).
- [ ] **Stock counts / cycle counts / physical inventory + variance posting** — **M** (OQ-STOCK-07).
- [ ] **Batch / lot tracking + expiry management** — **L**
- [ ] **Serial number tracking** — **M**
- [ ] **Reorder-point automation + auto-PO generation** — **M** (OQ-STOCK-06).
- [ ] **Stock reservations / allocations / available-to-promise** — **M** — tied to Sales Orders (T2.1).
- [ ] **Landed-cost capitalisation** (apportion freight/duty/insurance to unit cost) — **M** — ties to valuation (shared with §3.4).
- [ ] **Stock write-offs / scrap posting** (dedicated movement + GL) — **M** — partial via ADJUSTMENT today.
- [ ] **ABC analysis** (value segmentation) — **M** — needs valuation + reporting.
- [ ] **Barcode scanner / warehouse operation support** — **M**
- [ ] **Putaway / picking / packing workflows** — **L** — tied to multi-location.
- [ ] **Consignment / van stock** (routes-aware allocation) — **L**

---

### 3.6 Manufacturing / Production  *(NOT_STARTED · area XL)*

> Greenfield (T3.1). All financial/operational dependencies (GL/AR/AP/stock/products) are shipped; **multi-level BOM** and **valuation/COGS (T2.2)** are the gating prerequisites for WIP costing. New movement types PRODUCTION_ISSUE/PRODUCTION_RECEIPT/SCRAP; new perms PRODUCTION.{MANAGE,VIEW,SCHEDULE,QC}, LABOUR.RECORD.

- [~] **Bill of Materials — multi-level / recursive** — **M** — V3 ships single-level `product_components`; multi-level expansion deferred (FR-PROD-16). *Foundation; build first.*
- [ ] **BOM versions & effectivity** (date-range, yield/scrap %, status) — **M**
- [ ] **Phantom / non-inventory components** — **S**
- [ ] **Production Orders** (lifecycle DRAFT→RELEASED→IN_PROGRESS→COMPLETED/CLOSED/VOID; `PRODUCTION_ORDER` numbering) — **L**
- [ ] **Production Order lines** (component allocation/issue, scrap, received) — **M**
- [ ] **Routings** (work centers, operation sequences, timings) — **L**
- [ ] **Work orders / shop-floor execution** — **L**
- [ ] **Labour tracking** (time per work order, operator) — **M**
- [ ] **Production costing — WIP & variance** (DR WIP / CR material+wages+overhead → DR FG / CR WIP) — **L** — *requires T2.2 valuation.*
- [ ] **Finished-goods receipt** (WIP→FG stock + GL) — **M**
- [ ] **Quality control & inspection** — **L**
- [ ] **By-products / co-products** — **M**
- [ ] **Scrap / yield / loss tracking** — **S**
- [ ] **Batch / lot / serial production tracking** — **M**
- [ ] **Production variance reporting** (material/labour/overhead) — **M**
- [ ] **MRP — demand explosion / netting / suggested orders** — **XL**
- [ ] **Production scheduling** (forward/backward, capacity) — **L**
- [ ] **MPS — master production schedule** — **L**
- [ ] **Manufacturing lead-time data** (product-level for MRP) — **S**
- [ ] **Capacity & resource planning** (work-center load) — **M**
- [ ] **Subcontracting / outsourced production** — **L**
- [ ] **Rework / re-manufacturing** — **M**
- [ ] **Engineering BOM vs Manufacturing BOM** — **M**

---

### 3.7 HR & Payroll (Tanzania)  *(NOT_STARTED · area XL)*

> Greenfield, single module `com.erp.modules.hr`. Hard dependency on GL (payroll journals) + Cash&Bank (disbursement). Lands ~V16 (after Cash V13, VAT V14). TZ statutory detail is the heavy part (PAYE/NSSF/NHIF/WCF/SDL). New perms HR.{EMPLOYEE.VIEW, PAYROLL.RUN, PAYROLL.POST, PAYROLL.REVERSE, EMPLOYEE_SELF_SERVICE}; numbering `PAYROLL-####`/`PAYSLIP-####`; event PAYROLL.FINALISED.

- [ ] **Employee master & org structure** — **L**
- [ ] **Employment contracts & types** — **M**
- [ ] **Attendance & time tracking** — **M**
- [ ] **Leave management** (types, accruals, approvals) — **L**
- [ ] **Payroll setup** (earnings, deductions, benefits → GL accounts) — **M**
- [ ] **Statutory deductions — Tanzania** (PAYE 14-band, NSSF 10%/5%, NHIF, WCF, SDL 0.5%, HESLB) — **L** — *YTD-cumulative computation; seeded, updatable rate tables.*
- [ ] **Payroll runs & processing** (DRAFT→FINALISED→POSTED) — **L**
- [ ] **Payslips** (employee visibility, YTD) — **M**
- [ ] **Payroll→GL posting** (`PAYROLL.FINALISED` handler; DR expense / CR payable per type) — **L**
- [ ] **Loans & advances to employees** — **M**
- [ ] **Expense claims & reimbursement** — **M**
- [ ] **End-of-service & gratuity** (TZ statutory) — **M**
- [ ] **Tax certificates & statutory returns** (P9, PAYE/NSSF/NHIF/WCF/SDL) — **M**
- [ ] **Payroll ledger & history** (immutable; correction = reversal run) — **S**
- [ ] **Salary review & increment** (bulk, effective-dated, audited) — **S**
- [ ] **Self-service portal** (payslips, leave, personal data) — **M**
- [ ] **Recruitment & onboarding (lite)** — **S**
- [ ] **Performance & appraisal** — **L**
- [ ] **Training & development** — **M**

---

### 3.8 Fixed Assets  *(NOT_STARTED · area L)*

> Greenfield (T3.3). Depends on GL + fiscal periods (shipped); soft AP link for acquisition. Extend `gl_configs` with FIXED_ASSETS / ACCUMULATED_DEPRECIATION / DEPRECIATION_EXPENSE / GAIN_LOSS_ON_DISPOSAL / REVALUATION_RESERVE / CWIP. New perms FA.{VIEW, REGISTER.MANAGE, DEPRECIATE, DISPOSE, VERIFY}; event DEPRECIATION.RUN.EXECUTED; numbering `DEPR-####`. v1 scope = register→acquire→straight-line/reducing-balance→depreciation run→disposal/write-off→reports.

- [ ] **Asset register master** (cost, category, life, salvage, location, supplier link) — **M**
- [ ] **Asset categories master** — **S**
- [ ] **Asset acquisition from AP/Purchases** (capital flag on bill → asset entry) — **M**
- [ ] **Depreciation — straight-line** — **M**
- [ ] **Depreciation — reducing balance** — **M**
- [ ] **Depreciation schedule generation** — **M**
- [ ] **Depreciation run (GL posting, fiscal-period gated, idempotent)** — **M** — *first live FA posting handler.*
- [ ] **Asset disposal / sale** (NBV, proceeds, gain/loss to GL) — **M**
- [ ] **Write-off (scrapped/abandoned)** — **M**
- [ ] **Asset transfers / relocation** (no GL) — **S**
- [ ] **Net book value calculation** (derived) — **S**
- [ ] **Asset tags / barcodes / serials** — **S**
- [ ] **Asset register reports** — **M**
- [ ] **GL account mapping (gl_configs extension)** — **S**
- [ ] **Fiscal-period gating** — **S**
- [ ] **FA permissions & audit** — **S**
- [ ] **Depreciation — units of production** — **L** *(deferred)*
- [ ] **Asset revaluation** (reserve to GL) — **L** *(deferred)*
- [ ] **Asset impairment** — **L** *(deferred)*
- [ ] **Capital Work in Progress (CWIP)** — **L** *(deferred)*
- [ ] **Component depreciation** — **L** *(deferred)*
- [ ] **Maintenance schedules** — **M** *(deferred)*
- [ ] **Insurance tracking** — **S** *(deferred)*
- [ ] **Periodic asset verification / physical count** — **M** *(deferred)*

---

### 3.9 CRM  *(NOT_STARTED · area L)*

> Greenfield (T3.4). Reads Parties (V2), feeds Sales Orders (T2.1). Thin v1: lead/opportunity/activity capture and pipeline; deep automation deferred. *(Enumerated at the standard CRM sub-feature level; sequence after Sales Orders so opportunity→quote→order flows.)*

- [ ] **Lead capture & management** (source, status, qualification) — **M**
- [ ] **Account / contact management** (links Parties customers) — **M**
- [ ] **Opportunity / pipeline management** (stages, value, probability, forecast) — **M**
- [ ] **Activity & interaction tracking** (calls, emails, meetings, notes, tasks) — **M**
- [ ] **Quotation integration** (opportunity → quote → SO) — **M** — depends on Sales Orders (T2.1).
- [ ] **Sales pipeline / funnel reporting** — **M** — depends on Reporting (T2.3).
- [ ] **Campaign management** (marketing campaigns, response tracking) — **M**
- [ ] **Customer segmentation & targeting** — **S**
- [ ] **Case / ticket / service management** (post-sale support) — **L**
- [ ] **Customer communication log / email integration** — **M** — depends on Notifications (X.2).
- [ ] **CRM dashboards & KPIs** (win rate, cycle time, conversion) — **M**
- [ ] **CRM permissions & audit** — **S**

---

### 3.10 Projects / Job Costing  *(NOT_STARTED · area L)*

> Greenfield (T3.5). Built thin: project master + WBS + timesheets + budgets + cost-tagging seams across GL/AR/AP/stock/sales (nullable `project_id` FK + read filters — additive, no change to core posting). Revenue recognition (WIP, milestone billing, ASC-606) and Gantt/resource-levelling deferred.

- [ ] **Project master** (code, customer, budget, dates, status, manager) — **M**
- [ ] **Work Breakdown Structure / tasks** (hierarchical, roll-up) — **M**
- [ ] **Timesheets** (labour hours against tasks; billable flag) — **M**
- [ ] **Project budgeting** (planned cost by type; budget-control flags) — **M**
- [ ] **Job costing — cost/revenue tagging across GL/AR/AP/stock/sales** — **L** — *the integration spine: nullable `project_id` on `journal_entries`, `ar_payments`, `ap_payments`, `stock_movements`, `sales_invoices`.*
- [ ] **Cost ledger / project actuals roll-up** (labour/material/subcontract/overhead/revenue, margin, WIP, variance) — **L**
- [ ] **GL/AR/AP/stock tagging seams** (additive context field at post-time) — **M**
- [ ] **Resource allocation & utilisation** — **M**
- [ ] **Milestone billing / revenue recognition** — **L** — *full ASC-606 deferred.*
- [ ] **Project reporting** (health, variance, profitability) — **M** — depends on Reporting (T2.3).
- [ ] **Gantt / timeline visualisation** — **S** *(deferred; data model supports it)*

---

### 3.11 Budgeting & Management Accounting  *(NOT_STARTED · area XL)*

> Greenfield (T3.6). GL is **dimension-ready** (`journal_lines.branch_id` is a nullable analysis tag, ADR-0013 D-7) but the **cost-centre / dimension framework is not built** — it is the gating prerequisite for budgeting, profit centres, and controlling.

- [~] **Dimensions / analytical tags on GL lines (beyond branch)** — **L** — branch tag exists; cost-centre/project/department dimensions designed but not built (extend `journal_lines` with nullable FKs + GROUP BY on reads). *Build-first enabler.*
- [ ] **Cost centre master & allocation** (dept/division/project/location) — **M**
- [ ] **Profit centres** (revenue+expense roll-up by centre) — **M**
- [ ] **Budgets** (per account/period/cost-centre; `budgets` + `budget_lines`) — **M**
- [ ] **Budget vs actual variance reporting** — **M** — reads GL + budgets + fiscal periods.
- [ ] **Forecasting / rolling forecast** — **L**
- [ ] **Commitment accounting** (PO commitments vs budget) — **L** — reads `po_lines`; ties to procurement encumbrance.
- [ ] **Allocations / cost distribution** (overhead across centres; posts GL `source_type=ALLOCATION`) — **M**
- [ ] **What-if / scenario modelling** — **M**
- [ ] **Management accounting reports & dashboards** (departmental P&L, contribution margin) — **M** — depends on Reporting (T2.3).

---

### 3.12 Cross-cutting platform capabilities  *(PARTIAL · area XL)*

> The spine is shipped (outbox, audit, Money, numbering). The remaining items are **enablers many modules want** — documents/PDF and notifications especially are demanded by Sales, Procurement, AR/AP, HR, and Reporting.

- [x] **Transactional outbox / event-driven architecture** (V6) — **XL** — *multi-instance SKIP-LOCKED upgrade is a separate item (§3.13).*
- [x] **Audit trail** (append-only, immutable, searchable) (V1) — **M**
- [x] **Money embeddable** (amount + currency) (V1, ADR-0005) — **S** — *FX/arithmetic deferred.*
- [x] **Number sequence / document numbering** (`code_sequence`, pessimistic lock) — **M** — *admin view/reset UI missing.*
- [~] **Fiscal period administration & period close** (V10) — **M** — tables + close gating exist; admin UI + cross-module `PeriodCloseValidator` missing.
- [~] **Multicurrency & FX framework** — **L** — Money + `base_currency` exist; rate table + `RevaluationService` + cross-currency settlement absent (X.6; shared with §3.1).
- [~] **Observability / structured error logging** — **S** — `GlobalExceptionHandler` catch-all has a TODO and does not log before 5xx (ISSUES-REGISTER #4, ~1-line fix); outbox metrics absent.
- [~] **Scheduled jobs / task scheduling** — **S** — `@Scheduled` outbox polling works; no general scheduler (needed for depreciation, payroll, ageing snapshots).
- [ ] **Document generation / PDF rendering** (invoices, POs, GRNs, statements, payslips) — **M** — X.1; `DocumentService` + template renderer; add iText/PDF lib; outbox-triggerable.
- [ ] **Email / SMS notifications** — **M** — X.2; `NotificationDispatcher implements DomainEventHandler`; Spring Mail present but unused; needs provider + queue/retry + templates.
- [ ] **Bulk import (CSV/Excel)** — **M** — X.4; `BulkImportService` parse→validate→batch-apply→rollback; enables seeding + corrections.
- [ ] **Bulk export (CSV/Excel/PDF)** — **M** — X.4; trial balance, P&L, BS, registers.
- [ ] **Approval workflow (threshold-based multi-step)** — **M** — X.5; `approval_status`/`approver_id` + finalise-path checks (PO, payment runs, credit-note reversal). *Required before Manufacturing/high-value payments.*
- [ ] **Template / branding management for documents** — **M** — companion to X.1; per-company letterhead/logo/terms.
- [ ] **File attachments / file storage** — **M** — invoice/PO scans, payment proof, customs docs; storage backend + metadata + permissions.
- [ ] **Global search / cross-module entity search** — **L**
- [ ] **In-app notifications / user alerts** — **M** — distinct from X.2; `notification_queue` + frontend bus.
- [ ] **Webhooks / external integration / public API** — **L**
- [ ] **Data archival / retention management** — **L** — archive old transactions preserving audit + reporting.

---

### 3.13 Non-functional / Production-hardening  *(PARTIAL · area XXL)*

> Strong foundation (audit, RBAC/ScopeGuard, pagination everywhere, additive migrations, Dockerfile, login brute-force lock). The gaps are operational readiness, not features.

- [x] Migrations discipline (Flyway additive, validate, no rollback; V1–V12 frozen) — **S**
- [x] Logout / refresh-token denylist (G2) — **S** — *access-token JTI denylist (Redis) deferred for multi-instance.*
- [x] Audit log no-update/delete DB grant (F11) — **S**
- [~] Login brute-force limiting done; **API rate limiting** (per-user quotas) — **M**
- [~] Health endpoint: liveness done; **readiness probe** (DB + outbox lag) — **S**
- [~] Structured logging framework: audit done; **business-module audit events** + **catch-all exception logging** (ISSUES-REGISTER #4) — **S/M**
- [~] Containerisation: Dockerfile + compose done; **K8s manifests / Helm + non-root hardening + probes** — **M**
- [~] Secrets management: env-var wiring done; **secret-store integration runbook (Vault/AWS/K8s)** — **M**
- [~] Logging infra: stdout (12-factor) done; **exception logging + aggregation (ELK/Datadog)** — **S**
- [~] Performance: pagination + indexes done; **load test, slow-query profiling, SLOs** — **L**
- [~] Multi-tenant: company isolation enforced + tested; **resource limits / query timeouts / tenant quotas** — **M**
- [~] Security hardening: SQLi/CSRF/XSS/bcrypt/JWT done; **full OWASP review + CSP headers + ZAP scan** — **M**
- [ ] **Stable RS256 JWT key from secret store (G1, production gate)** — **M** — app supports file mode; deployment runbook + key-rotation review missing.
- [ ] **Outbox multi-instance scaling (SELECT … FOR UPDATE SKIP LOCKED)** — **S** — X.8; not blocking single-container QA.
- [ ] **Metrics (Micrometer/Prometheus: outbox latency, handler errors, DB pool, API timing)** — **M**
- [ ] **Distributed tracing (trace-id, Jaeger/Zipkin/OTel)** — **L**
- [ ] **OpenAPI / Swagger API docs (springdoc)** — **M**
- [ ] **CI/CD pipeline (GitHub Actions: lint/test/build/push/deploy + E2E)** — **M**
- [ ] **i18n / localisation (English-only today; Swahili for TZ)** — **M**
- [~] **Responsive / mobile UI** — **M** — viewport + some breakpoints; full mobile UX review for field agents.
- [ ] **Offline mode / field-agent support (service worker + sync queue)** — **L**
- [ ] **Accessibility (WCAG 2.1 AA + axe gate in CI)** — **M**
- [ ] **Data retention / GDPR-like (SAR export, retention policy, soft-delete jobs)** — **M**
- [ ] **Backup / restore / disaster recovery (snapshots, PITR, RTO/RPO runbook)** — **M**

---

## 4. Critical dependencies (the must-build-first chain)

These are the chokepoints — build them in this order or the things downstream have nowhere to land.

1. **GL posting engine (SHIPPED, V10)** — the gate for everything financial. AR/AP/Cash/FA/Payroll/Manufacturing/Projects/Budgeting all post here; TB/P&L/BS/cash-flow read from here. *Already done — this is why the rest is now possible.*
2. **Inventory VALUATION → COGS (T2.2)** — the highest-leverage *operational* dependency. Needed for a **true P&L and balance-sheet inventory value**, **margin analysis**, **ABC analysis**, **landed-cost capitalisation**, and **all manufacturing WIP costing**. Until this lands, P&L omits cost of sales and Manufacturing cannot be costed. Build early in operational depth.
3. **Cash & Bank (V13)** — completes the cash side of AR/AP (settlement + the cash leg), replaces the hard-coded Cash-1000 lookup, and **unblocks the cash-flow statement**, bank reconciliation, payroll disbursement, P-card, and forecasting.
4. **Cost-centre / dimension framework on GL** — needed before **Budgeting**, **profit centres**, **controlling/management-accounting reports**, and clean **project costing**. GL is dimension-ready (branch tag); the framework activates it.
5. **Multi-level BOM (Products depth)** — gating prerequisite for **Manufacturing** (MRP, routings, WIP).
6. **Document generation / PDF (X.1) + Notifications (X.2)** — cross-cutting enablers many modules *want* (send POs/invoices/statements/payslips; email statements/RFQs; alerts). Not strictly blocking but unlock real-world usability across Sales, Procurement, AR/AP, HR, Reporting.
7. **Approvals (X.5)** — required before Manufacturing ships and for high-value PO/payment governance.
8. **Multicurrency / FX (X.6)** — cross-cutting; base-currency-only is accepted for now, but FX revaluation + realised/unrealised gain-loss is the deferred round that touches GL, Sales, Purchases, AR, AP.
9. **Reporting module skeleton (T2.3)** — `com.erp.modules.reporting` is the hub P&L/BS/analytics/dashboards all live in; create it early so statements have a home.

---

## 5. Phased delivery sequence to "full ERP"

Ordered phases (not calendar dates). Relative size noted per phase. Cross-cutting enablers are interleaved where a phase first needs them. Phase labels extend ROADMAP's tier language.

### Phase A — Finish the finance tier  *(relative size: M–L)*
**Adds:** Cash & Bank (ADR-0016 / V13) ✅; VAT return + WHT (ADR-0017 / V14) ✅; **Reporting (ADR-0018 / V15) ✅ — P&L + Balance Sheet + Cash-Flow (indirect) + GL account-ledger drill-down, all comparative, with PDF/Excel/CSV export** (T2.3 first slice); **year-end close + reopen (ADR-0019 / V16) ✅**. **Phase A COMPLETE — the full Tier-1 finance tier ships.**
**You can now:** see real cash position and reconcile banks; settle AR/AP with a posted cash leg; file a periodic VAT return; **produce a P&L and a balance sheet** for a period; close a fiscal year and carry balances forward. *This is the moment ERPCLEAN2 is demonstrably an ERP, not just balanced books.*

### Phase B — Operational depth + cash-flow completion  *(relative size: L–XL)*
**Adds:** **Inventory valuation + COGS** (T2.2 / V1n — `stock_cost_layers`, cost columns, COGS posting handler); **Cash Flow Statement** + stock valuation/ageing + sales/purchase analytics views (T2.3 depth); Sales Orders + POS + full returns/credit-notes + delivery notes (T2.1 / V1n); Procurement upstream — requisitions, RFQ, multi-step PO approval (with X.5 Approvals), supplier price lists, blanket POs, purchase returns/RMA, landed cost; inventory multi-location + transfers + stock counts + reservations. Enablers: **Documents/PDF (X.1)**, **Notifications (X.2)**, **Import/Export (X.4)**, **Approvals (X.5)**.
**You can now:** report true gross margin and inventory value; run a full order-to-cash (quote→order→deliver→invoice) and till sales; run a controlled procure-to-pay with requisitions/approvals/landed-cost; manage stock across locations with counts and reservations; send branded PDFs and email notifications; bulk-import master data.

### Phase C — Extension modules: Fixed Assets, Manufacturing, HR/Payroll  *(relative size: XL)*
**Adds:** **Fixed Assets** (register → depreciation runs → disposal, GL-posted, fiscal-period gated; gl_configs FA accounts); **Manufacturing** (multi-level BOM → production orders → component issue → WIP costing on T2.2 → finished-goods receipt; routings/work-orders/labour; MRP later) (T3.1); **HR & Payroll** (employee master, leave/attendance, TZ statutory payroll, payroll→GL, disbursement via Cash&Bank; self-service portal) (T3.2 / ~V16). Enabler: **cost-centre/dimension framework** if not yet built; **scheduled-jobs framework** for depreciation/payroll runs.
**You can now:** depreciate assets to the books on a schedule; manufacture finished goods with real WIP costing and variance; run a compliant Tanzanian payroll posting to GL with statutory deductions and payslips.

### Phase D — CRM, Projects, Budgeting & controlling  *(relative size: L–XL)*
**Adds:** **CRM** (leads→opportunities→activities→pipeline, feeding Sales Orders) (T3.4); **Projects / Job Costing** (project master + WBS + timesheets + cost-tagging seams across GL/AR/AP/stock/sales + cost roll-up; milestone billing) (T3.5); **Budgeting & Management Accounting** (cost-centre master + dimensions + budgets + variance + forecasting + allocations + departmental P&L) (T3.6); plus Reporting dashboards/KPIs + comparative reporting + custom report builder.
**You can now:** manage a sales pipeline; cost and bill projects; budget by cost centre and report budget-vs-actual + management dashboards.

### Phase E — Cross-cutting completion + production hardening  *(relative size: L–XL, interleaved throughout)*
**Adds (interleave as enablers earlier where needed):** Multicurrency/FX (X.6 — rates, revaluation, realised/unrealised gain-loss); webhooks/public API; global search; file attachments; in-app alerts; data archival. **Hardening:** catch-all exception logging (immediate), metrics (Micrometer/Prometheus), readiness probe, stable RS256 from secret store (G1), outbox SKIP-LOCKED (X.8), OpenAPI docs, CI/CD pipeline, K8s/Helm + hardened image, i18n (Swahili), accessibility (axe gate), load-test + SLOs, OWASP review + CSP, backup/DR runbook, data-retention/GDPR.
**You can now:** transact in multiple currencies with FX gain/loss; integrate with external systems; and **run in production** with observability, automated deploys, secure secrets, multi-instance scaling, and DR.

> **Note on interleaving:** Observability's 1-line exception-logging fix and the readiness probe should land in **Phase A** (cheap, high value). Approvals (X.5) and Documents/Notifications (X.1/X.2) are pulled into **Phase B** because Procurement and Sales depth need them. Secret-store + CI/CD should be in place before the first real production deploy regardless of which phase the business is in.

---

## 6. Definition of "full ERP" / exit criteria

ERPCLEAN2 is a **complete full ERP** when all of the following are true:

**Financial core**
- [ ] Cash & Bank operational: receipts/payments post a cash leg, banks reconcile.
- [ ] Periodic VAT return computes output-vs-input VAT and produces a filing record.
- [ ] Year-end close automated: P&L→retained-earnings + opening carry-forward + new FY opened.
- [ ] AR and AP sub-ledgers continuously reconcile to their GL control accounts (already true).
- [ ] Multicurrency: transact in foreign currency with FX revaluation and realised/unrealised gain-loss posting.

**Reporting**
- [ ] Trial Balance (done), Profit & Loss, Balance Sheet, Cash Flow Statement, and Statement of Changes in Equity all produce correct, period-comparable, drill-downable output.
- [ ] Sales / purchase / inventory analytics + role-based dashboards/KPIs available.
- [ ] Every report exportable to PDF/Excel/CSV; key reports schedulable/emailable.

**Operational depth**
- [ ] Sales: SO + POS + returns/credit-notes + delivery notes + pricing depth + (multi-channel optional).
- [ ] Procurement: requisitions + RFQ + multi-step approvals + landed cost + supplier returns/contracts.
- [ ] Inventory: **valuation + COGS posting**, multi-location + transfers, physical counts, batch/serial, reservations.

**Extension modules**
- [ ] Fixed Assets: register + depreciation runs + disposals, all GL-posted.
- [ ] Manufacturing: multi-level BOM + production orders + WIP costing + finished-goods receipt (MRP optional for "full").
- [ ] HR & Payroll: TZ-statutory payroll posting to GL with payslips + self-service.
- [ ] CRM: lead→opportunity→pipeline feeding Sales Orders.
- [ ] Projects: cost/revenue tagging + job-cost roll-up.
- [ ] Budgeting: cost centres + budgets + variance + management reports.

**Cross-cutting & platform**
- [ ] Document/PDF generation + notifications + approvals + bulk import/export + attachments + cost-centre dimensions all available.
- [ ] Outbox event-driven backbone (done) extended for multi-instance.

**Production-readiness**
- [ ] Structured logging + metrics + tracing + readiness probes.
- [ ] Secrets from a secret store (stable RS256), CI/CD pipeline, K8s/Helm deploy, OpenAPI docs.
- [ ] Security (OWASP/CSP), accessibility (WCAG 2.1 AA), i18n (Swahili), backup/DR + data-retention.

**Cross-cutting invariants that must hold throughout**
- [ ] Every posting append-only; corrections are reversing entries (no updates/deletes on financial tables).
- [ ] Every read/write tenant-scoped (`RequestContext` + `ScopeGuard`) and RBAC-gated (`@perm`).
- [ ] Every mutation audited; every cross-module integration DTO-only over the outbox.
- [ ] Migrations strictly additive (never edit a shipped Vn).

---

*This document tracks the remaining path. As each item ships (requirements → ADR → build → security/verify → PR → owner merge), flip its checkbox and cite the migration/ADR. Never push to main; never edit a shipped migration. ADR/Vn numbers above ADR-0015 / V12 are indicative — claim the next free number when an item enters its cycle.*

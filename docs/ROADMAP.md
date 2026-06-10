# ERPCLEAN2 Roadmap

A living, sequenced plan for the OWNER + team. Grounded in the shipped code (migrations V1–V9, ADR-0001–0012). Each roadmap item names its real integration seam, what it reuses, what it depends on, an effort size, and how it ships (ADR-00NN / Vn).

**Goal: a full ERP.** This is the complete intended scope, not an MVP shortlist — every tier below (Accounting, Operational depth, the Tier-3 extension modules — Manufacturing, HR/Payroll, Fixed Assets, CRM, Projects, Budgeting — and the cross-cutting platform capabilities) is **committed scope**. The tiering is **build order driven by dependencies**, not priority-to-drop: we build the financial spine first because everything else (COGS, payroll, depreciation, project costing, VAT) posts into it. Nothing here is "nice to have" — it's "not yet built."

---

## 1. Where we are

**Shipped — the trading / inventory core:**

- **IAM** (V1, ADR-0001/2/3/4) — org → company → branch, RBAC via `@perm` + `ScopeGuard` (`ScopeGuard.java:102` resolves uid→company per target type), `audit_logs`, per-company + multi-branch tenant scope (`RequestContext`).
- **Parties** (V2, ADR-0006) — customers / suppliers / agents / other_parties; customers already carry `credit_limit_amount`/`currency` (V2:18-50) but **no balance tracking**.
- **Products** (V3/V4, ADR-0007) — products, units_of_measure, price_lists, recipes/components, barcodes.
- **Sales** (V5, ADR-0008) — invoice-channel `sales_invoices` with `net_total_amount` / `vat_total_amount` / `gross_total_amount` / `tax_summary` JSONB (V5:58-61), lines, cash/mobile-money tenders, VAT via `tax_rates`; finalise → **`SALE.FINALISED`** event.
- **Stock** (V7, ADR-0010) — `stock_on_hand`, `stock_movements`, adjustments; **driven entirely by events** (`SaleIssueStockHandler` consumes `SALE.FINALISED`).
- **Purchases** (V8, ADR-0011) — purchase_orders → goods_receipts → **`STOCK.RECEIVED`** event.
- **Routes** (V9, ADR-0012) — sales territories.
- **Platform** — transactional **OUTBOX** (V6, ADR-0009): `domain_events` + `@Scheduled DomainEventDispatcher` + `DomainEventHandler` beans + `processed_events` idempotency (`IdempotencyGuard`); Money = `amount NUMERIC(19,4)` + `currency CHAR(3)` (ADR-0005); `code_sequence` numbering; Flyway additive migrations.

**Verdict:** The operational core is complete and event-wired — sales deduct stock, receipts add stock, all idempotently. **It is not yet an ERP, because nothing posts to books:** no General Ledger, no AR/AP balances, no payments-to-books, no financial statements, no VAT return. Today's events fire into the void on the finance side — `domain_events` carries `SALE.FINALISED` and `STOCK.RECEIVED` but the only registered consumers are the four stock handlers (`com.erp.modules.stock.events`). The accounting consumers do not exist yet.

---

## 2. Guiding principles

These are how Sales and Stock shipped, and how every roadmap item below will ship:

1. **Requirements → ADR → build → verify/security → deploy.** Each item starts with a `docs/requirements/*.md`, then a numbered ADR (next is **ADR-0013**), then code, then a security review (`docs/security/*.md`), then merge via PR (owner merges — never push to main).
2. **Additive migrations only.** Never edit V1–V9. The next migration is **V10**; items below claim sequential Vn numbers. Append-only posting tables — corrections are **reversing entries**, never updates/deletes (PROJECT-CONVENTIONS §3.6).
3. **Reuse the platform spine — do not rebuild it.** Every financial item rides the **outbox** (`DomainEventHandler` + `IdempotencyGuard` + `processed_events`), the **Money** embeddable (ADR-0005), **RBAC** (`@perm` + new `MODULE.RESOURCE.ACTION` permission codes), **ScopeGuard** (extend `companyIdOf` with new target types), and **audit_logs**. New financial modules are pure consumers/readers — **DTO-only across module boundaries**, never importing sales/stock entities (the `SaleIssueStockHandler` consumes `SaleFinalisedPayload`, not `SalesInvoice`).
4. **Ship thin, then deepen.** Sales shipped invoice-channel first (no SO, no POS); Stock shipped event-driven movements before valuation. Each item below ships a minimal posting/recording slice first, then adds depth (allocation, valuation, multicurrency) in later increments.

---

## 3. The roadmap, tiered

For each item: **scope** · **integration point** · **reuse** · **depends-on** · **effort** · **ships-as**.

### Tier 1 — Accounting (the critical path: nothing reports until this lands)

**T1.1 General Ledger (GL)** — double-entry chart of accounts + journals; the posting engine everything else feeds.
- *Integration point:* New `SalesPostingHandler implements DomainEventHandler`, `eventType() = SALE.FINALISED`, mirroring `SaleIssueStockHandler` exactly — reads `sales_invoices` (net/vat/gross + `tax_summary`) by the payload's `invoiceUid`, looks up `gl_configs` accounts, posts balanced `journal_entries`+`journal_lines`, writes `processed_events(consumer="GL.SALES_POST")` in the same TX. `SaleVoidingHandler` (`SALE.VOIDED`) posts the reversing entry. Extend `ScopeGuard.companyIdOf` with `case "account"`.
- *Reuse:* Outbox + `IdempotencyGuard`; Money (ADR-0005); `code_sequence` (JOURNAL_BATCH); append-only posting (PROJECT-CONVENTIONS §3.6); audit on `GL.POST`; perms `GL.VIEW`/`GL.MANAGE`/`GL.POST`.
- *Depends-on:* V1 (IAM), V5 (sales totals + `tax_summary`), V6 (outbox), ADR-0005, ADR-0009. All shipped.
- *Effort:* **XL** · *Ships-as:* **ADR-0013 / V10** (chart_of_accounts, journal_batches, journal_entries, journal_lines, fiscal_periods, gl_configs; seed TZ COA + `gl_configs` mapping SALES_REVENUE→4100, VAT_PAYABLE→2200, AR→1200, AP→2100, INVENTORY→1300, COGS→5100).

**T1.2 Accounts Receivable (AR)** — customer sub-ledger: open invoices, payments, allocation, balances, ageing.
- *Integration point:* `ArInvoicePostedHandler` consumes `SALE.FINALISED` → creates `ar_invoices` row (idempotent on `invoiceUid`). Payments/allocations reduce open balance. `ArBalanceCalculator` + `ArAgingService` read the sub-ledger. References `customers.credit_limit_amount` (V2:18-50). Publishes `AR.PAYMENT.RECORDED` for GL.
- *Reuse:* Outbox; Money; RBAC (`AR.INVOICE.VIEW`, `AR.PAYMENT.RECORD`, `AR.PAYMENT.ALLOCATE`); `ScopeGuard`; audit; uid/id duality (`UidEntity`).
- *Depends-on:* Sales (shipped), Parties (shipped), outbox (shipped). GL is **parallel, non-blocking** (AR emits events GL later consumes).
- *Effort:* **L** · *Ships-as:* **ADR-0014 / V11** (ar_invoices, ar_payments, ar_payment_allocations; views ar_customer_balance, ar_ageing_detail).

**T1.3 Accounts Payable (AP)** — supplier sub-ledger + supplier bills + 3-way match.
- *Integration point:* `ApGrReceivedHandler` consumes `STOCK.RECEIVED` → queues the goods receipt for bill matching (today V8 has PO+GR but **no supplier_bills table**). `match3Way` reconciles `supplier_bill_lines` ↔ `po_lines` ↔ `goods_receipt_lines` (V8 GR lines carry `unit_cost_amount`/`qty_in_base`). Publishes `AP.BILL.POSTED` for GL.
- *Reuse:* Outbox; Money; RBAC (`AP.BILL.VIEW`, `AP.BILL.APPROVE`, `AP.MATCHING.REVIEW`, `AP.PAYMENT.RUN`); `code_sequence` (supplier bill no.); audit.
- *Depends-on:* Purchases + GoodsReceipt (shipped), Parties (shipped), outbox (shipped).
- *Effort:* **L** · *Ships-as:* **ADR-0015 / V12** (supplier_bills, supplier_bill_lines, bill_po_line_match, ap_payments, ap_payment_allocations; view ap_supplier_balance).

**T1.4 Payments / Cash & Bank** — cash/bank accounts, receipts & disbursements, reconciliation; the bridge from money movement to GL.
- *Integration point:* Settles AR/AP allocations (T1.2/T1.3) and posts the cash side to GL (T1.1) — `DEBIT cash / CREDIT AR` on receipt, `DEBIT AP / CREDIT bank` on payment. Today sales tenders live on `sales_invoice_payments` (cash/mobile-money) with no cash-account home; this gives them one.
- *Reuse:* Money; outbox (`CASH.RECEIPT.RECORDED`, `BANK.PAYMENT.MADE`); RBAC (`CASH.RECEIPT.RECORD`, `BANK.PAYMENT.RUN`, `BANK.RECONCILE`); audit; `code_sequence`.
- *Depends-on:* GL (T1.1), AR (T1.2), AP (T1.3).
- *Effort:* **L** · *Ships-as:* **ADR-0016 / V13** (cash_bank_accounts, cash_transactions, bank_reconciliations).

**T1.5 Tax / VAT return** — ✅ **DONE** (ADR-0017 / V14, `com.erp.modules.tax`). Monthly accrual-basis output−input VAT return, DRAFT→FILED with a synchronous GL settlement (DR `VAT_PAYABLE` / CR new `VAT_INPUT` / ±`VAT_DUE`) + credit carry-forward; input VAT split to `VAT_INPUT` at AP bill-match. **Withholding tax** included (register + certificates + AR/AP payment legs). 525 backend tests, 6 web screens. TRA EFD/VFD deferred.
- *Integration point:* `TaxReturnService.compute` aggregates finalised `sales_invoices` (output VAT, from `tax_summary` per band) + received `goods_receipt_lines` (input VAT) over a period window. Reuses the `tax_rates` master (per-company, vat_status-keyed) and the `InvoiceTotalsCalculator` tax-exclusive algorithm (ADR-0008 D-4). Note: **input VAT needs AP/purchase-VAT input** — today GR lines have cost but no VAT columns (V8:50-85), so this is realistic only after AP (T1.3) or a purchase-VAT slice.
- *Reuse:* `tax_rates`; Money; `code_sequence` (TAXRET-####); outbox (`TAXRETURN.GENERATED`); audit; RBAC (`TAX.RETURN.VIEW`, `TAX.RETURN.SUBMIT`).
- *Depends-on:* Sales (shipped), AP/purchase-VAT (T1.3), GL optional. TRA EFD/VFD fiscalisation stays deferred (OQ-SALES-03).
- *Effort:* **L** · *Ships-as:* **ADR-0017 / V14** (vat_return_periods, vat_return_lines, vat_adjustments, vat_return_summary JSONB; optional withholding_taxes).

### Tier 2 — Operational depth

**T2.1 Sales Order / POS / Returns** — pre-invoice order capture, point-of-sale channel, credit notes / returns.
- *Integration point:* New channels feed the existing finalise path; returns publish `SALE.VOIDED` (already defined, `DomainEventType.java:21`) so stock reversal (`SaleReversalStockHandler`) and GL reversal (T1.1) fire for free. Credit notes flow into AR (T1.2) as negative open items.
- *Reuse:* Sales finalise pipeline; outbox; tax_rates; Money; RBAC.
- *Depends-on:* Sales (shipped); AR (T1.2) for credit-note balances; GL (T1.1) for posting.
- *Effort:* **L** · *Ships-as:* **ADR-00NN / V1n** (sales_orders, pos_sessions, credit_notes).

**T2.2 Inventory valuation / COGS** — cost layers (FIFO/weighted-avg), inventory value, cost of goods sold.
- *Integration point:* Extends `stock_movements` (V7) with cost; on `SALE.FINALISED` a valuation handler computes COGS and emits to GL (`DEBIT COGS / CREDIT inventory`); on `STOCK.RECEIVED` it layers in `goods_receipt_lines.unit_cost_amount`. **COGS posting is the dependency knot:** it needs both stock valuation *and* GL.
- *Reuse:* Stock movements + posting service; outbox; Money; GL configs (INVENTORY, COGS accounts).
- *Depends-on:* Stock (shipped), GL (T1.1), AP cost data (T1.3).
- *Effort:* **L** · *Ships-as:* **ADR-00NN / V1n** (stock_cost_layers; cost columns on movements).

**T2.3 Reporting & Dashboards** — Trial Balance, P&L, Balance Sheet, sales/stock/purchase analytics, KPIs.
- *Integration point:* Read-only **DB views** over transactional + GL tables, all filtered by `RequestContext` company/branch. `TrialBalanceQuery` = `GROUP BY account, SUM(debit−credit)` over `journal_lines`. New `com.erp.modules.reporting` service + `ReportingController` (no `ReportingController` exists today). MVP can sum transactional lines; financial statements require GL (T1.1).
- *Reuse:* `RequestContext` scoping; `ApiResponse<T>`/`PageMeta`; Money; outbox (optional cache invalidation); RBAC (`REPORTING.VIEW`, `REPORTING.EXPORT`, `FINANCIAL.VIEW`).
- *Depends-on:* Sales/Stock/Purchases (shipped) for analytics; **GL (T1.1) for TB/P&L/BS**.
- *Effort:* **M** · *Ships-as:* **ADR-00NN / V1n** (v_sales_by_customer, v_sales_by_product, v_stock_aging, v_purchase_variance, v_trial_balance_source; optional reporting_snapshots).

**T2.4 Pricing & credit control** — promotions/price-rule depth; enforce `customers.credit_limit_amount` at finalise.
- *Integration point:* Credit check reads the AR open-balance (T1.2) against `customers.credit_limit_amount` (V2:18-50) in the sales finalise path; blocks/warns over limit.
- *Reuse:* AR balances; price_lists (shipped); RBAC.
- *Depends-on:* AR (T1.2), Sales (shipped).
- *Effort:* **M** · *Ships-as:* **ADR-00NN / V1n**.

### Tier 3 — Extension modules

| Item | Scope | Integration / depends-on | Effort | Ships-as |
|---|---|---|---|---|
| **T3.1 Manufacturing** | Production orders, BOM consumption | Reuses recipes/components (V3) + stock posting (V7); emits stock issue/receipt events; COGS via T2.2 | **XL** | ADR-00NN / V1n |
| **T3.2 HR / Payroll** | Employees, pay runs, statutory deductions | Posts payroll journals to GL (T1.1); cash side via T1.4 | **XL** | ADR-00NN / V1n |
| **T3.3 Fixed Assets** | Asset register, depreciation | Depreciation runs post to GL (T1.1) on a schedule; fiscal_periods (T1.1) | **L** | ADR-00NN / V1n |
| **T3.4 CRM** | Leads, opportunities, activities | Reads parties (V2); feeds Sales Orders (T2.1) | **L** | ADR-00NN / V1n |
| **T3.5 Projects / Job costing** | Project ledger, WIP | Tags costs/revenue to projects across GL/AR/AP | **L** | ADR-00NN / V1n |
| **T3.6 Budgeting** | Budgets vs actuals | Reads GL (T1.1) + fiscal_periods; reporting (T2.3) overlay | **M** | ADR-00NN / V1n |

### Cross-cutting platform

| Item | Scope | Integration / reuse | Effort | Ships-as |
|---|---|---|---|---|
| **X.1 Documents / PDF** | Invoice/PO/GRN/statement PDFs | `DocumentService` + template renderer per aggregate; reuses `code_sequence` numbering; can be outbox-triggered | **M** | ADR-00NN / V1n |
| **X.2 Notifications** | Email/SMS on events | `NotificationDispatcher implements DomainEventHandler` over `domain_events`; `app_users.email/phone` exist; Spring Mail in pom | **M** | ADR-00NN / V1n |
| **X.3 Period / fiscal-year mgmt** | Open/close periods, year-end | `fiscal_periods` lands with GL (T1.1); `PeriodCloseValidator` blocks posting into closed periods across all financial modules | **M** | ADR-00NN (folds into ADR-0013) |
| **X.4 Import / Export** | Bulk CSV/Excel load | `BulkImportService`: parse → row-validate → transactional batch-apply → error collection/rollback | **M** | ADR-00NN |
| **X.5 Approvals** | Threshold-based approval workflow | `approval_status`/`approver_id` fields + threshold check in finalise paths (PO, payment run); gated by RBAC + audit | **M** | ADR-00NN |
| **X.6 Multicurrency (deepen)** | FX rates, revaluation, realised gain/loss | Money already pairs amount+currency (ADR-0005); add `currency_master` + rate table + `RevaluationService` posting to GL; **add `base_currency` to companies** (ADR-0005 D-4 config) | **L** | ADR-00NN / V1n |
| **X.7 Observability** | Structured error logging | Enhance `GlobalExceptionHandler` to log before 5xx (ISSUES-REGISTER #4 — 1-line fix); add metrics on outbox dispatch | **S** | (no migration) |
| **X.8 Outbox scaling** | Multi-instance dispatch | `DomainEvent` already has `@Version`; add `SELECT … FOR UPDATE SKIP LOCKED` when running >1 container (`DomainEventDispatcher` docstring flags this seam) | **S** | (no migration) |

---

## 4. Critical path & sequencing

The spine is **GL → (AR + AP + Cash/Bank) → auto-posting from Sales/Purchases/Stock → Reporting (TB/P&L/BS)**. Everything financial is gated on GL because the books are the single source of truth a TB/P&L/BS reads from.

```
                          ┌──────────────────────────────────────┐
SHIPPED CORE (V1–V9):     │ IAM · Parties · Products · Sales ·     │
events already firing →   │ Stock · Purchases · Routes · OUTBOX    │
                          └───────────────┬──────────────────────┘
                                          │ SALE.FINALISED / SALE.VOIDED / STOCK.RECEIVED
                                          ▼
                              ┌───────────────────────┐
                              │  T1.1 GL (V10)         │  ◀── the gate; nothing reports without it
                              │  posting engine + COA  │
                              └───┬─────────┬─────────┬┘
                  ┌───────────────┘         │         └───────────────┐
                  ▼                         ▼                         ▼
        ┌──────────────────┐    ┌──────────────────┐      ┌──────────────────────┐
        │ T1.2 AR (V11)    │    │ T1.3 AP (V12)    │      │ T2.2 COGS/valuation  │
        │ ◀ SALE.FINALISED │    │ ◀ STOCK.RECEIVED │      │ (needs stock val.+GL)│
        └────────┬─────────┘    └────────┬─────────┘      └──────────┬───────────┘
                 └──────────┬────────────┘                          │
                            ▼                                       │
                  ┌──────────────────────┐                         │
                  │ T1.4 Cash & Bank(V13)│ (settles AR/AP→GL)       │
                  └──────────┬───────────┘                          │
                             │              ┌─────────────────┐     │
                             │              │ T1.5 VAT return │◀────┤ (input VAT needs AP)
                             │              │ (V14)           │     │
                             ▼              └─────────────────┘     ▼
                  ┌────────────────────────────────────────────────────┐
                  │ T2.3 Reporting: TB ◀ journal_lines; P&L/BS ◀ GL     │
                  └────────────────────────────────────────────────────┘
```

**What unblocks what:**
- **GL (T1.1) unblocks everything financial** — AR/AP/Cash all post to it; TB/P&L/BS read from it.
- **AR needs Sales** (shipped, consumes `SALE.FINALISED`); **AP needs Purchases/GR** (shipped, consumes `STOCK.RECEIVED`). Both can build **in parallel** once GL's posting contract exists.
- **Cash/Bank needs GL + AR + AP** (it settles sub-ledgers and posts the cash side).
- **COGS needs stock valuation, which needs GL** (the `DEBIT COGS / CREDIT inventory` entry has nowhere to land otherwise).
- **VAT return needs AP / purchase-VAT input** for the input-VAT side (GR lines have cost but no VAT today, V8:50-85).
- **TB needs only GL** (`GROUP BY account, SUM(debit−credit)` over `journal_lines`); **P&L / BS need GL + a full period of postings**.

---

## 5. Recommended next 3 increments

Each is one **requirements → ADR → build → security/verify → PR** cycle.

**Increment 1 — General Ledger foundation (ADR-0013 / V10).** *Effort: XL.*
Requirements doc `docs/requirements/gl.md` → ADR-0013 → V10 migration (6 tables + TZ COA seed + `gl_configs`) → entities + `GLPostingService` (validate debits==credits, lookup account by config, post in one TX) → **`SalesPostingHandler`** consuming `SALE.FINALISED` and **`SaleVoidingHandler`** consuming `SALE.VOIDED`, both mirroring `SaleIssueStockHandler` (consumer marker via `IdempotencyGuard`, system `RequestContext`, `@Transactional(MANDATORY)`) → extend `ScopeGuard` with `case "account"` → `TrialBalanceQuery` → perms `GL.VIEW`/`GL.MANAGE`/`GL.POST`. **Acceptance:** finalising a sale auto-creates a balanced journal entry; voiding posts the reversal; trial balance nets to zero. This turns the system from "tracks stock" into "keeps books."

**Increment 2 — AR + AP sub-ledgers (ADR-0014 / V11 and ADR-0015 / V12), built in parallel.** *Effort: L + L.*
`ArInvoicePostedHandler` (consumes `SALE.FINALISED` → `ar_invoices`) with payments/allocation/ageing; `ApGrReceivedHandler` (consumes `STOCK.RECEIVED` → queue supplier bill) with 3-way match against `goods_receipt_lines`. Both publish `AR.PAYMENT.RECORDED` / `AP.BILL.POSTED` for GL to consume. **Acceptance:** customer/supplier balances and ageing are correct end-to-end; AR/AP events post to GL.

**Increment 3 — Cash & Bank + Reporting MVP (ADR-0016 / V13, ADR-00NN / V1n).** *Effort: L + M.*
Cash/bank accounts that settle AR/AP allocations and post the cash leg to GL; then the `com.erp.modules.reporting` module with a **Trial Balance** off `journal_lines`, P&L and Balance Sheet off GL, plus first sales/stock analytics views. **Acceptance:** a clean TB, a P&L and a BS for a period — the moment ERPCLEAN2 is demonstrably an ERP, not just an inventory system.

---

*Sequence from here. ADR numbers above ADR-0015 are indicative — claim the next free number when each item enters its requirements→ADR cycle. Migrations claim sequential Vn (next free is V10). Never edit a shipped migration; correct via a new one. Never push to main — branch → PR → owner merges.*

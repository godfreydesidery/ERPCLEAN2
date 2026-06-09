# 0015 — Accounts Payable (AP) data model: the supplier sub-ledger behind GL 2100 — bill entry, 3-way match within tolerance, the first GL posting for the purchase, single + payment-run payments, debit notes, opening balances

- **Status:** Accepted
- **Date:** 2026-06-09
- **Deciders:** solutions-architect (owner-ratified AP requirements 2026-06-09 — all AP scoping forks resolved; no ADR-0015-blocking open question remains, accounts-payable.md §11)
- **Context source:** [docs/requirements/accounts-payable.md](../requirements/accounts-payable.md) (RATIFIED 2026-06-09 — FR-AP-01..15, BR-AP-01..11, US-AP-01..06, §7 flows, §10 accepted boundary, §11 OQ log; ground truth for every rule below). [ADR-0013](0013-general-ledger-data-model.md) + the **shipped** GL code (`com.erp.modules.gl`): `GLPostingService.post(JournalEntryDraft)` (the synchronous double-entry engine), `JournalEntryDraft`/`LineDraft`, `GLConfigResolver.resolve(companyId, GlConfigKey)` (keys incl. `ACCOUNTS_PAYABLE`/`INVENTORY`/`COGS`/`CASH` all **seeded but not yet posted-to** — D-13/D-15), `FiscalPeriodResolver`. **Note: a Goods Receipt posts to STOCK only, NOT to GL** (ADR-0011 / gl.md §10.2) — so AP's bill match is where the purchase first hits the books. [ADR-0011](0011-purchases-data-model.md) + [V8__purchases.sql](../../backend/src/main/resources/db/migration/V8__purchases.sql) (`purchase_orders`/`purchase_order_lines`: `ordered_qty_in_base`, `received_qty_in_base` maintained, `unit_cost_amount`; `goods_receipts`/`goods_receipt_lines`: `received_qty`, `qty_in_base`, `unit_cost_amount`, `line_cost_amount`, FK `purchase_order_line_id`; `STOCK.RECEIVED` event). The 3-way match reads PO + GR lines as DTOs. [ADR-0006](0006-parties-data-model.md) + [V2__parties.sql](../../backend/src/main/resources/db/migration/V2__parties.sql) (`suppliers.supplier_kind` `GOODS`|`SERVICE`, per-company multi-branch). [ADR-0009](0009-transactional-outbox.md) (the outbox — **AP is NOT a consumer**, D-5). [ADR-0005](0005-money-and-currency.md) (`Money` NUMERIC(19,4)+currency; `companies.base_currency` added in V10). [ADR-0007](0007-products-data-model.md) (`code_sequence(company_id, entity_kind)` row-locked numbering). [[db-naming-convention]] verified against shipped V1–V10. Latest shipped migration is **V10** → AP is **`V12__accounts_payable.sql`** (additive; never edits V1–V11). AR is the sibling [ADR-0014](0014-accounts-receivable-data-model.md) / **V11**.

This ADR is the **technical data model + integration design** for the Accounts Payable module (AP, ROADMAP T1.3, Increment 2), built in parallel with AR. It translates the ratified spec into tables, columns, types, keys, indexes, the enforcement split, the 3-way match, the GL-posting mechanism, the reconciliation design, permissions/audit/scope — concrete enough that the backend engineer writes `V12__accounts_payable.sql` + the entities + the bill-match + payment posting paths **without guessing a business rule**. It writes **no production code, no entities, no migration SQL**. Nothing ratified is re-litigated.

## Context

AP is the **supplier sub-ledger** — the per-supplier detail (bills, payables, payments, balances) behind the GL **`2100 Accounts Payable`** control account. The platform is shipped and consumed unchanged: GL gives the posting engine + `gl_configs` (with `ACCOUNTS_PAYABLE`/`INVENTORY` seeded, not yet posted-to); Purchases gives the PO + GR (the 3-way match's first two legs); Parties gives the Supplier; `code_sequence` gives `BILL-####`/`PAYRUN-####`. The central force mirrors AR's, with **one decisive asymmetry**:

- **The reconciliation invariant (BR-AP-02, NFR-AP-01): Σ(supplier payable balances) == GL 2100 balance at all times.** The asymmetry to AR: **the Goods Receipt did NOT post to GL** (Stock only — ADR-0011 / gl.md §10.2). So **the AP bill match is the FIRST GL posting for the purchase** — AP *must* post (the mirror of AR's no-double-post: AR must not re-post because Sales already did; AP must post because the GR did not). Resolved in D-3/D-6/D-8.

- **The debit account of the matched bill is an ADR decision (the spec flag): INVENTORY vs a PURCHASES/GRNI-clearing expense account.** Because inventory **valuation + COGS is deferred (T2.2)**, v1 AP books the purchase debit per `gl_configs` **without a COGS roll-up** (no cost layer, no COGS). The forces: posting to `INVENTORY` (1300) inflates an asset that v1 never relieves to COGS (the balance sheet carries inventory at bill cost forever until T2.2 builds valuation) — vs posting to a `PURCHASES` expense (a clean P&L expense that T2.2 later reclassifies). Resolved in D-6 with a recommended choice + a NEW gl_config key.

- **The GL-posting mechanism — synchronous `GLPostingService.post` vs an outbox event.** Same forces as AR: a bill match / payment / debit note is a single in-request user command on one aggregate; sync keeps sub-ledger + control atomic (no reconciliation gap); the outbox is for cross-aggregate async. Resolved in D-4.

- **3-way match within tolerance.** AP reads PO + GR lines (DTOs) and reconciles quantity + price against the bill within a tolerance; over-tolerance holds the bill (no post). The forces: where the match result lives (a `bill_match` row vs flags on the bill), the tolerance config shape, the partial-bill-vs-partial-GR policy. Resolved in D-2/D-3.

- **AP is bill-entry-driven — NO event consumer.** Unlike AR (whose open item is system-created from `SALE.FINALISED`), AP's payable is **operator-entered**; the GR does NOT auto-create a payable (BR-AP-01, the accepted bill-driven-AP gap — no GRNI accrual). So AP has **no outbox consumer** (confirmed — D-5); it reads PO/GR via DTOs synchronously at bill-entry time.

- **Schema freeze / migration ordering.** IAM=V1 … AR=V11 — all frozen by the time AP lands. AP is **additive `V12__accounts_payable.sql`**; it FKs only frozen `companies`/`branches`/`app_users`/`suppliers` (and intra-module AP tables); it references PO/GR lines and GL `journal_entries` by **scalar uid/id**, never a cross-module FK.

## Decision

### D-1 — Module placement: one `com.erp.modules.ap` module; controllers flat in `com.erp.api`

AP lives under **`com.erp.modules.ap`** — a flat sibling of `gl`/`ar`/`purchases` (PROJECT-CONVENTIONS §2). Internal layout:

```
com.erp.modules.ap
├── domain.entity   SupplierBill, SupplierBillLine, BillMatch, ApPayment, ApPaymentAllocation,
│                   ApDebitNote
├── domain.dto      SupplierBillDto, SupplierBillLineDto, EnterBillRequest, BillLineRequest,
│                   BillMatchResultDto, RunMatchRequest, AcceptVarianceRequest,
│                   ApPaymentDto, PaySingleBillRequest, PaymentRunRequest, PaymentAllocationDto,
│                   ApDebitNoteDto, RaiseDebitNoteRequest, SetOpeningBalanceRequest,
│                   ApBalanceDto, ApReconciliationDto, ApAgeingRowDto
├── domain.enums    SupplierBillStatus (DRAFT|MATCHED|HELD|APPROVED|PAID|PARTIALLY_PAID),
│                   SupplierBillSource (BILL|OPENING_BALANCE),
│                   BillMatchStatus (MATCHED|HELD_PRICE_VARIANCE|HELD_QTY_VARIANCE|VARIANCE_ACCEPTED),
│                   ApPaymentKind (SINGLE|PAYMENT_RUN)
├── repository      SupplierBillRepository, SupplierBillLineRepository, BillMatchRepository,
│                   ApPaymentRepository, ApPaymentAllocationRepository, ApDebitNoteRepository
├── service         SupplierBillService(+Impl)        — enter + line edit,
│                   BillMatchService(+Impl)            — 3-way match + post on match (D-3/D-6),
│                   ApPaymentService(+Impl)            — single + payment run + post (D-3/D-6),
│                   ApDebitNoteService(+Impl)          — debit note + post (D-6),
│                   ApOpeningBalanceService(+Impl)     — opening balances + post (D-6),
│                   PurchaseMatchReader                — reads PO/GR via Purchases DTOs (D-11),
│                   ApBalanceQuery / ApAgeingQuery / ApReconciliationQuery (D-7/D-8),
│                   ApBillNumberGenerator / ApPaymentRunNumberGenerator — BILL-/PAYRUN-#### (D-12)
└── (no events package — AP is bill-entry-driven, no outbox consumer — D-5)
```

Controllers flat in `com.erp.api` — `SupplierBillController`, `BillMatchController`, `ApPaymentController`, `ApDebitNoteController`, `ApOpeningBalanceController`, `ApStatementController` — touching only services (`ModuleBoundaryTest`).

### D-2 — The six table groups: `supplier_bills` + `supplier_bill_lines`, `bill_match`, `ap_payments` + `ap_payment_allocations`, `ap_debit_notes`

All masters/logs plural; junctions singular. Every table carries `company_id` (BR-AP-08); `branch_id` is a nullable analysis tag (mirrors GL D-7). Cross-module refs (PO/GR lines, `journal_entries`) are **scalar uid/id, no FK** (D-11).

> **Why a separate `bill_match` table rather than match flags on the bill (the pick).** The 3-way match is a **per-line** reconciliation (bill line vs PO line vs GR line, quantity + price) with a status and the variance amounts. A bill can hold for review on some lines and match on others; the match is re-run after a variance is accepted. Storing the match result as a per-line `bill_match` row (one per `supplier_bill_line`) keeps the variance audit trail, lets `HELD` and `MATCHED` coexist within a bill, and records *what* varied (price vs qty, by how much). Flags on the bill line would lose the variance detail and the re-match history. (Examined in Alternatives.)

> **Why payment header + allocation junction (the pick).** A single payment / payment run is one money event (one CR Cash/Bank GL post); it settles one-or-many payables. One payment → many allocations (a payment run pays many bills); one bill → many allocations (a partly-paid bill paid over several payments). Many-to-many resolved by `ap_payment_allocations`, with the bill's `outstanding_amount` maintained down. A payment run is just an `ap_payments` row with `kind = PAYMENT_RUN`, a `PAYRUN-####` number, and many allocations — **no separate batch-header table needed** (the payment IS the batch header). (Examined in Alternatives.)

#### (a) `supplier_bills` (the payables — the sub-ledger detail behind GL 2100) + (b) `supplier_bill_lines`

##### `supplier_bills`

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | lines/match/allocations/debit-notes FK this |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_supplier_bill_uid`; URLs address by uid; `ScopeGuard case "supplierbill"` (D-12) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag |
| `supplier_id` | `BIGINT` | NO | FK → `suppliers(id)` (Parties, frozen V2) — the creditor |
| `bill_number` | `VARCHAR(30)` | YES | our internal `BILL-####` from `code_sequence (company_id,'AP_BILL')` (D-12); NULL while `DRAFT`, set on entry-finalise (the PO/GR `…_when_…` precedent); `uq_supplier_bill_company_number` |
| `supplier_invoice_no` | `VARCHAR(60)` | NO | the **supplier's own invoice number** (their demand-for-payment ref) — distinct from our `bill_number` |
| `source` | `VARCHAR(20)` | NO | `SupplierBillSource`: `BILL`\|`OPENING_BALANCE`; CHECK below |
| `purchase_order_uid` | `VARCHAR(26)` | YES | the `purchase_orders.uid` the goods bill matches against (scalar, **no FK** — D-11); NULL for an opening balance or a (deferred) non-goods bill |
| `bill_date` | `DATE` | NO | the supplier's bill date |
| `due_date` | `DATE` | NO | bill terms if stated, else supplier net-days, else net-on-receipt (OQ-AP-02); drives ageing |
| `net_amount` | `NUMERIC(19,4)` | NO | goods/services net; `Money`, base currency |
| `vat_amount` | `NUMERIC(19,4)` | NO | input VAT if the bill states it (OQ-AP-04); DEFAULT 0 |
| `gross_amount` | `NUMERIC(19,4)` | NO | the payable total (net + vat) |
| `outstanding_amount` | `NUMERIC(19,4)` | NO | unpaid balance — maintained down by payments/debit-notes; starts == `gross_amount` once posted |
| `currency` | `VARCHAR(3)` | NO | = company base currency (BR-AP-10) |
| `status` | `VARCHAR(25)` | NO | `SupplierBillStatus`: `DRAFT`\|`MATCHED`\|`HELD`\|`APPROVED`\|`PARTIALLY_PAID`\|`PAID`; DEFAULT `'DRAFT'`; CHECK below |
| `posted_gl_entry_uid` | `VARCHAR(26)` | YES | the `journal_entries.uid` of the match post (DR Inventory-or-Purchases / CR 2100) — scalar, no FK; NULL until matched+posted |
| `matched_at` / `matched_by` | `TIMESTAMPTZ`/`BIGINT` | YES | set when the bill matches/posts |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | … | mixed | standard; append-only post-posting (BR-AP-09) |

- `uq_supplier_bill_uid UNIQUE (uid)`; `uq_supplier_bill_company_number UNIQUE (company_id, bill_number)` (NULLs distinct → DRAFTs coexist, the PO precedent).
- `uq_supplier_bill_supplier_invoice UNIQUE (company_id, supplier_id, supplier_invoice_no)` — **prevents entering the same supplier invoice twice** (a finance-grade duplicate-payable guard; the AP analogue of the AR per-invoice uniqueness).
- `fk_supplier_bill_company`, `fk_supplier_bill_branch`, `fk_supplier_bill_supplier` (→ `suppliers`).
- `chk_supplier_bill_source CHECK (source IN ('BILL','OPENING_BALANCE'))`.
- `chk_supplier_bill_status CHECK (status IN ('DRAFT','MATCHED','HELD','APPROVED','PARTIALLY_PAID','PAID'))`.
- `chk_supplier_bill_amounts CHECK (net_amount >= 0 AND vat_amount >= 0 AND gross_amount >= 0 AND outstanding_amount >= 0 AND outstanding_amount <= gross_amount)`.
- `chk_supplier_bill_dates CHECK (due_date >= bill_date)`.
- `chk_supplier_bill_number_when_posted CHECK ((status = 'DRAFT' AND bill_number IS NULL) OR (status <> 'DRAFT' AND bill_number IS NOT NULL))` (the PO/GR pattern).

Indexes:
```
CREATE INDEX ix_supplier_bills_company           ON supplier_bills (company_id);
CREATE INDEX ix_supplier_bills_company_supplier  ON supplier_bills (company_id, supplier_id);
CREATE INDEX ix_supplier_bills_status            ON supplier_bills (company_id, status);
CREATE INDEX ix_supplier_bills_open              ON supplier_bills (company_id, supplier_id, due_date)
    WHERE status IN ('MATCHED','APPROVED','PARTIALLY_PAID');                          -- payment-run selection + ageing working set
CREATE INDEX ix_supplier_bills_po                ON supplier_bills (company_id, purchase_order_uid);
```

##### `supplier_bill_lines` (child)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_supplier_bill_line_uid` |
| `supplier_bill_id` | `BIGINT` | NO | FK → `supplier_bills(id)` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; denormalised (tenant predicate) |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; denormalised |
| `line_no` | `SMALLINT` | NO | `uq_supplier_bill_line_no (supplier_bill_id, line_no)` |
| `product_id` | `BIGINT` | YES | FK → `products(id)` (frozen V3); NULL for a non-stock/expense line (deferred service-bill path, OQ-AP-04) |
| `po_line_uid` | `VARCHAR(26)` | YES | the `purchase_order_lines.uid` this bill line matches (scalar, **no FK** — D-11); NULL for a non-PO line |
| `gr_line_uid` | `VARCHAR(26)` | YES | the `goods_receipt_lines.uid` this bill line draws against (scalar, no FK) |
| `description` | `VARCHAR(200)` | NO | the bill line text (snapshot) |
| `billed_qty` | `NUMERIC(19,6)` | NO | the quantity the supplier billed; CHECK `> 0` |
| `unit_cost_amount` | `NUMERIC(19,4)` | NO | the supplier's unit cost (matched vs PO `unit_cost_amount`) |
| `line_net_amount` | `NUMERIC(19,4)` | NO | billed_qty × unit_cost (net) |
| `currency` | `VARCHAR(3)` | NO | = base currency |
| `created_at`/`created_by` | … | mixed | append-only |

- `uq_supplier_bill_line_uid`, `uq_supplier_bill_line_no`; `fk_supplier_bill_line_bill` (→ `supplier_bills`), `fk_supplier_bill_line_company`/`_branch`, `fk_supplier_bill_line_product` (→ `products`, nullable).
- `chk_supplier_bill_line_qty CHECK (billed_qty > 0)`; `chk_supplier_bill_line_cost CHECK (unit_cost_amount >= 0 AND line_net_amount >= 0)`.
- Indexes: `ix_supplier_bill_lines_bill (supplier_bill_id)`; `ix_supplier_bill_lines_company (company_id)`; `ix_supplier_bill_lines_po_line (po_line_uid)`.

#### (c) `bill_match` (the 3-way match result — one row per bill line)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `supplier_bill_id` | `BIGINT` | NO | FK → `supplier_bills(id)` |
| `supplier_bill_line_id` | `BIGINT` | NO | FK → `supplier_bill_lines(id)` — the line matched |
| `po_unit_cost_amount` | `NUMERIC(19,4)` | YES | snapshot of the PO line unit cost at match (the price reference) |
| `gr_received_qty` | `NUMERIC(19,6)` | YES | snapshot of the GR received qty (the qty reference) |
| `billed_qty` | `NUMERIC(19,6)` | NO | snapshot of the bill line qty |
| `price_variance_amount` | `NUMERIC(19,4)` | NO | bill unit cost − PO unit cost (signed); DEFAULT 0 |
| `price_variance_pct` | `NUMERIC(9,4)` | NO | the variance as a fraction of PO cost; DEFAULT 0 |
| `qty_variance` | `NUMERIC(19,6)` | NO | billed_qty − received-not-yet-billed qty (signed); DEFAULT 0 |
| `match_status` | `VARCHAR(25)` | NO | `BillMatchStatus`: `MATCHED`\|`HELD_PRICE_VARIANCE`\|`HELD_QTY_VARIANCE`\|`VARIANCE_ACCEPTED`; CHECK below |
| `tolerance_pct` | `NUMERIC(9,4)` | YES | the price tolerance applied at match (snapshot of the config — OQ-AP-01) |
| `tolerance_abs_amount` | `NUMERIC(19,4)` | YES | the absolute tolerance applied (snapshot) |
| `accepted_by` | `BIGINT` | YES | the operator who accepted an over-tolerance variance (`AP.BILL.MATCH`); NULL until accepted |
| `accepted_at` | `TIMESTAMPTZ` | YES | when accepted |
| `matched_at` / `created_*` | … | mixed | when the match ran |

- `uq_bill_match_line UNIQUE (supplier_bill_line_id)` — one current match per bill line (re-match updates the row).
- `fk_bill_match_company`, `fk_bill_match_bill` (→ `supplier_bills`), `fk_bill_match_line` (→ `supplier_bill_lines`), `fk_bill_match_accepted_by` (→ `app_users`).
- `chk_bill_match_status CHECK (match_status IN ('MATCHED','HELD_PRICE_VARIANCE','HELD_QTY_VARIANCE','VARIANCE_ACCEPTED'))`.
- Indexes: `ix_bill_match_bill (supplier_bill_id)`; `ix_bill_match_company (company_id)`; `ix_bill_match_held (company_id) WHERE match_status IN ('HELD_PRICE_VARIANCE','HELD_QTY_VARIANCE')` (the held-bills review queue).

#### (d) `ap_payments` (single + payment run — `PAYRUN-####`) + (e) `ap_payment_allocations` (junction)

##### `ap_payments`

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_ap_payment_uid`; `ScopeGuard case "appayment"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)` |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag |
| `supplier_id` | `BIGINT` | YES | FK → `suppliers(id)`; the payee for a `SINGLE` payment (a payment run may span suppliers → NULL, the per-bill supplier is on each allocation's bill) |
| `payment_number` | `VARCHAR(30)` | NO | `PAYRUN-####` (run) or a single-payment series via `code_sequence` (D-12); `uq_ap_payment_company_number` |
| `kind` | `VARCHAR(20)` | NO | `ApPaymentKind`: `SINGLE`\|`PAYMENT_RUN`; CHECK below |
| `payment_date` | `DATE` | NO | value date; GL posting_date |
| `amount` | `NUMERIC(19,4)` | NO | total paid (= Σ allocations); CHECK `> 0` |
| `currency` | `VARCHAR(3)` | NO | = base currency |
| `tender_type` | `VARCHAR(20)` | NO | `CASH`\|`BANK_TRANSFER`\|`MOBILE_MONEY`; the Cash/Bank GL account is `gl_configs.CASH` in v1 (full method→account map is T1.4, OQ-AP-03) |
| `bank_reference` | `VARCHAR(80)` | YES | cheque/transfer ref (analysis) |
| `gl_entry_uid` | `VARCHAR(26)` | YES | the `journal_entries.uid` of the DR 2100 / CR Cash post (scalar, no FK) |
| `version` / audit | … | mixed | append-only; correction is a cancelling payment (BR-AP-09) |

- `uq_ap_payment_uid`, `uq_ap_payment_company_number`; `fk_ap_payment_company`/`_branch`/`_supplier`.
- `chk_ap_payment_amount CHECK (amount > 0)`; `chk_ap_payment_kind CHECK (kind IN ('SINGLE','PAYMENT_RUN'))`; `chk_ap_payment_tender CHECK (tender_type IN ('CASH','BANK_TRANSFER','MOBILE_MONEY'))`.
- Indexes: `ix_ap_payments_company (company_id)`; `ix_ap_payments_company_supplier (company_id, supplier_id)`; `ix_ap_payments_company_date (company_id, payment_date)`.

##### `ap_payment_allocations` (junction — singular, no `uid`)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; denormalised |
| `ap_payment_id` | `BIGINT` | NO | FK → `ap_payments(id)` |
| `supplier_bill_id` | `BIGINT` | NO | FK → `supplier_bills(id)` — the payable this slice settles |
| `allocated_amount` | `NUMERIC(19,4)` | NO | the slice paid against this bill; CHECK `> 0` |
| `created_at`/`created_by` | … | mixed | append-only |

- `uq_ap_payment_allocation_pair UNIQUE (ap_payment_id, supplier_bill_id)`.
- `fk_ap_payment_allocation_company`, `fk_ap_payment_allocation_payment` (→ `ap_payments`), `fk_ap_payment_allocation_bill` (→ `supplier_bills`).
- `chk_ap_payment_allocation_amount CHECK (allocated_amount > 0)`.
- Indexes: `ix_ap_payment_allocations_payment (ap_payment_id)`; `ix_ap_payment_allocations_bill (supplier_bill_id)`; `ix_ap_payment_allocations_company (company_id)`.

The **no-double-pay** invariant (BR-AP-06, NFR-AP-04): each allocation `≤ the bill's outstanding_amount`, and `Σ allocations ≤ gross_amount` — **service-enforced** in `ApPaymentService` (`BigDecimal`), serialising `outstanding_amount` under a `SELECT … FOR UPDATE` on the selected bills (a concurrent single payment + payment run cannot over-pay). A fully-settled bill (`status = PAID`, `outstanding = 0`) is excluded from `ix_supplier_bills_open` so a later run never re-selects it.

#### (f) `ap_debit_notes` (reduce a payable)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_ap_debit_note_uid`; `ScopeGuard case "apdebitnote"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)` |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag |
| `supplier_id` | `BIGINT` | NO | FK → `suppliers(id)` |
| `debit_note_number` | `VARCHAR(30)` | NO | `DBN-####` via `code_sequence (company_id,'AP_DEBIT_NOTE')`; `uq_ap_debit_note_company_number` |
| `supplier_bill_id` | `BIGINT` | YES | FK → `supplier_bills(id)` — the payable reduced (NULL for a general supplier credit) |
| `note_date` | `DATE` | NO | business date; GL posting_date |
| `amount` | `NUMERIC(19,4)` | NO | the reducing amount; CHECK `> 0` |
| `net_amount` | `NUMERIC(19,4)` | NO | the inventory/purchases-contra portion (DR AP / CR Inventory-or-Purchases) |
| `vat_amount` | `NUMERIC(19,4)` | NO | the VAT-contra portion (CR VAT input) if any; DEFAULT 0 |
| `currency` | `VARCHAR(3)` | NO | = base currency |
| `reason` | `VARCHAR(255)` | NO | supplier credit / over-charge / short-delivery (audited) |
| `gl_entry_uid` | `VARCHAR(26)` | YES | the `journal_entries.uid` of the reduction post (scalar, no FK) |
| `version` / audit | … | mixed | append-only |

- `uq_ap_debit_note_uid`, `uq_ap_debit_note_company_number`; `fk_ap_debit_note_company`/`_branch`/`_supplier`/`_bill`.
- `chk_ap_debit_note_amount CHECK (amount > 0 AND net_amount >= 0 AND vat_amount >= 0)`.
- Indexes: `ix_ap_debit_notes_company (company_id)`; `ix_ap_debit_notes_company_supplier (company_id, supplier_id)`; `ix_ap_debit_notes_bill (supplier_bill_id)`.

No standalone opening-balance table — an **opening balance is a `supplier_bills` row with `source = OPENING_BALANCE`** (status posts straight to `APPROVED`/payable, no match), uniform to statements/ageing/balances. No statement table — reads (D-7).

### D-3 — The sub-ledger engine: 3-way match within tolerance, maintained `outstanding_amount`, the FIRST-GL-posting rule

**The reconciliation invariant (BR-AP-02):** `Σ supplier_bills.outstanding_amount (company) == GL 2100 balance`. Each mutation keeps both sides moving by the same amount:

| event | sub-ledger effect | GL effect | who posts GL |
| --- | --- | --- | --- |
| goods receipt (Purchases) | — none in AP — | — none — (Stock only) | nobody (BR-AP-01 — the accepted no-GRNI gap) |
| bill entered (DRAFT) | `supplier_bills` DRAFT (+ lines) | — none — | nobody (nothing posts while unmatched/held) |
| 3-way match within tolerance | bill → `MATCHED`, `outstanding = gross` | **DR Inventory-or-Purchases [+ DR VAT input] / CR 2100** (the **FIRST GL posting for the purchase**) | **AP — sync `GLPostingService.post` (D-4)** |
| match over tolerance | bill → `HELD`, `bill_match` HELD_* | — none — (nothing posts while held) | nobody |
| accept variance | `bill_match` → `VARIANCE_ACCEPTED`, bill → `MATCHED` | DR Inventory-or-Purchases / CR 2100 (posts as on match) | **AP — sync post** |
| single payment / payment run | each bill `outstanding −= allocated`; `PAID`/`PARTIALLY_PAID` | DR 2100 / CR Cash/Bank (for the paid total) | **AP — sync post** |
| debit note | bill `outstanding −= amount` | DR 2100 / CR Inventory-or-Purchases[-or-VAT] | **AP — sync post** |
| opening balance | create bill (source OPENING_BALANCE), payable | DR Inventory-or-Purchases / CR 2100 (or DR opening-balance-equity contra — D-6) | **AP — sync post** |

**The 3-way match (`BillMatchService`, FR-AP-03/04/05, BR-AP-04):** for each `supplier_bill_line` with a `po_line_uid`/`gr_line_uid`, read the PO line (`unit_cost_amount`) + GR line (`qty_in_base`/`received_qty`) via `PurchaseMatchReader` (Purchases DTOs — D-11):
- **Quantity:** `billed_qty ≤ received-not-yet-billed qty` (per line, up to the GR's received qty not already billed — OQ-AP-05); over-billing the received qty → `HELD_QTY_VARIANCE`. Quantity must match within the (default zero) qty tolerance.
- **Price:** `|bill unit_cost − PO unit_cost|` within the tolerance (D below); over → `HELD_PRICE_VARIANCE`.
- **Within tolerance** on all lines → bill `MATCHED`, posts to GL (D-6). **Any line over tolerance** → bill `HELD`, nothing posts; an `AP.BILL.MATCH` operator accepts the variance (per line, audited → `VARIANCE_ACCEPTED`, then posts) or rejects the bill (no payable, no post).

**Tolerance config (OQ-AP-01 — recommended default, flag the value):** a per-company configurable **price tolerance = the greater of 2% of the PO unit cost OR a small absolute amount**, with **quantity exact** (no qty tolerance by default — over-received-qty billing is held). The tolerance lives as a **company setting** (recommend a thin `ap_settings` row keyed by company, or reuse a config table; the simplest additive home is a couple of columns the seeder sets — the engineer's call; the snapshot of the applied tolerance is stored on `bill_match` for audit). **The exact value is the open OQ-AP-01 — non-blocking; the concept (a tolerance exists, over-tolerance holds) is fixed.**

**Enforcement split:**

| invariant | enforcement | mechanism |
| --- | --- | --- |
| `outstanding ∈ [0, gross]` (single row) | **DB CHECK** | `chk_supplier_bill_amounts` |
| `gross == net + vat` (cross-field, single row) | **service** | `SupplierBillService` (the bill totals are computed/validated at entry) |
| no duplicate supplier invoice | **DB** | `uq_supplier_bill_supplier_invoice` |
| 3-way match within tolerance; over → HELD; nothing posts while held | **service** | `BillMatchService` (cross-document compare; the DTO reads) |
| Σ allocations ≤ gross; each ≤ outstanding (no double-pay) | **service** | `ApPaymentService` under `SELECT … FOR UPDATE` (BR-AP-06, NFR-AP-04) |
| bill posts to GL only on match (first GL posting) | **service** | `BillMatchService` calls `GLPostingService.post` on match (D-4/D-6) |
| company isolation | **DB + service** | `company_id` NOT NULL + FK + tenant predicate + `assertCanActIn` every read path |
| base-currency only | **service** | every amount currency == `companies.base_currency` (BR-AP-10) |
| append-only / correct via reversal/debit-note | **structural** | no delete path on posted bills/payments; correction is a cancelling payment / reversing match / debit note |

### D-4 — GL-posting mechanism: SYNCHRONOUS `GLPostingService.post(...)` in the same TX (NOT the outbox)

**Decision: a bill match / payment / debit note / opening balance posts to GL by a synchronous `GLPostingService.post` call in the same service TX as the sub-ledger write.** Same justification as AR (ADR-0014 D-4), restated for AP:
1. **Atomicity = reconciliation by construction.** The payable creation (on match) and the CR 2100 are one TX — no in-flight window where the sub-ledger and 2100 disagree (BR-AP-02 holds at all times). A payment's `outstanding` decrement and the DR 2100 are one TX.
2. **A bill match / payment is a single in-request user command** on one aggregate, not a cross-aggregate async reaction. The outbox buys no decoupling here.
3. **The engine is built for it; failure rolls back the command** (missing `gl_configs` debit account / `ACCOUNTS_PAYABLE` / Cash, or a closed period → the whole match/payment rolls back, the operator sees a clear error, nothing half-recorded). AP is an **allowed leaf→service dependency** on `gl.service.GLPostingService` (D-11). This is correct precisely because the bill match IS the authoritative event creating the liability — there is no upstream business act (like a sale) that must survive a GL failure; the AP command and the GL post are the same intent.

**AP has NO outbox consumer (D-5)** — there is no async creation path (the GR does not auto-create a payable). Every AP posting is synchronous.

### D-5 — AP is bill-entry-driven: NO event consumer (confirmed)

Unlike AR (`ArSalePostedHandler` consumes `SALE.FINALISED`), **AP consumes no event**. BR-AP-01 / FR-AP-02: a Goods Receipt does NOT create a payable; AP does **not** consume `STOCK.RECEIVED`. The payable is born only when an operator **enters a bill** and it **matches**. The accepted consequence — the liability is not on the books between GR and bill entry (no GRNI accrual) — is owner-accepted risk (§10.1), not a defect. AP therefore has **no `com.erp.modules.ap.events` package**; it reads PO/GR synchronously at match time via `PurchaseMatchReader` (D-11). This is the deliberate AR/AP asymmetry and it is confirmed here so the engineer builds no consumer.

### D-6 — The exact GL postings per event + the debit-account choice + the gl_config keys (incl. NEW key)

All postings go through `GLPostingService.post` (sync, D-4). Accounts resolved via `GLConfigResolver`. **The debit-account decision (the spec flag):**

> **Decision — book the matched-bill debit to a NEW `PURCHASES` gl_config key (a purchases/GRNI-clearing EXPENSE account, recommend `5150 Purchases`), NOT to `INVENTORY` (1300).** Rationale: inventory **valuation + COGS is deferred to T2.2** — v1 has no cost-layer machinery to relieve `1300 Inventory` to `5100 COGS` on a sale. If AP debited `1300`, the balance sheet would carry inventory at bill cost **forever** (it is never relieved in v1), overstating the asset and understating expense — a misleading set of books. Debiting a **`PURCHASES` expense** (5150) books the purchase as a period expense (the simple periodic-inventory model, correct for a v1 that does not value stock), and T2.2's valuation increment **reclassifies** purchases→inventory+COGS when it lands (the AP bill cost is the documented input it builds on — NFR-AP-08). So: **`PURCHASES` is the NEW gl_config key to seed** (→ `5150 Purchases`, EXPENSE/DEBIT, a new CoA account). The `INVENTORY` key stays seeded-but-not-posted-to until T2.2. (If the owner prefers perpetual-inventory semantics from day one and accepts the unrelieved-asset caveat, the debit can be remapped to `INVENTORY` by a single `gl_configs` change with no code change — the key is config, not hard-coded; flagged in Open items.)

| AP event | GL journal (via `GLPostingService.post`) | gl_config keys |
| --- | --- | --- |
| **goods receipt** | — none — (Stock only, BR-AP-01) | (none) |
| **bill entered / held** | — none — (nothing posts while unmatched/held) | (none) |
| **bill matched** (first GL posting) | DR **Purchases** `net_amount` · [DR VAT Input `vat_amount` if > 0] · CR **Accounts Payable** `gross_amount` | **`PURCHASES`** (NEW, → `5150`) + `VAT_PAYABLE`* + `ACCOUNTS_PAYABLE` |
| **single payment / payment run** | DR `ACCOUNTS_PAYABLE` (paid total) · CR Cash/Bank (paid total) | `ACCOUNTS_PAYABLE` + `CASH` (both seeded) |
| **debit note** | DR `ACCOUNTS_PAYABLE` `amount` · CR `PURCHASES` `net_amount` [· CR VAT Input `vat_amount`] | `ACCOUNTS_PAYABLE` + `PURCHASES` + `VAT_PAYABLE`* |
| **opening balance** | DR `PURCHASES`-or-`OPENING_BALANCE_EQUITY` `gross` · CR `ACCOUNTS_PAYABLE` `gross` | `ACCOUNTS_PAYABLE` + **`OPENING_BALANCE_EQUITY`** (NEW, shared with AR D-13) |

\* **Input VAT (OQ-AP-04):** v1 captures the bill's stated VAT into `vat_amount` for the payable total. The GL debit leg for input VAT is a **distinct account from output `VAT_PAYABLE`** in a correct VAT model — but the full input-VAT-recovery + VAT-return is **T1.5**. v1 recommendation: book the input-VAT debit to the **same `VAT_PAYABLE`** account (it nets output against input in one 2200 balance — acceptable for a v1 that does not yet file a return) **or** add a `VAT_INPUT` key (→ a new `2210 VAT Input` account) — recommend **the latter is deferred to T1.5**; v1 books input VAT to `VAT_PAYABLE` (net VAT position). Flag as OQ-AP-04 (non-blocking; the goods 3-way match is fixed). **Simplest v1:** if the bill states no VAT, `vat_amount = 0` and the VAT line is omitted (a zero line violates `chk_journal_line_one_side`).

`JournalSourceType` — AP introduces source values `AP_BILL`, `AP_PAYMENT`, `AP_DEBIT_NOTE`, reusing reserved `AP`/`OPENING_BALANCE` (the enum reserves `AP`, ADR-0013 D-13; widen the `chk_journal_*_source_type` IN-list via additive ALTER — D-14). `source_ref` = the AP document uid. A missing mapping / closed period **fails the AP command** (BR-GL-10 / FR-AP-07).

### D-7 — Statements + ageing + balance are READS (FR-AP-08, supplier balances)

- **AP balance per supplier** = `Σ supplier_bills.outstanding_amount WHERE supplier_id AND status IN (MATCHED,APPROVED,PARTIALLY_PAID)`. `ApBalanceQuery`, company-scoped.
- **Ageing** by `due_date` vs an as-at date (same five buckets as AR for consistency — Current / 1–30 / 31–60 / 61–90 / 90+), `ApAgeingQuery`, hitting `ix_supplier_bills_open`.
- **Reconciliation read** = `ApReconciliationQuery` → `{ subLedgerTotal, glControlBalance(2100), difference }` (the AP analogue of AR D-8; a non-zero difference is a finance-grade defect, FR-AP-08).
No materialised view in v1 (indexes suffice at QA scale; a Reporting snapshot is T2.3 — NFR-AP-08).

### D-8 — Reconciliation design (the crux) — sub-ledger ⇄ GL 2100, the FIRST-GL-posting rule made structural

Guaranteed by structure, not a periodic job:
1. **The GR posts nothing to GL** (ADR-0011) — so there is no prior 2100 entry to reconcile against; the **bill match is the sole creator of the 2100 credit** for a purchase. One matched bill ⇒ one 2100 credit (by AP) + one payable (by AP), the same amount, in one TX (D-4). (The mirror of AR's no-double-post: AR must not re-post; AP must post because the GR did not.)
2. **Every AP-originated event posts to GL synchronously and atomically** (D-4) — the sub-ledger movement and the 2100 movement are the same amount in the same TX; no eventual-consistency gap.
3. **Nothing posts while a bill is DRAFT or HELD** (D-3) — the payable enters the books exactly when it enters the sub-ledger (on match), never before.

`ApReconciliationQuery` surfaces the equality; an IT pins it (enter + match a bill, assert 2100 credited == sub-ledger payable; pay it, assert both drop by the paid amount). The AP analogue of GL's "trial balance nets to zero."

### D-9 — No credit-limit equivalent; the bill-match read direction

AP has **no AP-side limit check** (unlike AR's credit limit — the requirement specifies none for AP). The only cross-module read is AP→Purchases at match time (PO/GR lines via DTOs — D-11), and AP→GL for posting + reconciliation. **No cycle:** Purchases does not depend on AP (it emits `STOCK.RECEIVED` and is done; it does not know AP exists); GL does not depend on AP. AP is a pure leaf reader/poster.

### D-10 — No Sales-style cross-module relaxation needed

AP introduces **no relaxation of a Purchases invariant** (the AR↔Sales credit-sale enablement has no AP analogue — the GR already records `unit_cost_amount`/`qty_in_base`, exactly what the 3-way match reads). The only Purchases-side need is a **DTO read contract**: a `PurchaseOrderService`/`GoodsReceiptService` method returning PO line + GR line match facts (`po_line_uid`, `unit_cost_amount`, `received_qty`/`qty_in_base`, `received-not-yet-billed` residual) for a PO, company-scoped — an additive Purchases service method + DTO (the GL→Sales `findPostingTotalsByUidAndCompany` precedent), no Purchases schema change. Flagged as the one Purchases touch (D-11).

### D-11 — Module boundary: AP is a leaf reader/poster; the allowed edges

`ModuleBoundaryTest` discipline (PROJECT-CONVENTIONS §2, NFR-AP-06):
- **AP → `gl.service.GLPostingService` + `gl.service.GLConfigResolver` + `gl.domain.dto.JournalEntryDraft`** — the synchronous posting edge (D-4); leaf→service, DTO/service-interface only, never a GL entity. The AR ADR introduces the first such GL-service consumer edge; AP reuses the allow-rule.
- **AP → `purchases.domain.dto` + `PurchaseOrderService`/`GoodsReceiptService`** — the 3-way-match read (D-10), the established GL→Sales DTO-read pattern; never a Purchases entity/repository import.
- **AP → `suppliers` (Parties)** — `supplier_bills`/`ap_payments`/`ap_debit_notes` FK `suppliers(id)` (intra-DB FK to frozen V2 master, the accepted Sales/Purchases pattern); AP reads supplier net-days via a Parties DTO/scalar, never a Parties entity.
- **AP → `products` (Parties V3)** — `supplier_bill_lines.product_id` FK `products(id)` (intra-DB FK to frozen master).
- **No outbox edge** (D-5 — AP consumes no event); AP does NOT depend on `platform.events` for consumption (it may not need it at all).
- **No cross-module FK** into `purchase_orders`/`goods_receipts`/`journal_entries`: `purchase_order_uid`/`po_line_uid`/`gr_line_uid`/`gl_entry_uid` are plain `VARCHAR(26)` scalars (the `stock_movements.source_document_uid` discipline). FKs to `suppliers`/`products` are intra-DB (frozen masters).

### D-12 — ScopeGuard additions + numbering (`code_sequence` kinds)

`ScopeGuard.companyIdOf` gains the AP target types:
```java
case "supplierbill" -> supplierBills.findCompanyIdByUid(uid);
case "appayment"    -> apPayments.findCompanyIdByUid(uid);
case "apdebitnote"  -> apDebitNotes.findCompanyIdByUid(uid);
```
Each backed by a `findCompanyIdByUid` projection; `ScopeGuard` gains three AP repository deps (the accepted cross-cutting-spine pattern). `assertCanActIn` on **every read path** (NFR-AP-01): balance, ageing, bill list, held-bills queue, payment list, reconciliation.

**`code_sequence` kinds** (created on first use, no seeded row): `AP_BILL` (`BILL-####`), `AP_PAYMENT_RUN` (`PAYRUN-####`), `AP_PAYMENT_SINGLE` (a single-payment series — or reuse `AP_PAYMENT_RUN`; recommend one `AP_PAYMENT` kind covering both with the `kind` column distinguishing them — the simplest), `AP_DEBIT_NOTE` (`DBN-####`).

### D-13 — Permission catalogue + audit emit points + the gl_configs/CoA seed additions

**Permissions (FR-AP-15, seeded in V12, granted to `ORG_ADMIN`):**

| code | module | description |
| --- | --- | --- |
| `AP.VIEW` | ap | View the AP sub-ledger, balances, ageing, and the reconciliation read |
| `AP.BILL.ENTER` | ap | Enter a supplier bill (BILL-####) and edit its draft lines |
| `AP.BILL.MATCH` | ap | Run / accept the 3-way match; accept an over-tolerance variance |
| `AP.PAYMENT.RUN` | ap | Pay a single bill and run a payment run (PAYRUN-####) |
| `AP.DEBITNOTE` | ap | Raise a debit note / adjustment against an open payable |
| `AP.OPENING.SET` | ap | Enter AP opening balances at go-live |

**Audit emit points (NFR-AP-03):**

| action | when | target_type / target |
| --- | --- | --- |
| `AP.BILL.ENTER` | bill entered | `supplier_bills` / id |
| `AP.BILL.MATCH` | 3-way match run / accept-variance / reject | `supplier_bills` / id |
| `AP.BILL.POST` | matched bill posted to GL | `supplier_bills` / id |
| `AP.PAYMENT.MAKE` | single payment / payment run posted | `ap_payments` / id |
| `AP.DEBITNOTE.RAISE` | debit note raised + posted | `ap_debit_notes` / id |
| `AP.OPENING.SET` | opening balance entered + posted | `supplier_bills` / id |

**CoA + gl_configs seed additions (V12, additive):**
- **CoA:** add `5150 Purchases` (EXPENSE/DEBIT) per existing company (CROSS JOIN, deterministic seed-uid). `2100 Accounts Payable`, `1000 Cash`, `2200 VAT Payable`, `1300 Inventory` already seeded by V10. `OPENING_BALANCE_EQUITY`'s account (`3000`/`3100`) is seeded by AR's V11 (or AP seeds it if AP lands first — `ON CONFLICT` / idempotent seed).
- **`gl_configs`:** widen `chk_gl_config_key` to admit `PURCHASES` (and `OPENING_BALANCE_EQUITY` if not added by V11); seed `PURCHASES → 5150` per company. `ACCOUNTS_PAYABLE → 2100` is already seeded by V10 — AP reuses it (the key was seeded-not-posted; AP is the increment that makes it live).
- **`journal_*` source-type CHECK widen** for `AP_BILL`/`AP_PAYMENT`/`AP_DEBIT_NOTE`.
- A Java `ApGlSeeder` seeds the new CoA + key for new companies (the `GlConfigSeeder` precedent).

### D-14 — Migration: additive `V12__accounts_payable.sql`, never a V1–V11 edit; ordering

IAM=V1 … AR=V11 — all frozen. AP is **`V12__accounts_payable.sql`**, additive. Ordering (FK dependencies):
1. **`supplier_bills`** (FKs `companies`/`branches`/`suppliers`) → **`supplier_bill_lines`** (FKs `supplier_bills`/`products`).
2. **`bill_match`** (FKs `supplier_bills`/`supplier_bill_lines`/`app_users`).
3. **`ap_payments`** (FKs `companies`/`branches`/`suppliers`) → **`ap_payment_allocations`** (FKs `ap_payments`/`supplier_bills`).
4. **`ap_debit_notes`** (FKs `supplier_bills`).
5. **(optional) `ap_settings`** for the tolerance config (or tolerance columns — D-3), seeded per company with the OQ-AP-01 default.
6. **Indexes** for all of the above.
7. **CoA seed** (`5150 Purchases`) per existing company.
8. **`gl_configs` CHECK widen + key seed** (`PURCHASES`, `OPENING_BALANCE_EQUITY` if needed) per company.
9. **`journal_*` source-type CHECK widen** (`AP_BILL`/`AP_PAYMENT`/`AP_DEBIT_NOTE`).
10. **Permission seed** (`AP.*`, `ON CONFLICT DO NOTHING`) + `ORG_ADMIN` CROSS-JOIN grant.

No `code_sequence` row seeded (kinds created on first use). No outbox table, no FK into `domain_events`/`purchase_orders`/`goods_receipts`/`journal_entries` (cross-module scalars). No trigger. Table style follows shipped V8/V10 exactly. The CHECK widens (steps 8/9) are the sanctioned additive `DROP/ADD CONSTRAINT` pattern (ADR-0013 D-13), not V10/V11 edits.

> **V11/V12 ordering note:** AR (V11) and AP (V12) share the `OPENING_BALANCE_EQUITY` gl_config key and the source-type CHECK widens. They are written so each is idempotent (`ON CONFLICT DO NOTHING`, `IF NOT EXISTS`-style seeds, and the CHECK re-add tolerates the prior widen by including the union of both modules' source values when each runs). AR=V11 lands first by number; AP=V12 second. The two can be built in **parallel** (separate branches/modules) and merged in numeric order — neither edits the other's tables.

## Consequences

**Easier / safer:**
- **The books gain a supplier sub-ledger and the purchase liability finally lands** (D-3/D-8): the bill match is the first GL posting for the purchase (CR 2100), closing the gap where the liability was nowhere on the books. Reconciliation holds by construction — bill match creates the 2100 credit and the payable in one TX; payment drops both.
- **The debit-account decision avoids a misleading balance sheet** (D-6): booking to `PURCHASES` (expense) rather than `INVENTORY` (asset) means v1 — which has no COGS relief — does not carry inventory at bill cost forever; T2.2's valuation increment reclassifies, and the key is config so the choice is one `gl_configs` row to flip if the owner wants perpetual semantics.
- **The 3-way match is auditable per line** (D-2c): `bill_match` records what varied (price/qty, by how much) and who accepted an over-tolerance variance; HELD and MATCHED coexist within a bill; re-match updates the row.
- **AP slots onto the shipped engine with no rework** — the `ACCOUNTS_PAYABLE` key is already seeded; AP is the increment that makes it live. One NEW key (`PURCHASES`), one CoA account, all additive.
- **No event consumer to build** (D-5) — AP is bill-entry-driven; every posting is synchronous; no outbox idempotency machinery, no GLPostingSafeInvoker isolation. Simpler than AR.

**Harder / to watch:**
- **The no-double-pay and outstanding-balance invariants are service-owned** (D-3) — `ApPaymentService` must serialise `outstanding_amount` under `SELECT … FOR UPDATE` so a single payment + a concurrent payment run cannot over-pay (NFR-AP-04). An IT must pin "a settled bill is excluded from a later run" and "concurrent payments don't over-pay."
- **The accepted no-GRNI gap** (BR-AP-01, §10.1) — between GR and bill entry the liability is not on the books. Owner-accepted; a GRNI-clearing accrual is a later additive slice. Surfaced, not hidden.
- **Input VAT is netted to `VAT_PAYABLE` in v1** (D-6, OQ-AP-04) — correct enough for a v1 that files no VAT return; the proper `VAT_INPUT` account + the return is T1.5. Flagged.
- **The PURCHASES-vs-INVENTORY choice is a real accounting decision** (D-6) — the recommended `PURCHASES` is correct for periodic inventory; if the owner runs perpetual inventory and accepts the unrelieved-asset caveat until T2.2, remap the key. The ADR records the default and the lever.

**Migration / delivery cost:**
- 1 additive Flyway file (`V12__accounts_payable.sql`): **6 new tables** (`supplier_bills`, `supplier_bill_lines`, `bill_match`, `ap_payments`, `ap_payment_allocations`, `ap_debit_notes`) [+ optional `ap_settings`] + FKs/uniques/CHECKs + ~18 indexes; **CoA seed** (`5150`)/company; **gl_configs CHECK widen + 1–2 key seeds**/company; **journal source-type CHECK widen**; **permission seed** (6 perms + grant). No outbox table, no `code_sequence` row, no trigger. Depends only on frozen V1/V2/V3.
- Backend (AP module): the `com.erp.modules.ap` set per D-1 — 6 entities + enums, 6 repositories (each `findCompanyIdByUid`), the services (bill/match/payment/debit-note/opening + balance/ageing/reconciliation queries + number generators + `PurchaseMatchReader`), ~6 controllers, the `ApGlSeeder`. **No events handler.**
- Backend (Purchases touch — D-10): one `PurchaseOrderService`/`GoodsReceiptService` match-read method + DTO (received-not-yet-billed residual). **No Purchases schema change.**
- Backend (platform touch): `ScopeGuard` gains 3 AP cases + 3 repo deps (D-12); ArchUnit allow-list gains AP→GL-service + AP→Purchases-DTO edges (D-11).
- Web: supplier-bill entry + lines, 3-way match screen (variances, held queue, accept-variance), single payment + payment run (bill selection by supplier/due date), debit note, supplier balances + ageing, opening balances, reconciliation read — `ApiResponse<T>`, Long-as-string, address by uid.
- Deployment risk: **low** — additive on frozen schema; synchronous posting reuses the proven engine; no broker, no consumer.

## Alternatives considered

- **Outbox event for bill-match/payment posting (instead of synchronous `GLPostingService.post`).** AP would emit `AP.BILL.POSTED`/`AP.PAYMENT.MADE` for GL to consume. **Rejected (D-4):** opens an in-flight window where the payable is created but 2100 not yet credited — a reconciliation read during it is wrong (BR-AP-02 holds at all times). A bill match is a single synchronous user command, not a cross-aggregate reaction; sync keeps sub-ledger + 2100 atomic and rolls back cleanly on a GL config/period failure. AP has no async creation path at all (D-5), so there is nothing the outbox decouples.
- **GRNI accrual on goods receipt (post a liability when stock is received, clear it on bill match).** Closes the no-liability gap. **Rejected for v1 (BR-AP-01, §10.1, owner-accepted):** it requires the GR to post to GL (which ADR-0011 deliberately does not do — GR is Stock-only) and a GRNI-clearing account with a two-step accrue/clear flow. The owner accepted the bill-driven gap for v1; a GRNI-clearing accrual is a later additive slice (the AP debit account is config, so a GRNI-clearing key slots in). The alternative is noted as the eventual direction, not built.
- **Debit the matched bill to `INVENTORY` (1300) rather than `PURCHASES` (5150).** Perpetual-inventory semantics. **Rejected as the default (D-6):** v1 has no COGS-relief machinery (T2.2 deferred), so `1300` would inflate forever — a misleading balance sheet. `PURCHASES` (periodic) is correct for a v1 that does not value stock; T2.2 reclassifies. The choice is a `gl_configs` row, so the owner can flip it without code if perpetual semantics + the unrelieved-asset caveat are preferred.
- **One generic `ap_documents` table (bill | payment | debit_note) with a kind discriminator.** Fewer tables. **Rejected (D-2):** bills carry lines + a match + a PO link; payments carry allocations; debit notes carry a contra split — three different shapes, GL postings, permissions, and numbering. A discriminator hides those and invites a posting bug. Purpose-named tables match the shipped one-document-per-table style.
- **Match flags on the bill line instead of a `bill_match` table.** Fewer tables. **Rejected (D-2c):** loses the variance detail (what varied, by how much), the accept-variance audit (who/when), and the re-match history; cannot cleanly represent a bill with some lines matched and some held. The per-line `bill_match` row is the auditable, re-runnable shape.
- **A separate payment-run batch-header table distinct from `ap_payments`.** A run header owning many payments. **Rejected (D-2d):** a payment run pays many bills in **one** payment (one CR Cash post) — the run IS one payment with many allocations. A `kind = PAYMENT_RUN` flag + the allocation junction expresses it without a second header table; a single payment is the same shape with one allocation. Boring and uniform.

## Open / flagged items (do NOT block the build; recommended defaults stand — accounts-payable.md §11)

1. **OQ-AP-01 — Tolerance value/shape.** **Default:** price within the greater of 2% of PO cost OR a small absolute amount, per line; quantity exact (no qty tolerance). The concept (a tolerance exists, over-tolerance holds) is fixed; the value is a configurable company setting confirmed before go-live. *Blocks build:* **NO.**
2. **OQ-AP-02 — Bill due date / supplier terms.** **Default:** bill's stated terms if present, else supplier net-days, else net-on-receipt (0 days). *Blocks build:* **NO.**
3. **OQ-AP-03 — Payment method / bank selection & approval.** **Default:** one default Cash/Bank GL account per company from `gl_configs.CASH`; method selection + payment-approval workflow are later additive slices (T1.4 / X.5). *Blocks build:* **NO.**
4. **OQ-AP-04 — Input VAT / service bills.** **Default:** capture the bill total incl. stated VAT into `vat_amount`; book the input-VAT debit to `VAT_PAYABLE` (net VAT position) — a dedicated `VAT_INPUT` account + the VAT return is T1.5. A pure expense/service bill (no GR to match) posts to an expense account without the goods 3-way match — a thin additive path (or deferred). The goods 3-way match (the core) is fixed. *Blocks build:* **NO.**
5. **OQ-AP-05 — Partial bill vs partial GR.** **Default:** match per line up to the received-not-yet-billed quantity; the remainder stays open for a later bill; over-billing the received qty is held as a variance. *Blocks build:* **NO.**
6. **The PURCHASES-vs-INVENTORY debit account (D-6).** **Default:** `PURCHASES` (→ `5150`, periodic-inventory, correct for v1). Remap to `INVENTORY` is a one-row `gl_configs` change if the owner wants perpetual semantics. *Blocks build:* **NO** — decide the seed value before the migration; the key is config either way.
7. **OQ-CUR-03 (carried) — Rounding & TZS decimals.** **Default:** HALF_UP, TZS = 0 dp; bill total + GL legs + allocations round identically. *Blocks build:* **NO** for the model; confirm before go-live.

None changes the six-table schema or the reconciliation/first-GL-posting rules; all are policy/tuning/additive choices the design is built to.

## Summary

This ADR is the technical design for **AP Increment 2 (T1.3)** — the **supplier sub-ledger** in `com.erp.modules.ap` behind GL `2100`, defined in additive **`V12__accounts_payable.sql`** (never editing frozen V1–V11). **Six tables:** `supplier_bills` (`BILL-####`, `supplier_invoice_no`, `net/vat/gross/outstanding`, `status DRAFT|MATCHED|HELD|APPROVED|PARTIALLY_PAID|PAID`, `source BILL|OPENING_BALANCE`, `purchase_order_uid` scalar, `uq_supplier_bill_supplier_invoice` duplicate-payable guard) + `supplier_bill_lines` (`po_line_uid`/`gr_line_uid` scalars); `bill_match` (per-line 3-way result — price/qty variance, HELD/accepted, tolerance snapshot); `ap_payments` (`PAYRUN-####`, `kind SINGLE|PAYMENT_RUN`) + `ap_payment_allocations` (junction, payment↔bill — the run is one payment with many allocations); `ap_debit_notes` (`DBN-####`). **The reconciliation crux (D-3/D-8):** the GR posts nothing to GL, so the **bill match is the FIRST GL posting for the purchase** (the mirror of AR's no-double-post — AP *must* post because the GR did *not*); every AP-originated event posts to GL **synchronously via `GLPostingService.post` in the same TX** (D-4 — atomicity = reconciliation; no outbox, **no event consumer** — D-5, AP is bill-entry-driven). **3-way match (D-3):** quantity (bill vs received-not-yet-billed) + price (bill vs PO unit cost) within a configurable tolerance (OQ-AP-01 default: 2%-or-abs price, qty exact); over-tolerance → HELD, nothing posts, accept-variance (audited) or reject. **Exact GL postings + NEW key (D-6):** matched bill = DR **`PURCHASES`** (NEW key → `5150`, the **recommended debit account** — periodic-inventory, since COGS/valuation is T2.2; remap to `INVENTORY` is a config change) [+ DR VAT] / CR `ACCOUNTS_PAYABLE`; payment = DR AP / CR `CASH`; debit note = DR AP / CR Purchases[-or-VAT]; opening balance = DR Purchases-or-`OPENING_BALANCE_EQUITY` / CR AP. **No Sales-style relaxation** (the GR already carries the cost the match needs — D-10); the one Purchases touch is an additive DTO read method (no schema change). **Statements/ageing/balance/reconciliation are reads** (D-7/D-8). **Scope/security:** `ScopeGuard` gains `supplierbill`/`appayment`/`apdebitnote`; `assertCanActIn` every read path; perms `AP.VIEW`/`AP.BILL.ENTER`/`AP.BILL.MATCH`/`AP.PAYMENT.RUN`/`AP.DEBITNOTE`/`AP.OPENING.SET`; audit on every mutation. **`code_sequence` kinds** `AP_BILL`/`AP_PAYMENT(_RUN)`/`AP_DEBIT_NOTE`. **Module boundary (D-11):** AP → `gl.service.GLPostingService` + `purchases.domain.dto`/services + `suppliers`/`products` FKs; no event consumer; no cross-module FK into PO/GR/`journal_entries` (scalar uids); no cycle (Purchases/GL do not depend on AP). **Ready for build:** every flagged item has a recommended default the design is built to; the six tables, the 3-way match, the four sync posting commands, and the reconciliation rule are concrete enough to write without guessing a business rule. **Additive on frozen V1–V11:** confirmed — 6 new tables, 1 CoA account, 1 new gl_config key (`PURCHASES`) + the shared `OPENING_BALANCE_EQUITY`, additive CHECK widens (the sanctioned `chk_sales_invoice_doc_type` pattern), no V1–V11 edit.

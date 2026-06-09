# 0014 — Accounts Receivable (AR) data model: the customer sub-ledger behind GL 1200 — open items from credit sales, receipts + allocation, write-offs, credit notes, opening balances, the credit-limit check, and the additive Sales credit-sale enablement

- **Status:** Accepted
- **Date:** 2026-06-09
- **Deciders:** solutions-architect (owner-ratified AR requirements 2026-06-09 — all five AR scoping forks resolved; no ADR-0014-blocking open question remains, accounts-receivable.md §11)
- **Context source:** [docs/requirements/accounts-receivable.md](../requirements/accounts-receivable.md) (RATIFIED 2026-06-09 — FR-AR-01..21, BR-AR-01..12, US-AR-01..07, §7 flows, §10 accepted boundary, §11 OQ log; ground truth for every rule below). [ADR-0013](0013-general-ledger-data-model.md) + the **shipped** GL code (`com.erp.modules.gl`): `GLPostingService.post(JournalEntryDraft)` (the synchronous double-entry engine — validates ≥2 lines, balance, OPEN period, base currency; writes batch+entry+lines atomically), the `JournalEntryDraft`/`LineDraft` internal DTOs, `GLConfigResolver.resolve(companyId, GlConfigKey)` (keys `SALES_REVENUE`/`VAT_PAYABLE`/`ACCOUNTS_RECEIVABLE`/`CASH`/`INVENTORY`/`COGS`/`ACCOUNTS_PAYABLE` all seeded — D-15/D-13), `FiscalPeriodResolver`, the `SalesPostingHandler` (consumer `GL.SALES_POST`) + `GLPostingSafeInvoker.postSaleInNewTx(...)` which **already branches `DR CASH` vs `DR ACCOUNTS_RECEIVABLE` on the `cashSale` flag** read from `InvoicePostingTotalsDto.isCashSale`. [ADR-0008](0008-sales-data-model.md) + [V5__sales.sql](../../backend/src/main/resources/db/migration/V5__sales.sql) + `SalesInvoiceServiceImpl` (`sales_invoices`: `customer_id`, `currency`, `net_total_amount`/`vat_total_amount`/`gross_total_amount`, `status`, `finalised_at`; `finalise()` calls `assertPaidInFull(inv, payments)` — the v1 invariant; `SALE.FINALISED`/`SALE.VOIDED` payloads; `findPostingTotalsByUidAndCompany` returns `isCashSale = true` hard-coded — OQ-GL-02; **credit/on-account sales deferred, OQ-SALES-06**). [ADR-0006](0006-parties-data-model.md) + [V2__parties.sql](../../backend/src/main/resources/db/migration/V2__parties.sql) (`customers.customer_kind` `CASH_WALK_IN`|`CREDIT_ACCOUNT`, `credit_limit_amount`/`credit_limit_currency`, `payment_terms_days`). [ADR-0009](0009-transactional-outbox.md) (`DomainEventHandler`/`IdempotencyGuard`/`processed_events(consumer,event_uid)`; the `@Scheduled` dispatcher runs each handler in a per-event TX; handlers `@Transactional(MANDATORY)`; the `GLPostingSafeInvoker` REQUIRES_NEW isolation pattern + the lesson that a fallible handler sharing a dispatch must isolate its work or it poisons co-consumers). [ADR-0005](0005-money-and-currency.md) (`Money` NUMERIC(19,4)+currency; `companies.base_currency` added in V10). [ADR-0007](0007-products-data-model.md) (`code_sequence(company_id, entity_kind)` row-locked numbering, D-6). [[db-naming-convention]] verified against shipped V1–V10 (plural masters/logs, singular junctions, singular constraint roots `uq_`/`fk_`/`chk_`, plural index names `ix_`, `uid VARCHAR(26)`, `company_id` scalar, audit cols, partial-unique pattern, append-only posting). Latest shipped migration is **V10** → AR is **`V11__accounts_receivable.sql`** (additive; never edits V1–V10). AP is the sibling [ADR-0015](0015-accounts-payable-data-model.md) / **V12**.

This ADR is the **technical data model + integration design** for the Accounts Receivable module (AR, ROADMAP T1.2, Increment 2). It translates the ratified business spec into tables, columns, types, keys, indexes, the enforcement split, the outbox consumer, the GL-posting mechanism, the reconciliation design, permissions/audit/scope, and the **additive Sales credit-sale enablement** — concrete enough that the backend engineer writes `V11__accounts_receivable.sql` + the entities + the `ArSalePostedHandler` + the receipt/write-off/credit-note posting paths **without guessing a business rule**. It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing ratified is re-litigated.

## Context

AR is the **customer sub-ledger** — the per-customer detail (open items, receipts, allocations, balances, ageing) behind the GL **`1200 Accounts Receivable`** control account. The dependency platform is shipped and consumed unchanged: GL gives the posting engine + `gl_configs` + the outbox; Parties gives the Customer (`credit_limit_amount`, `payment_terms_days`, `customer_kind`); the outbox gives `DomainEventHandler`/`IdempotencyGuard`; `code_sequence` gives `RCT-####`/`CRN-####` numbering. The central force is therefore the same as GL's: **mirror the proven outbox-consumer + per-company-sub-ledger patterns; resolve only the genuinely new modelling questions a sub-ledger that must reconcile to a GL control account introduces.** Those questions and their forces:

- **The reconciliation invariant is the chief acceptance bar (BR-AR-02, NFR-AR-01): Σ(customer AR balances) == GL 1200 balance at all times.** The non-obvious consequence is **who posts what** — and getting it wrong double-counts receivables. The **GL `SalesPostingHandler` already debits AR control on a credit sale** (verified: `GLPostingSafeInvoker.postSaleInNewTx` DRs `ACCOUNTS_RECEIVABLE` when `cashSale == false`). So AR creating its open item must **NOT** re-post to GL. Resolved in D-3 (the no-double-post rule) and the per-event posting table (D-6).

- **AR is both an event CONSUMER (Sales→AR) and a query PROVIDER (Sales reads AR's credit balance at finalise).** A naive design where Sales calls AR and AR also calls Sales is a module cycle. The forces: keep AR a leaf of Sales' events; expose the credit-balance read as an AR-owned query Sales consumes; keep the direction Sales→AR for the synchronous read and AR←Sales for the async event. Resolved in D-9 + D-11.

- **A receipt / write-off / standalone credit note is a NEW financial event GL has not seen, so it DOES post to GL.** The mechanism — synchronous `GLPostingService.post` in the same TX, vs an outbox event GL consumes — is an ADR decision (the spec flag, §3 of the requirement). The forces: a receipt is a single atomic user action (sync keeps sub-ledger + control atomic, no in-flight reconciliation gap); the outbox is for cross-aggregate async (sale→stock, sale→GL). Resolved in D-4.

- **Open-item creation must be idempotent and skip cash sales.** AR consumes the same `SALE.FINALISED` the GL/Stock handlers consume, but only for `CREDIT_ACCOUNT` customers, and a redelivered event must create one open item, not two. Resolved in D-5 (consumer `AR.SALE_POST`, the dedup marker + a DB partial-unique backstop).

- **The Sales credit-sale path does not yet exist (OQ-SALES-06): v1 sales are paid-in-full at finalise.** AR's open-item machinery is built now, but it only fires when a credit sale can finalise unpaid. This is an **additive cross-module touch to Sales** (the `products.vat_status` precedent): relax the paid-in-full invariant for `CREDIT_ACCOUNT` customers, and make `isCashSale` derive from the customer kind so the unpaid balance flows to AR + the GL handler DRs AR control. Resolved in D-10.

- **Allocation vs posting must be separated.** A receipt's cash leg posts to GL **once**; re-allocating that receipt across open items is a sub-ledger-only change that posts nothing (BR-AR-12). The forces: a clean receipt→allocation grain where allocation is a junction, allocation never re-posts, over-allocation is rejected at the service, on-account remainder is a first-class state. Resolved in D-2/D-3.

- **Schema freeze / migration ordering.** IAM=V1 … GL=V10 — all frozen and shipped. AR is a **new** module landing as a purely **additive `V11__accounts_receivable.sql`**; it must not edit V1–V10. It FKs only frozen `companies`/`branches`/`app_users`/`customers` (and intra-module AR tables); it references `sales_invoices` and GL `journal_entries` by **scalar uid/id**, never a cross-module FK (the `stock_movements.source_document_uid` discipline).

## Decision

### D-1 — Module placement: one `com.erp.modules.ar` module; controllers flat in `com.erp.api`

AR lives under **`com.erp.modules.ar`** — a flat sibling of `gl`/`sales`/`stock`/`purchases` (PROJECT-CONVENTIONS §2; the same reasoning ADR-0013 D-1 used to reject `accounting.gl`). Internal layout:

```
com.erp.modules.ar
├── domain.entity   ArInvoice, ArReceipt, ArReceiptAllocation, ArCreditNote, ArWriteOff
├── domain.dto      ArInvoiceDto, ArReceiptDto, RecordReceiptRequest, AllocationLineRequest,
│                   ArCreditNoteDto, RaiseCreditNoteRequest, ArWriteOffDto, WriteOffRequest,
│                   SetOpeningBalanceRequest, ArBalanceDto (the credit-limit read — D-9),
│                   ArAgeingRowDto, ArStatementDto, ArReconciliationDto
├── domain.enums    ArInvoiceStatus (OPEN|PARTIAL|PAID|WRITTEN_OFF),
│                   ArInvoiceSource (SALE|OPENING_BALANCE),
│                   ArReceiptStatus (UNALLOCATED|PARTIAL|ALLOCATED),
│                   AgeingBucket (CURRENT|D1_30|D31_60|D61_90|D90_PLUS)
├── repository      ArInvoiceRepository, ArReceiptRepository, ArReceiptAllocationRepository,
│                   ArCreditNoteRepository, ArWriteOffRepository
├── service         ArInvoiceService(+Impl)          — open-item reads, statements, ageing,
│                   ArReceiptService(+Impl)           — record + allocate + post (D-3),
│                   ArWriteOffService(+Impl)          — write-off + post (D-6),
│                   ArCreditNoteService(+Impl)        — credit note + post (D-6),
│                   ArOpeningBalanceService(+Impl)    — opening balances + post (D-6),
│                   ArBalanceQuery                    — current AR balance per customer (D-9),
│                   ArAgeingQuery / ArStatementQuery  — ageing buckets + statement (D-7),
│                   ArReconciliationQuery             — sub-ledger total vs GL 1200 (D-8),
│                   ArReceiptNumberGenerator          — RCT-#### via code_sequence (D-12)
└── events          ArSalePostedHandler               — SALE.FINALISED → ar_invoice (D-5)
```

Controllers stay flat in `com.erp.api` — `ArInvoiceController`, `ArReceiptController`, `ArWriteOffController`, `ArCreditNoteController`, `ArStatementController`, `ArOpeningBalanceController` — touching only services (`ModuleBoundaryTest`). The one `events` handler is an AR bean implementing `platform.events.DomainEventHandler` (the only cross-cutting coupling), exactly as the Stock/GL handlers do.

### D-2 — The five table groups: `ar_invoices`, `ar_receipts` + `ar_receipt_allocations`, `ar_credit_notes`, `ar_write_offs`

All masters/logs plural per the shipped convention. Every table carries `company_id` (BR-AR-07) and participates in the §3.2 tenant predicate. The sub-ledger is **company-level**; `branch_id` is a nullable **analysis tag** (mirrors GL D-7 — the books are company-level, branch is a dimension). Cross-module references (`sales_invoices`, GL `journal_entries`) are **scalar uid/id, no FK** (D-11).

> **Why split receipt header + allocation junction (the pick, justified in Alternatives).** A receipt is one money event with one cash-leg GL post; an allocation applies (part of) it to one open item. One receipt → many allocations (oldest-first auto fills several open items); one open item → many allocations (paid by several receipts over time). That is a **many-to-many** resolved by the `ar_receipt_allocations` junction, with the receipt's `unallocated_amount` as the first-class on-account remainder. Folding allocation onto the receipt (a single allocated-invoice column) cannot express a receipt that pays three invoices, nor re-allocation without rewriting history. Split is the boring relational shape and it makes "re-allocate posts nothing to GL" (BR-AR-12) structurally obvious: only `ar_receipts` carries the GL link.

#### (a) `ar_invoices` (the open items — the sub-ledger detail behind GL 1200)

One row per credit-sale invoice (or per opening balance). The receivable detail; **created, never the GL control debit** (D-3).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | allocation/credit-note/write-off children FK this |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_ar_invoice_uid`; URLs address by uid; `ScopeGuard case "arinvoice"` (D-12) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope; never updated |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag (the sale's branch); nullable for an opening balance |
| `customer_id` | `BIGINT` | NO | FK → `customers(id)` (Parties, frozen V2) — the debtor |
| `source` | `VARCHAR(20)` | NO | `ArInvoiceSource`: `SALE`\|`OPENING_BALANCE`; CHECK below |
| `source_invoice_uid` | `VARCHAR(26)` | YES | the originating **`sales_invoices.uid`** for a `SALE` (scalar, **no FK** — cross-module, D-11); NULL for `OPENING_BALANCE` |
| `document_no` | `VARCHAR(30)` | YES | the human invoice number (snapshot of `sales_invoices.invoice_number` for a SALE; operator-entered for an opening balance) |
| `original_amount` | `NUMERIC(19,4)` | NO | the invoice gross at creation (the receivable's full size); `Money` amount, base currency |
| `outstanding_amount` | `NUMERIC(19,4)` | NO | the unpaid balance — **maintained** down by allocations/credit-notes/write-offs (the AR analogue of `purchase_order_lines.received_qty_in_base`); starts == `original_amount` |
| `currency` | `VARCHAR(3)` | NO | the document currency = company base currency (BR-AR-11) |
| `invoice_date` | `DATE` | NO | the business invoice date (the sale's finalise date for a SALE) |
| `due_date` | `DATE` | NO | derived: `invoice_date + customers.payment_terms_days` (if set), else `invoice_date` (net-on-receipt, OQ-AR-01); drives ageing (D-7) |
| `status` | `VARCHAR(20)` | NO | `ArInvoiceStatus`: `OPEN`\|`PARTIAL`\|`PAID`\|`WRITTEN_OFF`; DEFAULT `'OPEN'`; maintained by the service from `outstanding_amount` (D-3); CHECK below |
| `version` | `BIGINT` | NO | optimistic lock, DEFAULT 0 |
| audit cols | `TIMESTAMPTZ`/`BIGINT` | mixed | `created_at`/`created_by`/`updated_at`/`updated_by` (`*_by` → `app_users.id`, no FK — system-write pattern; SYSTEM create has NULL `created_by`) |

**Constraints:**
- `uq_ar_invoice_uid UNIQUE (uid)`.
- `uq_ar_invoice_source_sale UNIQUE (company_id, source_invoice_uid) WHERE source = 'SALE'` — **partial unique**: one open item per source invoice per company (FR-AR-04, BR-AR-08); the DB backstop to the consumer dedup (D-5), exactly the `uq_journal_entry_sales_source` pattern. Opening balances (NULL `source_invoice_uid`) are exempt and coexist.
- `fk_ar_invoice_company`, `fk_ar_invoice_branch`, `fk_ar_invoice_customer` (→ `customers`).
- `chk_ar_invoice_source CHECK (source IN ('SALE','OPENING_BALANCE'))`.
- `chk_ar_invoice_status CHECK (status IN ('OPEN','PARTIAL','PAID','WRITTEN_OFF'))`.
- `chk_ar_invoice_amounts CHECK (original_amount > 0 AND outstanding_amount >= 0 AND outstanding_amount <= original_amount)` — the single-row money invariant the DB can express; the "Σ allocations + write-off + credit-note == original − outstanding" cross-row sum is service-enforced (D-3).
- `chk_ar_invoice_dates CHECK (due_date >= invoice_date)`.

**Indexes:**
```
CREATE INDEX ix_ar_invoices_company           ON ar_invoices (company_id);
CREATE INDEX ix_ar_invoices_company_customer  ON ar_invoices (company_id, customer_id);              -- customer statement/balance
CREATE INDEX ix_ar_invoices_open              ON ar_invoices (company_id, customer_id, due_date)
    WHERE status IN ('OPEN','PARTIAL');                                                               -- oldest-first allocation + ageing working set
CREATE INDEX ix_ar_invoices_company_due       ON ar_invoices (company_id, due_date);                 -- ageing as-at-date
```

#### (b) `ar_receipts` (money received — `RCT-####`) + (c) `ar_receipt_allocations` (junction)

##### `ar_receipts`

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_ar_receipt_uid`; `ScopeGuard case "arreceipt"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag (the originating branch) |
| `customer_id` | `BIGINT` | NO | FK → `customers(id)` — who paid |
| `receipt_number` | `VARCHAR(30)` | NO | `RCT-####` from `code_sequence (company_id,'AR_RECEIPT')` (D-12); `uq_ar_receipt_company_number` |
| `receipt_date` | `DATE` | NO | the value date (business date; ageing/GL posting_date source) |
| `amount` | `NUMERIC(19,4)` | NO | total money received; `Money`, base currency; CHECK `> 0` |
| `unallocated_amount` | `NUMERIC(19,4)` | NO | the on-account remainder (amount − Σ allocations); starts == `amount`, maintained down as allocations are made (FR-AR-09, BR-AR-05) |
| `currency` | `VARCHAR(3)` | NO | = company base currency |
| `tender_type` | `VARCHAR(20)` | NO | `CASH`\|`BANK_TRANSFER`\|`MOBILE_MONEY` (mirrors `sales_invoice_payments.tender_type`; CHECK); the Cash/Bank GL account is `gl_configs.CASH` in v1 — the full method→account map is T1.4 (OQ deferred) |
| `bank_reference` | `VARCHAR(80)` | YES | cheque/transfer/M-Pesa ref (analysis only) |
| `gl_entry_uid` | `VARCHAR(26)` | YES | the **`journal_entries.uid`** of the cash-leg post (scalar, no FK — cross-module link for traceability/reconciliation, D-11); set when the sync post returns (D-4); NULL only if GL infra failed (anomaly) |
| `status` | `VARCHAR(20)` | NO | `ArReceiptStatus`: `UNALLOCATED`\|`PARTIAL`\|`ALLOCATED`; derived from `unallocated_amount`; CHECK below |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | … | mixed | standard; **no soft delete** — append-only (BR-AR-09), correction is a cancelling receipt |

- `uq_ar_receipt_uid UNIQUE (uid)`; `uq_ar_receipt_company_number UNIQUE (company_id, receipt_number)`.
- `fk_ar_receipt_company`, `fk_ar_receipt_branch`, `fk_ar_receipt_customer`.
- `chk_ar_receipt_amount CHECK (amount > 0)`.
- `chk_ar_receipt_unallocated CHECK (unallocated_amount >= 0 AND unallocated_amount <= amount)` — over-allocation can never drive it negative (BR-AR-04).
- `chk_ar_receipt_tender CHECK (tender_type IN ('CASH','BANK_TRANSFER','MOBILE_MONEY'))`.
- `chk_ar_receipt_status CHECK (status IN ('UNALLOCATED','PARTIAL','ALLOCATED'))`.
- Indexes: `ix_ar_receipts_company (company_id)`; `ix_ar_receipts_company_customer (company_id, customer_id)`; `ix_ar_receipts_company_date (company_id, receipt_date)`; `ix_ar_receipts_onaccount (company_id, customer_id) WHERE unallocated_amount > 0` (the on-account credit working set).

##### `ar_receipt_allocations` (junction — singular per convention, no `uid`)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; denormalised (tenant predicate without a join) |
| `receipt_id` | `BIGINT` | NO | FK → `ar_receipts(id)` |
| `ar_invoice_id` | `BIGINT` | NO | FK → `ar_invoices(id)` — the open item this slice settles |
| `allocated_amount` | `NUMERIC(19,4)` | NO | the slice applied; CHECK `> 0` |
| `allocated_at` | `TIMESTAMPTZ` | NO | DEFAULT `now()` |
| `allocated_by` | `BIGINT` | YES | the operator (NULL for auto-allocation by the receipt service) |
| `created_at`/`created_by` | … | mixed | **no `updated_*`** — re-allocation is delete+insert within the receipt's allocation set, audited (a sub-ledger-only change, posts nothing — BR-AR-12) |

- `uq_ar_receipt_allocation_pair UNIQUE (receipt_id, ar_invoice_id)` — one allocation row per receipt×invoice (re-allocation adjusts amount or delete/re-inserts; keeps the set clean).
- `fk_ar_receipt_allocation_company`, `fk_ar_receipt_allocation_receipt` (→ `ar_receipts`), `fk_ar_receipt_allocation_invoice` (→ `ar_invoices`).
- `chk_ar_receipt_allocation_amount CHECK (allocated_amount > 0)`.
- Indexes: `ix_ar_receipt_allocations_receipt (receipt_id)`; `ix_ar_receipt_allocations_invoice (ar_invoice_id)`; `ix_ar_receipt_allocations_company (company_id)`.

> The cross-row sums — `Σ allocated_amount per receipt + unallocated_amount == amount` (BR-AR-04/10) and each allocation `≤ the open item's outstanding` (no over-settle) — are **service-enforced** in `ArReceiptService` (`BigDecimal.compareTo`), the same way GL's balance is service-enforced (no single CHECK can sum siblings). Concurrency on `outstanding_amount` is serialised by an optimistic-lock retry or a `SELECT … FOR UPDATE` on the open items being allocated (NFR-AR-02; the `code_sequence`/`received_qty` precedent).

#### (d) `ar_credit_notes` (reduce a receivable) + (e) `ar_write_offs` (bad debt)

**Two tables, not one `ar_adjustments` (the pick).** A credit note and a write-off are different business events with different GL postings (credit note: DR Revenue + DR VAT / CR AR; write-off: DR bad-debt expense / CR AR — D-6), different permissions (`AR.WRITEOFF` vs the credit-note path), and different numbering (`CRN-####` vs none). Folding them into a generic `ar_adjustments` with a `kind` column blurs the GL posting rule and the permission gate. Two thin purpose-named tables are clearer and match the shipped one-table-per-document-kind style. (Examined in Alternatives.)

##### `ar_credit_notes`

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_ar_credit_note_uid`; `ScopeGuard case "arcreditnote"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)` |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag |
| `customer_id` | `BIGINT` | NO | FK → `customers(id)` |
| `credit_note_number` | `VARCHAR(30)` | NO | `CRN-####` via `code_sequence (company_id,'AR_CREDIT_NOTE')`; `uq_ar_credit_note_company_number` |
| `ar_invoice_id` | `BIGINT` | YES | FK → `ar_invoices(id)` — the open item reduced (NULL for an unapplied credit, treated like on-account — rare in v1) |
| `note_date` | `DATE` | NO | business date; GL posting_date |
| `amount` | `NUMERIC(19,4)` | NO | the credited (reducing) amount; CHECK `> 0` |
| `net_amount` | `NUMERIC(19,4)` | NO | the revenue-contra portion (the GL DR Revenue leg, D-6) |
| `vat_amount` | `NUMERIC(19,4)` | NO | the VAT-contra portion (DR VAT Payable); DEFAULT 0 |
| `currency` | `VARCHAR(3)` | NO | = base currency |
| `reason` | `VARCHAR(255)` | NO | return / over-charge correction (audited) |
| `origin` | `VARCHAR(20)` | NO | `STANDALONE`\|`SALE_VOID` (OQ-AR-04): `STANDALONE` posts the GL reduction; `SALE_VOID` rides the GL `SaleVoidingHandler` reversal so it does **NOT** post again (D-6); CHECK below |
| `gl_entry_uid` | `VARCHAR(26)` | YES | the `journal_entries.uid` of the reduction post (NULL for `SALE_VOID` — GL already posted via the void handler; scalar, no FK) |
| `version` / audit | … | mixed | append-only |

- `uq_ar_credit_note_uid`, `uq_ar_credit_note_company_number`; `fk_ar_credit_note_company`/`_branch`/`_customer`/`_invoice` (→ `ar_invoices`).
- `chk_ar_credit_note_amount CHECK (amount > 0 AND net_amount >= 0 AND vat_amount >= 0)`; `chk_ar_credit_note_origin CHECK (origin IN ('STANDALONE','SALE_VOID'))`.
- Indexes: `ix_ar_credit_notes_company (company_id)`; `ix_ar_credit_notes_company_customer (company_id, customer_id)`; `ix_ar_credit_notes_invoice (ar_invoice_id)`.

##### `ar_write_offs`

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_ar_write_off_uid`; `ScopeGuard case "arwriteoff"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)` |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag |
| `customer_id` | `BIGINT` | NO | FK → `customers(id)` |
| `ar_invoice_id` | `BIGINT` | NO | FK → `ar_invoices(id)` — the open item written off (closed to `WRITTEN_OFF`) |
| `write_off_date` | `DATE` | NO | business date; GL posting_date |
| `amount` | `NUMERIC(19,4)` | NO | the written-off (remaining outstanding) amount; CHECK `> 0` |
| `currency` | `VARCHAR(3)` | NO | = base currency |
| `reason` | `VARCHAR(255)` | NO | the bad-debt justification (audited) |
| `gl_entry_uid` | `VARCHAR(26)` | YES | the `journal_entries.uid` of the DR bad-debt / CR AR post (scalar, no FK) |
| `version` / audit | … | mixed | append-only |

- `uq_ar_write_off_uid`; `fk_ar_write_off_company`/`_branch`/`_customer`/`_invoice`.
- `chk_ar_write_off_amount CHECK (amount > 0)`.
- Indexes: `ix_ar_write_offs_company (company_id)`; `ix_ar_write_offs_invoice (ar_invoice_id)`.

No standalone `ar_opening_balances` table — an **opening balance is an `ar_invoices` row with `source = OPENING_BALANCE`** (D-6); statements/ageing/balances treat it uniformly as an open item (the requirement's intent, FR-AR-15). No statement table — statements/ageing are reads (D-7).

### D-3 — The sub-ledger engine: maintained `outstanding_amount`, status from balance, append-only, the no-double-post rule

**The reconciliation invariant (BR-AR-02, the load-bearing rule).** `Σ ar_invoices.outstanding_amount (company) − Σ ar_receipts.unallocated_amount (company) == GL 1200 balance`. The on-account credit (`unallocated_amount`) is a customer credit that has not yet reduced an open item but *has* reduced the control account (the cash leg posted CR AR for the full receipt). So the reconciliation read nets it out (D-8). Every mutation keeps both sides moving by the same amount:

| event | sub-ledger effect | GL effect | who posts GL |
| --- | --- | --- | --- |
| credit sale finalises | create `ar_invoices` (OPEN, outstanding = original) | DR 1200 / CR Revenue / CR VAT | **GL `SalesPostingHandler` — AR posts NOTHING (D-5, BR-AR-02 no-double-post)** |
| receipt recorded + allocated | `ar_receipts` (+ allocations); each open item `outstanding −= allocated`; receipt `unallocated = amount − Σ allocated` | DR Cash/Bank / CR 1200 (for the **receipt amount**) | **AR — synchronous `GLPostingService.post` (D-4)** |
| re-allocate a receipt | move allocation slices between open items | **nothing** (BR-AR-12) | nobody |
| write-off | open item → `WRITTEN_OFF`, `outstanding = 0` | DR bad-debt expense / CR 1200 | **AR — sync post** |
| standalone credit note | open item `outstanding −= amount` | DR Revenue / DR VAT / CR 1200 | **AR — sync post** |
| credit note from a voided sale | open item `outstanding −= amount` / close | (GL `SaleVoidingHandler` already reversed) | **nobody — AR records only (D-6)** |
| opening balance | create `ar_invoices` (source OPENING_BALANCE) | DR 1200 / CR opening-balance-equity | **AR — sync post (D-6)** |

**The double-entry-of-the-sub-ledger enforcement split:**

| invariant | enforcement | mechanism |
| --- | --- | --- |
| `outstanding ∈ [0, original]` (single row) | **DB CHECK** | `chk_ar_invoice_amounts` |
| `unallocated ∈ [0, amount]` (single row) | **DB CHECK** | `chk_ar_receipt_unallocated` |
| Σ allocations + unallocated == receipt amount (cross-row) | **service** | `ArReceiptService` (`BigDecimal`) — over-allocation rejected (BR-AR-04) |
| each allocation ≤ the open item's outstanding (no over-settle) | **service** | re-read + check under lock |
| status derived from balance (OPEN/PARTIAL/PAID/WRITTEN_OFF) | **service** | set on every mutation |
| one open item per source invoice (idempotent) | **service + DB** | `IdempotencyGuard` (D-5) + `uq_ar_invoice_source_sale` partial-unique backstop |
| no double-post on credit-sale creation | **structural** | AR's create path simply has no GL call (D-5) |
| append-only / correct via reversal | **structural** | no delete path on receipts/notes/write-offs; correction is a cancelling document |
| company isolation | **DB + service** | `company_id` NOT NULL + FK + tenant predicate + `assertCanActIn` on every read path |
| base-currency only | **service** | every amount currency == `companies.base_currency` (BR-AR-11) |

### D-4 — GL-posting mechanism: SYNCHRONOUS `GLPostingService.post(...)` in the same TX (NOT the outbox) for receipts, write-offs, standalone credit notes, opening balances

**Decision: a receipt / write-off / standalone credit note / opening balance posts to GL by a synchronous `GLPostingService.post(JournalEntryDraft)` call inside the same service transaction as the sub-ledger write.** Not an outbox event. Justification:

1. **Atomicity = reconciliation by construction.** The sub-ledger write (e.g. reduce `outstanding`, set the receipt's `unallocated`) and the GL control-account credit are **one transaction**: either both happen or neither does. There is no in-flight window where the sub-ledger and GL 1200 disagree — the reconciliation invariant (BR-AR-02, the chief acceptance bar) holds at *every* committed state, not eventually. An outbox post would open a gap (sub-ledger written, GL not yet dispatched) during which a reconciliation read is wrong.
2. **A receipt is a single, synchronous, in-request user action** — one aggregate, one operator, one click. The outbox exists for **cross-aggregate async** where the producer must not block on the consumer and the consumer is a different module reacting later (sale → stock deduction, sale → GL posting). A receipt is not that shape; it is AR doing its own posting as part of its own command. Using the outbox here would be machinery for no decoupling benefit.
3. **The engine is built for it.** `GLPostingService.post` is `@Transactional` and designed to be called by any module that needs to post (ADR-0013 D-3 — "the single engine both manual and automatic posting go through"). AR is an **allowed leaf→service dependency** on `gl.service.GLPostingService` (D-11). A failed post (missing `gl_configs.CASH`/`BAD_DEBT_EXPENSE`, closed period) **rolls back the whole AR command** — the operator sees a clear error and the sub-ledger is untouched (the correct behaviour: no half-recorded receipt). This is the opposite of the sale-finalise case, where GL failure must NOT roll back the sale (hence the outbox + `GLPostingSafeInvoker` isolation there) — because there the sale is the authoritative business act and GL is a downstream projection. For an AR receipt, the GL post **is** the point; if it can't post, the receipt should not record.

**Contrast — the ONE async path is open-item creation (D-5),** which AR does via the outbox consumer `ArSalePostedHandler`, because that is genuinely cross-aggregate (Sales produced the event; AR reacts later) and **posts nothing to GL** (so there is no atomicity concern — it only writes sub-ledger detail).

So AR has exactly one outbox consumer (creation) and four synchronous GL-posting commands (receipt, write-off, standalone credit note, opening balance). Each sync command builds a `JournalEntryDraft` (resolving accounts via `GLConfigResolver`), calls `post`, and stores the returned `journal_entries.uid` in the sub-ledger row's `gl_entry_uid` for traceability — all in one TX.

### D-5 — Open-item creation: `ArSalePostedHandler` consumes `SALE.FINALISED` (credit only), creates the open item, posts NOTHING to GL

`ArSalePostedHandler` is an AR bean in `com.erp.modules.ar.events` implementing `platform.events.DomainEventHandler`, `@Transactional(MANDATORY)`, **consumer marker `AR.SALE_POST`** (distinct from `GL.SALES_POST`/`STOCK_*` so it dedupes on its own progress — ADR-0009 D-6). It mirrors the shipped `SalesPostingHandler`/`SaleIssueStockHandler` exactly: primary dedup via `IdempotencyGuard.alreadyProcessed`, system `RequestContext.Principal` from the event's company/branch (save/restore in a `finally`), effect applied, `IdempotencyGuard.markProcessed` in the same TX.

Flow:
1. `eventType() == SALE.FINALISED`. Dedup on `AR.SALE_POST` + `event.uid` → no-op if processed.
2. Re-read the invoice via `salesInvoiceService.findPostingTotalsByUidAndCompany(invoiceUid, companyId)` (the shipped Sales DTO method — D-11). If not found or not `FINALISED`, record an anomaly and still `markProcessed` (the shipped anomaly path).
3. **Skip cash sales**: if `totals.isCashSale()` is true (a `CASH_WALK_IN` customer or paid-in-full — see D-10), create **no** open item (FR-AR-02, BR-AR-01) and `markProcessed`. AR open items exist only for credit customers' unpaid balance.
4. For a credit sale, create the `ar_invoices` row: `source = SALE`, `source_invoice_uid = invoiceUid`, `original_amount = outstanding_amount =` the **unpaid** gross (in v1, with the D-10 enablement, the unpaid balance = gross − payments; the simple v1 rule is the full gross since a credit sale is unpaid at finalise — the engineer reads the residual from Sales via the same DTO, see D-10), `customer_id`, `invoice_date = finalisedAt::date`, `due_date` per the customer's `payment_terms_days` (else net-on-receipt), `status = OPEN`. **No GL post** — the GL `SalesPostingHandler` already debited 1200 (BR-AR-02). `markProcessed` in the same TX.

**Idempotency is structural:** the `AR.SALE_POST` marker (primary) + `uq_ar_invoice_source_sale` partial-unique (backstop) mean a redelivered `SALE.FINALISED` creates one open item, not two (NFR-AR-04 — an IT must deliver the event twice and assert one open item). Because creation posts nothing to GL, there is no GL-poison concern; this handler needs **no `GLPostingSafeInvoker`** isolation (unlike `SalesPostingHandler`).

### D-6 — The exact GL postings per event + the gl_config keys (incl. NEW keys to seed)

All postings go through `GLPostingService.post` (sync, D-4) or are already done by GL's sale handlers (creation / void). Accounts resolved via `GLConfigResolver.resolve(companyId, key)`. **Two NEW `gl_configs` keys AR introduces** (added to the `chk_gl_config_key` IN-list via an additive ALTER on the frozen `gl_configs` CHECK, and seeded per company — D-13):

- **`BAD_DEBT_EXPENSE`** → a new CoA expense account (recommend `5500 Bad Debt Expense`, EXPENSE/DEBIT) — for write-offs.
- **`OPENING_BALANCE_EQUITY`** → recommend mapping to the seeded `3000 Owner's Equity / Capital` (or a dedicated `3100 Opening Balance Equity`, EQUITY/CREDIT) — for the opening-balance journal's contra.

| AR event | GL journal (via `GLPostingService.post`) | gl_config keys |
| --- | --- | --- |
| **open-item creation** (credit sale) | — none — (GL `SalesPostingHandler` already posted DR 1200 / CR Revenue / CR VAT) | (none — D-5) |
| **receipt** (cash leg, posts once) | DR Cash/Bank `amount` · CR Accounts Receivable `amount` | `CASH` (debit) + `ACCOUNTS_RECEIVABLE` (credit) — both seeded |
| **re-allocate** a receipt | — none — (BR-AR-12) | (none) |
| **write-off** | DR Bad Debt Expense `amount` · CR Accounts Receivable `amount` | **`BAD_DEBT_EXPENSE`** (NEW) + `ACCOUNTS_RECEIVABLE` |
| **standalone credit note** | DR Sales Revenue `net_amount` · [DR VAT Payable `vat_amount` if > 0] · CR Accounts Receivable `amount` | `SALES_REVENUE` + `VAT_PAYABLE` + `ACCOUNTS_RECEIVABLE` (all seeded) |
| **credit note from a voided sale** | — none — (GL `SaleVoidingHandler` reversed) | (none — origin `SALE_VOID`, D-2d) |
| **opening balance** | DR Accounts Receivable `original_amount` · CR Opening Balance Equity `original_amount` | `ACCOUNTS_RECEIVABLE` + **`OPENING_BALANCE_EQUITY`** (NEW) |

Each posting uses `JournalSourceType` — AR introduces source values `AR_RECEIPT`, `AR_WRITEOFF`, `AR_CREDIT_NOTE`, `OPENING_BALANCE` (the last already in the v1 IN-list; the first three widen `chk_journal_batch_source_type`/`chk_journal_entry_source_type` via an additive ALTER — the `JournalSourceType` enum already reserves `AR`, D-13; the engineer adds the granular values or uses the reserved `AR`). `source_ref` = the AR document uid. A missing `gl_config` mapping or closed period **fails the AR command** (rolls back, D-4) per BR-GL-10 / FR-AR-17 — the operator fixes the mapping (`GL.MANAGE`) and retries.

### D-7 — Statements + ageing are READS (queries), not tables (FR-AR-08/12)

- **AR balance per customer** = `Σ ar_invoices.outstanding_amount WHERE customer_id AND status IN (OPEN,PARTIAL) − Σ ar_receipts.unallocated_amount WHERE customer_id` (the on-account credit). A `ArBalanceQuery` over the AR repositories, company-scoped.
- **Ageing** = the customer's open items bucketed by `due_date` vs an as-at date: `CURRENT` (due_date ≥ asAt), `D1_30`/`D31_60`/`D61_90` (1–30/31–60/61–90 days overdue), `D90_PLUS` (> 90). Computed in `ArAgeingQuery` (a `CASE` over `asAt − due_date` grouped by bucket), hitting `ix_ar_invoices_open`/`ix_ar_invoices_company_due`. Returns `ArAgeingRowDto` per bucket.
- **Statement** = `ArStatementQuery` — the customer's open items + ageing + recent receipts/credit notes as at a date; `ArStatementDto`. View/print only (no email/dunning — deferred). No materialised view in v1 (the indexes make it cheap at QA scale; a Reporting snapshot is T2.3's additive call — NFR-AR-08).
- **Reconciliation read** = `ArReconciliationQuery` returns `{ subLedgerTotal, glControlBalance, difference }` — `subLedgerTotal` from the AR repositories, `glControlBalance` from GL's `TrialBalanceQuery`/`1200` (via a GL service read — an allowed AR→GL read). A non-zero `difference` is a finance-grade defect (FR-AR-18).

### D-8 — Reconciliation design (the crux) — sub-ledger ⇄ GL 1200, the no-double-post rule made structural

The reconciliation is guaranteed by **three structural choices**, not by a periodic job:
1. **AR never posts the credit-sale debit** (D-5) — GL's `SalesPostingHandler` is the *sole* writer of the AR-control debit on a sale. AR's open item is the *detail* of that same debit. One sale ⇒ one 1200 debit (by GL) + one open item (by AR), the same amount. Double-posting is impossible because AR's creation path has no GL call.
2. **Every AR-originated event posts to GL synchronously and atomically** (D-4) — the sub-ledger movement and the 1200 movement are the same amount in the same TX. There is no eventual-consistency gap.
3. **The on-account credit is netted in the balance read** (D-7) — a receipt's full amount credits 1200 (CR AR for the receipt amount) even when only part is allocated; the unallocated remainder reduces the customer's net AR balance, so the sub-ledger total (`Σ outstanding − Σ unallocated`) still equals 1200.

`ArReconciliationQuery` (D-7) surfaces the equality for finance; an IT pins it (post a credit sale via the event, record a partial receipt, assert sub-ledger total == 1200 balance). This is the AR analogue of GL's "trial balance nets to zero" acceptance bar.

### D-9 — The credit-limit check: AR exposes a read query Sales consumes; the cycle is broken by the outbox

The credit limit (`customers.credit_limit_amount`) is enforced **at the Sales finalise path** (FR-AR-19, §3.5), not inside AR. The seam:

- AR exposes **`ArBalanceService.currentBalance(companyId, customerId) : ArBalanceDto`** (an AR-owned service + DTO) returning the customer's current AR balance (D-7). This is a **read** Sales calls synchronously at finalise.
- The Sales finalise path (D-10) computes `currentBalance + this sale's gross`; if it exceeds `credit_limit_amount`, it warns and requires `SALES.CREDIT.OVERRIDE` (audited).

**No cycle forms** (NFR-AR-06, `ModuleBoundaryTest`):
- **Sales → AR** is a **synchronous read** of `ar.service.ArBalanceService` + `ar.domain.dto.ArBalanceDto` (DTO/service-interface, scalar ids — never an AR entity). This is a new Sales→AR edge.
- **AR ← Sales** is an **async event** (`ArSalePostedHandler` consumes `SALE.FINALISED` via the outbox) + the existing `SalesInvoiceService.findPostingTotalsByUidAndCompany` **read** (AR → `sales.domain.dto` + `SalesInvoiceService`, the same edge GL already has).

So at the *static* level there are edges Sales→AR (service read) **and** AR→Sales (service read + event consume) — which looks like a cycle. It is **not a runtime cycle** because the AR→Sales direction's *event* arm is the outbox (a fully decoupled, async, store-and-forward boundary — ADR-0009: the producer does not call the consumer; the dispatcher does), and the *read* arms are stateless query calls that do not re-enter. The genuinely load-bearing argument for `ModuleBoundaryTest`: **`ModuleBoundaryTest` already tolerates cross-module service-DTO read edges in both directions** (GL→Sales, Stock→Sales/Products all exist); a bidirectional *read* edge between two modules is allowed by the convention (it is repository/entity imports that are forbidden). The credit-limit read (Sales→AR) and the totals read (AR→Sales) are both DTO/service reads, so both are permitted. **Document the ArchUnit stance explicitly:** add an allow-rule note that `sales → ar.service/ar.domain.dto` (credit-limit read) and `ar → sales.service/sales.domain.dto` (totals read) are intentional, DTO-only, no-entity edges; AR consumes `SALE.FINALISED` via `platform.events`. If `ModuleBoundaryTest` enforces acyclic module dependencies strictly (no bidirectional edges at all), the resolution is: **Sales reads AR through the event-decoupled path only is not possible for a synchronous pre-finalise check** — so the credit-limit read MUST be a permitted Sales→AR DTO edge, and AR's read of Sales totals happens **only inside the outbox handler** (which depends on `platform.events`, not a direct Sales→... call chain from AR's own command path). The handler's read of `SalesInvoiceService` is the established Stock/GL pattern and does not constitute AR "depending on" Sales in the command direction. Net: **one allowed Sales→AR read edge + AR's existing event-consumer read of Sales — no command-path cycle.**

### D-10 — Additive Sales credit-sale enablement (the named cross-module change; the `products.vat_status` precedent)

This is the cross-module touch that makes AR open items actually flow. **It is a Sales service-rule relaxation + a DTO derivation change — almost certainly NO Sales schema change** (`sales_invoices` already has `customer_id`, the amounts, `currency`, `status`, `finalised_at`; `customers` already has `customer_kind` + `credit_limit_amount` + `payment_terms_days`). The change has three parts:

1. **Relax the paid-in-full invariant for credit customers (`SalesInvoiceServiceImpl.finalise`).** Today `finalise()` calls `assertPaidInFull(inv, payments)` unconditionally (ADR-0008 D-8, the v1 cash-only reality). The relaxation: **if the invoice's customer is `CREDIT_ACCOUNT`, finalise is allowed without full payment** — payments may be zero or partial; the unpaid residual becomes the AR receivable. For a `CASH_WALK_IN` customer, paid-in-full still holds (unchanged). The finalise path reads the customer kind from Parties (a scalar read AR/Sales already have via the customer id) to branch.
2. **Insert the credit-limit check into `finalise()` for credit customers** (FR-AR-19, D-9): compute `ArBalanceService.currentBalance + inv.gross`; if over `credit_limit_amount`, warn + require `SALES.CREDIT.OVERRIDE` (audited: customer, balance, limit, amount, operator, time). Cash sales are never checked.
3. **Make `InvoicePostingTotalsDto.isCashSale` derive from the customer kind + payment residual, not hard-coded `true`** (`SalesInvoiceServiceImpl.findPostingTotalsByUidAndCompany`, line 553). The rule: `isCashSale = (customer_kind == CASH_WALK_IN) || fullyPaid`. When false (a credit customer with an unpaid residual), the **already-shipped** `GLPostingSafeInvoker.postSaleInNewTx` automatically DRs `ACCOUNTS_RECEIVABLE` instead of `CASH` (verified — the branch on `cashSale` exists), and `ArSalePostedHandler` (D-5) sees `isCashSale == false` and creates the open item. The DTO should also expose the **outstanding residual** (gross − Σ payments) so AR's open item `original_amount`/`outstanding_amount` is exactly the unpaid portion (a partially-paid credit sale yields an open item for the residual only) — add `outstandingAmount` (or `customerKind`) to `InvoicePostingTotalsDto`. This keeps GL (DR AR for the residual; the paid portion DRs Cash — a split entry the handler builds), Sales, and AR consistent.

> **Migration touch:** likely **none** (no new column — the data exists). If the residual cannot be derived cleanly from stored payments at re-read time, the minimal additive option is a computed read in the Sales projection (no schema change). Flagged: the engineer confirms the residual is derivable from `sales_invoice_payments` at finalise; if a partially-paid credit sale needs the split GL entry (DR Cash for paid + DR AR for residual / CR Revenue / CR VAT), `GLPostingSafeInvoker` gains one more line in the existing entry — an additive handler change, no schema. The simplest v1 scope (recommended): a credit sale finalises **fully unpaid** (zero payments), so the open item = full gross and the GL entry is the clean DR AR / CR Revenue / CR VAT the handler already posts; partial-payment-at-finalise for credit customers is a thin additive follow-up.

### D-11 — Module boundary: AR is a leaf consumer/reader; the allowed edges

`ModuleBoundaryTest` discipline (PROJECT-CONVENTIONS §2, NFR-AR-06):
- **AR → `platform.events`** — `ArSalePostedHandler` implements `DomainEventHandler`, uses `IdempotencyGuard`/`DomainEvent`/`DomainEventType`. The platform cross-cutting edge, identical to Stock/GL.
- **AR → `gl.service.GLPostingService` + `gl.service.GLConfigResolver` + `gl.domain.dto.JournalEntryDraft`** — the synchronous posting edge (D-4). A module→service **leaf→service** dependency, DTO + service-interface only, never a GL entity/repository. This is a NEW allowed edge (the first non-event GL consumer); document it in the ArchUnit allow-list. AR also reads GL's `1200` balance via a GL query service for reconciliation (D-8) — same allow-rule.
- **AR → `sales.domain.dto.InvoicePostingTotalsDto` + `SalesInvoiceService`** — the totals re-read inside `ArSalePostedHandler` (D-5), the established Stock/GL pattern.
- **AR → `customers` (Parties)** — `ar_invoices`/`ar_receipts`/etc. FK `customers(id)` (intra-DB FK to a frozen V2 table is allowed — the same way `sales_invoices` FKs `customers`); AR reads the customer (`payment_terms_days`, `customer_kind`, `credit_limit_amount`) via a Parties DTO/scalar projection, never a Parties entity import.
- **Sales → `ar.service.ArBalanceService` + `ar.domain.dto.ArBalanceDto`** — the credit-limit read (D-9/D-10), DTO/service-only.
- **No cross-module FK** into `sales_invoices` or GL `journal_entries`: `source_invoice_uid`/`gl_entry_uid` are plain `VARCHAR(26)` scalars (the `stock_movements.source_document_uid` discipline). FKs to `customers` are intra-DB (a frozen master), which is the accepted Sales/Purchases pattern.

### D-12 — ScopeGuard additions + numbering (`code_sequence` kinds)

`ScopeGuard.companyIdOf` gains the AR target types so 2-arg `@PreAuthorize` gates resolve an AR uid to its company:
```java
case "arinvoice"    -> arInvoices.findCompanyIdByUid(uid);
case "arreceipt"    -> arReceipts.findCompanyIdByUid(uid);
case "arcreditnote" -> arCreditNotes.findCompanyIdByUid(uid);
case "arwriteoff"   -> arWriteOffs.findCompanyIdByUid(uid);
```
Each backed by a single-column `findCompanyIdByUid` projection (the shipped pattern). `ScopeGuard` gains four AR repository constructor deps (the accepted cross-cutting-spine pattern — same as the GL/sales/purchases additions). `assertCanActIn` is called on **every read path** (NFR-AR-01): balance, ageing, statement, open-item list, reconciliation. The SYSTEM open-item creator (`ArSalePostedHandler`) runs under no user permission but is bounded by the event's company/branch context (FR-AR-21).

**`code_sequence` kinds** (created on first use, no seeded row — the shipped pattern): `AR_RECEIPT` (`RCT-####`), `AR_CREDIT_NOTE` (`CRN-####`). Allocations and write-offs carry no external document number (write-offs are identified by uid; the requirement names no `WO-####` series).

### D-13 — Permission catalogue + audit emit points + the gl_configs/CoA seed additions

**Permissions (FR-AR-21, seeded in V11, granted to `ORG_ADMIN` by the V7 CROSS-JOIN pattern):**

| code | module | description |
| --- | --- | --- |
| `AR.VIEW` | ar | View the AR sub-ledger, balances, ageing, and the reconciliation read |
| `AR.INVOICE.VIEW` | ar | View AR open items (the receivable detail) |
| `AR.RECEIPT.RECORD` | ar | Record a customer receipt (RCT-####) and post its cash leg |
| `AR.RECEIPT.ALLOCATE` | ar | Allocate / re-allocate a receipt across open items |
| `AR.WRITEOFF` | ar | Write off an uncollectable open item (bad debt) |
| `AR.STATEMENT.VIEW` | ar | View / print a customer statement |
| `AR.OPENING.SET` | ar | Enter AR opening balances at go-live |
| `SALES.CREDIT.OVERRIDE` | sales | Finalise a credit sale that exceeds the customer's credit limit (audited override) |

`SALES.CREDIT.OVERRIDE` is in module **`sales`** (it gates the Sales finalise path, D-10) and is seeded with the Sales-touch part of V11 (or it may be seeded in V11's permission block with `module = 'sales'`; either is additive `ON CONFLICT (code) DO NOTHING`). A credit-note raise permission is not separately specified by the requirement; the credit-note path is gated by `AR.WRITEOFF`'s sibling — recommend it rides `AR.RECEIPT.ALLOCATE` or a thin `AR.CREDITNOTE` (flag OQ — the requirement lists no explicit code; recommend adding `AR.CREDITNOTE` for symmetry with AP's `AP.DEBITNOTE`).

**Audit emit points (NFR-AR-03 — every mutation, IAM append-only audit):**

| action | when | target_type / target |
| --- | --- | --- |
| `AR.OPENITEM.CREATE` | open item created (actor = SYSTEM for the event handler) | `ar_invoices` / id |
| `AR.RECEIPT.RECORD` | receipt recorded + posted | `ar_receipts` / id |
| `AR.RECEIPT.ALLOCATE` | allocate / re-allocate | `ar_receipts` / id |
| `AR.WRITEOFF` | write-off posted | `ar_write_offs` / id |
| `AR.CREDITNOTE.RAISE` | credit note raised (+ posted if standalone) | `ar_credit_notes` / id |
| `AR.OPENING.SET` | opening balance entered + posted | `ar_invoices` / id |
| `SALES.CREDIT.OVERRIDE` | credit-limit override at finalise | `sales_invoices` / id (emitted by the Sales path) |

**CoA + gl_configs seed additions (V11, additive — never editing V10's seed rows):**
- **CoA:** add `5500 Bad Debt Expense` (EXPENSE/DEBIT) per existing company (CROSS JOIN, deterministic seed-uid, the V10 pattern); optionally `3100 Opening Balance Equity` (EQUITY/CREDIT) if not reusing `3000`.
- **`gl_configs`:** widen `chk_gl_config_key` (additive `ALTER … DROP/ADD CONSTRAINT` or the CHECK already admits all reserved keys — verify; AR adds `BAD_DEBT_EXPENSE`, `OPENING_BALANCE_EQUITY` to the IN-list) and seed `BAD_DEBT_EXPENSE → 5500`, `OPENING_BALANCE_EQUITY → 3000` (or `3100`) per company (joining the just-seeded CoA on `(company_id, account_code)`). `ACCOUNTS_RECEIVABLE → 1200`, `CASH → 1000`, `SALES_REVENUE → 4100`, `VAT_PAYABLE → 2200` are **already seeded** by V10 — AR reuses them.
- A Java `ArGlSeeder` (the `TaxRateSeeder`/`GlConfigSeeder` precedent) seeds the new CoA account + gl_config keys for new companies via `BootstrapRunner`/`CompanyService.create`.

### D-14 — Migration: additive `V11__accounts_receivable.sql`, never a V1–V10 edit; ordering

IAM=V1 … GL=V10 — all frozen. AR is **`V11__accounts_receivable.sql`**, purely additive. Ordering (FK dependencies):
1. **`ar_invoices`** (FKs `companies`/`branches`/`customers`).
2. **`ar_receipts`** (FKs same) → **`ar_receipt_allocations`** (FKs `ar_receipts` + `ar_invoices`).
3. **`ar_credit_notes`** (FKs `ar_invoices`) → **`ar_write_offs`** (FKs `ar_invoices`).
4. **Indexes** for all of the above (D-2).
5. **CoA seed** (`5500` [+ `3100`]) per existing company (CROSS JOIN; deterministic seed-uid).
6. **`gl_configs` CHECK widen + key seed** (`BAD_DEBT_EXPENSE`, `OPENING_BALANCE_EQUITY`) per existing company.
7. **`journal_*` source-type CHECK widen** (`AR_RECEIPT`/`AR_WRITEOFF`/`AR_CREDIT_NOTE` — additive `ALTER … DROP CONSTRAINT chk_… ; ADD CONSTRAINT chk_… CHECK (… IN (…))`).
8. **Permission seed** (`AR.*` + `SALES.CREDIT.OVERRIDE`, `ON CONFLICT (code) DO NOTHING`) + `ORG_ADMIN` `role_permission` CROSS-JOIN grant (the V7 pattern).

No `code_sequence` row seeded (`AR_RECEIPT`/`AR_CREDIT_NOTE` rows created on first use). No outbox table (V6 owns it). No FK into `domain_events`, `sales_invoices`, or `journal_entries` (cross-module scalars). No trigger. Table style follows shipped V5/V8/V10 exactly (`BIGINT GENERATED BY DEFAULT AS IDENTITY`, `uid VARCHAR(26)`, plural tables, singular constraint roots, plural `ix_`, `NUMERIC(19,4)` money). All FK targets exist in frozen V1/V2.

> **The frozen-CHECK edits in steps 6/7 are additive ALTERs, not V10 edits.** They `DROP` and re-`ADD` the named CHECK constraints on `gl_configs`/`journal_batches`/`journal_entries` with a widened IN-list — the exact `chk_sales_invoice_doc_type` widening pattern ADR-0013 D-13 anticipated. The tables and data are untouched; only the constraint's admissible set grows. This is the sanctioned way to add a posting role without editing the GL migration.

## Consequences

**Easier / safer:**
- **The books gain a customer sub-ledger that reconciles by construction** (D-3/D-8): AR never double-posts the credit-sale debit, every AR event posts to GL atomically in the same TX, and the on-account credit nets in the balance read — so `Σ sub-ledger == 1200` holds at every committed state, not eventually. The reconciliation read surfaces the equality for finance; an IT pins it.
- **The sync-post mechanism (D-4) keeps a receipt atomic** — no in-flight gap, a clear error if GL can't post, no half-recorded receipt. The outbox is reserved for the one genuinely-async, posts-nothing path (creation).
- **AR slots onto the shipped posting engine with no GL rework** — the `GLPostingSafeInvoker` cash-vs-AR branch already exists; the AR control debit on a credit sale "goes live" the moment `isCashSale` derives from the customer kind (D-10). Two new `gl_configs` keys + one CoA account, all additive.
- **The Sales credit-sale enablement is a service-rule relaxation + a DTO derivation** (D-10), almost certainly no schema change — the `products.vat_status` precedent. AR's open-item handler keys off `customerKind`/`isCashSale`; the credit path is purely additive.
- **The cycle is resolved deliberately** (D-9): Sales→AR is a DTO read for the credit limit; AR←Sales is the outbox event + the established totals read. The ArchUnit stance is documented, not stumbled into.

**Harder / to watch:**
- **The Σ-allocations and outstanding-balance invariants are service-owned** (D-3) — no DB CHECK can sum siblings; `ArReceiptService` is the single home and must serialise `outstanding_amount` under concurrency (optimistic-lock retry or `SELECT … FOR UPDATE`). An IT must pin "over-allocation rejected" and "concurrent receipts don't over-settle."
- **The Sales→AR credit-limit read couples the finalise path to AR being up** (D-9) — a synchronous read at finalise. Acceptable (same-process modular monolith); a future extraction would make it a remote call. Documented.
- **The bidirectional Sales↔AR read edges need an explicit ArchUnit allow-rule** (D-9/D-11) — if `ModuleBoundaryTest` enforces strict acyclic module deps, the rule must permit the two DTO read edges; the build will fail loudly if not, which is the desired signal.
- **`isCashSale` must be derived correctly** (D-10) — getting it wrong (a credit sale flagged cash) would DR Cash instead of AR control and create no open item, silently under-counting receivables. An IT must finalise a credit sale and assert (a) GL DRs 1200, (b) one open item exists, (c) reconciliation holds.

**Migration / delivery cost:**
- 1 additive Flyway file (`V11__accounts_receivable.sql`): **5 new tables** (`ar_invoices`, `ar_receipts`, `ar_receipt_allocations`, `ar_credit_notes`, `ar_write_offs`) + FKs/uniques/CHECKs + ~14 indexes; **CoA seed** (1–2 accounts/company); **gl_configs CHECK widen + 2 key seeds**/company; **journal source-type CHECK widen**; **permission seed** (8 perms + grant). No outbox table, no `code_sequence` row, no trigger. Depends only on frozen V1/V2.
- Backend (AR module): the `com.erp.modules.ar` set per D-1 — 5 entities + enums, 5 repositories (each with `findCompanyIdByUid`), the services (receipt/write-off/credit-note/opening + balance/ageing/statement/reconciliation queries + number generator), the one `events` handler, ~6 controllers, the `ArGlSeeder`.
- Backend (Sales touch — D-10): relax `assertPaidInFull` for credit customers; insert the credit-limit check; derive `isCashSale` + expose the residual in `InvoicePostingTotalsDto`. **No Sales schema change** (confirm residual derivable). One Sales→AR service dependency (`ArBalanceService`).
- Backend (platform touch): `ScopeGuard` gains 4 AR cases + 4 repo deps (D-12); ArchUnit allow-list gains the AR→GL-service edge + the Sales↔AR DTO read edges (D-11).
- Web: AR open-item list, receipt record + allocate (oldest-first default, manual override, on-account), write-off, credit note, customer statement + ageing, opening balances, reconciliation read — `ApiResponse<T>`, Long-as-string, address by uid.
- Deployment risk: **low** — additive on frozen schema; reuses the proven outbox-consumer + sync-posting machinery; the one operational note is the same FAILED-event/single-instance caveat as ADR-0009 for the `ArSalePostedHandler`.

## Alternatives considered

- **Outbox event for receipt/write-off posting (instead of synchronous `GLPostingService.post`).** AR would emit `AR.RECEIPT.RECORDED` and GL would consume it. **Rejected (D-4):** it opens an in-flight window where the sub-ledger is written but GL 1200 is not yet posted — a reconciliation read during that window is wrong, breaking the chief acceptance bar (BR-AR-02 holds *at all times*). A receipt is a single synchronous user command on one aggregate, not a cross-aggregate reaction; the outbox's decoupling buys nothing here and costs atomicity. Sync keeps sub-ledger + control in one TX. (The outbox IS used for creation — D-5 — because that genuinely is cross-aggregate and posts nothing.)
- **Combined `ar_payments` table with the allocated invoice folded in (no allocation junction).** Fewer tables. **Rejected (D-2):** a receipt can pay several open items (oldest-first auto), and an open item is paid by several receipts over time — a many-to-many that a single allocated-invoice column cannot express, and re-allocation would rewrite the receipt row (losing history, and muddying "re-allocate posts nothing to GL"). The junction is the boring relational shape; it makes the GL link live only on the receipt (BR-AR-12 structural).
- **One generic `ar_adjustments` table (kind = CREDIT_NOTE | WRITE_OFF).** One table. **Rejected (D-2d):** different GL postings, different permissions, different numbering — a `kind` discriminator hides those differences and invites a posting bug. Two thin purpose-named tables match the shipped one-document-per-table style and keep each GL posting rule local.
- **A stored `ar_balances` / materialised customer-balance table.** Pre-aggregated balances for fast reads. **Rejected for v1 (D-7):** the balance is `Σ outstanding − Σ unallocated` over indexed columns, cheap at QA scale, and a stored balance adds an invalidation problem (every receipt/note/write-off dirties it) and a second source of truth that can drift from the sub-ledger — the exact reconciliation risk this module exists to avoid. A Reporting snapshot is T2.3's additive call if volume warrants (NFR-AR-08).
- **AR posts the credit-sale debit itself (and GL's `SalesPostingHandler` skips AR for credit sales).** AR would own the full receivable posting. **Rejected (BR-AR-02, D-5):** GL's handler already posts DR 1200 / CR Revenue / CR VAT for a credit sale (the shipped cash-vs-AR branch); making AR also post would double the receivable, and splitting "GL posts cash sales, AR posts credit sales" fragments the single sale-posting path across two modules. AR records detail only; GL remains the sole sale-poster. No double-post.

## Open / flagged items (do NOT block the build; recommended defaults stand — accounts-receivable.md §11)

1. **OQ-AR-01 — Due-date / payment terms.** **Default:** `due_date = invoice_date + customers.payment_terms_days` if set, else net-on-receipt (0 days). The column + ageing buckets are fixed. *Blocks build:* **NO.**
2. **OQ-AR-02 — Statement format.** **Default:** one open-item statement (open items + 5 ageing buckets + recent activity), view/print. *Blocks build:* **NO.**
3. **OQ-AR-03 — Write-off approval.** **Default:** permissioned + audited single act (`AR.WRITEOFF`); no approval workflow, no allowance account. *Blocks build:* **NO.**
4. **OQ-AR-04 — Credit note: standalone vs ride the void.** **Default:** `origin = SALE_VOID` rides GL's `SaleVoidingHandler` (no second post); `origin = STANDALONE` posts DR Revenue / DR VAT / CR AR (D-2d/D-6). Both reconcile. *Blocks build:* **NO.**
5. **OQ-CUR-03 (carried) — Rounding & TZS decimals.** **Default:** HALF_UP, TZS = 0 dp; allocations + GL legs round identically (`BigDecimal.compareTo`). *Blocks build:* **NO** for the model; confirm before go-live.
6. **D-10 partial-payment-at-finalise for credit customers.** **Default:** v1 credit sale finalises fully unpaid (open item = full gross; clean DR AR entry). Partial-payment-at-finalise (split DR Cash + DR AR) is a thin additive follow-up. *Blocks build:* **NO.**
7. **`AR.CREDITNOTE` permission code.** The requirement lists no explicit credit-note code. **Recommend** adding `AR.CREDITNOTE` (symmetry with AP's `AP.DEBITNOTE`); fallback gates the path under `AR.RECEIPT.ALLOCATE`. *Blocks build:* **NO.**

None of the above changes the five-table schema or the reconciliation/no-double-post rules; all are policy/tuning/additive choices the design is built to.

## Summary

This ADR is the technical design for **AR Increment 2 (T1.2)** — the **customer sub-ledger** in `com.erp.modules.ar` behind GL `1200`, defined in additive **`V11__accounts_receivable.sql`** (never editing frozen V1–V10). **Five tables:** `ar_invoices` (open items — `original_amount`/`outstanding_amount`/`due_date`/`status OPEN|PARTIAL|PAID|WRITTEN_OFF`, `source SALE|OPENING_BALANCE`, `source_invoice_uid` scalar, `uq_ar_invoice_source_sale` partial-unique idempotency backstop); `ar_receipts` (`RCT-####`, `amount`/`unallocated_amount`, `gl_entry_uid` scalar) + `ar_receipt_allocations` (junction, receipt↔open-item); `ar_credit_notes` (`CRN-####`, `origin STANDALONE|SALE_VOID`) and `ar_write_offs` (bad debt). **The reconciliation crux (D-3/D-8):** AR never re-posts the credit-sale AR-control debit (GL's `SalesPostingHandler` already did — the **no-double-post rule, BR-AR-02**); every AR-originated event (receipt, write-off, standalone credit note, opening balance) posts to GL **synchronously via `GLPostingService.post` in the same TX** (D-4 — atomicity = reconciliation by construction; the outbox is reserved for the one cross-aggregate, posts-nothing path: open-item creation). **Open-item creation:** `ArSalePostedHandler` consumes `SALE.FINALISED` (consumer `AR.SALE_POST`), **credit sales only** (skips `isCashSale`), creates the open item, **posts nothing to GL** — idempotent via the marker + the partial-unique. **Exact GL postings + NEW gl_config keys (D-6):** receipt = DR `CASH` / CR `ACCOUNTS_RECEIVABLE`; write-off = DR **`BAD_DEBT_EXPENSE`** (NEW, → `5500`) / CR AR; standalone credit note = DR `SALES_REVENUE` + DR `VAT_PAYABLE` / CR AR; opening balance = DR AR / CR **`OPENING_BALANCE_EQUITY`** (NEW, → `3000`/`3100`). **Credit limit (D-9):** AR exposes `ArBalanceService.currentBalance`; Sales reads it at finalise (warn + `SALES.CREDIT.OVERRIDE`, audited) — no command-path cycle (Sales→AR is a DTO read; AR←Sales is the outbox event + the totals read). **Additive Sales credit-sale enablement (D-10, the `products.vat_status` precedent — almost certainly NO Sales schema change):** relax `assertPaidInFull` for `CREDIT_ACCOUNT` customers, insert the credit-limit check, and derive `InvoicePostingTotalsDto.isCashSale` from the customer kind so the unpaid balance flows to AR and the **already-shipped** `GLPostingSafeInvoker` AR-control branch goes live. **Statements/ageing/balance/reconciliation are reads** (D-7/D-8), not tables. **Scope/security:** `ScopeGuard` gains `arinvoice`/`arreceipt`/`arcreditnote`/`arwriteoff`; `assertCanActIn` on every read path; perms `AR.VIEW`/`AR.INVOICE.VIEW`/`AR.RECEIPT.RECORD`/`AR.RECEIPT.ALLOCATE`/`AR.WRITEOFF`/`AR.STATEMENT.VIEW`/`AR.OPENING.SET` + `SALES.CREDIT.OVERRIDE`; audit on every mutation. **`code_sequence` kinds** `AR_RECEIPT`/`AR_CREDIT_NOTE`. **Module boundary (D-11):** AR → `platform.events` + `gl.service.GLPostingService` (NEW allowed leaf→service edge) + `sales.domain.dto`/`SalesInvoiceService` + `customers` FK; Sales → `ar.service.ArBalanceService` (credit read); no cross-module FK into `sales_invoices`/`journal_entries` (scalar uids). **Ready for build:** every flagged item has a recommended default the design is built to; the five tables, the one handler, the four sync posting commands, the reconciliation rule, and the Sales touch are concrete enough to write without guessing a business rule. **Additive on frozen V1–V10:** confirmed — 5 new tables, 1 CoA account, 2 new gl_config keys, additive CHECK widens (the sanctioned `chk_sales_invoice_doc_type` pattern), no V1–V10 edit.

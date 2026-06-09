# 0016 — Cash & Bank data model: the cash book + bank book — named cash/bank accounts each linked to a GL 1xxx asset account, inter-account transfers, direct entries, a cheque register, manual bank reconciliation, and the additive AR/AP cash-leg routing that replaces the single `gl_configs` `CASH` bridge

- **Status:** Accepted
- **Date:** 2026-06-09
- **Deciders:** solutions-architect (owner-ratified Cash & Bank requirements 2026-06-09 — all Cash & Bank scoping forks resolved; no ADR-0016-blocking open question remains, cash-and-bank.md §11)
- **Context source:** [docs/requirements/cash-and-bank.md](../requirements/cash-and-bank.md) (RATIFIED 2026-06-09 — FR-CASH-01..19, BR-CASH-01..13, US-CASH-01..07, §7 flows, §10 accepted boundary, §11 OQ log; ground truth for every rule below). [ADR-0013](0013-general-ledger-data-model.md) + the **shipped** GL code (`com.erp.modules.gl`): `GLPostingService.post(JournalEntryDraft)` (the synchronous double-entry engine — validates ≥2 lines, balance, OPEN period, active accounts, base currency; writes batch+entry+lines atomically and returns a `JournalEntryDto` carrying the new `journal_entries.uid`), the `JournalEntryDraft`/`LineDraft` internal DTOs (verified: `companyId, branchId, postingDate, description, sourceType, sourceRef, reversalOfId, postedBy, List<LineDraft>`), `GLConfigResolver.resolve(companyId, GlConfigKey)` (throws if the mapping is missing or the account inactive — BR-GL-10), `GlConfigKey` (enum has `CASH` active + `INVENTORY`/`COGS`/`ACCOUNTS_PAYABLE` reserved + `BAD_DEBT_EXPENSE`/`OPENING_BALANCE_EQUITY`/`PURCHASES` from AR/AP), `JournalSourceType` (`MANUAL`/`SALES`/`SALES_REVERSAL`/`OPENING_BALANCE`/`AR_*`/`AP_*` admitted; `CASH` **reserved, NOT yet admitted by the DB CHECK** — V13 widens it), `FiscalPeriodResolver`, `ChartOfAccount` (the `1xxx` asset accounts). [ADR-0014](0014-accounts-receivable-data-model.md) + the **shipped** `com.erp.modules.ar`: `ArReceiptServiceImpl.recordAndAllocate(RecordReceiptRequest)` posts the cash leg **DR `glConfig.resolve(companyId, CASH)` / CR `ACCOUNTS_RECEIVABLE`** synchronously in the same TX (verified at `ArReceiptServiceImpl:167-188`); `RecordReceiptRequest` (no cash/bank field today). [ADR-0015](0015-accounts-payable-data-model.md) + the **shipped** `com.erp.modules.ap`: `ApPaymentServiceImpl.postPaymentToGl(...)` posts **DR `ACCOUNTS_PAYABLE` / CR `glConfig.resolve(companyId, CASH)`** (verified at `ApPaymentServiceImpl:248-249`); `PaySingleBillRequest` / `PaymentRunRequest` (no cash/bank field today). [ADR-0005](0005-money-and-currency.md) (`Money` NUMERIC(19,4)+currency; cash/bank account currency = `companies.base_currency` in v1 — BR-CASH-11). [ADR-0007](0007-products-data-model.md) (`code_sequence(company_id, entity_kind)` row-locked numbering, D-6). [ADR-0002](0002-rbac-and-scope.md)/`ScopeGuard` (the `companyIdOf(targetType, uid)` switch + the per-module repository deps — verified the AR/AP cases + the constructor-injection pattern at `platform.security.ScopeGuard`). [[db-naming-convention]] verified against shipped V1–V12 (plural masters/logs, singular junctions, singular constraint roots `uq_`/`fk_`/`chk_`, plural index names `ix_`, `uid VARCHAR(26)` ULID, `company_id` scalar BIGINT, audit cols, partial-unique pattern, append-only posting). **Latest shipped migration is `V12__accounts_payable.sql` → Cash & Bank is `V13__cash_and_bank.sql`** (additive; never edits V1–V12). Next ADR is 0017.

This ADR is the **technical data model + integration design** for the Cash & Bank module (Cash & Bank, ROADMAP T1.4, Increment 3 — the Tier-1 finance finisher). It translates the ratified business spec into tables, columns, types, keys, indexes, the enforcement split, the synchronous GL-posting mechanism, the transfer / direct-entry / cheque / reconciliation mechanics, the cash-account⇄linked-GL reconciliation invariant, permissions/audit/scope, and the **additive AR/AP cash-leg routing** — concrete enough that the backend engineer writes `V13__cash_and_bank.sql` + the entities + the posting/reconciliation logic + the additive AR/AP touch **without guessing a business rule**. It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing ratified is re-litigated.

## Context

Cash & Bank is the **cash book + bank book** — the named money locations (petty cash, tills, bank accounts) where AR/AP settlement money actually lives, reconciled to the bank statement and to the GL. The dependency platform is shipped and consumed unchanged: GL gives the posting engine + `GLConfigResolver` + `chart_of_accounts` + `fiscal_periods`; AR/AP give the shipped receipt/payment services whose cash leg is currently hard-wired to `gl_configs.CASH`; Money fixes base-currency-only; `code_sequence` gives `CB-####`/`CBT-####`/cheque/reconciliation numbering. The central force mirrors AR/AP's, with **one decisive new shape** and **one decisive direction question**:

- **The chief acceptance bar is the same sub-ledger⇄control invariant, applied per money location (BR-CASH-02, NFR-CASH-01): a cash/bank account's book balance == its linked GL `1xxx` account balance at all times.** Because every cash/bank movement posts synchronously to that linked GL account in the same TX, they move together by construction — exactly AR ⇄ `1200` / AP ⇄ `2100`, but now there is **one GL control account per cash/bank account** instead of one shared `CASH`. Resolved in D-3/D-7/D-9.

- **The single `gl_configs` `CASH` bridge is being replaced (OQ-CASH-07).** Today AR's receipt cash leg and AP's payment cash leg both resolve `glConfig.resolve(companyId, GlConfigKey.CASH)` (verified, shipped). Cash & Bank introduces **named cash/bank accounts each with their own linked GL account**, so an AR receipt / AP payment must now choose *which* cash/bank account — and the debit/credit becomes **that account's linked GL account**, not the bare `CASH` mapping. Resolved in D-8 (the additive touch) + D-10 (`CASH` becomes the resolution of the company default account).

- **The dependency direction of the additive touch — AR/AP→CashBank, never CashBank→AR/AP.** AR and AP already exist and ship; the touch makes them resolve a cash/bank account's linked GL account through a Cash & Bank service and record the settlement as a `cash_transactions` row. The forces: keep Cash & Bank a leaf that AR/AP *call* (a new AR→CashBank / AP→CashBank read + write), never a module that imports AR/AP entities or reacts to AR/AP events (no cycle, no `ModuleBoundaryTest` violation). Resolved in D-8/D-11.

- **The cash/bank ledger grain — direction flag vs signed amount.** Every movement is a row in `cash_transactions`. GL itself chose **separate debit/credit columns** (a line is one side or the other; `chk_journal_line_one_side`). The forces: mirror that proven choice for auditability and to avoid sign-mistake bugs, vs a single signed amount that is terser but loses the explicit IN/OUT intent and invites `+`/`−` errors at every read. Resolved in D-3 (a `direction` enum + a non-negative `amount` — the cash-book analogue of GL's two columns, simpler than two money columns because a cash transaction is always exactly one side).

- **Transfers: a header table or paired transactions.** A transfer is one business act that produces two cash movements (OUT source, IN destination) + one balanced GL entry. The forces: a `cash_transfers` header makes the act a first-class, auditable, numbered (`CBT-####`) entity that owns the two legs and the single GL entry ref — vs two loosely-coupled `cash_transactions` sharing a ref string, which scatters the act's identity. Resolved in D-4 (a `cash_transfers` header owning two `cash_transactions`).

- **Cash/bank account ⇄ GL account cardinality (OQ-CASH-08).** Whether two cash/bank accounts may share a linked GL account. The forces: a shared GL account means two book balances reconcile to one GL balance — the reconciliation read can no longer pin a single account's drift. Resolved in D-1 (recommended **one-to-one**, enforced by a partial-unique on the link).

- **Schema freeze / migration ordering.** IAM=V1 … AP=V12 — all frozen and shipped. Cash & Bank is **additive `V13__cash_and_bank.sql`**; it must not edit V1–V12. It FKs only frozen `companies`/`branches`/`app_users`/`chart_of_accounts` (and intra-module Cash & Bank tables); it references AR receipts / AP payments / GL `journal_entries` by **scalar uid**, never a cross-module FK (the `stock_movements.source_document_uid` discipline). The one frozen-table touch is two **nullable ALTER ADD COLUMN** on `ar_receipts`/`ap_payments` (D-8) — additive, not a rewrite.

## Decision

### D-1 — Module placement: one `com.erp.modules.cashbank` module; controllers flat in `com.erp.api`

Cash & Bank lives under **`com.erp.modules.cashbank`** — a flat sibling of `gl`/`ar`/`ap`/`sales`/`purchases` (PROJECT-CONVENTIONS §2; the same reasoning ADR-0013 D-1 used to reject a nested `treasury.cashbank`). **`cashbank`, not `cash`** — the module owns both the cash book and the bank book, and "cash" alone reads as petty-cash-only. Internal layout:

```
com.erp.modules.cashbank
├── domain.entity   CashBankAccount, CashTransaction, CashTransfer, Cheque, BankReconciliation
├── domain.dto      CashBankAccountDto, CreateCashBankAccountRequest, UpdateCashBankAccountRequest,
│                   CashTransactionDto, RecordDirectEntryRequest, RecordTransferRequest,
│                   ChequeDto, RegisterChequeRequest, ChequeStatusChangeRequest,
│                   BankReconciliationDto, OpenReconciliationRequest, MarkClearedRequest,
│                   CompleteReconciliationRequest, CashAccountStatementDto, CashAccountBalanceDto,
│                   CashAccountGlResolutionDto (the AR/AP resolution read — D-8)
├── domain.enums    CashBankAccountType (CASH|BANK),
│                   CashTxnDirection (IN|OUT),
│                   CashTxnType (AR_RECEIPT|AP_PAYMENT|TRANSFER_IN|TRANSFER_OUT|DIRECT_ENTRY),
│                   ChequeStatus (ISSUED|CLEARED|CANCELLED),
│                   ReconciliationStatus (DRAFT|COMPLETED)
├── repository      CashBankAccountRepository, CashTransactionRepository, CashTransferRepository,
│                   ChequeRepository, BankReconciliationRepository
├── service         CashBankAccountService(+Impl)      — create/edit/deactivate + default flag + GL link,
│                   CashTransferService(+Impl)          — transfer + 2 txns + post (D-4/D-7),
│                   CashDirectEntryService(+Impl)       — direct entry + 1 txn + post (D-4/D-7),
│                   ChequeService(+Impl)                — register + status transitions (D-5),
│                   BankReconciliationService(+Impl)    — open/mark-cleared/complete (D-6),
│                   CashAccountStatementQuery           — running statement + balance (D-7),
│                   CashGlReconciliationQuery           — book balance vs linked GL balance (D-9),
│                   CashBankAccountResolver             — companyId + cashBankAccountUid? → linked GL acct (D-8),
│                   CashTransactionRecorder             — internal: append a cash_transactions row (D-8),
│                   CashBankNumberGenerator             — CB-####/CBT-####/cheque/recon via code_sequence (D-12)
└── (no events package — Cash & Bank is in-request user-action driven; every post is synchronous — D-7)
```

Controllers stay flat in `com.erp.api` — `CashBankAccountController`, `CashTransferController`, `CashDirectEntryController`, `ChequeController`, `BankReconciliationController`, `CashAccountStatementController` — touching only services (`ModuleBoundaryTest`). **No outbox consumer** (every Cash & Bank movement is a single in-request user action that posts synchronously — D-7, the AR/AP-payment precedent, ADR-0015 D-5).

### D-2 — The five table groups: `cash_bank_accounts`, `cash_transactions`, `cash_transfers`, `cheques`, `bank_reconciliations`

All masters/logs plural; junction/log style per the shipped convention. Every table carries `company_id` (BR-CASH-08) and participates in the §3.2 tenant predicate. Cross-module references (AR receipt / AP payment / `journal_entries` uids) are **scalar VARCHAR(26), no FK** (D-11). FKs into `chart_of_accounts` are intra-DB FKs to a frozen master (the accepted AR/AP `customers`/`suppliers` pattern).

#### (a) `cash_bank_accounts` (the money location — master, per company)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` | NO | transactions/transfers/cheques/reconciliations FK this |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_cash_bank_account_uid`; URLs address by uid; `ScopeGuard case "cashbankaccount"` (D-12) |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope; never updated |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; nullable — a CASH till/petty-cash **may** be branch-scoped; a BANK account is company-level (OQ-CASH-03 default) |
| `code` | `VARCHAR(30)` | NO | `CB-####` from `code_sequence (company_id,'CASH_BANK_ACCOUNT')` (D-12); `uq_cash_bank_account_company_code` |
| `name` | `VARCHAR(120)` | NO | the money-location name ("Main Bank — CRDB", "Front-Till", "Petty Cash") |
| `account_type` | `VARCHAR(10)` | NO | `CashBankAccountType`: `CASH`\|`BANK`; CHECK below |
| `bank_name` | `VARCHAR(120)` | YES | for BANK only (required when `account_type='BANK'` — service-enforced, FR-CASH-01) |
| `bank_account_no` | `VARCHAR(60)` | YES | for BANK only |
| `bank_branch` | `VARCHAR(120)` | YES | for BANK only |
| `currency` | `VARCHAR(3)` | NO | = `companies.base_currency` (BR-CASH-11); service-enforced equal to base |
| `gl_account_id` | `BIGINT` | NO | **FK → `chart_of_accounts(id)`** — the linked `1xxx` asset account (BR-CASH-01); mandatory at create; **one GL account per cash/bank account** (D-1, partial-unique below) |
| `is_default` | `BOOLEAN` | NO | DEFAULT FALSE; the company default for AR/AP fallback (BR-CASH-09); **at most one TRUE per company** (partial-unique below) |
| `active` | `BOOLEAN` | NO | DEFAULT TRUE; a deactivated account takes no new transaction but keeps its history (FR-CASH-02, BR-CASH-08/13) |
| `version` | `BIGINT` | NO | optimistic lock, DEFAULT 0 |
| audit cols | `TIMESTAMPTZ`/`BIGINT` | mixed | `created_at`/`created_by`/`updated_at`/`updated_by` (`*_by` → `app_users.id`, no FK — the system-write pattern) |

**Constraints:**
- `uq_cash_bank_account_uid UNIQUE (uid)`; `uq_cash_bank_account_company_code UNIQUE (company_id, code)`.
- `uq_cash_bank_account_gl UNIQUE (company_id, gl_account_id)` — **one-to-one** cash/bank account ⇄ linked GL account per company (D-1, OQ-CASH-08 default); two accounts may not share a GL account, so each book balance reconciles to a distinct GL balance.
- `uq_cash_bank_account_default UNIQUE (company_id) WHERE is_default` — **partial unique**: at most one default per company (BR-CASH-09; the `uq_user_branch_default` precedent from IAM).
- `fk_cash_bank_account_company`, `fk_cash_bank_account_branch`, `fk_cash_bank_account_gl_account` (→ `chart_of_accounts(id)`).
- `chk_cash_bank_account_type CHECK (account_type IN ('CASH','BANK'))`.
- The "BANK requires bank_name; CASH forbids it" rule is **service-enforced** (FR-CASH-01) — a partial CHECK is possible (`CHECK ((account_type='BANK') OR (bank_name IS NULL AND bank_account_no IS NULL AND bank_branch IS NULL))`) and **recommended** as a DB backstop (`chk_cash_bank_account_bank_details`).

**Indexes:**
```
CREATE INDEX ix_cash_bank_accounts_company        ON cash_bank_accounts (company_id);
CREATE INDEX ix_cash_bank_accounts_company_active ON cash_bank_accounts (company_id, active);  -- active targets for AR/AP/transfer
CREATE INDEX ix_cash_bank_accounts_gl             ON cash_bank_accounts (company_id, gl_account_id);
-- the default lookup is served by the partial-unique uq_cash_bank_account_default
```

#### (b) `cash_transactions` (the cash/bank ledger — every movement; append-only)

One row per movement on a cash/bank account. **The book balance of an account = `Σ(IN amount) − Σ(OUT amount)` over its non-void transactions** (BR-CASH-02). Append-only; corrections are reversing transactions (BR-CASH-10).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_cash_transaction_uid`; `ScopeGuard case "cashtransaction"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag (the account's branch or the operator's) |
| `cash_bank_account_id` | `BIGINT` | NO | FK → `cash_bank_accounts(id)` — the money location moved |
| `txn_number` | `VARCHAR(30)` | NO | `CBTX-####` from `code_sequence (company_id,'CASH_TXN')` (D-12); `uq_cash_transaction_company_number` |
| `txn_date` | `DATE` | NO | the business value date (GL `posting_date` source; statement ordering — NFR-CASH-07) |
| `direction` | `VARCHAR(3)` | NO | `CashTxnDirection`: `IN`\|`OUT` (the cash-book analogue of GL's debit/credit columns — D-3); CHECK below |
| `amount` | `NUMERIC(19,4)` | NO | the movement amount, **always ≥ 0** (the sign is carried by `direction`, D-3); CHECK `> 0`; `Money`, base currency |
| `currency` | `VARCHAR(3)` | NO | = company base currency |
| `txn_type` | `VARCHAR(20)` | NO | `CashTxnType`: `AR_RECEIPT`\|`AP_PAYMENT`\|`TRANSFER_IN`\|`TRANSFER_OUT`\|`DIRECT_ENTRY`; CHECK below |
| `source_ref` | `VARCHAR(26)` | YES | the originating document uid (AR receipt / AP payment / `cash_transfers.uid` / cheque) — scalar, **no FK** (D-11); NULL for an orphan direct entry |
| `counter_gl_account_id` | `BIGINT` | YES | for `DIRECT_ENTRY`: the income/expense/equity account the **other** GL leg hits (FR-CASH-09, BR-CASH-05); NULL for non-direct types (the counter account is the AR-control / AP-control / the other cash account); FK → `chart_of_accounts(id)` |
| `journal_entry_ref` | `VARCHAR(26)` | YES | the **`journal_entries.uid`** of the GL entry this movement is part of (scalar, no FK — traceability/reconciliation); for AR/AP types this is the receipt's/payment's GL entry; NULL only if the post failed (anomaly) |
| `cleared` | `BOOLEAN` | NO | DEFAULT FALSE; marked TRUE during a bank reconciliation (FR-CASH-13, D-6) |
| `cleared_in_reconciliation_id` | `BIGINT` | YES | FK → `bank_reconciliations(id)` — set when marked cleared; immutable once that reconciliation COMPLETES (BR-CASH-07, D-6) |
| `memo` | `VARCHAR(255)` | YES | free-text note (audited) |
| `version` | `BIGINT` | NO | DEFAULT 0 |
| audit cols | … | mixed | standard; **no soft delete** — append-only (BR-CASH-10); a correction is a reversing transaction |

**Constraints:**
- `uq_cash_transaction_uid UNIQUE (uid)`; `uq_cash_transaction_company_number UNIQUE (company_id, txn_number)`.
- `fk_cash_transaction_company`, `fk_cash_transaction_branch`, `fk_cash_transaction_account` (→ `cash_bank_accounts`), `fk_cash_transaction_counter_gl` (→ `chart_of_accounts`), `fk_cash_transaction_reconciliation` (→ `bank_reconciliations`).
- `chk_cash_transaction_direction CHECK (direction IN ('IN','OUT'))`.
- `chk_cash_transaction_type CHECK (txn_type IN ('AR_RECEIPT','AP_PAYMENT','TRANSFER_IN','TRANSFER_OUT','DIRECT_ENTRY'))`.
- `chk_cash_transaction_amount CHECK (amount > 0)`.
- `chk_cash_transaction_counter_gl CHECK ((txn_type = 'DIRECT_ENTRY' AND counter_gl_account_id IS NOT NULL) OR (txn_type <> 'DIRECT_ENTRY'))` — a direct entry must carry its counter account (BR-CASH-05); other types resolve the counter leg from the source document.
- `chk_cash_transaction_cleared CHECK ((cleared = FALSE AND cleared_in_reconciliation_id IS NULL) OR (cleared = TRUE AND cleared_in_reconciliation_id IS NOT NULL))` — cleared iff linked to a reconciliation (D-6).

**Indexes:**
```
CREATE INDEX ix_cash_transactions_company           ON cash_transactions (company_id);
CREATE INDEX ix_cash_transactions_account_date      ON cash_transactions (cash_bank_account_id, txn_date, id);  -- running statement (date then id for stable order)
CREATE INDEX ix_cash_transactions_account_balance   ON cash_transactions (cash_bank_account_id);                -- Σ for the book balance
CREATE INDEX ix_cash_transactions_uncleared         ON cash_transactions (cash_bank_account_id) WHERE cleared = FALSE;  -- the reconciliation working set
CREATE INDEX ix_cash_transactions_source            ON cash_transactions (company_id, source_ref);              -- trace a receipt/payment/transfer to its cash row(s)
```

> **Why not a single signed `amount` (D-3, the pick, justified in Alternatives).** GL stores each line as a non-negative amount in *either* a debit *or* a credit column (`chk_journal_line_one_side`) precisely so a posting's side is explicit and a sign mistake cannot silently flip a debit into a credit. The cash book is simpler — a transaction is always exactly one side — so it does **not** need two money columns; a `direction` enum + a non-negative `amount` carries the same explicit intent with one money column. Every balance/statement read is `SUM(CASE WHEN direction='IN' THEN amount ELSE -amount END)` — the sign lives in one place (the read), derived from an explicit enum, never stored as a `±` that a writer could get wrong.

#### (c) `cash_transfers` (the inter-account transfer header — `CBT-####`)

A transfer is **one business act** owning two `cash_transactions` (an `OUT` on source, an `IN` on destination) and **one** balanced GL entry (D-4). The header is the numbered, auditable identity of the act.

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_cash_transfer_uid`; `ScopeGuard case "cashtransfer"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)`; tenant scope (transfer is same-company — BR-CASH-04) |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag |
| `transfer_number` | `VARCHAR(30)` | NO | `CBT-####` from `code_sequence (company_id,'CASH_TRANSFER')`; `uq_cash_transfer_company_number` |
| `source_account_id` | `BIGINT` | NO | FK → `cash_bank_accounts(id)` — money leaves here (CR its GL account) |
| `destination_account_id` | `BIGINT` | NO | FK → `cash_bank_accounts(id)` — money arrives here (DR its GL account) |
| `transfer_date` | `DATE` | NO | business value date; GL posting_date |
| `amount` | `NUMERIC(19,4)` | NO | the transferred amount; CHECK `> 0`; base currency |
| `currency` | `VARCHAR(3)` | NO | = base currency |
| `reference` | `VARCHAR(255)` | YES | operator note (audited) |
| `out_txn_id` | `BIGINT` | NO | FK → `cash_transactions(id)` — the source `OUT` row |
| `in_txn_id` | `BIGINT` | NO | FK → `cash_transactions(id)` — the destination `IN` row |
| `journal_entry_ref` | `VARCHAR(26)` | YES | the `journal_entries.uid` of the balanced transfer entry (scalar, no FK) |
| `version` / audit | … | mixed | append-only (BR-CASH-10) |

- `uq_cash_transfer_uid`, `uq_cash_transfer_company_number`; `fk_cash_transfer_company`/`_branch`/`_source`/`_destination`/`_out_txn`/`_in_txn`.
- `chk_cash_transfer_amount CHECK (amount > 0)`; `chk_cash_transfer_distinct CHECK (source_account_id <> destination_account_id)` — source ≠ destination (BR-CASH-04).
- Indexes: `ix_cash_transfers_company (company_id)`; `ix_cash_transfers_source (source_account_id)`; `ix_cash_transfers_destination (destination_account_id)`; `ix_cash_transfers_company_date (company_id, transfer_date)`.

#### (d) `cheques` (the cheque register — bank-account payments)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_cheque_uid`; `ScopeGuard case "cheque"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)` |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag |
| `cash_bank_account_id` | `BIGINT` | NO | FK → `cash_bank_accounts(id)` — **must be a BANK account** (FR-CASH-10; service-enforced, the register is for bank-account payments) |
| `cheque_number` | `VARCHAR(40)` | NO | the physical cheque number; **unique per bank account** (BR-CASH-12, partial/scoped unique below) |
| `payee` | `VARCHAR(160)` | NO | who the cheque is payable to |
| `amount` | `NUMERIC(19,4)` | NO | the cheque amount; CHECK `> 0`; base currency |
| `currency` | `VARCHAR(3)` | NO | = base currency |
| `issue_date` | `DATE` | NO | when written |
| `value_date` | `DATE` | NO | when it clears; **value_date ≥ issue_date** (a post-dated cheque has value_date > issue_date — CHECK below) |
| `status` | `VARCHAR(12)` | NO | `ChequeStatus`: `ISSUED`\|`CLEARED`\|`CANCELLED`; DEFAULT `'ISSUED'`; CHECK below |
| `ap_payment_uid` | `VARCHAR(26)` | YES | the **`ap_payments.uid`** the cheque settles (scalar, no FK — D-11); NULL for a cheque settling a direct entry |
| `cash_transaction_uid` | `VARCHAR(26)` | YES | the `cash_transactions.uid` of the money movement the cheque settles (scalar, no FK) |
| `cleared_at` / `cancelled_at` | `TIMESTAMPTZ` | YES | set on the terminal transition |
| `version` / audit | … | mixed | append-only; the lifecycle is status transitions, not deletes |

- `uq_cheque_uid UNIQUE (uid)`; `uq_cheque_bank_number UNIQUE (cash_bank_account_id, cheque_number)` — **cheque number unique per bank account** (BR-CASH-12).
- `fk_cheque_company`/`_branch`/`_account` (→ `cash_bank_accounts`).
- `chk_cheque_status CHECK (status IN ('ISSUED','CLEARED','CANCELLED'))`.
- `chk_cheque_amount CHECK (amount > 0)`; `chk_cheque_dates CHECK (value_date >= issue_date)`.
- Indexes: `ix_cheques_company (company_id)`; `ix_cheques_account (cash_bank_account_id)`; `ix_cheques_status (company_id, status)`; `ix_cheques_ap_payment (ap_payment_uid)`.

> **Cheque printing is DEFERRED (OQ-CASH-02).** The register captures everything a printer needs (number, payee, amount, dates, the bank account); **printing** (MICR layout, amount-in-words) depends on the cross-cutting PDF capability (ROADMAP X.1) and is an additive consumer of this table — out of v1.

#### (e) `bank_reconciliations` (the manual bank reconciliation)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | `BIGINT IDENTITY PK` | NO | |
| `uid` | `VARCHAR(26)` | NO | ULID; `uq_bank_reconciliation_uid`; `ScopeGuard case "bankreconciliation"` |
| `company_id` | `BIGINT` | NO | FK → `companies(id)` |
| `branch_id` | `BIGINT` | YES | FK → `branches(id)`; analysis tag |
| `cash_bank_account_id` | `BIGINT` | NO | FK → `cash_bank_accounts(id)` — the account being reconciled (BANK in practice; FR-CASH-13) |
| `reconciliation_number` | `VARCHAR(30)` | NO | `REC-####` from `code_sequence (company_id,'BANK_RECONCILIATION')`; `uq_bank_reconciliation_company_number` |
| `statement_date` | `DATE` | NO | the bank statement closing date (NFR-CASH-07) |
| `statement_closing_balance` | `NUMERIC(19,4)` | NO | the bank's closing balance, operator-entered (the figure book must agree with — BR-CASH-06) |
| `cleared_book_balance` | `NUMERIC(19,4)` | YES | the computed Σ of cleared transactions at completion (snapshot for audit; NULL while DRAFT) |
| `status` | `VARCHAR(10)` | NO | `ReconciliationStatus`: `DRAFT`\|`COMPLETED`; DEFAULT `'DRAFT'`; CHECK below |
| `reconciled_by` | `BIGINT` | YES | the operator who completed it (→ `app_users.id`, no FK) |
| `completed_at` | `TIMESTAMPTZ` | YES | when completed |
| `version` / audit | … | mixed | append-only once COMPLETED (BR-CASH-07) |

- `uq_bank_reconciliation_uid`, `uq_bank_reconciliation_company_number`; `fk_bank_reconciliation_company`/`_branch`/`_account`.
- `chk_bank_reconciliation_status CHECK (status IN ('DRAFT','COMPLETED'))`.
- Indexes: `ix_bank_reconciliations_company (company_id)`; `ix_bank_reconciliations_account (cash_bank_account_id)`; `ix_bank_reconciliations_account_date (cash_bank_account_id, statement_date)`.

> **The cleared-flag mechanics (the decision — D-6).** Marking is a **direct FK on `cash_transactions`** (`cleared` + `cleared_in_reconciliation_id`), **not** a separate `bank_reconciliation_lines` junction. Rationale: a transaction belongs to **at most one** completed reconciliation (it clears once), so the relationship is many-transactions-to-one-reconciliation — a FK, not a many-to-many. The cleared transactions of a reconciliation are `cash_transactions WHERE cleared_in_reconciliation_id = :recId`; no junction row adds information. (A `reconciliation_line` would be needed only if a transaction could partially clear across multiple statements — not a v1 shape; OQ-CASH-06 un-reconcile is deferred.)

### D-3 — The cash-book ledger grain: `direction` (IN/OUT) + non-negative `amount`; book balance from the read

Every movement is a `cash_transactions` row with an explicit `direction` and a positive `amount` (D-2b). The **book balance** of a cash/bank account is a read: `SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END)` over its transactions (D-7). This mirrors GL's two-column choice (the sign is explicit and structural, not a stored `±`) while staying simpler than two money columns because a cash transaction is always exactly one side. The five `txn_type` values map to a fixed direction in normal use — `AR_RECEIPT`/`TRANSFER_IN` are `IN`; `AP_PAYMENT`/`TRANSFER_OUT` are `OUT`; `DIRECT_ENTRY` is either (the operator picks) — but `direction` is stored explicitly so a reversing transaction (BR-CASH-10) can carry the opposite direction without inventing a "reversal" type.

**The enforcement split (DB vs service):**

| invariant | enforcement | mechanism |
| --- | --- | --- |
| `amount > 0`; `direction ∈ {IN,OUT}`; `txn_type` in the set | **DB CHECK** | `chk_cash_transaction_amount`/`_direction`/`_type` |
| one GL account per cash/bank account | **DB** | `uq_cash_bank_account_gl` |
| at most one default per company | **DB** | `uq_cash_bank_account_default` (partial unique) |
| cheque number unique per bank account | **DB** | `uq_cheque_bank_number` |
| source ≠ destination on a transfer | **DB** | `chk_cash_transfer_distinct` |
| value_date ≥ issue_date on a cheque | **DB** | `chk_cheque_dates` |
| cleared iff linked to a reconciliation | **DB** | `chk_cash_transaction_cleared` |
| direct entry carries a counter GL account | **DB** | `chk_cash_transaction_counter_gl` |
| BANK has bank details / CASH has none | **DB backstop + service** | `chk_cash_bank_account_bank_details` + `CashBankAccountService` |
| currency == company base currency | **service** | every amount currency == `companies.base_currency` (BR-CASH-11) |
| **book balance == linked GL account balance** (the chief invariant) | **structural (sync post in one TX)** | every movement posts to the linked GL account synchronously, same TX (D-7) — by construction (BR-CASH-02) |
| transfer is balanced (DR dest GL == CR source GL) | **structural** | the single `GLPostingService.post` validates balance; one amount, two legs |
| direct entry is balanced | **structural** | same — `GLPostingService.post` validates two equal-and-opposite legs |
| only an **active** account takes a new transaction | **service** | `CashBankAccountService`/transfer/entry/AR-AP-resolution reject inactive (FR-CASH-07, BR-CASH-08) |
| reconciliation completes only if book(cleared) == statement | **service** | `BankReconciliationService` (`BigDecimal.compareTo`) (BR-CASH-06) |
| cleared flag immutable once reconciliation COMPLETED | **service** | `BankReconciliationService` refuses to un-mark a transaction whose reconciliation is COMPLETED (BR-CASH-07) |
| account with transactions cannot be deleted (deactivate only) | **service** | `CashBankAccountService` (BR-CASH-13, the GL account-with-postings precedent) |
| append-only / correct via reversal | **structural** | no delete path on `cash_transactions`/`cash_transfers`/posted movements |
| company isolation | **DB + service** | `company_id` NOT NULL + FK + tenant predicate + `assertCanActIn` on **every** read path |

### D-4 — Transfers and direct entries: the mechanics + GL postings

**Transfer (`CashTransferService`, FR-CASH-08, BR-CASH-04).** One command, in one TX:
1. Resolve source + destination `cash_bank_accounts` (same company; both active; source ≠ destination).
2. Insert the `OUT` `cash_transaction` on the source (`txn_type=TRANSFER_OUT`, `direction=OUT`, `amount`) and the `IN` on the destination (`txn_type=TRANSFER_IN`, `direction=IN`, `amount`).
3. Post **one balanced GL entry** via `GLPostingService.post`: **DR destination's `gl_account_id` `amount` / CR source's `gl_account_id` `amount`** (`JournalSourceType.CASH_TRANSFER`, `sourceRef = cash_transfers.uid`).
4. Insert the `cash_transfers` header linking `out_txn_id`/`in_txn_id` and storing the returned `journal_entries.uid` on the header and on both transactions' `journal_entry_ref`.
5. Audit `CASH.TRANSFER.RECORD`. Both book balances move by `amount`; both still equal their linked GL balances (the two GL legs are the same amount — BR-CASH-02 holds).

**Direct entry (`CashDirectEntryService`, FR-CASH-09, BR-CASH-05).** One command, in one TX:
1. Resolve the `cash_bank_account` (active) and the `counter_gl_account_id` (an active income/expense/equity `chart_of_accounts` — resolved by id from the request's uid, validated active via `GLConfigResolver`-style active check).
2. Insert **one** `cash_transaction` (`txn_type=DIRECT_ENTRY`, `direction=IN|OUT`, `amount`, `counter_gl_account_id`).
3. Post **one balanced GL entry**: for `OUT` (e.g. a bank charge) **DR counter / CR cash-bank `gl_account_id`**; for `IN` (e.g. interest received) **DR cash-bank `gl_account_id` / CR counter** (`JournalSourceType.CASH_DIRECT`, `sourceRef = cash_transactions.uid`).
4. Store the returned `journal_entries.uid` on the transaction's `journal_entry_ref`. Audit `CASH.ENTRY.RECORD`.

Both commands use the shipped `GLPostingService.post(JournalEntryDraft)` (verified contract) and roll the **whole command** back on a GL failure (missing/inactive account, closed period — BR-GL-10 / FR-CASH-16), exactly as AR/AP do (ADR-0014 D-4). The cash/bank movement and the GL post are the same intent in the same TX; there is no in-flight reconciliation gap.

### D-5 — Cheque register mechanics (issue → clear / cancel)

`ChequeService` (CHEQUE.MANAGE, FR-CASH-10/11):
- **Register** a cheque against a BANK `cash_bank_account` (service-enforced BANK type): `cheque_number` (unique per bank account — `uq_cheque_bank_number`), `payee`, `amount`, `issue_date`, `value_date` (≥ issue_date; post-dated when >), `status=ISSUED`, optionally `ap_payment_uid` / `cash_transaction_uid` of the payment it settles. **The cheque posts NOTHING to GL** — the GL effect rode the AP payment / direct entry the cheque settles (FR-CASH-10). The register tracks the instrument's lifecycle only.
- **Transition** `ISSUED → CLEARED` (the bank honoured it, on/after value_date) or `ISSUED → CANCELLED` (stopped/spoiled). Both are terminal (service-enforced — no CLEARED→anything). Where a cancelled cheque's payment must be undone, the **payment is reversed** via a reversing GL entry on the AP/direct path (append-only — BR-CASH-10); the cheque register is not edited to undo money.
- Audit `CHEQUE.REGISTER` / `CHEQUE.CLEAR` / `CHEQUE.CANCEL`.

### D-6 — Bank reconciliation mechanics (mark cleared + the book==statement completion check)

`BankReconciliationService` (CASH.RECONCILE, FR-CASH-13/14/15, BR-CASH-06/07):
1. **Open** a `bank_reconciliations` row (DRAFT) for a `cash_bank_account`: `statement_date`, `statement_closing_balance`.
2. **Mark cleared** (`MarkClearedRequest`): set the chosen `cash_transactions.cleared = TRUE` and `cleared_in_reconciliation_id = :recId` (only transactions of the same account and company, only while the reconciliation is DRAFT). Un-mark (clear → uncleared) is allowed **only while DRAFT**.
3. **Complete** (`CompleteReconciliationRequest`): compute `cleared_book_balance = SUM(CASE WHEN direction='IN' THEN amount ELSE -amount END) WHERE cleared_in_reconciliation_id = :recId`; **only if `cleared_book_balance == statement_closing_balance`** (`BigDecimal.compareTo`, rounded per ADR-0005) set `status=COMPLETED`, `reconciled_by`, `completed_at` (BR-CASH-06). An out-of-balance reconciliation **cannot complete** and stays DRAFT.
4. **Immutability**: once COMPLETED, a transaction's `cleared` flag (and its `cleared_in_reconciliation_id`) is immutable — the service refuses to un-mark or re-mark it; a correction is a reversing transaction / a new reconciliation (BR-CASH-07/10, OQ-CASH-06 un-reconcile deferred).
5. Audit `CASH.RECONCILE.OPEN` / `CASH.RECONCILE.MARK` / `CASH.RECONCILE.COMPLETE`.

> **No statement file import in v1 (OQ-CASH-01).** Marking is manual; the closing balance is hand-entered. The model is built so a CSV/MT940 importer feeds onto the same mark-cleared mechanism later (NFR-CASH-08).

### D-7 — GL-posting mechanism: SYNCHRONOUS `GLPostingService.post(...)` in the same TX (NOT the outbox)

**Decision: a transfer, a direct entry, and the cash legs of AR receipts / AP payments post to GL by a synchronous `GLPostingService.post(JournalEntryDraft)` call inside the same service transaction as the cash/bank write.** Not an outbox event. Same justification as AR/AP (ADR-0014 D-4 / ADR-0015 D-4), restated for Cash & Bank:
1. **Atomicity = reconciliation by construction.** The cash movement (the `cash_transactions` row) and the GL post are one TX — the book balance and the linked GL account move together or not at all (BR-CASH-02 holds at every committed state, no eventual-consistency gap — NFR-CASH-04). An outbox post would open a window where the cash book and the GL disagree, breaking the chief acceptance bar.
2. **A transfer / direct entry / AR-AP settlement is a single in-request user command** on one aggregate (one operator, one click), not a cross-aggregate async reaction. The outbox buys no decoupling here.
3. **The engine is built for it; failure rolls back the command.** `GLPostingService.post` is `@Transactional` and AR/AP already call it as an allowed leaf→service dependency (D-11). A missing/inactive linked-GL or counter account, or a closed period, **fails the whole command** (the operator sees a clear error; nothing half-recorded). This is correct because the cash movement IS the authoritative act and the GL post is the same intent — there is no upstream business act (like a sale) that must survive a GL failure.

**No outbox consumer for Cash & Bank** (like AP, ADR-0015 D-5; unlike AR there is no async creation path). Every Cash & Bank posting is synchronous.

**The exact GL postings + NEW journal source types (D-7).** All via `GLPostingService.post`; the cash/bank account's GL account is the account's own `gl_account_id` (resolved from the entity, **not** via `GLConfigResolver` — the link is on the account row).

| Cash & Bank operation | GL journal (via `GLPostingService.post`) | source type | accounts used |
| --- | --- | --- | --- |
| **inter-account transfer** | DR destination's `gl_account_id` `amount` · CR source's `gl_account_id` `amount` | **`CASH_TRANSFER`** (NEW) | the two accounts' linked GL accounts |
| **direct entry — money out** (bank charge) | DR `counter_gl_account_id` `amount` · CR account's `gl_account_id` `amount` | **`CASH_DIRECT`** (NEW) | the cash/bank GL account + the chosen counter |
| **direct entry — money in** (interest) | DR account's `gl_account_id` `amount` · CR `counter_gl_account_id` `amount` | **`CASH_DIRECT`** (NEW) | the cash/bank GL account + the chosen counter |
| **AR receipt cash leg** (D-8) | DR **chosen account's `gl_account_id`** `amount` · CR `ACCOUNTS_RECEIVABLE` | `AR_RECEIPT` (unchanged; posted by AR) | the chosen cash/bank GL account replaces the bare `CASH` |
| **AP payment cash leg** (D-8) | DR `ACCOUNTS_PAYABLE` · CR **chosen account's `gl_account_id`** `amount` | `AP_PAYMENT` (unchanged; posted by AP) | the chosen cash/bank GL account replaces the bare `CASH` |

`JournalSourceType` introduces **`CASH_TRANSFER`** and **`CASH_DIRECT`** (the enum reserves `CASH`; the engineer adds the two granular values or uses the reserved `CASH` — recommend the two granular values for traceability). The `chk_journal_batch_source_type` / `chk_journal_entry_source_type` CHECKs are **widened additively** (the sanctioned `DROP/ADD CONSTRAINT` pattern, ADR-0013 D-13 / ADR-0014 D-14) to admit `CASH_TRANSFER`/`CASH_DIRECT` — V13, never a V10 edit. `source_ref` = the Cash & Bank document uid.

### D-8 — The additive AR/AP touch: reroute the cash leg to the chosen account's linked GL account + record a `cash_transactions` row

This is the load-bearing integration (cash-and-bank.md §3.6, the `products.vat_status` precedent). **It reroutes one existing line in each of two shipped services and adds two nullable columns — no AR/AP rewrite.**

**(1) Request DTOs gain an optional cash/bank account reference (additive record components).**
- `RecordReceiptRequest` gains `String cashBankAccountUid` (nullable) — appended as a record component (callers that omit it compile/serialise unchanged; absent → default account).
- `PaySingleBillRequest` and `PaymentRunRequest` each gain `String cashBankAccountUid` (nullable) — same.

**(2) AR/AP resolve the cash-leg GL account through Cash & Bank, replacing the bare `CASH` lookup.**
- The shipped `ArReceiptServiceImpl:167` `ChartOfAccount cashAcct = glConfig.resolve(companyId, GlConfigKey.CASH)` becomes `CashBankAccountResolver.resolveCashLegGlAccount(companyId, req.cashBankAccountUid())` → returns the chosen account's linked GL `ChartOfAccount` (or the company default's if the uid is null). The receipt's DR leg uses **that** account's id.
- The shipped `ApPaymentServiceImpl:249` `ChartOfAccount cashAcct = glConfig.resolve(companyId, GlConfigKey.CASH)` becomes the same `CashBankAccountResolver` call. The payment's CR leg uses that account's id.
- `CashBankAccountResolver` (Cash & Bank service) — `resolveCashLegGlAccount(Long companyId, String cashBankAccountUid)`: if uid given, load that account (same company, **active** — else fail with a clear message, FR-CASH-07); if null, load the company default (`is_default = TRUE`); if **no default and no uid**, **fail** with a clear message (BR-CASH-09, never post to null). Returns a `CashAccountGlResolutionDto { cashBankAccountId, glAccountId, glAccountCode }` (DTO, not an entity — the boundary).

**(3) AR/AP record a `cash_transactions` row so the cash book shows the settlement.** After the receipt/payment posts its GL entry, AR/AP call **`CashTransactionRecorder.recordSettlement(...)`** (Cash & Bank service) to append a `cash_transactions` row: `cash_bank_account_id` (the resolved account), `txn_type = AR_RECEIPT | AP_PAYMENT`, `direction = IN` (receipt) / `OUT` (payment), `amount`, `source_ref` = the receipt/payment uid, `journal_entry_ref` = the receipt's/payment's `journal_entries.uid` (the AR/AP post — Cash & Bank does **not** re-post; the GL leg already exists). This keeps the cash book's running statement (D-7) complete and the account's book balance moving with the receipt/payment. The recorder runs **in the AR/AP service TX** (same synchronous, atomic guarantee).

**(4) Record where it landed — two nullable columns on the frozen AR/AP tables (the decision).** Add **`cash_bank_account_id BIGINT NULL`** (FK → `cash_bank_accounts(id)`) to **`ar_receipts`** and **`ap_payments`** via additive `ALTER TABLE … ADD COLUMN` in V13. Rationale: the receipt/payment must record *which* money location it settled into (for the statement, for audit, for tracing); a nullable column is the minimal additive change (existing rows stay NULL — they predate Cash & Bank and resolved to the old `CASH`; the column is populated going forward). **This is the only frozen-table touch and it is additive** (a nullable column, an FK to a new table — no data migration, no NOT NULL backfill). The `cash_transactions.source_ref` already links the other direction; the column on `ar_receipts`/`ap_payments` is the convenient forward link AR/AP read for their own statement/DTO.

> **Why a column on `ar_receipts`/`ap_payments` rather than deriving it from `cash_transactions.source_ref`.** Deriving would require AR/AP to query Cash & Bank to learn where their own receipt/payment landed — a cross-module read on every receipt DTO. A nullable scalar column on the AR/AP row is cheaper, keeps the AR/AP DTO self-contained, and is a one-line additive ALTER. The FK direction (AR/AP → `cash_bank_accounts`) is consistent with AR/AP being the dependents (D-11).

### D-9 — The cash-account ⇄ linked-GL reconciliation invariant (the crux), made structural

The invariant (BR-CASH-02, NFR-CASH-01): **for every cash/bank account, book balance == linked GL account balance, at all times.** Guaranteed by structure, not a periodic job:
1. **One GL account per cash/bank account** (D-1, `uq_cash_bank_account_gl`) — each book balance maps to exactly one GL balance, so the equality is per-account and pinnable.
2. **Every movement on the account posts to *that* GL account synchronously in the same TX** (D-7) — the `cash_transactions` IN/OUT and the GL DR/CR are the same amount in the same TX. There is no committed state where the cash book and the GL disagree.
3. **AR/AP settlements post their cash leg to the chosen account's GL account AND record a `cash_transactions` row** (D-8) — the same amount hits the book balance and the linked GL account (the receipt/payment GL leg). The `cash_transactions` row is the cash-book detail of that GL movement; one settlement ⇒ one GL cash-leg movement + one `cash_transactions` row, the same amount.

`CashGlReconciliationQuery` surfaces the equality per account `{ cashBankAccountUid, bookBalance, linkedGlBalance, difference }` — `bookBalance` from `cash_transactions`, `linkedGlBalance` from GL's `TrialBalanceQuery` for the `gl_account_id` (an allowed Cash & Bank→GL read). A non-zero `difference` is a finance-grade defect (FR-CASH-17). An IT pins it: record a transfer + a direct entry + an AR receipt routed to an account, assert each account's book balance == its linked GL balance.

### D-10 — How `gl_configs` `CASH` maps to the default cash/bank account (OQ-CASH-07)

**Decision: the company default cash/bank account *is* the resolution of the old `CASH` role.** `gl_configs.CASH` stays seeded and unchanged (V10); it is no longer the AR/AP cash-leg account — `CashBankAccountResolver` (D-8) is. The migration/seed path (D-13): **at go-live, the company default cash/bank account's `gl_account_id` should be the same `1xxx` account `gl_configs.CASH` currently maps to** (recommend seeding a default cash/bank account per existing company whose `gl_account_id` = the company's `CASH`-mapped account — `1000 Cash`), so existing AR/AP callers that name no account keep posting to the same GL account they always did. `gl_configs.CASH` is retained as the seed source for that default account's GL link and as a fallback the engineer may keep if a company has not yet created any cash/bank account (defensive — but FR-CASH-07/BR-CASH-09 say no-default-and-no-uid should fail; recommend the seeded default makes this path unreachable in practice). **No `gl_configs` schema change; no new key.**

### D-11 — Module boundary: Cash & Bank is a leaf poster/reader; AR/AP depend on Cash & Bank (the direction)

`ModuleBoundaryTest` discipline (PROJECT-CONVENTIONS §2, NFR-CASH-06). The active ArchUnit rules today are controller→repository, service→controller, and audit-repo isolation (verified — there is **no** strict acyclic module-dependency rule yet, so the new edges below do not break a shipped test; they are documented as the intended allow-set for when the per-module rule lands):

- **Cash & Bank → `gl.service.GLPostingService` + `gl.domain.dto.JournalEntryDraft` + `gl.domain.enums.JournalSourceType`** — the synchronous posting edge (D-7), leaf→service, DTO/service-interface only, never a GL entity beyond the posting contract. The AR/AP precedent (ADR-0014 D-11) — reuse the allow-rule.
- **Cash & Bank → `gl.repository.ChartOfAccountRepository`** (or a GL service read) — `cash_bank_accounts.gl_account_id` / `cash_transactions.counter_gl_account_id` FK `chart_of_accounts(id)` and the service validates the linked/counter account is active. This is an **intra-DB FK to a frozen GL master** (the accepted AR/AP `customers`/`suppliers` pattern) + a read; document it. Cash & Bank also reads GL's account balance via `gl.service.TrialBalanceQuery` for D-9 (an allowed Cash & Bank→GL read).
- **AR → `cashbank.service.CashBankAccountResolver` + `cashbank.service.CashTransactionRecorder` + `cashbank.domain.dto.CashAccountGlResolutionDto`** — the additive touch (D-8): a NEW **AR→CashBank** edge, DTO/service-only, never a Cash & Bank entity.
- **AP → `cashbank.service.CashBankAccountResolver` + `cashbank.service.CashTransactionRecorder` + `cashbank.domain.dto.CashAccountGlResolutionDto`** — the same NEW **AP→CashBank** edge.
- **`ar_receipts.cash_bank_account_id` / `ap_payments.cash_bank_account_id` FK → `cash_bank_accounts(id)`** — an intra-DB FK from the AR/AP tables to the new Cash & Bank master (D-8). FK direction AR/AP → Cash & Bank, consistent with the dependent direction.
- **No cycle.** Cash & Bank **never imports an AR/AP entity, repository, service, or DTO**, and **never reacts to an AR/AP event** — it is a pure leaf that AR/AP *call*. The direction is uniformly **AR/AP → CashBank → GL** (and CashBank → GL for posting/reconciliation). GL does not depend on Cash & Bank; Purchases/Sales do not. There is no command-path cycle. **Document the ArchUnit stance:** add an allow-rule note that `ar/ap → cashbank.service`/`cashbank.domain.dto` are intentional DTO/service-only edges, and `cashbank → gl.service`/`gl.domain.dto`/`gl.repository.ChartOfAccountRepository` mirror the AR/AP→GL allow-rule.
- **No cross-module FK** into `ar_receipts`/`ap_payments`/`journal_entries` from Cash & Bank: `source_ref`/`journal_entry_ref`/`ap_payment_uid`/`cash_transaction_uid` are plain `VARCHAR(26)` scalars (the `stock_movements.source_document_uid` discipline). FKs to `chart_of_accounts` (and `ar_receipts`/`ap_payments` → `cash_bank_accounts`) are intra-DB FKs to masters.

### D-12 — ScopeGuard additions + numbering (`code_sequence` kinds)

`ScopeGuard.companyIdOf` gains the Cash & Bank target types (the verified switch + constructor-dep pattern — add five repository deps and five cases):
```java
case "cashbankaccount"     -> cashBankAccounts.findCompanyIdByUid(uid);
case "cashtransaction"     -> cashTransactions.findCompanyIdByUid(uid);
case "cashtransfer"        -> cashTransfers.findCompanyIdByUid(uid);
case "cheque"              -> cheques.findCompanyIdByUid(uid);
case "bankreconciliation"  -> bankReconciliations.findCompanyIdByUid(uid);
```
Each backed by a `findCompanyIdByUid` projection. `ScopeGuard` gains five Cash & Bank repository constructor deps (the accepted cross-cutting-spine pattern — same as the GL/AR/AP additions). `assertCanActIn` on **every read path** (NFR-CASH-01): account list, balance, statement, cheque register, reconciliation read, the GL-reconciliation read, and inside `CashBankAccountResolver` (so an AR/AP caller cannot route to another company's account).

**`code_sequence` kinds** (created on first use, no seeded row — the shipped pattern): `CASH_BANK_ACCOUNT` (`CB-####`), `CASH_TRANSFER` (`CBT-####`), `CASH_TXN` (`CBTX-####`), `CASH_DIRECT` (may reuse `CASH_TXN` — the direct entry's `cash_transactions` row is numbered by `CASH_TXN`; recommend one `CASH_TXN` kind for all transaction rows, the `kind`/`txn_type` column distinguishing them), `BANK_RECONCILIATION` (`REC-####`). Cheques carry the **physical** `cheque_number` (operator-entered, not a `code_sequence` series — BR-CASH-12 uniqueness is per bank account).

### D-13 — Permission catalogue + audit emit points + the seed additions

**Permissions (FR-CASH-19, seeded in V13, granted to `ORG_ADMIN` by the V7 CROSS-JOIN pattern):**

| code | module | description |
| --- | --- | --- |
| `CASH.VIEW` | cashbank | View cash/bank accounts, balances, statements, and the GL-reconciliation read |
| `CASH.ACCOUNT.MANAGE` | cashbank | Create/edit/deactivate a cash/bank account, set its GL link, set the company default |
| `CASH.TRANSFER` | cashbank | Record an inter-account transfer (CBT-####) |
| `CASH.ENTRY.RECORD` | cashbank | Record a direct cash/bank entry (bank charge, interest, sundry) |
| `CASH.RECONCILE` | cashbank | Perform a manual bank reconciliation (open/mark-cleared/complete) |
| `CHEQUE.MANAGE` | cashbank | Manage the cheque register (register/clear/cancel) |

The AR/AP cash-leg routing (D-8) is gated by the **existing** `AR.RECEIPT.RECORD` / `AP.PAYMENT.RUN` permissions (it is part of the receipt/payment command, not a separate Cash & Bank act) — no new permission for the routing itself.

**Audit emit points (NFR-CASH-03 — every mutation, IAM append-only audit):**

| action | when | target_type / target |
| --- | --- | --- |
| `CASH.ACCOUNT.CREATE` | account created (+ default flag set) | `cash_bank_accounts` / id |
| `CASH.ACCOUNT.UPDATE` | account edited / deactivated / default changed / GL link set | `cash_bank_accounts` / id |
| `CASH.TRANSFER.RECORD` | transfer recorded + posted | `cash_transfers` / id |
| `CASH.ENTRY.RECORD` | direct entry recorded + posted | `cash_transactions` / id |
| `CASH.SETTLEMENT.RECORD` | AR receipt / AP payment cash-transaction recorded (actor = the AR/AP operator) | `cash_transactions` / id |
| `CHEQUE.REGISTER` / `CHEQUE.CLEAR` / `CHEQUE.CANCEL` | cheque registered / cleared / cancelled | `cheques` / id |
| `CASH.RECONCILE.OPEN` / `CASH.RECONCILE.MARK` / `CASH.RECONCILE.COMPLETE` | reconciliation opened / marked / completed | `bank_reconciliations` / id |

**Seed additions (V13, additive — never editing prior seed rows):**
- **No new CoA account** required by the module itself — the linked `1xxx` accounts are operator setup (the seeded `1000 Cash` + bank accounts the operator adds via `GL.MANAGE`). Recommend the seeder also creates a `1010 Bank` (ASSET/DEBIT) per company if not present, so a default BANK account has a distinct linked account (optional — operator can add it).
- **No new `gl_configs` key** — `CASH` is retained and becomes the seed source for the default cash/bank account's GL link (D-10).
- **A seeded default cash/bank account per existing company** (D-10): one `cash_bank_accounts` row (`account_type=CASH` or `BANK`, `is_default=TRUE`, `gl_account_id` = the company's `gl_configs.CASH` account), via a Java `CashBankSeeder` (the `ArGlSeeder`/`ApGlSeeder` precedent) wired into `BootstrapRunner`/`CompanyService.create` for new companies, and seeded in V13 for existing companies (CROSS JOIN on `companies` × the `CASH`-mapped account, deterministic seed-uid, `ON CONFLICT DO NOTHING`). This makes the unspecified-account AR/AP path resolve to the same GL account it did before Cash & Bank — zero behaviour change for unchanged callers.
- **`journal_*` source-type CHECK widen** for `CASH_TRANSFER`/`CASH_DIRECT` (D-7) — additive `DROP/ADD CONSTRAINT`.

### D-14 — Migration: additive `V13__cash_and_bank.sql`, never a V1–V12 edit; ordering

IAM=V1 … AP=V12 — all frozen. Cash & Bank is **`V13__cash_and_bank.sql`**, purely additive. Ordering (FK dependencies):
1. **`cash_bank_accounts`** (FKs `companies`/`branches`/`chart_of_accounts`).
2. **`bank_reconciliations`** (FKs `companies`/`branches`/`cash_bank_accounts`) — **before** `cash_transactions`, because `cash_transactions.cleared_in_reconciliation_id` FKs it.
3. **`cash_transactions`** (FKs `companies`/`branches`/`cash_bank_accounts`/`chart_of_accounts`(counter)/`bank_reconciliations`).
4. **`cash_transfers`** (FKs `companies`/`branches`/`cash_bank_accounts`×2/`cash_transactions`×2 — after `cash_transactions`).
5. **`cheques`** (FKs `companies`/`branches`/`cash_bank_accounts`).
6. **Indexes** for all of the above (D-2).
7. **AR/AP additive ALTERs** (D-8): `ALTER TABLE ar_receipts ADD COLUMN cash_bank_account_id BIGINT NULL` + `fk_ar_receipt_cash_bank_account`; `ALTER TABLE ap_payments ADD COLUMN cash_bank_account_id BIGINT NULL` + `fk_ap_payment_cash_bank_account` — additive nullable columns + FKs to the new master, no data migration.
8. **`journal_*` source-type CHECK widen** (`CASH_TRANSFER`/`CASH_DIRECT`) — additive `DROP/ADD CONSTRAINT` (the sanctioned ADR-0013 D-13 pattern, union of all prior source values + the two new).
9. **Default cash/bank account seed** per existing company (D-10/D-13) — CROSS JOIN `companies` × the `gl_configs.CASH` account, deterministic seed-uid, `is_default=TRUE`, `ON CONFLICT DO NOTHING`.
10. **Permission seed** (`CASH.*` + `CHEQUE.MANAGE`, `ON CONFLICT (code) DO NOTHING`) + `ORG_ADMIN` `role_permission` CROSS-JOIN grant (the V7 pattern).

No `code_sequence` row seeded (kinds created on first use). No outbox table, no FK into `domain_events`/`ar_receipts`/`ap_payments`/`journal_entries` **from** Cash & Bank (cross-module scalars); the only frozen-table touch is the two nullable ALTERs in step 7 (AR/AP → Cash & Bank direction). No trigger. Table style follows shipped V10/V11/V12 exactly (`BIGINT GENERATED BY DEFAULT AS IDENTITY`, `uid VARCHAR(26)`, plural tables, singular constraint roots, plural `ix_`, `NUMERIC(19,4)` money). All FK targets exist in frozen V1/V10/V11/V12.

## Consequences

**Easier / safer:**
- **The books gain a real cash book + bank book that reconciles by construction** (D-7/D-9): each cash/bank account maps one-to-one to a GL `1xxx` account, every movement posts to that account synchronously in the same TX, so `book balance == linked GL balance` holds at every committed state. The single undifferentiated `CASH` number becomes per-location balances the treasurer can actually read.
- **The AR/AP touch is genuinely additive** (D-8): two nullable columns, one rerouted line in each of two shipped services (`glConfig.resolve(CASH)` → `CashBankAccountResolver`), and a `CashTransactionRecorder` call. The default-account seed (D-10) means unchanged callers post to the same GL account they always did — zero behaviour change for omitted accounts, the `products.vat_status` shape.
- **The sync-post mechanism (D-7) keeps every movement atomic** — no in-flight gap, a clear error if GL can't post, no half-recorded transfer/entry/settlement. The AR/AP precedent is reused with no GL rework.
- **The direction is clean (D-11):** AR/AP → CashBank → GL, no cycle, Cash & Bank a pure leaf. The cheque register and reconciliation are self-contained; printing and statement-import slot on later additively (NFR-CASH-08).

**Harder / to watch:**
- **The book-balance read is a SUM over `cash_transactions`** (D-3/D-7) — cheap at QA scale with `ix_cash_transactions_account_balance`, but a high-volume bank account accrues many rows; a Reporting snapshot is the T2.3 additive call if volume warrants (NFR-CASH-08). No stored balance in v1 (it would add an invalidation/drift problem — the exact risk this module exists to avoid).
- **The reconciliation completion check and the cleared-immutability rule are service-owned** (D-6) — no DB CHECK can sum the cleared subset; `BankReconciliationService` is the single home, and it must refuse to un-mark a transaction in a COMPLETED reconciliation (BR-CASH-07). An IT must pin "cannot complete out-of-balance" and "cannot un-clear a reconciled transaction."
- **The AR/AP→CashBank edge couples the receipt/payment path to Cash & Bank being available** (D-8) — a synchronous resolve + record at settlement. Acceptable (same-process modular monolith); documented. The resolver must fail loudly (not silently default to `CASH`) when no account and no default exist (BR-CASH-09).
- **The default-account seed must be correct at go-live** (D-10) — if a company's seeded default's `gl_account_id` differs from its old `gl_configs.CASH` account, unchanged AR/AP callers would silently change which GL account the cash leg hits. The seed CROSS-JOINs on the `CASH`-mapped account precisely to prevent this; an IT should assert a default-account AR receipt posts to the same GL account a pre-Cash-&-Bank receipt did.

**Migration / delivery cost:**
- 1 additive Flyway file (`V13__cash_and_bank.sql`): **5 new tables** (`cash_bank_accounts`, `cash_transactions`, `cash_transfers`, `cheques`, `bank_reconciliations`) + FKs/uniques/CHECKs + ~16 indexes; **2 additive nullable ALTERs** on `ar_receipts`/`ap_payments` + their FKs; **journal source-type CHECK widen** (`CASH_TRANSFER`/`CASH_DIRECT`); **default cash/bank account seed**/company; **permission seed** (6 perms + grant). No new CoA account required, no new `gl_configs` key, no outbox table, no `code_sequence` row, no trigger.
- Backend (Cash & Bank module): the `com.erp.modules.cashbank` set per D-1 — 5 entities + enums, 5 repositories (each with `findCompanyIdByUid`), the services (account/transfer/direct-entry/cheque/reconciliation + statement/balance/GL-reconciliation queries + `CashBankAccountResolver` + `CashTransactionRecorder` + number generator), ~6 controllers, the `CashBankSeeder`. **No events handler.**
- Backend (AR/AP touch — D-8): add `cashBankAccountUid` to `RecordReceiptRequest`/`PaySingleBillRequest`/`PaymentRunRequest`; reroute the one `glConfig.resolve(CASH)` line in `ArReceiptServiceImpl`/`ApPaymentServiceImpl` to `CashBankAccountResolver`; call `CashTransactionRecorder` after the post; set the new `cash_bank_account_id` on the receipt/payment. One AR→CashBank + one AP→CashBank service dependency.
- Backend (platform touch): `ScopeGuard` gains 5 Cash & Bank cases + 5 repo deps (D-12); JournalSourceType gains `CASH_TRANSFER`/`CASH_DIRECT`; ArchUnit allow-list gains the CashBank→GL edge + the AR/AP→CashBank edges (D-11).
- Web: cash/bank account list + create/edit (GL link, default flag, BANK details), per-account running statement + balance, transfer, direct entry, cheque register (register/clear/cancel), bank reconciliation (open/mark-cleared/complete with the book-vs-statement check), the GL-reconciliation read; AR receipt + AP payment screens gain a cash/bank account picker (default pre-selected) — `ApiResponse<T>`, Long-as-string, address by uid.
- Deployment risk: **low** — additive on frozen schema; reuses the proven synchronous-posting machinery; the two AR/AP ALTERs are nullable additive columns; the default-account seed makes the AR/AP behaviour change a no-op for omitted accounts.

## Alternatives considered

- **Single signed `amount` on `cash_transactions` instead of `direction` + non-negative `amount`.** Terser (one column, sign carries IN/OUT). **Rejected (D-3):** GL deliberately uses explicit debit/credit columns (`chk_journal_line_one_side`) so a side cannot be silently flipped by a sign bug; the cash book mirrors that intent with a `direction` enum + a positive `amount` (the sign lives in one place — the read's `CASE`), which is auditable and reversal-friendly (a reversing txn carries the opposite `direction`, not a negated amount). A stored `±` invites a writer error at every insert.
- **Paired `cash_transactions` sharing a `transfer_ref` string, no `cash_transfers` header.** Fewer tables. **Rejected (D-4):** a transfer is one business act with one number (`CBT-####`), one GL entry, and two legs; a header makes that act a first-class, auditable, numbered entity that owns its legs and GL ref, and lets the transfer list/screen address one row. Two loosely-coupled txns sharing a string scatter the act's identity and make "the transfer" un-addressable.
- **N:1 cash/bank account → GL account (two accounts share a linked GL account).** Flexible (e.g. all tills roll to one "Cash on hand" GL account). **Rejected as the default (D-1, OQ-CASH-08):** a shared GL account means two book balances reconcile to one GL balance — `CashGlReconciliationQuery` can no longer pin a single account's drift, defeating the per-account reconciliation read. One-to-one (the `uq_cash_bank_account_gl` partial-unique) keeps each account reconcilable to a distinct GL account; an operator who wants a rolled-up view uses a GL report, not a shared link.
- **A `bank_reconciliation_lines` junction (a row per cleared transaction in a reconciliation) instead of the FK on `cash_transactions`.** A classic header-lines shape. **Rejected (D-6):** a transaction clears in **at most one** reconciliation (many-txns-to-one-recon), so a FK + flag on `cash_transactions` carries the full relationship with no extra row; a junction would be needed only if a transaction could partially clear across statements (not a v1 shape; un-reconcile is deferred — OQ-CASH-06). The direct FK is the boring relational shape and keeps "the cleared transactions of a reconciliation" a one-predicate query.
- **Reroute the AR/AP cash leg by remapping `gl_configs.CASH` per receipt (a dynamic config) instead of a `CashBankAccountResolver`.** Keep the bare `resolve(CASH)` call but make `CASH` resolve dynamically. **Rejected (D-8):** `gl_configs` is a static per-company role→account map, not a per-transaction selector; overloading it to mean "the chosen account this time" breaks its contract and hides the account choice. A `CashBankAccountResolver(companyId, uid?)` is the explicit, testable seam, and the default-account fallback (D-10) preserves the unchanged-caller behaviour cleanly.
- **AR/AP derive their landed account from `cash_transactions.source_ref` (no column on `ar_receipts`/`ap_payments`).** No frozen-table touch. **Rejected (D-8):** AR/AP would query Cash & Bank to learn where their own receipt/payment landed — a cross-module read on every receipt DTO and a coupling the other way. A nullable scalar column on the AR/AP row (one additive ALTER) keeps the AR/AP DTO self-contained and the dependency direction clean.
- **Outbox event for transfer/direct-entry/settlement posting (instead of synchronous `GLPostingService.post`).** Cash & Bank would emit events GL consumes. **Rejected (D-7):** opens an in-flight window where the cash book is written but the GL is not yet posted — a reconciliation read during it is wrong (BR-CASH-02 holds at all times). A cash movement is a single synchronous user command, not a cross-aggregate reaction; the outbox decouples nothing here and costs atomicity. The AR/AP precedent (sync) is correct.

## Open / flagged items (do NOT block the build; recommended defaults stand — cash-and-bank.md §11)

1. **OQ-CASH-01 — Bank statement file import.** **Default:** manual marking + hand-entered closing balance in v1; a CSV importer feeding the same mark-cleared model is the first additive slice. *Blocks build:* **NO.**
2. **OQ-CASH-02 — Cheque printing.** **Default:** the register ships in v1; printing rides the cross-cutting PDF capability (X.1) as an additive consumer of `cheques`. *Blocks build:* **NO.**
3. **OQ-CASH-03 — Petty-cash / till branch-scoping.** **Default:** `cash_bank_accounts.branch_id` is nullable; a CASH till may carry its branch, a BANK account carries none (company-level). *Blocks build:* **NO.**
4. **OQ-CASH-04 — Multi-currency / FX cash accounts.** **Default:** base currency only (`currency = companies.base_currency`); foreign-currency bank accounts + FX revaluation are X.6. The model does not preclude them (NFR-CASH-08). *Blocks build:* **NO.**
5. **OQ-CASH-05 — Deposit slips / batched lodgements.** **Default:** each AR receipt routes to a chosen account directly; deposit batching is a later additive convenience on the same per-account statement. *Blocks build:* **NO.**
6. **OQ-CASH-06 — Reconciliation reversal / un-reconcile.** **Default:** cleared flags on COMPLETED reconciliations are immutable (BR-CASH-07); correct via a reversing transaction / a new reconciliation. An explicit un-reconcile is a later additive slice. *Blocks build:* **NO.**
7. **OQ-CASH-07 — `gl_configs.CASH` → default cash/bank account.** **Default (D-10):** the company default cash/bank account is the resolution of `CASH`; `gl_configs.CASH` is retained as the seed source for the default account's GL link. Unchanged AR/AP callers keep working. *Blocks build:* **NO** — it is the design decision this ADR makes.
8. **OQ-CASH-08 — One-to-one cash/bank account ⇄ GL account.** **Default (D-1):** one GL account per cash/bank account (`uq_cash_bank_account_gl`); a shared-GL configuration is not built. *Blocks build:* **NO.**
9. **OQ-CUR-03 (carried) — Rounding & TZS decimals.** **Default:** HALF_UP, TZS = 0 dp; transfer legs, direct-entry legs, the AR/AP cash legs, and the reconciliation book-vs-statement check round identically (`BigDecimal.compareTo`). *Blocks build:* **NO** for the model; confirm before go-live.

None of the above changes the five-table schema, the synchronous-posting rule, the cash-account⇄linked-GL reconciliation invariant, or the additive AR/AP touch; all are policy/tuning/additive choices the design is built to.

## Summary

This ADR is the technical design for **Cash & Bank Increment 3 (T1.4)** — the **cash book + bank book** in `com.erp.modules.cashbank`, defined in additive **`V13__cash_and_bank.sql`** (never editing frozen V1–V12). **Five tables:** `cash_bank_accounts` (the money location — `account_type CASH|BANK`, `gl_account_id` FK → `chart_of_accounts` **one-to-one** via `uq_cash_bank_account_gl`, `is_default` partial-unique per company, nullable `branch_id` for tills, `CB-####`); `cash_transactions` (the ledger — `direction IN|OUT` + non-negative `amount` [the cash-book analogue of GL's debit/credit columns — D-3], `txn_type AR_RECEIPT|AP_PAYMENT|TRANSFER_IN|TRANSFER_OUT|DIRECT_ENTRY`, `source_ref`/`journal_entry_ref` scalars, `cleared` + `cleared_in_reconciliation_id` FK, append-only); `cash_transfers` (the transfer header owning two `cash_transactions` + one balanced GL entry — `CBT-####`); `cheques` (the register — `cheque_number` unique per bank account `uq_cheque_bank_number`, `status ISSUED|CLEARED|CANCELLED`, `value_date ≥ issue_date`, posts nothing to GL — the GL effect rides the payment); `bank_reconciliations` (manual — `statement_closing_balance`, `status DRAFT|COMPLETED`, cleared marking via the FK on `cash_transactions`). **The reconciliation crux (D-9):** one GL account per cash/bank account + every movement posts to that account synchronously in the same TX ⇒ **book balance == linked GL balance at all times** (BR-CASH-02). **GL posting (D-7):** transfers + direct entries post via synchronous `GLPostingService.post` in the same TX (the AR/AP precedent — atomicity = reconciliation, no outbox, no consumer); NEW journal source types **`CASH_TRANSFER`**/**`CASH_DIRECT`** (additive CHECK widen). **The additive AR/AP touch (D-8):** add nullable `cashBankAccountUid` to `RecordReceiptRequest`/`PaySingleBillRequest`/`PaymentRunRequest`; reroute the shipped `glConfig.resolve(CASH)` line in `ArReceiptServiceImpl`/`ApPaymentServiceImpl` to `CashBankAccountResolver` (chosen account's linked GL account, default if unspecified — BR-CASH-09, fail if neither); call `CashTransactionRecorder` to append the `cash_transactions` settlement row; record `cash_bank_account_id` via a nullable additive ALTER on `ar_receipts`/`ap_payments` — the **only** frozen-table touch, additive. **`gl_configs.CASH` (D-10):** retained, becomes the seed source for the per-company **default cash/bank account**'s GL link, so unchanged callers post to the same GL account — no `gl_configs` change. **Scope/security:** `ScopeGuard` gains `cashbankaccount`/`cashtransaction`/`cashtransfer`/`cheque`/`bankreconciliation`; `assertCanActIn` on every read path (incl. the resolver); perms `CASH.VIEW`/`CASH.ACCOUNT.MANAGE`/`CASH.TRANSFER`/`CASH.ENTRY.RECORD`/`CASH.RECONCILE`/`CHEQUE.MANAGE`; audit on every mutation. **`code_sequence` kinds** `CASH_BANK_ACCOUNT`/`CASH_TRANSFER`/`CASH_TXN`/`BANK_RECONCILIATION`. **Module boundary (D-11):** CashBank → `gl.service.GLPostingService` + `chart_of_accounts` FK + `TrialBalanceQuery`; AR/AP → `cashbank.service.CashBankAccountResolver`/`CashTransactionRecorder` (NEW AR/AP→CashBank edges); `ar_receipts`/`ap_payments` → `cash_bank_accounts` FK; Cash & Bank imports no AR/AP type and reacts to no AR/AP event — **no cycle** (AR/AP → CashBank → GL). **Ready for build:** every flagged item has a recommended default the design is built to; the five tables, the transfer/direct-entry/cheque/reconciliation mechanics, the four synchronous postings, the reconciliation invariant, and the additive AR/AP touch (services + request DTOs + the two nullable columns + the settlement `cash_transactions` rows) are concrete enough to write without guessing a business rule. **Additive on frozen V1–V12:** confirmed — 5 new tables, 2 nullable AR/AP ALTERs (additive), no new CoA account, no new `gl_configs` key, additive journal-source-type CHECK widen (the sanctioned `chk_sales_invoice_doc_type` pattern), a per-company default-account seed, no V1–V12 edit.

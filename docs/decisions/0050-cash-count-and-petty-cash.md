# ADR-0050: End-of-day cash count + petty cash (cashier close-out)

- **Status:** Proposed (2026-07-04) — awaits owner sign-off on the `V83`/`V84` DDL (migration-approval standing rule) and on the flagged decisions below.
- **Deciders:** Owner + Solutions Architect
- **Deferred item:** D-7 (`docs/DEFERRED-ITEMS.md`). **Effort:** M–L. **Migrations:** `V83`, `V84` (provisional — re-verify next-free vs `origin/develop` at build; current max applied = `V82`).
- **Related:** ADR-0016 (cash & bank module — accounts, cash-book, direct entry, GL seam), ADR-0013 (GL posting engine + `gl_configs` roles), ADR-0029 (sales-depth — POS session close/reconcile + `POS_CASH_OVER`/`POS_CASH_SHORT` variance posting, the copy-ready template), ADR-0004 (audit aspect / append-only), ADR-0043 (schema freeze / durable DB — additive-only), ADR-0046 (provisioning over data-migrations).

## Context

The cashier (persona **John**) can post AR receipts and direct cash/bank entries, but has **two gaps**:

1. **No end-of-day cash count.** No way to record the counted drawer (by denomination), compare it to the expected book balance, and record/settle the over/short variance.
2. **No petty cash.** No imprest float, no disbursement/replenishment workflow, no fund balance.

Draft DDL exists at `docs/proposed-migrations/V83__cash_counts.sql` and `V84__petty_cash.sql`. This ADR confirms/adjusts them and resolves the design questions.

### Facts from the code that drive the decisions (verified 2026-07-04)

- **The cash↔GL seam is a synchronous, in-TX service call — not the outbox.** `CashDirectEntryServiceImpl.recordDirectEntry` (cashbank) calls `glPosting.post(draft)` (`GLPostingService`, gl module) **directly, in the same transaction**, and writes **both** a `cash_transactions` row **and** the balanced GL entry, linking them via `cash_transactions.journal_entry_ref = journalEntry.uid`. It is fail-fast: a GL error rolls the whole command back. (POS reconcile instead uses the tolerant `GLPostingSafeInvoker.postInNewTx`, REQUIRES_NEW + swallow-on-failure — appropriate for an async-ish operator step, not for a strict cash command.)
- **`ModuleBoundaryTest` does not (yet) forbid cross-module domain/service imports.** It enforces only controller↛repository, controller placement, service↛controller, and audit append-only. cashbank **already** imports `GLPostingService`, `GLConfigResolver`, `ChartOfAccountRepository`, `CompanyRepository`. So cash-count/petty-cash (living **in the cashbank module**) may reuse the same seam with **no** new boundary violation.
- **The over/short accounts and the variance source-type already exist.** `ChartOfAccountServiceImpl` seeds `4900 Cash Over (Till Surplus)` (INCOME) and `5170 Cash Short / Till Shortage` (EXPENSE) per company; `GlConfigServiceImpl` maps `GlConfigKey.POS_CASH_OVER→4900` / `POS_CASH_SHORT→5170`; `JournalSourceType.POS_VARIANCE` is DB-admitted (V43). `PosSessionServiceImpl.postVarianceGl` is a copy-ready posting template (over: DR Cash / CR over-income; short: DR short-expense / CR Cash).
- **`cash_transactions.txn_type` has a CHECK** (`AR_RECEIPT, AP_PAYMENT, TRANSFER_IN, TRANSFER_OUT, DIRECT_ENTRY`) and `chk_cash_transaction_counter_gl` requires `counter_gl_account_id NOT NULL` when `txn_type='DIRECT_ENTRY'`. → A cash-count variance can be booked as a **`DIRECT_ENTRY`** whose counter is the over/short account, with **no** CHECK-widening migration.
- **Expected cash has no as-of-date query yet.** `CashTransactionRepository.bookBalance(accountId)` = `SUM(IN)-SUM(OUT)` over **all** rows (not date-bounded). A date-bounded `bookBalanceAsOf(accountId, date)` is required.
- **The account model is `CASH | BANK`** (`CashBankAccountType`). A till is a `CASH` `cash_bank_account` linked 1:1 to a GL 1xxx account. The default CASH account is already seeded per company by `CashBankSeeder`.
- **Provisioning idiom:** per-company defaults run through `CompanyProvisioningServiceImpl` calling idempotent `*Seeder.seedDefaults(companyId)` — the sanctioned home for a default petty-cash fund (not a Flyway backfill).
- **Permissions:** `R__seed_permissions.sql` inserts `(code, module, description)` and CROSS-JOIN-grants the whole catalogue to `ORG_ADMIN`, so new codes auto-grant. uid-scoped GETs resolve tenancy via `ScopeGuard`'s `targetType` switch (`ScopeGuard.java` ~line 519).

## Decision

### D-7.1 — GL posting: cash-count variance AUTO-POSTS; petty cash is RECORD-ONLY this slice

The two sub-features have **very different blast radii**, so they get different stances:

- **Cash-count variance → AUTO-POST (recommended).** The machinery already exists end-to-end: the over/short accounts (`POS_CASH_OVER 4900` / `POS_CASH_SHORT 5170`), the `POS_VARIANCE` source type, and the exact posting shape (`PosSessionServiceImpl.postVarianceGl`). Reconcile posts the variance through the **same in-TX `glPosting.post` seam** the module already uses for direct entries, **and** writes a linked adjusting `cash_transactions` row (`txn_type=DIRECT_ENTRY`, counter = the over/short account) so the **cash-book and GL move together to the counted amount** and `CashGlReconciliationQuery` stays flat. Not posting a real over/short is itself a control gap, and the cost here is near-zero. **Seam:** synchronous `GLPostingService.post(draft)` in the reconcile TX (fail-fast, like direct entry — a human cash command must not silently drop its GL leg). **Accounts:** debit/credit against the **till's own linked GL account** (`cashBankAccount.glAccountId`, more precise than POS's generic `gl_configs.CASH`) and `POS_CASH_OVER`/`POS_CASH_SHORT` (already configured — no new keys). **Source type:** `POS_VARIANCE` (reused; no enum/CHECK change) — the audit trail and journal description name the cash-count uid, so reporting can still distinguish it.

- **Petty-cash disbursement/replenishment → RECORD-ONLY this slice (recommended).** Auto-posting petty cash needs materially more: a **new petty-cash asset GL account** (1xxx), a **new `GlConfigKey.PETTY_CASH`** + `GlConfig` seed, a **funding-account seam** (replenishment moves cash from a bank/cash account → the fund), and per-disbursement expense-account posting. That is a genuine cross-module change (gl seeders + a new config role) and out of proportion to a first cashier slice. The classic **imprest voucher model legitimately defers expense recognition to replenishment**, so record-only is accounting-defensible: we **capture** the intended expense `gl_account_id` on every disbursement row (data is ready), maintain the **fund balance** on the fund row, and let a **manual GL journal** (already supported) — or the fast-follow ADR — post the ledger. The `journal_entry_ref` column is reserved now so the fast-follow is additive-only.

> This asymmetry is deliberate and defensible on cost. If the owner prefers symmetry, the fallback is **record-only for both** (smallest slice; cash-count variance then also waits for the same manual journal). Auto-posting **both** now is explicitly rejected for this slice (drags in the petty-cash GL account + config + funding seam).

### D-7.2 — Expected cash is DERIVED, never typed

The cashier enters the **denomination breakdown**; `counted_amount = Σ(denomination × quantity)`. The system **derives** `expected_amount = bookBalanceAsOf(till, business_date)` via a new repository query
`SUM(CASE WHEN direction='IN' THEN amount ELSE -amount END) WHERE cash_bank_account_id = :id AND txn_date <= :date`,
and `variance_amount = counted_amount − expected_amount` (over > 0, short < 0). Deriving it is the least-error-prone choice: the till's cash-book already **is** opening + AR-receipt cash + direct entries + transfers − payments, which is exactly the drawer's expected content (the same identity POS uses: opening + cash-sales − payouts). Typing "expected" invites reconciling the count against a number the cashier just guessed. The derived figure is shown read-only on the count screen before the cashier confirms.

> **Boundary (documented, not enforced by a unique index):** a cash count targets a `CASH` `cash_bank_account` (the cash-book till). A **POS till session** already has its **own** close/reconcile variance path (ADR-0029); do **not** run both on the same physical till/day or the variance is booked twice. John's tills receive `AR_RECEIPT`/`DIRECT_ENTRY` cash-book rows, **not** POS sessions, so there is no overlap in the target flow.

### D-7.3 — Two PRs off this one ADR

Build as **two PRs**, cash-count first:

1. **PR-A — Cash count (`V83`)**: migration + entities + service + controller + FE + tests. Higher value, lower risk, auto-post is cheap.
2. **PR-B — Petty cash (`V84`)**: migration + entities + service + controller + provisioning seeder + FE + tests. Record-only.

Rationale: distinct tables, services, controllers, screens, and permission families; "one logical change per PR" (CLAUDE.md). They share only the module and the cashier persona. The tables are independent, so version order is by merge order — assign **`V83` to whichever merges first** (recommend cash-count). Re-verify next-free vs `origin/develop` at each build.

### D-7.4 — Account model

- **Cash count is against a `cash_bank_account` of type `CASH`** (the till). `cash_counts.cash_account_id` becomes a **real FK** to `cash_bank_accounts` (same module, same DB — no reason for a soft ref) and **NOT NULL** (expected can't be derived without a till). The service rejects a `BANK`-type target (a bank account is not physically counted).
- **A petty-cash fund is its OWN aggregate (`petty_cash_funds`), not a `cash_bank_account`.** It carries imprest-specific fields (`float_amount`, `custodian_id`, `balance_amount`) that don't fit `cash_bank_account`, has **no** 1:1 GL link, and must stay **out** of bank reconciliation and cash-book statements. Its balance is tracked on the fund row; movements are `petty_cash_transactions`. (In the fast-follow that adds GL, the fund maps to the new `PETTY_CASH` asset account via config, still without becoming a `cash_bank_account`.)

### D-7.5 — Migrations (additive, plain transactional — no `CONCURRENTLY`)

Per the D-1/D-6 lesson, this repo has **no non-transactional migration mode**; all index builds are plain. Both migrations are single-transaction, additive, on populated tables (new tables → constraints validate instantly).

**`V83__cash_counts.sql`**
```sql
CREATE TABLE cash_counts (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    uid                VARCHAR(26)   NOT NULL,
    company_id         BIGINT        NOT NULL,
    branch_id          BIGINT        NOT NULL,
    cash_account_id    BIGINT        NOT NULL,                 -- the till (cash_bank_accounts, type CASH)
    count_number       VARCHAR(30)   NOT NULL,                 -- CC-#### per company
    counted_by         BIGINT        NOT NULL,                 -- app_users.id
    business_date      DATE          NOT NULL,
    expected_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,       -- derived: till book balance as-of business_date
    counted_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,       -- Σ denomination lines
    variance_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,       -- counted - expected (over>0 / short<0)
    currency           VARCHAR(3)    NOT NULL DEFAULT 'TZS',
    status             VARCHAR(20)   NOT NULL DEFAULT 'OPEN',  -- OPEN|COUNTED|RECONCILED
    journal_entry_ref  VARCHAR(26),                            -- GL variance entry uid (set on RECONCILE)
    notes              VARCHAR(500),
    counted_at         TIMESTAMPTZ,
    reconciled_at      TIMESTAMPTZ,
    reconciled_by      BIGINT,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ,
    updated_by         BIGINT,

    CONSTRAINT uq_cash_count_uid        UNIQUE (uid),
    CONSTRAINT uq_cash_count_number     UNIQUE (company_id, count_number),
    CONSTRAINT fk_cash_count_company    FOREIGN KEY (company_id)      REFERENCES companies (id),
    CONSTRAINT fk_cash_count_branch     FOREIGN KEY (branch_id)       REFERENCES branches (id),
    CONSTRAINT fk_cash_count_account    FOREIGN KEY (cash_account_id) REFERENCES cash_bank_accounts (id),
    CONSTRAINT fk_cash_count_counted_by FOREIGN KEY (counted_by)      REFERENCES app_users (id),
    CONSTRAINT chk_cash_count_status    CHECK (status IN ('OPEN','COUNTED','RECONCILED'))
);
CREATE INDEX ix_cash_count_company_date ON cash_counts (company_id, branch_id, business_date);
CREATE INDEX ix_cash_count_account_date ON cash_counts (cash_account_id, business_date);

CREATE TABLE cash_count_denominations (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cash_count_id  BIGINT        NOT NULL,
    denomination   NUMERIC(19,4) NOT NULL,
    quantity       INTEGER       NOT NULL DEFAULT 0,
    line_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,           -- denomination * quantity
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     BIGINT,

    CONSTRAINT fk_cash_count_denom_count FOREIGN KEY (cash_count_id) REFERENCES cash_counts (id),
    CONSTRAINT uq_cash_count_denom       UNIQUE (cash_count_id, denomination),
    CONSTRAINT chk_cash_count_denom_qty  CHECK (quantity >= 0),
    CONSTRAINT chk_cash_count_denom_face CHECK (denomination > 0)
);
```
Changes vs the proposal: `cash_account_id` → NOT NULL + real FK; added `count_number` (+ per-company unique), `journal_entry_ref`, `reconciled_at`/`reconciled_by`, two reporting indexes, and a positive-face-value check. The denomination child is scoped through its parent (no `company_id` — it is a pure detail table).

**`V84__petty_cash.sql`**
```sql
CREATE TABLE petty_cash_funds (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    uid             VARCHAR(26)   NOT NULL,
    company_id      BIGINT        NOT NULL,
    branch_id       BIGINT        NOT NULL,
    code            VARCHAR(30)   NOT NULL,                    -- PCF-#### per company
    name            VARCHAR(120)  NOT NULL,
    custodian_id    BIGINT,                                    -- app_users.id (soft-FK)
    float_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,          -- authorised imprest ceiling
    balance_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,          -- current cash on hand
    currency        VARCHAR(3)    NOT NULL DEFAULT 'TZS',
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE|INACTIVE|ARCHIVED
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,

    CONSTRAINT uq_petty_cash_fund_uid     UNIQUE (uid),
    CONSTRAINT uq_petty_cash_fund_code    UNIQUE (company_id, code),
    CONSTRAINT uq_petty_cash_fund_name    UNIQUE (company_id, branch_id, name),
    CONSTRAINT fk_petty_cash_fund_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_petty_cash_fund_branch  FOREIGN KEY (branch_id)  REFERENCES branches (id),
    CONSTRAINT chk_petty_cash_fund_status CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED')),
    CONSTRAINT chk_petty_cash_fund_float  CHECK (float_amount >= 0)
);

CREATE TABLE petty_cash_transactions (
    id                 BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    uid                VARCHAR(26)   NOT NULL,
    petty_cash_fund_id BIGINT        NOT NULL,
    company_id         BIGINT        NOT NULL,
    branch_id          BIGINT        NOT NULL,
    txn_number         VARCHAR(30)   NOT NULL,                 -- PC-#### per company
    txn_type           VARCHAR(20)   NOT NULL,                 -- DISBURSEMENT|REPLENISHMENT|ADJUSTMENT
    txn_date           DATE          NOT NULL,
    amount             NUMERIC(19,4) NOT NULL,                 -- always positive
    balance_after      NUMERIC(19,4) NOT NULL DEFAULT 0,       -- fund balance after this txn
    gl_account_id      BIGINT,                                 -- expense acct for a disbursement (captured; soft-FK)
    journal_entry_ref  VARCHAR(26),                            -- reserved; NULL in the record-only slice
    reference          VARCHAR(120),
    description        VARCHAR(500),
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ,
    updated_by         BIGINT,

    CONSTRAINT uq_petty_cash_txn_uid     UNIQUE (uid),
    CONSTRAINT uq_petty_cash_txn_number  UNIQUE (company_id, txn_number),
    CONSTRAINT fk_petty_cash_txn_fund    FOREIGN KEY (petty_cash_fund_id) REFERENCES petty_cash_funds (id),
    CONSTRAINT fk_petty_cash_txn_company FOREIGN KEY (company_id)         REFERENCES companies (id),
    CONSTRAINT fk_petty_cash_txn_branch  FOREIGN KEY (branch_id)          REFERENCES branches (id),
    CONSTRAINT chk_petty_cash_txn_type   CHECK (txn_type IN ('DISBURSEMENT','REPLENISHMENT','ADJUSTMENT')),
    CONSTRAINT chk_petty_cash_txn_amount CHECK (amount > 0)
);
CREATE INDEX ix_petty_cash_txn_fund_date ON petty_cash_transactions (petty_cash_fund_id, txn_date);
```
Changes vs the proposal: fund gains `code` (+ per-company unique) and a non-negative-float check; the transaction gains `branch_id` (tenancy parity), `txn_number` (+ per-company unique), `balance_after`, and a reserved `journal_entry_ref`; plus a fund/date index.

### D-7.6 — Permissions (module `cashbank`)

Add to `R__seed_permissions.sql` (auto-granted to `ORG_ADMIN` by the existing CROSS JOIN):
- `CASH.COUNT.MANAGE` — open/record/reconcile an end-of-day cash count (stays in the `CASH.*` family).
- `CASH.COUNT.VIEW` — view cash counts and denominations.
- `PETTY_CASH.MANAGE` — create/edit funds; record disbursement/replenishment/adjustment.
- `PETTY_CASH.VIEW` — view funds and their transactions.

Register `ScopeGuard` target types for the uid-scoped GETs: `cashcount`, `pettycashfund`, `pettycashtransaction` (each `findCompanyIdByUid`). Angular route-guard `requirePermission()` codes must equal these exactly (route-guard ↔ endpoint parity).

### D-7.7 — Explicitly OUT of scope (follow-ups)

- **Petty-cash GL auto-posting** (new `PETTY_CASH` asset account + `GlConfigKey` + funding-account seam) — fast-follow ADR; `journal_entry_ref` reserved for it.
- **Cash-count approval workflow** (supervisor sign-off before RECONCILE posts) — the `COUNTED` state is the seam for it later; not built now.
- **Multi-currency petty cash / multi-currency counts** — single currency per fund/till (base currency); FX out.
- **Denomination master configuration** (per-currency configurable note/coin sets) — the FE ships a hardcoded TZS denomination ladder; a configurable master is a follow-up.
- **Petty-cash over-float / negative-balance hard gates, custodian handover, per-voucher receipts/attachments** — soft-validated or deferred.
- **POS-till ↔ cash-count unification** — kept as two separate reconcile paths (documented boundary in D-7.2).

## Consequences

- **Positive:** John gets a denomination-based end-of-day count with a *derived* expected figure and an auto-posted, cash-book-consistent over/short — reusing existing accounts and the existing in-TX GL seam (zero new GL config surface). Petty cash gets a working float/disbursement/replenishment ledger with balances, ready for GL in an additive fast-follow. No new module, no boundary-rule change, no CHECK-widening migration.
- **Control:** cash-count reconcile writes both a GL entry and a linked `DIRECT_ENTRY` cash-book row, so `CashGlReconciliationQuery` stays flat and the next count's expected already reflects the adjustment.
- **Accounting caveat (flagged):** petty-cash disbursements do **not** hit the P&L until a manual journal (or the fast-follow) posts them — the imprest voucher model. Accountants must know the interim.
- **Watch:** the documented POS-till vs cash-count double-count boundary (D-7.2) is a convention, not a DB constraint; a shop that both runs POS sessions and manually counts the *same* till would double-book variance. Reporting distinguishes cash-count variance from POS variance by the source uid/description even though both use `POS_VARIANCE`.
- **Contract additions:** new `cashbank` DTOs/endpoints (see the plan); web `cashbank.service.ts` + models; four new permission codes; three new `ScopeGuard` target types; one new provisioning seeder (`PettyCashFundSeeder`).

## Decisions requiring the owner

1. **GL stance (D-7.1).** Recommended: **cash-count variance auto-posts** (reuse `POS_CASH_OVER 4900`/`POS_CASH_SHORT 5170` + the till's linked GL account, via in-TX `glPosting.post` + a linked `DIRECT_ENTRY` cash-book row); **petty cash record-only** (GL via manual journal / fast-follow). Alternatives: symmetric record-only (smallest), or auto-post both (drags in a new petty-cash GL account + config + funding seam).
2. **Expected cash (D-7.2).** Recommended: **derived** = till book balance as-of `business_date` (new `bookBalanceAsOf` query), read-only on screen; cashier enters denominations only.
3. **Scope split (D-7.3).** Recommended: **two PRs** off this ADR, cash-count (`V83`) first, petty cash (`V84`) second.
4. **Account model (D-7.4).** Recommended: cash-count target = a `CASH`-type `cash_bank_account` (hard FK, NOT NULL); petty-cash fund = its **own** aggregate, not a `cash_bank_account`.
5. **Approve the `V83`/`V84` DDL + versions** (D-7.5) before the migration files are created (migration-approval standing rule). Confirm next-free is `V83`/`V84` at build time.
6. **Permission codes (D-7.6).** Recommended: `CASH.COUNT.MANAGE`/`.VIEW`, `PETTY_CASH.MANAGE`/`.VIEW` (module `cashbank`).
7. **Default petty-cash fund provisioning (D-7.4/scope).** Recommended: a `PettyCashFundSeeder` creates **one** ACTIVE "Petty Cash" fund (float 0, no custodian) per company via `CompanyProvisioningService` (idempotent) so the feature is usable out of the box. Alternative: no seed (funds created manually).

## Alternatives considered

- **Route the variance through the outbox (`domain_event`) instead of an in-TX post** — rejected: the cashbank module's established pattern for *human cash commands* is the synchronous, fail-fast `glPosting.post` (direct entry), so the cash-book row and its GL entry commit atomically. The outbox is for cross-module *side effects*; a cash count's own GL leg is not a side effect, it is part of the command.
- **Model petty cash as a `cash_bank_account` (type `PETTY`)** — rejected: it would inherit the 1:1 GL link, the cash-book, bank reconciliation, and statements — none of which fit an imprest float, and it would need a new account-type enum + CHECK widen. A dedicated aggregate is cleaner and keeps petty cash out of the bank-rec surface.
- **Let the cashier type the expected amount** — rejected (D-7.2): reconciling a count against a hand-entered "expected" defeats the control; the derived book balance is authoritative and free.
- **A new `CASH_COUNT_VARIANCE` source type + `CASH_OVER`/`CASH_SHORT` config keys** — rejected for this slice: it needs a `JournalSourceType` CHECK widen (migration) and new `gl_configs` seeds for zero functional gain over reusing `POS_VARIANCE` + `POS_CASH_OVER/SHORT`. If reporting later needs a clean split, add the source type additively then.
- **One combined PR for both sub-features** — rejected (D-7.3): larger diff, two independent tables/screens, harder review; two PRs ship and revert independently.

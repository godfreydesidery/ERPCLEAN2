# Requirements — General Ledger / Financial Accounting (the books)

> Status: **RATIFIED (owner-confirmed 2026-06-08).** The owner answered all six scoping forks — chart of
> accounts (numeric ranges, system-seeded TZ small-business CoA, editable, account types drive
> statement placement & normal balance); sales auto-posting (finalise auto-posts a balanced journal via
> the outbox using a configurable `gl_configs` account map; void reverses); fiscal calendar (12 monthly
> periods, configurable fiscal-year start month, open/close, closed-period posting rejected); corrections
> (append-only immutable ledger — reverse-then-re-post, never edit/delete); manual journals (INCLUDED in
> v1, must balance before posting, incl. opening balances); and multi-currency (base-currency-only in v1,
> FX revaluation deferred). Each is reflected below as a fixed v1 requirement; everything not chosen has
> moved to the **Deferred** list (§2). **No ADR-0013-blocking open question remains.**
>
> Author: system-analyst · Domain: `gl` (financial / posting). Business-level spec only.
> **No schema, no API shapes, no tables/columns, no code** — those are the solutions-architect's, in
> **ADR-0013** (next step). Do not infer a data model from this document.
>
> **This is GL Increment 1 of the full-ERP roadmap (docs/ROADMAP.md T1.1 / §5 Increment 1).** GL is the
> **critical-path gate**: nothing in Tier 1 (AR, AP, Cash/Bank, VAT return) or Reporting (TB/P&L/BS)
> reports until the books exist. This increment turns the system from "tracks stock" into "keeps books."
>
> **Depends on:** IAM (org → company → branch, permissions, `RequestContext`, audit, `ScopeGuard`),
> Sales (the `SALE.FINALISED` / `SALE.VOIDED` events + invoice totals — net/vat/gross + `tax_summary`,
> ADR-0008), the transactional **outbox** (ADR-0009 — `DomainEventHandler`, `IdempotencyGuard`,
> `processed_events`; GL is a pure **consumer**, DTO-only), **Money** (ADR-0005 — amount + currency,
> company base currency config), and `code_sequence` (ADR-0007 — journal batch numbering). All shipped.

## 1. Business context & why now

The system already trades: IAM scopes it, Parties says who we deal with, Products says what we sell,
Sales takes the money, Stock and Purchases move the goods — all event-wired through the outbox. **But
nothing posts to books.** `domain_events` carries `SALE.FINALISED` and `STOCK.RECEIVED`, and the only
registered consumers are the four Stock handlers; on the finance side the events fire into the void
(ROADMAP §1 verdict). There is no General Ledger, no trial balance, no profit-and-loss, no balance
sheet, no VAT return. **It is not yet an ERP, because it does not keep books.**

The **General Ledger is the books** — the single, append-only, double-entry record of every financial
effect, organised by **account**, from which a **trial balance**, a **profit & loss**, and a **balance
sheet** are read. It is the **spine** the rest of the roadmap is built on:

- **Sales** finalising must post **revenue** and **VAT payable** to the books (this increment).
- **AR / AP** (T1.2/T1.3) post customer/supplier control-account balances to the books (later).
- **Cash & Bank** (T1.4) posts the cash side of every receipt/payment to the books (later).
- **COGS / inventory valuation** (T2.2) posts cost-of-goods-sold and inventory movement to the books
  (later).
- **Reporting** (T2.3) reads the books: a trial balance is `GROUP BY account, SUM(debit − credit)` over
  the ledger; P&L and balance sheet are the same ledger sliced by account type.

So GL is built **first and thin**: a **posting engine** (validate, balance, post, append-only) + a
**chart of accounts** (seeded, editable) + **fiscal periods** (open/close) + **two live posting paths**
— **manual journals** (accountants post accruals, adjustments, opening balances) and **automatic
posting of sales on finalise** (the first event-driven poster, mirroring `SaleIssueStockHandler`). Every
later financial module is then a new poster/reader against this same engine. **AR/AP control-account
posting, COGS/inventory posting, Cash/Bank posting, and the VAT return are SEPARATE later increments**
(§10 accepted risk) — this increment delivers the engine and the sales-revenue/VAT posting.

What GL needs as inputs **already exists**: the outbox delivers `SALE.FINALISED` / `SALE.VOIDED` with
the invoice uid (ADR-0009 / ADR-0008 D-9); the finalised `sales_invoices` row carries `net_total_amount`
/ `vat_total_amount` / `gross_total_amount` / `tax_summary` (ADR-0008 D-2); `Money` gives amount +
currency and the company base currency is config (ADR-0005 D-4); `code_sequence` gives concurrency-safe
numbering; RBAC, `ScopeGuard`, and audit are the platform spine. GL consumes the sales event as a **DTO
only** — it never imports a Sales entity (ADR-0009 D-1 boundary discipline).

### Vocabulary (read this first)

- **Chart of accounts (CoA)** — the company's complete list of **accounts**, organised by **numeric
  range** (1000s Assets, 2000s Liabilities, 3000s Equity, 4000s Income, 5000s Expenses). Per company.
  **System-seeded** with a standard Tanzanian small-business set, then **editable** (add/edit/deactivate;
  cannot delete an account that has postings).
- **Account** — a named bucket in the CoA that financial effects are posted to (e.g. `1200 Accounts
  Receivable`, `4100 Sales Revenue`, `2200 VAT Payable`). Carries a **code** (unique per company), a
  **name**, an **account type**, an **active/inactive** state, and audit. The atomic unit of the books.
- **Account type** — one of **ASSET · LIABILITY · EQUITY · INCOME · EXPENSE**. Drives two things: the
  **financial-statement placement** (INCOME/EXPENSE → P&L; ASSET/LIABILITY/EQUITY → Balance Sheet) and
  the **normal balance** (ASSET/EXPENSE are normally **debit**; LIABILITY/EQUITY/INCOME are normally
  **credit**). The type, not the code range alone, is the authority for placement and normal balance.
- **Normal balance** — the side (**debit** or **credit**) on which an account normally carries a positive
  balance, determined by its account type. Used for statement sign and presentation, never to block a
  posting (a contra movement is legal).
- **Journal entry** — one **balanced** financial transaction posted to the books: a date, a description,
  a source reference, and **two or more journal lines** whose **debits equal credits**. The unit of
  posting. Once posted it is **immutable** (corrected only by a reversing entry).
- **Journal line** — one leg of a journal entry: exactly **one account**, and a **debit OR a credit
  amount** (never both, never neither, both positive). All amounts are in the company base currency (v1).
- **Journal batch** — the numbered container a posting run groups its journal entries under (the
  `code_sequence` `JOURNAL_BATCH` number, e.g. `JB-####`). A manual posting is one batch; a sales
  auto-post is one batch; a reversal is one batch.
- **Debit / Credit** — the two sides of double-entry. Every line is one or the other; every entry's
  debits sum equals its credits sum (the double-entry invariant). Whether a debit increases or decreases
  an account depends on the account's type/normal balance.
- **Posting** — the act of writing a balanced journal entry to the books. Posting is **append-only**: a
  posted entry is never edited or deleted (PROJECT-CONVENTIONS §3.6).
- **Fiscal period** — one month of the company's financial year (12 per year). A period is **OPEN**
  (postings allowed) or **CLOSED** (postings rejected). The **fiscal-year start month is configurable
  per company** (e.g. January or July).
- **Fiscal year** — the 12-period accounting year of a company, beginning at its configured start month.
- **Trial balance (TB)** — the read that lists every account with its **total debits and total credits**
  (or net balance) over a period/as-at date; a correct set of books has **total debits == total credits**
  (the TB nets to zero). The first proof the books are sound; the source for P&L and balance sheet.
- **Control account** — an account whose balance summarises a sub-ledger (e.g. `1200 Accounts
  Receivable` is the control account for the AR customer sub-ledger). v1 **seeds** the AR/AP control
  accounts and posts **AR/VAT** from sales; **AR/AP sub-ledger reconciliation is a later increment**.
- **Reversing entry** — a journal entry that **negates** a prior posted entry (swapping its debits and
  credits), used to correct an error or to void a posted transaction. The **only** way to undo a posting
  in an append-only ledger; the prior entry stays on the books, the reversal stands beside it.
- **Opening balance** — the starting balance of an account when the books begin (or at a new fiscal
  year), entered as a **manual journal** that itself must balance (assets debit, liabilities/equity
  credit, retained-earnings the balancing figure).
- **`gl_configs` (account mapping)** — the per-company configuration that maps **posting roles**
  (SALES_REVENUE, VAT_PAYABLE, ACCOUNTS_RECEIVABLE, CASH, …) to **actual CoA accounts**, so the
  auto-poster knows which account to debit/credit without hard-coded account codes. The required mappings
  must be set before auto-posting works (BR-GL-10).

> **Word discipline (carried into the glossary):** an **account** (a GL bucket) is not a **party** (a
> customer/supplier) and not a **cash/bank account** (T1.4, a money location) — three distinct things.
> A **journal entry** (a balanced posting) is not a **sales invoice** (a sales document) — the invoice
> is the *source*; the journal entry is what GL *posts from* it. **Posting** writes to the books; it is
> not "saving a draft." A **reversal** undoes a posting by adding an opposite entry; it never deletes.

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-08). This is **GL Increment 1**: the
> posting engine + chart of accounts + fiscal periods + manual journals + sales-revenue/VAT auto-posting
> + trial balance. Subsequent financial posting (AR/AP control accounts, COGS/inventory, Cash/Bank,
> VAT return) are **separate later increments** that post into / read from this engine — see §10.

### In scope (v1 — "keep balanced double-entry books and auto-post sales")

- **Chart of accounts**, per company: a **system-seeded standard Tanzanian small-business CoA** organised
  by numeric range (1000s Assets, 2000s Liabilities, 3000s Equity, 4000s Income, 5000s Expenses), with
  each account carrying an **account type** (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE) that drives
  financial-statement placement and normal balance. The seed is **editable**: users can **add**, **edit**,
  and **deactivate** accounts; they **cannot delete an account that has postings**. Account **code is
  unique per company**.
- **Manual journal entries** — accountants post balanced journals (accruals, adjustments, reclassifications,
  and **opening balances**) with DR/CR lines that **must balance (Σ debits == Σ credits) before posting**.
- **Automatic posting of a sale on finalise** — finalising a sale **auto-posts a balanced journal entry**
  via the **outbox** (a `SalesPostingHandler` consuming `SALE.FINALISED`, mirroring `SaleIssueStockHandler`):
  **DR Accounts Receivable** (credit sale) **or Cash** (cash sale), **CR Sales Revenue**, **CR VAT
  Payable**, using a configurable **account mapping (`gl_configs`)**. **Idempotent** (consumer marker via
  `processed_events`), posted under the originating event's company/branch context.
- **Reversal of a sale on void** — **`SALE.VOIDED`** auto-posts the **reversing entry** (a
  `SaleVoidingHandler` consuming `SALE.VOIDED`), negating the original sale's journal entry; idempotent.
- **Fiscal calendar** — **12 monthly periods** per fiscal year; the **fiscal-year start month is
  configurable per company** (e.g. Jan or Jul). Periods are **OPEN/CLOSED**; **posting into a CLOSED
  period is rejected** (manual and automatic). Period 12 close yields the year's end-state for opening
  balances of the next year.
- **Append-only, immutable ledger** — a **posted journal entry is never edited or deleted**; corrections
  are made by posting a **reversing entry** then a correct **re-post** (PROJECT-CONVENTIONS §3.6). Full
  audit trail on every post and every period close.
- **Trial balance read** — a per-company read listing every account with total debits / total credits (or
  net), as-at a date or over a period, that **nets to zero** when the books are sound (the acceptance bar).
- **Double-entry invariant enforced** — every journal entry has **≥ 2 lines**, **Σ debits == Σ credits**,
  each line hits **one account** with a **debit OR a credit** amount; the entry carries a **date that must
  fall in an OPEN period**; an unbalanced or closed-period entry is **rejected** (balanced-or-rejected).
- **Base-currency-only posting** — GL posts in the **company base currency** (ADR-0005 D-4) only; a
  foreign-currency source transaction is **converted at entry** to the base currency before posting.
- **Permissions** — `GL.VIEW` (read CoA / TB / journals), `GL.MANAGE` (maintain CoA + `gl_configs`),
  `GL.POST` (post manual journals), `GL.PERIOD.CLOSE` (open/close periods); per-company scope;
  `assertCanActIn` on every read path; audit on every post and close.
- **Journal batch numbering** via the generic `code_sequence` (`JOURNAL_BATCH`, per company), the same
  concurrency-safe mechanism Sales/Purchases/Products use.

### Deferred (recognised, NOT built in v1 — separate later increments)

- **AR / AP control-account posting & sub-ledger reconciliation** (ROADMAP T1.2/T1.3) — v1 **posts AR**
  from a credit sale (DR Accounts Receivable) and **seeds** the AR/AP control accounts, but the **customer/
  supplier sub-ledger** (open invoices, payments, allocation, ageing) and its **reconciliation to the
  control account** are the AR/AP increments. GL just provides the control accounts and the posting engine.
- **COGS / inventory posting** (ROADMAP T2.2) — the `DR COGS / CR Inventory` entry on a sale (and the
  inventory-in entry on a goods receipt) needs **stock valuation**, which is deferred. v1 seeds the
  COGS/INVENTORY accounts in the CoA and `gl_configs` but **posts no cost entry**. (DEFERRED dependency on
  Products/Stock valuation, T2.2.)
- **Cash & Bank posting** (ROADMAP T1.4) — the cash side of receipts/payments (`DR Cash / CR AR`, etc.)
  posts when the Cash/Bank module lands; v1's sales auto-post hits the **Cash account directly** for a cash
  sale (the simple bridge) but a full cash/bank module with reconciliation is deferred.
- **VAT return** (ROADMAP T1.5) — v1 **posts VAT payable** to the books on each sale; the **periodic
  output-vs-input VAT computation and filing record** is the VAT-return increment (and needs input-VAT
  from AP/purchases).
- **FX revaluation / realised & unrealised gain-loss** (ROADMAP X.6 / ADR-0005 D-8) — multi-currency
  posting beyond base, revaluation of open foreign balances at period close, and gain/loss posting are
  **DEFERRED** (accepted scope boundary, §10).
- **Full year-end-close automation** — v1 supports period open/close and a period-12 close yielding the
  year-end state for next-year opening balances; an **automated year-end roll-up** (P&L → retained
  earnings closing entry, automatic opening-balance carry-forward) is a later slice (§10, flagged).
- **Per-product-category / per-revenue-line revenue & VAT mapping** — v1 maps to **one** Sales Revenue
  account and **one** VAT Payable account (a fixed mapping); per-category revenue/VAT split is a later
  additive `gl_configs` option (§10).
- **Budgeting vs actuals** (ROADMAP T3.6), **multi-company consolidation / group reporting currency**
  (OQ-CUR-01), **dimensions / cost centres / project tagging** (ROADMAP T3.5), **recurring journals**,
  **journal approval workflow** (ROADMAP X.5), and **financial statements (P&L / Balance Sheet) themselves**
  (ROADMAP T2.3 Reporting reads GL; v1 GL delivers the **trial balance** read, the statements are the
  Reporting increment).

### Explicitly NOT this module

- **The sub-ledgers themselves** — AR (customer balances/ageing) and AP (supplier balances/3-way match)
  are their own modules (T1.2/T1.3); they **post to** GL. GL holds the control-account totals, not the
  per-party detail.
- **Cash/bank accounts & reconciliation** — the Cash & Bank module (T1.4). GL has a **Cash GL account**;
  it is not a bank reconciliation system.
- **Stock valuation / COGS computation** — the Stock-valuation increment (T2.2) computes cost; GL **posts**
  the cost entry it is handed (later).
- **Sales / Purchases / Stock transactions** — those modules own their documents and **emit events**; GL
  **consumes** the events (DTO-only) and posts. GL never owns a sales invoice or a stock movement.
- **Financial statements & analytics** — P&L, balance sheet, dashboards are Reporting (T2.3); GL provides
  the trial-balance source and the account-type placement they read.

## 3. The books: accounts, journals, periods

### 3.1 The chart of accounts (the structure)

A company's books are organised as a **chart of accounts**: a flat list of **accounts** grouped by
**numeric range**. The range convention (ratified):

| Range | Account type | Statement | Normal balance |
| --- | --- | --- | --- |
| **1000–1999** | **ASSET** | Balance Sheet | Debit |
| **2000–2999** | **LIABILITY** | Balance Sheet | Credit |
| **3000–3999** | **EQUITY** | Balance Sheet | Credit |
| **4000–4999** | **INCOME** | Profit & Loss | Credit |
| **5000–5999** | **EXPENSE** | Profit & Loss | Debit |

Each account carries a **code** (unique per company), a **name**, an **account type** (the authority for
statement placement and normal balance — the range is the convention, the type is the data), an
**active/inactive** state, and audit. The CoA is **system-seeded** with a standard Tanzanian
small-business set (illustrative seed — the exact list is the architect's seed in ADR-0013, but it must
include at minimum the accounts the auto-poster and the future increments map to):

- `1000 Cash`, `1100 Bank`, `1200 Accounts Receivable` (AR control), `1300 Inventory`
- `2100 Accounts Payable` (AP control), `2200 VAT Payable`
- `3000 Owner's Equity / Capital`, `3900 Retained Earnings`
- `4100 Sales Revenue`
- `5100 Cost of Goods Sold`, plus standard expense accounts (rent, salaries, utilities, …)

The seed is **editable**: a user with `GL.MANAGE` may **add** new accounts (any code in the right range,
unique per company), **edit** an account's name/type/active flag, and **deactivate** an account. The hard
rule: an account that **has postings cannot be deleted** (BR-GL-07) — deactivate it instead (an inactive
account is excluded from new postings but stays on historical entries and the trial balance).

### 3.2 The journal (the posting)

Every financial effect is a **journal entry**: a date, a description, a source reference, and **two or
more journal lines** whose **debits equal credits**. Each line names exactly **one account** and carries
**either a debit or a credit amount** (positive; never both, never neither). A posting groups its
entries under a numbered **journal batch** (`JB-####` from `code_sequence`). Posting is **append-only**:
once posted, an entry is **immutable** — never edited, never deleted (PROJECT-CONVENTIONS §3.6).

Two posting paths in v1:

- **Manual** — an accountant composes a journal (accrual, adjustment, opening balance), the system checks
  it **balances** (Σ debits == Σ credits) and the date is in an **OPEN period**, then posts it (`GL.POST`).
- **Automatic** — the `SalesPostingHandler` composes and posts the sales journal from a `SALE.FINALISED`
  event (no human in the loop; posted under the system context), and the `SaleVoidingHandler` posts the
  reversal from `SALE.VOIDED`. Both go through the **same posting engine** and the **same invariants** as
  a manual journal — the only difference is the author (the system) and that the lines are derived from
  the event + `gl_configs` rather than keyed.

### 3.3 The fiscal calendar (when posting is allowed)

A company's financial year has **12 monthly periods**. The **fiscal-year start month is configurable per
company** (e.g. a January start gives Jan–Dec periods; a July start gives Jul–Jun periods). Each period
is **OPEN** or **CLOSED**:

- An **OPEN** period accepts postings (manual and automatic) whose entry date falls within it.
- A **CLOSED** period **rejects** all postings — manual journals are refused, and an auto-post whose
  source event dates into a closed period is refused (the event is handled per the closed-period policy,
  §7.4). Closing a period is a permissioned, audited act (`GL.PERIOD.CLOSE`).
- **Period 12 close** yields the fiscal year's end state, which seeds the next year's **opening balances**
  (entered as a manual opening-balance journal in v1 — see FR-GL-13). **Full year-end-close automation**
  (an automatic P&L-to-retained-earnings closing entry and opening-balance carry-forward) is a later slice
  (§10, flagged — does not block this increment).

## 4. Actors / personas

- **Accountant / bookkeeper** — composes and **posts manual journals** (accruals, adjustments, opening
  balances), reads the trial balance, and maintains the chart of accounts within policy. Holds `GL.POST`
  (and usually `GL.VIEW`; `GL.MANAGE` if they maintain the CoA).
- **Financial controller / finance manager** — owns the chart of accounts and the `gl_configs` account
  mapping (`GL.MANAGE`), **opens and closes fiscal periods** (`GL.PERIOD.CLOSE`), and reviews the books.
  The senior finance authority on the deployment.
- **SYSTEM (the auto-poster)** — **not a human**. The outbox consumers (`SalesPostingHandler`,
  `SaleVoidingHandler`) that post the sales journal / reversal automatically when a sale finalises /
  voids, running **under the originating event's company/branch context** (not a logged-in user), with no
  permission check (the producing action — finalising a sale — was already permissioned, ADR-0009 D-9).
  The system poster is a first-class actor in this module: most postings in a live deployment are its work.
- **Auditor / read-only finance user** — reads the chart of accounts, journals, and trial balance
  (`GL.VIEW`); posts nothing.
- *(Later increments add the AR/AP/Cash posters as further SYSTEM consumers of this engine — named here
  so the engine is built for many posters, not just sales.)*

## 5. Functional requirements

> IDs are `FR-GL-NN`. Each is a crisp, testable, **ratified** statement. "Posting" = writing a balanced
> journal entry to the append-only books through the GL posting engine.

### Chart of accounts

- **FR-GL-01** The system maintains a **chart of accounts per company**: a list of **accounts**, each with
  a **code** (unique within the company), a **name**, an **account type** (ASSET / LIABILITY / EQUITY /
  INCOME / EXPENSE), an **active/inactive** state, and audit. Accounts are organised by **numeric range**
  (1000s Assets, 2000s Liabilities, 3000s Equity, 4000s Income, 5000s Expenses).
- **FR-GL-02** On company setup the system **seeds a standard Tanzanian small-business chart of accounts**
  (including, at minimum, Cash, Bank, Accounts Receivable, Inventory, Accounts Payable, VAT Payable,
  Owner's Equity, Retained Earnings, Sales Revenue, Cost of Goods Sold, and standard expense accounts).
  The seed is **per company** (a new company gets its own seeded CoA).
- **FR-GL-03** A user with `GL.MANAGE` may **add** a new account (code unique per company, in the
  appropriate range, with a valid account type), **edit** an account's name / type / active flag, and
  **deactivate** an account. An **inactive** account is excluded from **new** postings but remains on
  historical entries and the trial balance.
- **FR-GL-04** The system **prevents deletion of an account that has any postings** (BR-GL-07): such an
  account may only be **deactivated**. An account with no postings may be deleted.
- **FR-GL-05** Each account's **account type drives its financial-statement placement** (INCOME / EXPENSE
  → P&L; ASSET / LIABILITY / EQUITY → Balance Sheet) and its **normal balance** (ASSET / EXPENSE = debit;
  LIABILITY / EQUITY / INCOME = credit). The type is the authority; the range is the seeding convention.

### Manual journal entries

- **FR-GL-06** A user with `GL.POST` may **post a manual journal entry**: a date, a description, an
  optional source reference, and **two or more journal lines**, each naming **one active account** with a
  **debit OR a credit amount** (positive). The entry is grouped under a **journal batch** numbered
  `JB-####` (from `code_sequence`, per company).
- **FR-GL-07** The system **rejects a manual journal that does not balance**: it posts **only** when
  **Σ debits == Σ credits** across the entry's lines (BR-GL-01). An unbalanced journal is refused with a
  clear message; nothing is written to the books.
- **FR-GL-08** The system **rejects posting into a CLOSED period**: a journal whose **entry date** falls in
  a closed (or non-existent) fiscal period is refused (BR-GL-03). It posts only into an **OPEN** period.
- **FR-GL-09** The system **rejects a journal line that names an inactive account** (BR-GL-04) and a line
  that carries neither a debit nor a credit, or both (BR-GL-08).

### Automatic posting from Sales (the first event-driven poster)

- **FR-GL-10** When a sale **finalises**, the system **auto-posts a balanced journal entry** for it, driven
  by the **`SALE.FINALISED`** outbox event (consumed by a `SalesPostingHandler`, mirroring
  `SaleIssueStockHandler`). The entry, using the `gl_configs` account mapping, is: **DR Accounts
  Receivable** (a credit sale) **or DR Cash** (a cash sale) for the **gross**; **CR Sales Revenue** for the
  **net**; **CR VAT Payable** for the **VAT** — derived from the invoice's `net_total_amount` /
  `vat_total_amount` / `gross_total_amount` (ADR-0008). The entry is **balanced by construction**
  (net + VAT == gross) and posted under the **originating event's company/branch context**.
- **FR-GL-11** The sales auto-post is **idempotent**: a redelivered `SALE.FINALISED` event posts **no
  second journal entry** (consumer-side dedupe via the `processed_events` marker, ADR-0009 D-6). Processing
  the same event twice yields the same books.
- **FR-GL-12** When a sale is **voided**, the system **auto-posts the reversing entry** for the original
  sales journal, driven by the **`SALE.VOIDED`** outbox event (consumed by a `SaleVoidingHandler`): it
  **negates** the original entry (the original DR becomes a CR and vice versa) so the net effect on the
  books is zero. The reversal is **idempotent** (redelivered `SALE.VOIDED` posts no second reversal) and
  the **original entry is retained** (append-only — void reverses, never deletes). If the original sale was
  never posted (out-of-order / anomaly), the handler records an **anomaly** rather than posting a phantom
  reversal (mirrors the Stock OQ-STOCK-10 reconciliation).

### Opening balances & fiscal periods

- **FR-GL-13** A user with `GL.POST` may enter **opening balances** as a **manual journal** (FR-GL-06):
  assets debited, liabilities/equity credited, with the balancing figure to an equity / retained-earnings
  account, posted into the **first open period**. The opening-balance journal must **balance** like any
  other (BR-GL-01).
- **FR-GL-14** The system maintains a company's **fiscal calendar**: **12 monthly periods** per fiscal
  year, with the **fiscal-year start month configurable per company**. Each period is **OPEN** or
  **CLOSED**.
- **FR-GL-15** A user with `GL.PERIOD.CLOSE` may **close** an open period and **(re)open** a closed period
  (a permissioned, audited act). A **closed** period **rejects** all postings (FR-GL-08); closing period 12
  yields the fiscal year's end state used to seed the next year's opening balances (FR-GL-13). *(Full
  year-end-close automation is deferred — §10, flagged.)*

### Reads

- **FR-GL-16** The system produces a **trial balance** for a company: every account with its **total
  debits** and **total credits** (and net balance), **as-at a date** or **over a period**. A correct set
  of books yields **total debits == total credits** (the TB nets to zero). The trial balance is the
  source the future P&L / balance sheet read (Reporting, T2.3).
- **FR-GL-17** A user with `GL.VIEW` may **read** the chart of accounts, posted journal entries (by batch,
  by account, by period), and the trial balance — all scoped to their company. No read crosses company
  scope.

### Configuration

- **FR-GL-18** The system maintains a per-company **account mapping (`gl_configs`)** linking posting roles
  (at minimum SALES_REVENUE, VAT_PAYABLE, ACCOUNTS_RECEIVABLE, CASH; plus the seeded-but-not-yet-posted
  INVENTORY, COGS, ACCOUNTS_PAYABLE for later increments) to **actual CoA accounts**. A user with
  `GL.MANAGE` maintains it. The auto-poster reads it to resolve which account to debit/credit — **no
  hard-coded account codes** (BR-GL-10). The required sales mappings (SALES_REVENUE, VAT_PAYABLE, and AR or
  CASH) **must be set before sales auto-posting works**; if a required mapping is missing when a
  `SALE.FINALISED` arrives, the handler **fails the event** (it retries / parks per the outbox, ADR-0009
  D-4) rather than posting to a wrong or null account.
- **FR-GL-19** All GL operations are **gated by IAM permissions**: `GL.VIEW`, `GL.MANAGE` (CoA +
  `gl_configs`), `GL.POST` (post manual journals), `GL.PERIOD.CLOSE` (open/close periods); exact codes are
  seeded with the module (FR-IAM-11). Per-company scope; `assertCanActIn` on every read path. The **SYSTEM
  auto-poster runs under no user permission** (the producing sales action was already permissioned,
  ADR-0009 D-9) but **is bounded by the event's company/branch context**.

## 6. Business rules (invariants)

> Ratified. These are the GL invariants the engine enforces; a violation is a finance-grade defect.

- **BR-GL-01 — Double-entry / balanced-or-rejected.** Every journal entry has **≥ 2 lines** and posts
  **only** when **Σ debits == Σ credits**. An unbalanced entry is **rejected**; nothing partial is written.
  (Manual and automatic alike; the auto-posted sales entry is balanced by construction, net + VAT == gross.)
- **BR-GL-02 — Immutable posted ledger / reverse-only.** A **posted journal entry is never edited or
  deleted**. The only correction is a **reversing entry** (then a correct re-post). The original entry
  stays on the books beside its reversal (PROJECT-CONVENTIONS §3.6). A void of a sale is a reversal, not a
  delete (FR-GL-12).
- **BR-GL-03 — No posting to a closed period.** An entry whose date falls in a **CLOSED** (or non-existent)
  fiscal period is **rejected** (FR-GL-08). Posting requires an **OPEN** period covering the entry date.
- **BR-GL-04 — No posting to an inactive account.** A journal line may name only an **active** account; a
  line naming an inactive account is rejected (FR-GL-09). (Existing postings to a since-deactivated account
  stand.)
- **BR-GL-05 — One company's books are isolated.** Every account, journal entry, journal line, fiscal
  period, and `gl_configs` row **belongs to exactly one company**; no posting, read, or trial balance
  crosses company scope. Cross-company GL leakage is a **release blocker** (NFR-GL-01).
- **BR-GL-06 — Base-currency-only posting (v1).** GL posts in the **company base currency** only
  (ADR-0005 D-4). A foreign-currency source transaction is **converted to base at entry** before posting;
  the ledger holds base-currency amounts. FX revaluation / gain-loss is **out of v1 scope** (§10).
- **BR-GL-07 — An account with postings cannot be deleted.** Such an account may only be **deactivated**
  (FR-GL-04). This protects the audit trail and the historical books.
- **BR-GL-08 — A journal line is one account, one side.** Each line names **exactly one account** and
  carries **a debit OR a credit amount** (positive) — never both, never neither (FR-GL-09).
- **BR-GL-09 — Idempotent auto-posting.** Processing the same outbox event twice posts **no second entry**:
  `SALE.FINALISED` posts the sales journal once, `SALE.VOIDED` posts the reversal once, regardless of
  redelivery (consumer-side dedupe, ADR-0009 D-6). Violation = double-counted revenue / VAT = a finance
  release blocker.
- **BR-GL-10 — Auto-posting needs its account mapping.** Sales auto-posting works **only** when the
  required `gl_configs` mappings (SALES_REVENUE, VAT_PAYABLE, and AR or CASH per sale kind) are set to
  valid active accounts. A missing required mapping **fails the event** (retry/park, ADR-0009 D-4) rather
  than mis-posting; no hard-coded account codes are used (FR-GL-18).
- **BR-GL-11 — A balanced reversal is itself balanced.** A reversing entry obeys BR-GL-01 (it swaps debits
  and credits of the entry it reverses, so it balances by construction) and BR-GL-03 (it posts into an open
  period — typically the void date's period). The original + the reversal net to zero on every account.
- **BR-GL-12 — Account type is the authority for placement & sign.** Statement placement (P&L vs Balance
  Sheet) and normal balance (debit vs credit) derive from the account's **type**, not from the code range
  alone (FR-GL-05). A miscoded account follows its **type**.

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Post a manual journal (happy path)
1. Accountant (logged in, `GL.POST`, active company) starts a **new journal**: enters a **date**, a
   **description**, and an optional **source reference**.
2. Adds **journal lines**: for each, picks an **active account** (branch/company-scoped CoA) and enters a
   **debit OR a credit** amount (base currency). At least **two** lines.
3. System shows the **running debit / credit totals** and whether the entry **balances**.
4. Accountant **posts**. System validates: **≥ 2 lines**, **Σ debits == Σ credits** (BR-GL-01), **date in
   an OPEN period** (BR-GL-03), **every account active** (BR-GL-04), **every line one-sided** (BR-GL-08).
5. On pass: the entry is written to the **append-only books** under a **`JB-####`** batch; the post is
   **audited** (actor, action, batch, company); the entry is now **immutable** (BR-GL-02).

### 7.2 Sale finalises → auto-post (happy path)
1. A sale **finalises** (Sales module, already permissioned) → emits **`SALE.FINALISED`** to the outbox in
   the finalise transaction (ADR-0008 D-9 / ADR-0009).
2. The **`SalesPostingHandler`** consumes the event (under the event's company/branch context), reads the
   invoice totals (net / VAT / gross) by the payload's invoice uid (DTO-only), and resolves accounts from
   **`gl_configs`** (SALES_REVENUE, VAT_PAYABLE, and AR or CASH per sale kind).
3. It composes the **balanced** entry — **DR AR/Cash** (gross), **CR Sales Revenue** (net), **CR VAT
   Payable** (VAT) — checks the **`processed_events`** marker (idempotency), and **posts** it through the
   same engine as a manual journal (balanced-or-rejected, open-period, active-account), writing the marker
   in the **same transaction** (ADR-0009 D-5/D-6).
4. The books now carry the sale; the trial balance reflects it. (No human in the loop; the producing sale
   was already audited by Sales — ADR-0009 D-9; GL audits the post.)

### 7.3 Sale voids → auto-reverse (happy path)
1. A sale is **voided** (Sales) → emits **`SALE.VOIDED`** to the outbox.
2. The **`SaleVoidingHandler`** consumes it, finds the original sales journal (by source reference /
   invoice uid), and **posts the reversing entry** (DR↔CR swapped), idempotently (marker).
3. The original entry **and** the reversal stand on the books (append-only); their net effect on every
   account is **zero** (BR-GL-11).

### 7.4 Main unhappy paths
- **Unbalanced manual journal** (7.1.4) → **rejected** with the debit/credit difference shown; nothing
  posts (BR-GL-01).
- **Manual journal dated in a closed period** (7.1.4) → **rejected**; the period must be reopened
  (`GL.PERIOD.CLOSE`) or the date moved to an open period (BR-GL-03).
- **Line on an inactive account** (7.1.2/4) → **rejected**; pick an active account (BR-GL-04).
- **`gl_configs` mapping missing** when a `SALE.FINALISED` arrives (7.2.2) → the handler **fails the event**
  (no partial / wrong post); it **retries** on the next poll and **parks FAILED** after the cap (ADR-0009
  D-4). Finance sets the mapping (`GL.MANAGE`), then the parked event is replayed — the sale posts. (No
  silent post to a null/wrong account, BR-GL-10.)
- **Auto-post would fall in a closed period** (7.2.3) → the handler **fails the event** (closed-period
  rejection applies to automatic posting too, BR-GL-03); finance reopens the period or applies the
  configured closed-period policy, then the event is replayed. *(Recommended default: fail-and-retry so no
  sale posts to a closed period; an alternative "post to the next open period" policy is a non-blocking
  detail — OQ-GL-01.)*
- **Redelivered `SALE.FINALISED` / `SALE.VOIDED`** (7.2/7.3) → the **idempotency marker** short-circuits;
  **no second entry / reversal** posts (BR-GL-09).
- **`SALE.VOIDED` for a sale never posted** (out-of-order, 7.3.2) → the handler records an **anomaly** for
  review rather than posting a phantom reversal (FR-GL-12; mirrors OQ-STOCK-10).
- **Attempt to edit/delete a posted entry** (any) → **refused**; the only correction is a **reversing
  entry** then a re-post (BR-GL-02).
- **Attempt to delete an account that has postings** → **refused**; deactivate it instead (BR-GL-07).

## 8. Non-functional

- **NFR-GL-01 — Tenant isolation.** Every account, journal entry/line, fiscal period, and `gl_configs` row
  is scoped by `company_id` and goes through the tenant-predicate repository base (ARCHITECTURE.md §5,
  PROJECT-CONVENTIONS §3.2); `assertCanActIn` guards **every read path**. Cross-company GL leakage is a
  **release blocker**, as for IAM/Sales. (GL rows are **company-scoped**; a posting may carry the
  originating **branch** for analysis, but the books are kept at company level — branch is a tag, not a
  separate ledger, in v1.)
- **NFR-GL-02 — Money correctness.** Every amount is a `Money` (amount + currency, ADR-0005) in the company
  base currency; debits and credits sum **exactly** (no float). The balance check (Σ debits == Σ credits)
  uses `BigDecimal` value comparison; a rounding discrepancy that leaves an entry unbalanced is a defect,
  not a tolerance. Rounding follows ADR-0005 D-2 (HALF_UP to the currency's minor units; TZS = 0 dp in
  practice, OQ-CUR-03).
- **NFR-GL-03 — Idempotency (the single biggest correctness risk).** Auto-posting from the outbox must be
  **exactly-effect-once**: a redelivered event posts no second entry (BR-GL-09, ADR-0009 D-6). An
  integration test must deliver the same `SALE.FINALISED` twice and assert the books move **once**. A
  violation double-counts revenue and VAT — a **release blocker**.
- **NFR-GL-04 — Append-only integrity.** The ledger is **append-only** (BR-GL-02): the engine offers **no**
  edit/delete of a posted entry; corrections are reversals. This is structural, not policy — a defect if an
  update/delete path exists.
- **NFR-GL-05 — Numbering concurrency.** Two postings running simultaneously must get distinct journal
  batch numbers (the `code_sequence` row-locked allocation guarantees this — ADR-0007 D-6).
- **NFR-GL-06 — Audit.** Every **post** (manual and automatic) and every **period open/close** is written
  to the IAM append-only audit trail with actor (or SYSTEM for the auto-poster), action, target, timestamp,
  and company context (mirrors FR-IAM-23). The outbox itself does not double-audit (ADR-0009 D-9); GL audits
  the **post** it performs.
- **NFR-GL-07 — DTO-only consumption.** GL consumes `SALE.FINALISED` / `SALE.VOIDED` as **event payloads /
  DTOs**; it **never imports a Sales (or any other module's) entity** (ADR-0009 D-1; `ModuleBoundaryTest`).
  It reads the invoice totals through a service-layer DTO call or the event payload, by uid.
- **NFR-GL-08 — Timestamps** are UTC, displayed per company time zone (Africa/Dar_es_Salaam default,
  iam.md locale). The **entry date** (which drives period assignment) is a business date, distinct from the
  posting timestamp.
- **NFR-GL-09 — Forward-compatibility.** The v1 model must not preclude the later increments that post into
  / read from this engine: AR/AP control-account posting (T1.2/T1.3), COGS/inventory posting (T2.2),
  Cash/Bank posting (T1.4), the VAT return (T1.5), FX revaluation (X.6), and per-category revenue/VAT
  mapping. Building these is deferred; precluding them is a defect.

## 9. Assumptions

- The dependency platform exists and is consumed as designed: the **outbox** delivers `SALE.FINALISED` /
  `SALE.VOIDED` (ADR-0009) with the invoice uid + company/branch + totals reference; **Money** (ADR-0005)
  and the **company base currency** config are in place; **`code_sequence`** (ADR-0007) provides
  `JOURNAL_BATCH` numbering; **RBAC / `ScopeGuard` / audit** are the platform spine. All shipped.
- The finalised `sales_invoices` row carries **`net_total_amount` / `vat_total_amount` /
  `gross_total_amount`** (and `tax_summary`) the auto-poster reads (ADR-0008 D-2); GL reads these by uid as
  a **DTO**, not by importing the entity.
- **Document currency = company base (TZS) in practice** for v1 sales (sales.md §9), so the convert-at-entry
  step (BR-GL-06) is identity in practice; the **shape** supports a foreign-currency source, but FX
  posting depth is deferred (§10).
- The **sale-kind signal (cash vs credit)** the auto-poster uses to choose **DR Cash** vs **DR Accounts
  Receivable** is available from the sale (v1 sales are paid-at-sale, so the practical default is **cash →
  DR Cash**; credit sales are deferred in Sales, so AR posting becomes live with the AR increment — the
  mapping role exists from day one so AR posting is additive, OQ-GL-02).
- The **fiscal-year start month** is confirmed per company at setup (default January for TZ small business
  unless the owner sets otherwise); the **TZS VAT rate (18%)** that produced the invoice VAT is Sales'
  concern (tax_rates, ADR-0008 D-5) — GL posts the VAT amount it is given, it does not re-rate.

## 10. ACCEPTED SCOPE BOUNDARY — what GL Increment 1 deliberately does NOT do (owner-accepted 2026-06-08)

> **Read this before building or consuming GL.** GL Increment 1 delivers the **posting engine + chart of
> accounts + fiscal periods + manual journals + sales-revenue/VAT auto-posting + trial balance**. The
> following are **deliberate boundaries**, owner-accepted; nobody may quietly assume otherwise.

1. **AR / AP sub-ledger posting & reconciliation is a SEPARATE increment (T1.2/T1.3).** v1 **posts AR** on
   a credit sale (DR Accounts Receivable) and **seeds** the AR/AP control accounts + mappings, but the
   **customer/supplier sub-ledger** (open items, payments, allocation, ageing) and its reconciliation to
   the control account are **not** in this increment. GL provides the control accounts and the engine; the
   sub-ledgers post into it later. (Practically, v1 sales are paid-at-sale, so the live sales auto-post is
   **DR Cash**; AR posting goes live with credit sales + the AR module.)

2. **COGS / inventory posting is a SEPARATE increment (T2.2) — DEFERRED dependency on stock valuation.**
   The `DR COGS / CR Inventory` entry on a sale (and the inventory-in entry on a goods receipt) needs
   **stock valuation**, which does not exist (Stock is quantity-only — stock.md §10). v1 **seeds the
   COGS/INVENTORY accounts and their `gl_configs` roles** but **posts no cost entry**. When valuation lands
   (T2.2), a `StockValuationPostingHandler` posts COGS/inventory into this same engine — additive, not a
   rework.

3. **Cash & Bank posting / reconciliation is a SEPARATE increment (T1.4).** v1's cash sale posts directly
   to the **Cash GL account**; a full cash/bank module (multiple cash/bank accounts, receipts/disbursements,
   bank reconciliation) and its posting are deferred.

4. **The VAT return is a SEPARATE increment (T1.5).** v1 **posts VAT Payable** to the books on each sale;
   the **periodic output-vs-input VAT computation and filing record** (which also needs input VAT from
   AP/purchases) is the VAT-return increment.

5. **FX revaluation / realised & unrealised gain-loss is DEFERRED (ROADMAP X.6 / ADR-0005 D-8).** v1 posts
   **base-currency only** (BR-GL-06); converting at entry is supported, but revaluing open foreign balances
   at period close and posting gain/loss is **out of scope** — an accepted boundary, not a defect, and not
   precluded by the v1 model (NFR-GL-09).

6. **Full year-end-close automation depth.** v1 supports period open/close and a period-12 close that
   yields the year-end state for **manually entered** next-year opening balances (FR-GL-13). An **automated
   year-end roll-up** (an automatic P&L-to-retained-earnings closing entry + automatic opening-balance
   carry-forward) is a **later slice** — flagged (OQ-GL-03), non-blocking, not precluded.

7. **Per-product-category / per-revenue-line revenue & VAT mapping.** v1 maps to **one** Sales Revenue
   account and **one** VAT Payable account (the ratified fixed mapping). A per-category revenue/VAT split is
   a later **additive** `gl_configs` option (OQ-GL-04), not v1.

All seven are additive by design (NFR-GL-09); none is precluded by the v1 model. GL Increment 1 is the
engine + sales revenue/VAT posting **now**; every other financial posting is a later increment **into**
this engine.

## 11. Open questions — status after ratification (2026-06-08)

> The **six scoping forks** the owner answered (chart of accounts; sales auto-posting; fiscal calendar;
> corrections; manual journals; multi-currency) are **RESOLVED** (recorded in
> `docs/requirements/open-questions.md` under GL). **No ADR-0013-blocking open question remains.** What
> stays open is **non-blocking** detail with a recommended default that stands unless the owner overrides
> — confirm during build / before go-live, not before ADR-0013.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0013)

- **OQ-GL-01 — Closed-period policy for an auto-post.** When a `SALE.FINALISED` would post into a **closed**
  period (a late/replayed event), does the handler **fail-and-retry until the period is reopened** (the
  recommended default — no sale ever posts to a closed period, BR-GL-03), or **post to the next open
  period**? *Recommended default:* **fail-and-retry** (BR-GL-03 holds for automatic posting too); finance
  reopens the period or moves it. *Decider:* owner (finance). *Blocks ADR-0013:* **NO** — the default
  stands; the alternative is a configurable policy added additively.
- **OQ-GL-02 — Cash-vs-credit sale signal for AR vs Cash.** v1 sales are paid-at-sale (cash), so the live
  auto-post is **DR Cash**. The **AR** posting path and its trigger (a credit sale, deferred in Sales) go
  live with the AR increment. *Recommended default:* DR Cash for v1 sales; the AR mapping role exists from
  day one so credit-sale AR posting is additive. *Decider:* owner (with the AR increment). *Blocks
  ADR-0013:* **NO** — both mapping roles are seeded; the credit path is wired when credit sales land.
- **OQ-GL-03 — Year-end-close automation depth.** v1 = manual opening balances + period-12 close yields the
  year-end state. *Recommended default:* manual opening-balance journal in v1; automated P&L→retained-
  earnings closing entry + opening-balance carry-forward is a later slice (§10.6). *Decider:* owner.
  *Blocks ADR-0013:* **NO** — deferred, not precluded.
- **OQ-GL-04 — Per-category revenue / VAT mapping.** v1 = one Sales Revenue + one VAT Payable account.
  *Recommended default:* fixed single mapping; per-product-category split is an additive `gl_configs`
  option later (§10.7). *Decider:* owner. *Blocks ADR-0013:* **NO** — additive.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Confirm rounding mode (half-up vs banker's) and
  TZS decimal places (0 in practice) — the balance check and every posted amount must round identically
  backend/frontend (NFR-GL-02). *Recommended default:* half-up, TZS = 0 dp. *Decider:* owner (finance
  input). *Blocks ADR-0013:* **NO** for the model; **confirm before go-live**.

## 12. Out of scope for v1 (deferred — restated)

AR/AP sub-ledger posting & reconciliation (T1.2/T1.3); COGS / inventory valuation posting (T2.2, DEFERRED
dependency on stock valuation); Cash & Bank posting / reconciliation (T1.4); the VAT return — periodic
output-vs-input computation & filing (T1.5); FX revaluation / realised & unrealised gain-loss (X.6 /
ADR-0005 D-8); full year-end-close automation (OQ-GL-03); per-product-category revenue/VAT mapping
(OQ-GL-04); budgeting vs actuals (T3.6); multi-company consolidation / group reporting currency (OQ-CUR-01);
dimensions / cost centres / project tagging (T3.5); recurring journals; journal approval workflow (X.5);
and the financial statements themselves — P&L / Balance Sheet (Reporting, T2.3 — GL delivers the trial
balance read; the statements read GL). Each is tracked for a later increment; none is precluded by the v1
model (NFR-GL-09).

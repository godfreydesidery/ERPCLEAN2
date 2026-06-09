# Requirements — Cash & Bank (the cash book + bank book — where the money actually lives)

> Status: **RATIFIED (owner-confirmed 2026-06-09).** The owner answered all Cash & Bank scoping forks —
> **multiple named cash/bank accounts** per company (petty cash, tills, per-bank/per-branch bank accounts),
> **each mapped to its own GL `1xxx` asset account** (this replaces the single `gl_configs` `CASH` account
> with real named accounts); **bank reconciliation is MANUAL** in v1 (mark transactions cleared against the
> statement, record a statement date + closing balance, the system checks book balance vs statement balance
> must agree to complete — **no statement file import**); and the **four v1 operations** (inter-account
> transfers; direct cash/bank entries not tied to AR/AP; cheque management with a cheque register and
> post-dated cheques; a per-account running statement & balance) **plus** the always-in core — **AR receipts
> and AP payments now route to a chosen cash/bank account.** Each is reflected below as a fixed v1
> requirement; everything not chosen has moved to the **Deferred** list (§2). **No ADR-0016-blocking open
> question remains.**
>
> Author: system-analyst · Domain: `cashbank` (financial / treasury). Business-level spec only.
> **No schema, no API shapes, no tables/columns, no code** — those are the solutions-architect's, in
> **ADR-0016** (next step). Do not infer a data model from this document.
>
> **This is Cash & Bank — Increment 3 (T1.4) of the full-ERP roadmap (docs/ROADMAP.md T1.4 / §5 Increment 3),
> the Tier-1 finance finisher.** Cash & Bank is the **cash book + bank book**: the named money locations
> (petty cash, tills, bank accounts) where AR/AP settlement money actually lives, reconciled to the bank
> statement and to the GL. It does **not** rebuild the books; it introduces real named cash/bank accounts
> (each linked to a GL `1xxx` asset account), posts every cash movement to GL synchronously, and reconciles
> each account both to the bank statement (manual reconciliation) and to its linked GL account.
>
> **Depends on:** **GL** (the books — ADR-0013 / V10; `chart_of_accounts`, the synchronous
> `GLPostingService.post`, `gl_configs` key/value mapping incl. the `CASH` key, `fiscal_periods`,
> `FiscalPeriodResolver`, `GLConfigResolver`, the `ScopeGuard` extension pattern); **AR** (ADR-0014 / V11 —
> a receipt posts **DR Cash/Bank / CR `1200`** synchronously; today the cash leg lands on the single
> `gl_configs` `CASH` account); **AP** (ADR-0015 / V12 — a payment posts **DR `2100` / CR Cash/Bank**
> synchronously; same single-CASH-account bridge today); **Money** (ADR-0005 — base currency only in v1);
> **RBAC / `ScopeGuard` / audit** (the platform spine); and `code_sequence` (account/transfer/cheque
> numbering). All shipped. **The central integration:** Cash & Bank turns the single-`CASH`-account bridge
> AR/AP use today into **a choice of real named cash/bank accounts** — an additive cross-module touch to the
> AR record-receipt and AP pay paths (§3.6, flagged for the architect).

## 1. Business context & why now

The books exist (GL), and the sub-ledgers exist (AR/AP). When a customer pays, AR records a receipt and
posts **DR Cash/Bank / CR `1200 Accounts Receivable`** to GL; when we pay a supplier, AP posts **DR `2100
Accounts Payable` / CR Cash/Bank**. But **"Cash/Bank" is a single hard-wired GL account today** — the
`gl_configs` `CASH` mapping (gl.md §10.3, accounts-receivable.md §10.2, accounts-payable.md §10.4 — "the
simple bridge"). Every receipt and every payment lands on the **same one account**, regardless of whether
the money went into the till, into petty cash, or into a specific bank account at a specific branch. There
is **no record of which money location holds what**, no way to tell the petty-cash float from the bank
balance, and **no way to reconcile against a bank statement**. The business has one undifferentiated "cash"
number on the trial balance and cannot answer the treasurer's daily questions: **how much is in the bank,
how much is in each till, what has cleared, and does our book balance agree with the bank's?**

**Cash & Bank closes that gap.** It introduces **named cash/bank accounts** — the **cash book** (petty cash,
tills) and the **bank book** (one account per bank / per branch) — each a real money location with its own
balance, **each linked to its own GL `1xxx` asset account** (so the single `CASH` account becomes several
named accounts). It answers, per money location:

- the **current balance** and a **running statement** (every transaction + a running balance);
- **inter-account transfers** — money moved between locations (bank → petty cash, cash deposit → bank),
  posting the GL double-entry;
- **direct cash/bank entries** — ad-hoc receipts/payments **not** tied to AR/AP (bank charges, interest
  received, owner drawings, sundry cash expense), posting to a chosen GL counter-account;
- a **cheque register** — cheque number, issued/cleared/cancelled status, post-dated cheques (issue date vs
  value date), and cheque printing (dependent on the cross-cutting PDF capability — flagged);
- **bank reconciliation** — marking transactions **cleared** against the bank statement, recording a
  statement date + closing balance, and confirming **book balance == statement balance** to complete it.

And it makes the **AR receipt / AP payment route to a chosen cash/bank account**: the money no longer lands
on an undifferentiated `CASH` account but on the **specific account** the operator selects (its linked GL
account being the debit/credit). This is an **additive cross-module touch** to the AR record-receipt and AP
pay paths — exactly the shape the Sales credit-limit check was for AR, and `products.vat_status` was for
Sales (§3.6, flagged for the architect).

### The two reconciliation rules (read this before anything else)

Cash & Bank has **two** integrity rules, and both shape every posting decision:

1. **Each cash/bank account reconciles to its linked GL account** (the sub-ledger ⇄ control pattern, the
   same shape as AR ⇄ `1200` and AP ⇄ `2100`). A cash/bank account maps to **exactly one** GL `1xxx` asset
   account; the account's **book balance** (the running sum of its transactions) **equals** that GL
   account's balance at all times (BR-CASH-02, NFR-CASH-01). Because every cash/bank movement posts to that
   linked GL account synchronously, they move together by construction. This is the chief acceptance bar —
   the "reconciliation-bar AC" every flow asserts.

2. **Each bank account reconciles to its bank statement** (the manual bank reconciliation, v1). The
   operator marks the account's transactions **cleared** against the bank statement, records a
   **reconciliation** with a statement date + statement closing balance, and the system confirms the
   **book balance agrees with the bank/statement balance** before the reconciliation can be **completed**
   (BR-CASH-06). This is the classic bank rec; v1 does it **manually** (no statement file import).

> **The non-obvious consequence — the single-CASH-account replacement.** Today AR/AP post the cash leg to
> one `gl_configs` `CASH` account. Cash & Bank replaces that one account with **several named cash/bank
> accounts, each with its own linked GL account.** So an AR receipt / AP payment must now **choose which
> cash/bank account** the money lands in — and the **debit/credit is that chosen account's linked GL
> account**, not the single `CASH` mapping. If the operator does not choose, a **company default cash/bank
> account** is used (BR-CASH-09). This is the load-bearing integration; getting it wrong means the money
> lands in the wrong location and the per-account balances are meaningless.

> **Flag for the architect (ADR-0016):** three decisions. (1) **The AR/AP additive touch** — the AR
> record-receipt request and the AP pay (single + payment-run) request **gain an optional cash/bank account
> reference**; absent → the company default cash/bank account; the **debit/credit account is the chosen
> account's linked GL account** (resolved through Cash & Bank, not the bare `gl_configs` `CASH`). Mirror how
> the Sales credit-limit check was an additive touch to AR (accounts-receivable.md §3.5) and
> `products.vat_status` to Sales. Keep the dependency direction AR→CashBank / AP→CashBank (Cash & Bank must
> not depend back on AR/AP entities). (2) **The GL-posting mechanism** for transfers / direct entries — a
> **synchronous `GLPostingService.post` in the same TX** (the AR/AP precedent, ADR-0014 D-4 / ADR-0015) — is
> the established pattern; Cash & Bank movements are single in-request user actions, so they post
> **synchronously**, not over the outbox. (3) **`ScopeGuard`** gains a new target type
> (`cashbankaccount`, and the cheque / reconciliation targets the architect names). State these; do not
> design the tables here.

### Vocabulary (read this first)

- **Cash/bank account** — a named **money location** the company holds funds in: a **CASH** account (petty
  cash, a till) or a **BANK** account (an account at a bank, per bank / per branch). Carries a **name**, a
  **type** (CASH | BANK), **bank details** (bank name, account number, bank branch — for a BANK account), a
  **currency** (= the company base currency in v1, BR-CASH-11), a **link to its GL account** (a `1xxx`
  asset account — BR-CASH-01), an **active/status** state, audit, and a **code** (e.g. `CB-####` via
  `code_sequence`). **Not** a GL account (a posting bucket) and **not** a party — a *money location* (gl.md
  word discipline: party / GL account / cash-bank account are three distinct things).
- **Cash book** — the set of CASH accounts (petty cash, tills) and their transactions.
- **Bank book** — the set of BANK accounts and their transactions.
- **Linked GL account** — the single GL `1xxx` asset account a cash/bank account maps to (BR-CASH-01). Every
  movement on the cash/bank account posts to this account; the account's book balance equals this GL
  account's balance (BR-CASH-02). This is what **replaces the single `gl_configs` `CASH`** with real named
  accounts.
- **Book balance** — a cash/bank account's balance **per our records**: the running sum of all its
  (non-void) transactions. Equals its linked GL account's balance (BR-CASH-02).
- **Statement balance / bank balance** — the **bank's** closing balance for an account as at a statement
  date, taken from the bank statement, entered by the operator during reconciliation. The figure the book
  balance must agree with to complete a reconciliation (BR-CASH-06).
- **Cleared / uncleared** — a bank/cash transaction is **uncleared** until it appears on (is confirmed
  against) the bank statement, then **cleared**. The operator marks transactions cleared during
  reconciliation; a cleared transaction that has been **reconciled** is immutable (BR-CASH-07).
- **(Bank) reconciliation** — the **manual** v1 act of agreeing an account's records to the bank statement:
  mark the cleared transactions, record a **statement date** + **statement closing balance**, and confirm
  **book balance == statement balance** before completing (BR-CASH-06). One reconciliation per account per
  statement period.
- **Inter-account transfer** — moving money **between two cash/bank accounts of the same company** (bank →
  petty cash, cash deposit → bank): posts **DR the destination account's GL account / CR the source
  account's GL account** for the transferred amount, balanced (BR-CASH-04). Numbered (e.g. `CBT-####`).
- **Direct cash/bank entry** — an ad-hoc receipt or payment **not** tied to AR/AP (bank charges, interest
  received, owner drawings, sundry cash expense): posts the cash/bank account's GL account against a
  **chosen GL counter-account** (an income/expense/equity account) — **DR/CR** the cash/bank GL account and
  the counter-account (BR-CASH-05). The treasury equivalent of a manual journal, but it moves a real money
  location.
- **Cheque register** — the record of cheques related to **bank-account payments**: each cheque carries a
  **cheque number**, a **status** (ISSUED | CLEARED | CANCELLED), an **issue date** and a **value date**
  (for a **post-dated** cheque the value date is later than the issue date), the bank account it draws on,
  and the payment it settles. **Cheque number is unique per bank account** (BR-CASH-12).
- **Post-dated cheque (PDC)** — a cheque whose **value date is later than its issue date** — written now,
  clears later. The register tracks it as ISSUED until it clears on/after its value date.
- **Default cash/bank account** — the **one** cash/bank account per company used when an AR receipt / AP
  payment does not specify which account the money lands in (BR-CASH-09). A company has at most one default.

> **Word discipline (carried into the glossary):** a **cash/bank account** (a money location, this module)
> is **not** a **GL account** (a posting bucket, gl.md) and **not** a **party** (a customer/supplier,
> Parties) — three distinct things; the cash/bank account *links to* exactly one GL account. **Book
> balance** (our records) is **not** **statement balance** (the bank's records) — the reconciliation makes
> them agree. A **cleared** transaction (confirmed on the bank statement) is **not** a **posted** journal
> entry (already on the books at the moment of the transaction) — every transaction posts to GL immediately;
> clearing is a *later* bank-statement confirmation. An **inter-account transfer** (money between two of our
> own accounts) is **not** an AR **receipt** (money from a customer) or an AP **payment** (money to a
> supplier). A **cheque** is an *instrument* in the register; the **payment** is the financial event it
> settles.

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-09). This is **Cash & Bank Increment 3
> (T1.4)**: named cash/bank accounts each linked to a GL account; inter-account transfers; direct
> cash/bank entries; a cheque register; per-account statement & balance; manual bank reconciliation; and the
> additive AR/AP touch (receipts/payments route to a chosen cash/bank account). Every cash/bank movement
> posts to GL synchronously and reconciles both to its linked GL account and (for banks) to the bank
> statement.

### In scope (v1 — "name the money locations, move money between them, settle AR/AP into them, reconcile to bank and to GL")

- **Named cash/bank accounts (the cash book + bank book).** Model **multiple** CASH accounts (petty cash,
  tills) and **multiple** BANK accounts (per bank / per branch) per company. A cash/bank account carries a
  **name**, a **type** (CASH | BANK), **bank details** (bank name, account number, bank branch — for BANK),
  a **currency** (= base in v1), a **link to its GL account** (a `1xxx` asset account), an **active/status**
  state, audit, and a **code** (`CB-####` via `code_sequence`). **Each account maps to its own GL account**
  — replacing the single `gl_configs` `CASH` with real named accounts (BR-CASH-01).
- **AR receipts and AP payments route to a chosen cash/bank account (the always-in core; additive AR/AP
  touch).** The AR record-receipt request and the AP pay (single + payment-run) request gain an **optional
  cash/bank account reference**; the receipt's debit / the payment's credit is **that account's linked GL
  account**. **Absent → the company default cash/bank account** (BR-CASH-09). An additive insertion into the
  AR/AP settlement paths (§3.6; flagged for the architect).
- **Inter-account transfers.** Move money between two cash/bank accounts of the **same company** (bank →
  petty cash, cash deposit → bank): a numbered transfer (`CBT-####`) posting **DR destination GL account /
  CR source GL account**, balanced (BR-CASH-04). Source ≠ destination; same company.
- **Direct cash/bank entries.** Record an ad-hoc receipt or payment **not** tied to AR/AP (bank charges,
  interest received, owner drawings, sundry cash expense): posts the cash/bank account's GL account against
  a **chosen GL counter-account**, **DR/CR** as appropriate (BR-CASH-05). Both legs balance.
- **Cheque management (cheque register).** A register of cheques related to bank-account payments: **cheque
  number**, **status** (ISSUED | CLEARED | CANCELLED), **issue date** + **value date** (post-dated cheques),
  the drawing bank account, and the payment settled. **Cheque number unique per bank account** (BR-CASH-12).
  **Cheque printing** is a presentation capability that **depends on the cross-cutting document/PDF
  capability (ROADMAP X.1)** — the register is in v1; **printing is flagged as dependent / deferred to X.1**
  (OQ-CASH-02).
- **Per-account statement & balance.** A per-cash/bank-account **running statement** (all transactions +
  running balance) and the **current balance** — a read/query, scoped to the company.
- **Manual bank reconciliation.** The operator marks the account's transactions **cleared** against the bank
  statement, records a **reconciliation** (statement date + statement closing balance), and the system
  confirms **book balance == statement balance** before the reconciliation can be **completed**
  (BR-CASH-06). A **cleared** flag on a transaction is **immutable once that transaction is part of a
  completed reconciliation** (BR-CASH-07). **NO statement file import** in v1 (CSV / MT940 deferred —
  OQ-CASH-01).
- **GL posting (synchronous).** Every cash/bank movement — a transfer, a direct entry, and the **cash legs
  of AR receipts / AP payments** — posts to GL **synchronously** via `GLPostingService.post` in the same
  transaction as the cash/bank write (the AR/AP precedent, ADR-0014 D-4). Append-only; corrections via
  reversing entries (BR-CASH-10). The cash/bank account balance reconciles to its linked GL account
  (BR-CASH-02).
- **Permissions** — `CASH.VIEW` (read accounts / balances / statements), `CASH.ACCOUNT.MANAGE` (create/edit/
  deactivate a cash/bank account + set its GL link + set the company default), `CASH.TRANSFER` (record an
  inter-account transfer), `CASH.ENTRY.RECORD` (record a direct cash/bank entry), `CASH.RECONCILE` (perform
  a bank reconciliation), `CHEQUE.MANAGE` (manage the cheque register — issue/clear/cancel); per-company
  scope (+ branch where relevant); `assertCanActIn` on **every read path**; audit on **every mutation**.
- **Numbering** via the generic `code_sequence`, per company: cash/bank account `CB-####`, transfer
  `CBT-####` (and a direct-entry / reconciliation / cheque series — the architect's `entity_kind`s) — the
  same concurrency-safe mechanism GL/AR/AP/Sales use.

### Deferred (recognised, NOT built in v1 — separate later increments)

- **Bank statement file import (CSV / MT940 / BAI2 / OFX).** v1 reconciliation is **manual** — the operator
  marks transactions cleared and enters the statement closing balance by hand. Importing a statement file
  and **auto-matching** lines is deferred (OQ-CASH-01). The manual reconciliation model is built so an
  importer feeds onto it later.
- **Online / API bank feeds (open-banking, direct bank integration).** Real-time balance/transaction feeds
  from the bank are deferred; v1 is manual-entry only.
- **Cheque printing (depends on the cross-cutting PDF capability, ROADMAP X.1).** The cheque **register** is
  in v1; **printing a cheque** (MICR layout, payee, amount-in-words) depends on the `DocumentService` / PDF
  template renderer (X.1) and is deferred to / gated on that capability (OQ-CASH-02). The register captures
  what a printer would need so printing is additive.
- **Multi-currency cash/bank accounts / FX.** v1 cash/bank accounts carry a **currency = the company base
  currency** (ADR-0005 D-4; BR-CASH-11). A **foreign-currency bank account** (a USD account at a TZ bank),
  multi-currency balances, and FX revaluation of foreign cash/bank balances at period close are deferred to
  the cross-cutting FX item (ROADMAP X.6 / gl.md §10.5, ADR-0005 D-8) — OQ-CASH-04.
- **Cash-flow statement / treasury forecasting / cash-position dashboard.** Reading the cash/bank balances
  into a cash-flow statement or a forward cash forecast is **Reporting** (T2.3); v1 delivers the per-account
  statement + balance reads they consume.
- **Deposit slips / batched lodgements (banking a batch of receipts as one deposit).** v1 routes each AR
  receipt to a chosen account directly; grouping several receipts into one bank deposit (a deposit batch
  that clears as one statement line) is a later additive convenience (OQ-CASH-05).
- **Payment-method richness on AR/AP (cash vs cheque vs transfer vs mobile money as a typed tender on the
  settlement).** v1 routes the AR/AP money to a chosen cash/bank account; a typed payment-method/instrument
  selection beyond the cash/bank-account choice + the cheque register is a later additive slice
  (accounts-payable.md OQ-AP-03).
- **Reconciliation reversal / un-reconcile workflow.** v1 completes a reconciliation when book == statement;
  unwinding a completed reconciliation (an un-reconcile to fix a mistake) is a later additive slice — v1
  corrects via a reversing entry / a new reconciliation (BR-CASH-10, OQ-CASH-06).

### Explicitly NOT this module

- **The General Ledger itself** — GL (ADR-0013) owns the books and the `1xxx` asset accounts a cash/bank
  account links to; Cash & Bank **posts** the cash movements to those GL accounts and **reconciles** to
  them. Cash & Bank never edits a posted journal; corrections are reversals (BR-CASH-10).
- **The AR / AP sub-ledgers** — AR (ADR-0014) and AP (ADR-0015) own the customer/supplier detail and the
  receipt/payment *events*; Cash & Bank owns the **money location** the receipt/payment lands in. The
  receipt/payment is recorded in AR/AP; Cash & Bank provides the account it settles into (the additive
  touch, §3.6). Cash & Bank does not own a receipt or a payable.
- **Parties** — a cash/bank account is **not** a party (a customer/supplier); it is a money location. The
  bank itself is captured as **bank details on the account**, not as a Parties Supplier, in v1.
- **Tax / VAT** — bank charges and interest are direct entries to a GL counter-account; any VAT treatment of
  a charge is out of v1 Cash & Bank scope (the VAT return is T1.5).
- **Financial statements & analytics** — the cash-flow statement, the cash-position dashboard, and treasury
  KPIs are Reporting (T2.3); Cash & Bank provides the per-account statement + balance they read.

## 3. The cash book + bank book: accounts, the GL link, transfers, entries, cheques, reconciliation

### 3.1 The cash/bank account (the money location linked to a GL account)

A cash/bank account is a **named money location** — a CASH account (petty cash, a till) or a BANK account
(an account at a bank). It carries name / type / bank details (for BANK) / currency (= base, v1) / a **link
to exactly one GL `1xxx` asset account** / active-status / audit / a `CB-####` code. The **link to a GL
account is mandatory and one-to-one in the posting sense** (BR-CASH-01): every movement on this cash/bank
account posts to *that* GL account, and the account's **book balance equals that GL account's balance**
(BR-CASH-02). Creating a cash/bank account is the act that turns the single `gl_configs` `CASH` bridge into
a real named account; a company may keep several. One cash/bank account may be flagged the **company default**
(BR-CASH-09), used when an AR receipt / AP payment does not specify a target account.

A **CASH** account (a till / petty cash) **may be branch-scoped** (a till belongs to a branch); a **BANK**
account is typically **company-level** (banks are not per till). v1 keeps the books at company level (gl.md
NFR-GL-01); a cash/bank account is company-scoped and **may carry a branch** as scope/analysis where it
belongs to one branch (a till) — the petty-cash/till branch-scoping default is OQ-CASH-03.

### 3.2 AR receipt / AP payment → lands in the chosen account → posts to GL (the always-in core)

The money AR/AP settle now lands in a **named** account:

- **AR receipt.** When AR records a receipt (accounts-receivable.md §3.3), the request **may name a
  cash/bank account**; the receipt's GL debit is **that account's linked GL account** (not the bare
  `gl_configs` `CASH`), and the cash/bank account's book balance rises by the receipt amount. **Absent a
  named account → the company default cash/bank account** (BR-CASH-09). The GL credit is still **`1200
  Accounts Receivable`** (AR's control). AR posts this leg synchronously today (ADR-0014 D-4); Cash & Bank
  only changes *which* GL account the debit resolves to (the chosen account's linked GL account) and records
  the transaction on the cash/bank account.
- **AP payment.** When AP pays (single or payment run, accounts-payable.md §3.3), the request **may name a
  cash/bank account**; the payment's GL credit is **that account's linked GL account**, and the cash/bank
  account's book balance falls by the paid amount. **Absent → the company default** (BR-CASH-09). The GL
  debit is still **`2100 Accounts Payable`** (AP's control).

This is an **additive cross-module touch** to the AR record-receipt and AP pay requests (an optional
cash/bank account reference; default if unspecified) — the same shape as the AR credit-limit additive touch
to Sales. The dependency direction is AR→CashBank / AP→CashBank (§3.6; flagged for the architect).

### 3.3 Inter-account transfer (money between our own accounts)

Move money between two cash/bank accounts of the **same company** (`CASH.TRANSFER`): pick a **source** and a
**destination** account (source ≠ destination), an amount, a date, and a reference; the system records a
numbered transfer (`CBT-####`) and posts **DR the destination account's GL account / CR the source
account's GL account** for the amount — **balanced by construction** (BR-CASH-04). The source book balance
falls and the destination book balance rises by the same amount; both reconcile to their linked GL accounts.
A transfer is **same-company only** (cross-company transfers are out of scope).

### 3.4 Direct cash/bank entry (a receipt/payment not tied to AR/AP)

Record an ad-hoc money movement **not** linked to a customer receipt or a supplier payment
(`CASH.ENTRY.RECORD`): bank charges, interest received, owner drawings, a sundry cash expense. The operator
picks the cash/bank account, the direction (money in / out), an amount, a date, a reference, and a **GL
counter-account** (the income / expense / equity account the other leg hits). The system posts the
balanced double-entry — e.g. a bank charge is **DR `bank charges expense` (the counter-account) / CR the
bank account's GL account**; interest received is **DR the bank account's GL account / CR `interest income`**
(BR-CASH-05). The cash/bank account's book balance moves by the amount; both legs balance and reconcile to
their GL accounts.

### 3.5 Cheque register (issue → clear / cancel)

Cheques relate to **bank-account payments**. The cheque register records, per cheque: a **cheque number**
(unique per bank account — BR-CASH-12), the **bank account** it draws on, the **payment** it settles, an
**issue date** and a **value date** (a **post-dated cheque** has value date > issue date), and a **status**
that moves **ISSUED → CLEARED** (when the bank honours it, on/after the value date) **or ISSUED → CANCELLED**
(a stopped / spoiled cheque). The GL effect rides the **payment** the cheque settles (the AP payment / direct
entry posts the money movement); the register tracks the **instrument's lifecycle** (CHEQUE.MANAGE). A
cancelled cheque whose payment must be undone is corrected via a **reversing entry** on the payment
(append-only — BR-CASH-10), not by editing a posted entry. **Cheque printing depends on the cross-cutting
PDF capability (X.1)** and is flagged/deferred to it (OQ-CASH-02).

### 3.6 The additive AR/AP touch (flagged for the architect)

The AR record-receipt and AP pay paths gain an **optional cash/bank account reference**; the receipt's
debit / the payment's credit resolves to **the chosen account's linked GL account**, defaulting to the
**company default cash/bank account** if unspecified (BR-CASH-09). This is a **small additive insertion**
into the existing AR/AP settlement commands — the same kind of additive change the AR credit-limit check was
to the Sales finalise path (accounts-receivable.md §3.5) and `products.vat_status` was to Sales.

> **Flag for the architect (ADR-0016):** the touch makes **AR and AP resolve the cash/bank account's GL
> account through Cash & Bank** (a new AR→CashBank / AP→CashBank read), replacing the bare `gl_configs`
> `CASH` lookup the simple bridge used. Whether AR/AP call a `CashBankAccountResolver` (companyId,
> cashBankAccountRef?) → linked GL account (defaulting to the company default), or Cash & Bank exposes the
> chosen account + its GL account as a DTO the AR/AP posting reads, is an ADR decision. Keep the direction
> AR→CashBank / AP→CashBank (Cash & Bank must not import AR/AP entities — the module-boundary discipline,
> gl.md NFR-GL-07). Also decide how the existing `gl_configs` `CASH` mapping relates to the new default
> cash/bank account (recommended: the company default cash/bank account *is* the resolution of `CASH`, so
> AR/AP unchanged-callers keep working) — OQ-CASH-07.

## 4. Actors / personas

- **Cashier / teller** — records **direct cash entries** (petty-cash payments, till floats) and operates a
  CASH account (till / petty cash); may record AR receipts into a cash/bank account (with AR's permission).
  Holds `CASH.VIEW`, `CASH.ENTRY.RECORD` (and `CASH.TRANSFER` for till → safe moves).
- **Accountant / bookkeeper** — records **inter-account transfers** and **direct bank entries** (bank
  charges, interest), reads per-account statements, and reconciles the cash/bank account to its linked GL
  account. Holds `CASH.VIEW`, `CASH.TRANSFER`, `CASH.ENTRY.RECORD` (and often `CASH.RECONCILE`).
- **Finance controller / treasurer** — owns the cash/bank account master (`CASH.ACCOUNT.MANAGE` — creates
  accounts, sets the GL link, sets the company default), performs **bank reconciliations**
  (`CASH.RECONCILE`), manages the **cheque register** (`CHEQUE.MANAGE`), and reviews the cash position. The
  senior treasury authority on the deployment.
- **AR cashier / AP payments officer (at settlement)** — not a Cash & Bank persona per se, but the operators
  whose AR receipt / AP payment now **chooses the cash/bank account** the money lands in (§3.2, the additive
  touch). They settle in AR/AP; Cash & Bank provides the target account.
- *(No SYSTEM auto-poster on the Cash & Bank side — every cash/bank movement is an in-request user action
  that posts synchronously, unlike GL's outbox-driven sales auto-poster. The AR open-item creator and GL's
  sales poster remain the only SYSTEM actors upstream.)*

## 5. Functional requirements

> IDs are `FR-CASH-NN`. Each is a crisp, testable, **ratified** statement. "Post to GL" = a synchronous
> `GLPostingService.post` in the same transaction as the cash/bank write (the AR/AP precedent, ADR-0014 D-4;
> mechanism confirmed for the architect, §1 flag). "Linked GL account" = the single GL `1xxx` asset account
> a cash/bank account maps to.

### Cash/bank accounts (the money locations)

- **FR-CASH-01** A user with `CASH.ACCOUNT.MANAGE` may **create a cash/bank account** per company: a
  **name**, a **type** (CASH | BANK), **bank details** (bank name, account number, bank branch — required
  for BANK, absent for CASH), a **currency** (= the company base currency, v1 — BR-CASH-11), a **link to one
  GL `1xxx` asset account** (mandatory — BR-CASH-01), an **active/status** state, and a **code** (`CB-####`
  from `code_sequence`, per company). A CASH account **may carry a branch** (a till / petty cash belongs to
  a branch — OQ-CASH-03); a BANK account is company-level.
- **FR-CASH-02** A user with `CASH.ACCOUNT.MANAGE` may **edit** a cash/bank account's name / bank details /
  active state and may **deactivate** it. A deactivated account is **excluded from new transactions**
  (transfers, entries, AR/AP routing) but **stays on its historical transactions and statement**. An account
  that **has transactions cannot be deleted** (it is deactivated instead) — mirroring the GL
  account-with-postings rule (BR-CASH-13, gl.md BR-GL-07).
- **FR-CASH-03** Each cash/bank account **maps to exactly one GL `1xxx` asset account** (its linked GL
  account); this **replaces the single `gl_configs` `CASH` account** with real named accounts (BR-CASH-01).
  Two cash/bank accounts **should not** share a linked GL account (so each account's balance reconciles
  cleanly to a distinct GL account — recommended one-to-one; OQ-CASH-08).
- **FR-CASH-04** A company may flag **one** cash/bank account as the **company default** (`CASH.ACCOUNT.MANAGE`),
  used when an AR receipt / AP payment does not name a target account (FR-CASH-12, BR-CASH-09). At most one
  default per company.

### AR receipt / AP payment routing (the additive core)

- **FR-CASH-05** The AR **record-receipt** request **gains an optional cash/bank account reference**: the
  receipt's GL **debit** is **the chosen account's linked GL account** (the GL **credit** remains `1200
  Accounts Receivable`), and the chosen cash/bank account's **book balance rises** by the receipt amount. If
  **no account is named**, the **company default cash/bank account** is used (BR-CASH-09). This is an
  **additive touch** to the AR receipt path (§3.6; flagged for ADR-0016).
- **FR-CASH-06** The AP **pay** request (single payment + payment run) **gains an optional cash/bank account
  reference**: the payment's GL **credit** is **the chosen account's linked GL account** (the GL **debit**
  remains `2100 Accounts Payable`), and the chosen cash/bank account's **book balance falls** by the paid
  amount. If **no account is named**, the **company default cash/bank account** is used (BR-CASH-09). This is
  an **additive touch** to the AP pay path (§3.6; flagged for ADR-0016).
- **FR-CASH-07** A receipt / payment may only target an **active** cash/bank account (BR-CASH-08); if a named
  account is inactive or the company has **no default** when none is named, the settlement **fails with a
  clear message** rather than posting to a wrong/null account (mirrors gl.md BR-GL-10).

### Inter-account transfers

- **FR-CASH-08** A user with `CASH.TRANSFER` may **record an inter-account transfer** (`CBT-####` from
  `code_sequence`): a **source** and a **destination** cash/bank account of the **same company** (source ≠
  destination — BR-CASH-04), an amount, a date, and a reference. The transfer **posts to GL**: **DR the
  destination account's GL account / CR the source account's GL account** for the amount, **balanced**
  (BR-CASH-04). The source book balance falls and the destination book balance rises by the same amount.

### Direct cash/bank entries

- **FR-CASH-09** A user with `CASH.ENTRY.RECORD` may **record a direct cash/bank entry** not tied to AR/AP:
  a cash/bank account, a direction (money in / out), an amount, a date, a reference, and a **GL
  counter-account** (an income / expense / equity account). The entry **posts the balanced double-entry** —
  the cash/bank account's GL account on one side, the counter-account on the other (DR/CR per direction —
  BR-CASH-05). The cash/bank account's book balance moves by the amount.

### Cheque register

- **FR-CASH-10** A user with `CHEQUE.MANAGE` may **register a cheque** against a bank-account payment: a
  **cheque number** (unique per bank account — BR-CASH-12), the drawing **bank account**, the **payment** it
  settles, an **issue date**, a **value date** (value date > issue date for a **post-dated** cheque), and a
  status of **ISSUED**. The cheque's GL effect rides the **payment** it settles (FR-CASH-06 / FR-CASH-09);
  the register tracks the instrument lifecycle.
- **FR-CASH-11** A user with `CHEQUE.MANAGE` may move a cheque **ISSUED → CLEARED** (the bank honoured it,
  on/after the value date) or **ISSUED → CANCELLED** (stopped / spoiled). A CLEARED or CANCELLED cheque is a
  terminal state. Where cancelling a cheque must undo its payment, the **payment is reversed via a reversing
  entry** (append-only — BR-CASH-10); the register is not edited in place. **Cheque printing depends on the
  cross-cutting PDF capability (X.1)** and is deferred to it (OQ-CASH-02).

### Per-account statement & balance (reads)

- **FR-CASH-12** A user with `CASH.VIEW` may read a cash/bank account's **current balance** (its book
  balance) and a **running statement** — every (non-void) transaction (receipts, payments, transfers,
  direct entries) in date order with a **running balance** — scoped to their company. No read crosses
  company scope (BR-CASH-08, NFR-CASH-01).

### Manual bank reconciliation

- **FR-CASH-13** A user with `CASH.RECONCILE` may **perform a manual bank reconciliation** of a bank account:
  **mark** the account's transactions as **CLEARED** against the bank statement, record a **statement date**
  and a **statement closing balance**, and the system computes the **book balance of cleared transactions**.
- **FR-CASH-14** The system **only allows a reconciliation to be COMPLETED when the book balance agrees with
  the statement closing balance** (book == statement — BR-CASH-06). A reconciliation that does not agree is
  **left open** (in progress) for the operator to resolve (mark/un-mark transactions, find the discrepancy);
  it cannot be completed while out of balance.
- **FR-CASH-15** Once a transaction is part of a **completed** reconciliation, its **cleared flag is
  immutable** (BR-CASH-07) — a reconciled transaction cannot be silently un-cleared or edited; a correction
  is a reversing entry / a new reconciliation (BR-CASH-10, OQ-CASH-06). **No statement file import** in v1
  (manual marking only — OQ-CASH-01).

### GL posting & reconciliation to GL

- **FR-CASH-16** Every cash/bank movement **posts to GL synchronously** via `GLPostingService.post` in the
  same transaction as the cash/bank write: an **inter-account transfer** (DR destination GL / CR source GL),
  a **direct entry** (cash/bank GL ↔ counter-account), and the **cash legs of AR receipts / AP payments**
  (the chosen account's linked GL account). Every posting obeys the GL invariants — balanced, an OPEN period,
  active accounts (gl.md BR-GL-01/03/04); a movement whose posting date falls in a **closed period** is
  handled per the GL closed-period policy (gl.md OQ-GL-01); a missing/inactive linked-GL or counter account
  **fails the operation** rather than mis-posting (gl.md BR-GL-10).
- **FR-CASH-17** The system maintains the **GL reconciliation invariant**: a cash/bank account's **book
  balance equals its linked GL account's balance** at all times (BR-CASH-02, NFR-CASH-01). A reconciliation
  read (book balance vs linked GL account balance) is available to finance; a discrepancy is a finance-grade
  defect.

### Scope & permissions

- **FR-CASH-18** Cash & Bank is **scoped per company**; every cash/bank account, transaction, transfer,
  direct entry, cheque, and reconciliation belongs to exactly one company; no read or balance crosses
  company scope. `assertCanActIn` guards **every read path** (BR-CASH-08, NFR-CASH-01). A branch-scoped CASH
  account (a till) carries its branch; the books stay at company level (gl.md NFR-GL-01).
- **FR-CASH-19** All Cash & Bank operations are **gated by IAM permissions**: `CASH.VIEW`,
  `CASH.ACCOUNT.MANAGE`, `CASH.TRANSFER`, `CASH.ENTRY.RECORD`, `CASH.RECONCILE`, `CHEQUE.MANAGE`. Exact codes
  are seeded with the module (FR-IAM-11). Per-company scope; **audit on every mutation** (NFR-CASH-03);
  `ScopeGuard` gains a new **`cashbankaccount`** target type (and the cheque / reconciliation targets the
  architect names) — a note for ADR-0016.

## 6. Business rules (invariants)

> Ratified. These are the Cash & Bank invariants; a violation that breaks a reconciliation (to GL or to the
> bank statement) is a finance-grade defect (a release blocker).

- **BR-CASH-01 — A cash/bank account maps to exactly one GL account.** Every cash/bank account links to a
  single GL `1xxx` asset account; this **replaces the single `gl_configs` `CASH`** with real named accounts.
  The link is mandatory and set at create (FR-CASH-01/03). Every movement on the account posts to that GL
  account.
- **BR-CASH-02 — Book balance reconciles to the linked GL account.** A cash/bank account's **book balance**
  (the running sum of its non-void transactions) **equals its linked GL account's balance at all times**
  (the sub-ledger ⇄ control pattern, mirroring AR ⇄ `1200` / AP ⇄ `2100`). Because every movement posts
  synchronously to that GL account, they move together by construction. A drift is a **release blocker**
  (FR-CASH-17, NFR-CASH-01).
- **BR-CASH-03 — Every cash/bank movement posts to GL synchronously.** A transfer, a direct entry, and the
  cash legs of AR receipts / AP payments each post a balanced journal via `GLPostingService.post` in the
  **same transaction** as the cash/bank write (the AR/AP precedent, ADR-0014 D-4) — never a deferred or
  outbox post; there is no in-flight gap between the cash movement and the books (FR-CASH-16).
- **BR-CASH-04 — A transfer is balanced and same-company.** An inter-account transfer posts **DR the
  destination account's GL account / CR the source account's GL account** for the same amount (balanced by
  construction); source ≠ destination; both accounts belong to the **same company** (FR-CASH-08).
- **BR-CASH-05 — A direct entry is balanced against a chosen counter-account.** A direct cash/bank entry
  posts the cash/bank account's GL account against a **chosen GL counter-account** (income / expense /
  equity), the two legs equal and opposite (FR-CASH-09).
- **BR-CASH-06 — Reconciliation requires book == statement to complete.** A bank reconciliation can be
  **completed only when** the **book balance of cleared transactions equals the entered statement closing
  balance** (FR-CASH-13/14). An out-of-balance reconciliation stays open; it never completes while book ≠
  statement.
- **BR-CASH-07 — A cleared flag is immutable once reconciled.** Once a transaction is part of a **completed**
  reconciliation, its **cleared** flag cannot be silently changed and the transaction cannot be edited
  (FR-CASH-15); a correction is a reversing entry / a new reconciliation (BR-CASH-10).
- **BR-CASH-08 — One company's cash/bank is isolated; only active accounts take new transactions.** Every
  cash/bank account, transaction, transfer, entry, cheque, and reconciliation **belongs to exactly one
  company**; no read or balance crosses company scope (cross-company leakage is a release blocker,
  NFR-CASH-01). A **new** transaction may target only an **active** cash/bank account (FR-CASH-07); a
  deactivated account keeps its history but takes no new movement.
- **BR-CASH-09 — AR receipt / AP payment targets an active cash/bank account; default if unspecified.** A
  receipt's debit / a payment's credit resolves to the **chosen** cash/bank account's linked GL account;
  **absent a chosen account, the company default cash/bank account is used** (FR-CASH-05/06). If no account
  is named and there is no company default — or the named account is inactive — the settlement **fails**
  rather than mis-posting (FR-CASH-07, mirrors gl.md BR-GL-10).
- **BR-CASH-10 — Append-only; correct via a reversing entry, not an edit.** A posted cash/bank movement (a
  transfer, a direct entry, the cash leg of a receipt/payment) is **never edited or deleted**; a correction
  is a **reversing entry** (mirroring the GL append-only rule, PROJECT-CONVENTIONS §3.6, gl.md BR-GL-02). A
  cancelled cheque whose payment must be undone reverses the payment (FR-CASH-11). Cash & Bank keeps a full
  history.
- **BR-CASH-11 — Base-currency reconciliation (v1).** A cash/bank account's currency is the **company base
  currency** (TZS in practice; ADR-0005 D-4); book balances, the linked GL account, and every movement are
  in base currency. **Multi-currency / foreign-currency bank accounts and FX revaluation are deferred** to
  the cross-cutting FX item (X.6 / gl.md §10.5) — OQ-CASH-04.
- **BR-CASH-12 — Cheque number unique per bank account.** Within a bank account, a **cheque number is
  unique** (no two register entries share a cheque number on the same bank account) — FR-CASH-10.
- **BR-CASH-13 — A cash/bank account with transactions cannot be deleted.** Such an account may only be
  **deactivated** (FR-CASH-02), protecting its history and the audit trail — mirroring the GL
  account-with-postings rule (gl.md BR-GL-07).

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Open a cash/bank account mapped to a GL account — happy path
1. A treasurer (`CASH.ACCOUNT.MANAGE`, active company) **creates a cash/bank account**: name, type (CASH |
   BANK), bank details (for BANK), currency (= base), and a **link to a GL `1xxx` asset account** (FR-CASH-01).
2. The system assigns a `CB-####` code, sets the account ACTIVE, and (optionally) flags it the **company
   default** (FR-CASH-04). The create is **audited** (NFR-CASH-03).
3. The account now appears as a target for AR receipts / AP payments, transfers, and direct entries; its
   **book balance starts at zero** and equals its (zero) linked GL account balance (BR-CASH-02).

### 7.2 AR receipt lands in a chosen account → posts to GL — happy path
1. An AR cashier records a customer **receipt** (accounts-receivable.md §3.3) and **chooses a cash/bank
   account** (e.g. "Main Bank — CRDB") in the receipt request (FR-CASH-05).
2. The receipt posts **DR the chosen account's linked GL account / CR `1200 Accounts Receivable`**
   synchronously (FR-CASH-16); the chosen cash/bank account's **book balance rises** by the receipt amount.
3. If the operator **does not choose** an account, the **company default cash/bank account** is used
   (BR-CASH-09). The reconciliation invariant holds: the account's book balance == its linked GL account
   balance (FR-CASH-17).

### 7.3 Inter-account transfer (bank → petty cash) — happy path
1. An accountant (`CASH.TRANSFER`) records a transfer (`CBT-####`): **source** = Main Bank, **destination** =
   Petty Cash, an amount, a date, a reference (FR-CASH-08).
2. The system validates source ≠ destination and **same company** (BR-CASH-04), then posts **DR Petty Cash's
   GL account / CR Main Bank's GL account** for the amount, balanced.
3. Main Bank's book balance falls and Petty Cash's rises by the same amount; both reconcile to their GL
   accounts (BR-CASH-02). The transfer is **audited** (NFR-CASH-03).

### 7.4 Direct bank-charge entry — happy path
1. An accountant (`CASH.ENTRY.RECORD`) records a **direct entry**: account = Main Bank, direction = money
   out, amount, date, reference, **counter-account** = `bank charges expense` (FR-CASH-09).
2. The system posts **DR bank charges expense / CR Main Bank's GL account** for the amount, balanced
   (BR-CASH-05); Main Bank's book balance falls by the charge.
3. The entry is **audited**; book balance == linked GL account balance (FR-CASH-17).

### 7.5 Manual bank reconciliation (mark cleared + balance check) — happy path
1. A treasurer (`CASH.RECONCILE`) opens a **reconciliation** of Main Bank against a bank statement: enters
   the **statement date** and the **statement closing balance** (FR-CASH-13).
2. The treasurer **marks** the account's transactions that appear on the statement as **CLEARED**; the system
   computes the **book balance of cleared transactions**.
3. When **book balance == statement closing balance**, the treasurer **completes** the reconciliation
   (FR-CASH-14, BR-CASH-06); the cleared transactions' cleared flags are now **immutable** (FR-CASH-15,
   BR-CASH-07). The reconciliation is **audited**.

### 7.6 Issue → clear a cheque — happy path
1. A treasurer (`CHEQUE.MANAGE`) **registers a cheque** against an AP payment / direct entry: cheque number
   (unique per bank account — BR-CASH-12), the drawing bank account, issue date, value date (later than
   issue date for a post-dated cheque), status **ISSUED** (FR-CASH-10).
2. The cheque's money movement rode the **payment** it settles (which posted to GL — FR-CASH-06/09).
3. When the bank honours the cheque (on/after the value date), the treasurer moves it **ISSUED → CLEARED**
   (FR-CASH-11); the clearing is **audited**. (A stopped cheque goes **ISSUED → CANCELLED**; if its payment
   must be undone, the payment is reversed — BR-CASH-10.)

### 7.7 Main unhappy paths
- **AR receipt / AP payment names an inactive account, or none is named and there is no company default**
  (7.2) → the settlement **fails** with a clear message; the operator picks an active account or finance
  sets a default (FR-CASH-07, BR-CASH-09) — no post to a wrong/null account.
- **Transfer source == destination, or accounts in different companies** (7.3) → **rejected** (BR-CASH-04).
- **Movement would post into a CLOSED period** (any) → handled per the GL closed-period policy (gl.md
  OQ-GL-01); finance reopens / moves the date (FR-CASH-16).
- **Missing/inactive linked GL account or counter-account** (any) → the operation **fails** rather than
  mis-posting; finance fixes the mapping (`CASH.ACCOUNT.MANAGE` / `GL.MANAGE`), then retries (FR-CASH-16,
  gl.md BR-GL-10).
- **Reconciliation where book ≠ statement** (7.5) → **cannot be completed**; it stays open until the
  operator resolves the discrepancy (mark/un-mark transactions, find the missing/duplicate line)
  (FR-CASH-14, BR-CASH-06).
- **Attempt to un-clear / edit a transaction in a completed reconciliation** (any) → **refused**; correct via
  a reversing entry / a new reconciliation (FR-CASH-15, BR-CASH-07/10).
- **Duplicate cheque number on the same bank account** (7.6) → **rejected** (BR-CASH-12).
- **Attempt to delete a cash/bank account that has transactions** (any) → **refused**; deactivate it instead
  (FR-CASH-02, BR-CASH-13).
- **Attempt to edit a posted transfer / direct entry / receipt-payment cash leg** (any) → **refused**;
  correct via a reversing entry (BR-CASH-10).
- **Book balance ≠ linked GL account balance** (reconciliation read) → a **finance-grade defect** surfaced
  for investigation (FR-CASH-17, NFR-CASH-01).

## 8. Non-functional

- **NFR-CASH-01 — Reconciliation integrity (to GL and to the statement) & tenant isolation.** A cash/bank
  account's **book balance must equal its linked GL account's balance** for every company at all times
  (BR-CASH-02); a drift is a **release blocker**. A **completed** bank reconciliation must have had **book ==
  statement** (BR-CASH-06). Every Cash & Bank row is scoped by `company_id` through the tenant-predicate
  repository base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS §3.2); `assertCanActIn` guards **every read
  path**. Cross-company Cash & Bank leakage is a **release blocker**, as for GL/AR/AP.
- **NFR-CASH-02 — Money correctness.** Every amount is a `Money` (amount + currency, ADR-0005) in the
  company base currency; transfer legs, direct-entry legs, and the AR/AP cash legs sum **exactly** (no float,
  `BigDecimal` compare, rounding per ADR-0005 D-2 / gl.md NFR-GL-02, OQ-CUR-03). A movement whose two GL legs
  do not balance is a defect; a reconciliation that completes with book ≠ statement is a defect.
- **NFR-CASH-03 — Audit.** Every **mutation** — account create/edit/deactivate + default-flag, transfer,
  direct entry, AR/AP cash-account routing, cheque issue/clear/cancel, mark-cleared, and reconciliation
  completion — is written to the IAM append-only audit trail with actor, action, target, timestamp, and
  company/branch context (mirrors NFR-GL-06).
- **NFR-CASH-04 — Synchronous-posting atomicity.** Every cash/bank movement and its GL posting commit in
  **one transaction** (BR-CASH-03, the AR/AP precedent, ADR-0014 D-4): the cash/bank book balance and the
  linked GL account move together or not at all — there is no eventual-consistency gap. The mechanism is the
  architect's; the requirement is atomicity.
- **NFR-CASH-05 — Numbering concurrency.** Two operators recording transfers / opening accounts /
  registering cheques simultaneously get distinct `CBT-####` / `CB-####` / cheque-series numbers (the
  `code_sequence` row-locked allocation — ADR-0007 D-6).
- **NFR-CASH-06 — DTO-only consumption / boundary direction.** Cash & Bank posts to GL through the
  `GLPostingService` / `GLConfigResolver` boundary (DTO + service-interface, never a GL entity beyond the
  posting contract — the AR/AP precedent). The **AR/AP additive touch** keeps the direction AR→CashBank /
  AP→CashBank: AR/AP read Cash & Bank's chosen-account → linked-GL-account resolution as a DTO/service call;
  Cash & Bank **never imports an AR/AP/Sales entity** (gl.md NFR-GL-07; `ModuleBoundaryTest`).
- **NFR-CASH-07 — Timestamps.** UTC, displayed per company time zone (Africa/Dar_es_Salaam default, iam.md
  locale). A transaction's **value date** and a cheque's **issue/value date** are business dates, distinct
  from the posting timestamp; a reconciliation's **statement date** is a business date.
- **NFR-CASH-08 — Forward-compatibility.** The v1 model must not preclude the later increments that build on
  Cash & Bank: **statement file import** + auto-matching feeding the manual reconciliation (OQ-CASH-01);
  **online/API bank feeds**; **cheque printing** via the cross-cutting PDF capability (X.1, OQ-CASH-02);
  **multi-currency / foreign-currency bank accounts + FX revaluation** (X.6 / gl.md §10.5, OQ-CASH-04);
  **deposit slips / batched lodgements** (OQ-CASH-05); the **cash-flow statement / cash-position dashboard**
  (Reporting, T2.3); and a **reconciliation reversal / un-reconcile** workflow (OQ-CASH-06). Building these
  is deferred; precluding them is a defect.

## 9. Assumptions

- The dependency platform exists and is consumed as designed: **GL** (ADR-0013 / V10 — `chart_of_accounts`
  with the seeded `1xxx` asset accounts, the synchronous `GLPostingService.post`, `gl_configs` incl. `CASH`,
  `fiscal_periods` + `FiscalPeriodResolver`, `GLConfigResolver`, the `ScopeGuard` extension pattern) is
  shipped; **AR** (ADR-0014 / V11) posts a receipt **DR Cash/Bank / CR `1200`** synchronously today on the
  single `CASH` account; **AP** (ADR-0015 / V12) posts a payment **DR `2100` / CR Cash/Bank** synchronously
  on the same single account; **Money** (ADR-0005) and `code_sequence` are in place. All shipped.
- The **single `gl_configs` `CASH` account is the bridge being replaced.** Today AR/AP post the cash leg to
  that one account (gl.md §10.3); Cash & Bank introduces named accounts each with their own linked GL
  account. How the existing `CASH` mapping relates to the new **company default cash/bank account** is the
  architect's (recommended: the company default cash/bank account *is* the resolution of `CASH`, so
  unchanged AR/AP callers keep working — OQ-CASH-07).
- **The GL-posting mechanism is synchronous `GLPostingService.post` in the same TX** — the established AR/AP
  precedent (ADR-0014 D-4): a cash/bank movement is a single in-request user action (one aggregate, one
  operator, one click), not a cross-aggregate async effect, so it does **not** ride the outbox. The
  requirement fixes the *synchronous, atomic* posting; the exact wiring is the architect's (ADR-0016).
- **Document currency = company base (TZS)** in practice for v1; cash/bank accounts carry the base currency
  (BR-CASH-11). The convert-at-entry shape supports a foreign-currency source but foreign-currency bank
  accounts + FX depth are deferred (OQ-CASH-04).
- **The GL `1xxx` asset accounts a cash/bank account links to exist** in the seeded TZ CoA (Cash, Bank, …)
  or are added via `GL.MANAGE`; the exact accounts/links are the operator's setup, the architect fixes only
  that the link is mandatory and one GL account per cash/bank account (BR-CASH-01).
- **Bank details (bank name, account number, bank branch)** are captured **on the cash/bank account**, not
  as a Parties record, in v1; modelling the bank as a party is out of scope.

## 10. ACCEPTED RISK & accepted scope boundary — what Cash & Bank v1 deliberately does NOT do (owner-accepted 2026-06-09)

> **Read this before building or consuming Cash & Bank.** Cash & Bank v1 delivers **named cash/bank
> accounts** (each linked to a GL `1xxx` account), **inter-account transfers**, **direct cash/bank entries**,
> a **cheque register**, **per-account statement & balance**, **manual bank reconciliation**, and the
> **additive AR/AP touch** (receipts/payments route to a chosen cash/bank account) — reconciling to both the
> linked GL account and the bank statement. The following are **deliberate boundaries**, owner-accepted.

1. **Bank reconciliation is MANUAL in v1 — no statement file import.** The operator marks transactions
   cleared and enters the statement closing balance by hand; the system enforces **book == statement to
   complete** (BR-CASH-06). Importing a CSV / MT940 / BAI2 / OFX statement and **auto-matching** lines is
   **deferred** (OQ-CASH-01). The manual model is built so an importer feeds onto it later (NFR-CASH-08).

2. **Cheque printing depends on the cross-cutting PDF capability (X.1).** The cheque **register** (number,
   status, post-dated value date, the payment it settles) is in v1; **printing a cheque** depends on the
   `DocumentService` / PDF renderer (ROADMAP X.1) and is **deferred to / gated on** it (OQ-CASH-02). The
   register captures what a printer needs so printing is additive.

3. **Multi-currency / foreign-currency bank accounts + FX are DEFERRED.** v1 cash/bank accounts carry the
   **company base currency** (BR-CASH-11); a foreign-currency bank account, multi-currency balances, and FX
   revaluation of foreign cash/bank balances at period close are the cross-cutting FX item (X.6 / gl.md
   §10.5, ADR-0005 D-8) — OQ-CASH-04. Not precluded by the v1 model (NFR-CASH-08).

4. **The AR/AP cash leg is the only change to AR/AP — it is an additive touch, not a rebuild.** Cash & Bank
   replaces *which GL account* the receipt debit / payment credit resolves to (the chosen account's linked
   GL account, default if unspecified — BR-CASH-09); it does **not** re-own the receipt or the payable (AR/AP
   keep those). The receipt/payment still posts synchronously as AR/AP already do (ADR-0014 D-4).

5. **No cash-flow statement / cash-position dashboard / treasury forecast in v1.** Those are Reporting
   (T2.3); v1 delivers the per-account statement + balance reads they consume.

6. **No deposit slips / batched lodgements, no typed payment-method tender beyond the account choice + cheque
   register, no un-reconcile workflow.** Each is a later additive slice (OQ-CASH-05 / accounts-payable.md
   OQ-AP-03 / OQ-CASH-06); v1 corrects via reversing entries.

All are additive by design (NFR-CASH-08); none is precluded by the v1 model.

## 11. Open questions — status after ratification (2026-06-09)

> The **Cash & Bank scoping forks** the owner answered (multiple named cash/bank accounts each linked to a GL
> account; manual bank reconciliation with the book==statement completion check, no file import; the four v1
> operations + the AR/AP routing touch; synchronous GL posting; the scope/permission set) are **RESOLVED**
> (recorded in `docs/requirements/open-questions.md` under Cash & Bank). **No ADR-0016-blocking open question
> remains.** What stays open is **non-blocking** detail with a recommended default that stands unless the
> owner overrides — confirm during build / before go-live, not before ADR-0016.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0016)

- **OQ-CASH-01 — Bank statement file import format.** v1 reconciliation is **manual**; importing a statement
  file (CSV / MT940 / BAI2 / OFX) and auto-matching is deferred. *Recommended default:* manual marking + a
  hand-entered statement closing balance in v1; a **CSV** importer first (the simplest, most universal) when
  the import slice is prioritised, feeding the same manual-reconciliation model. *Decider:* owner (finance).
  *Blocks ADR-0016:* **NO** — the manual reconciliation model is fixed; an importer is additive (NFR-CASH-08).
- **OQ-CASH-02 — Cheque printing dependency.** The cheque **register** is in v1; **printing** depends on the
  cross-cutting PDF capability (X.1). *Recommended default:* ship the register in v1; printing lands with /
  after X.1 (`DocumentService`), reusing the register data. *Decider:* owner + architect (with X.1). *Blocks
  ADR-0016:* **NO** — the register data is fixed; printing is an additive consumer of it.
- **OQ-CASH-03 — Petty-cash / till branch-scoping default.** A CASH account (till / petty cash) **may** be
  branch-scoped; a BANK account is company-level. *Recommended default:* a CASH account **may carry a
  branch** (nullable — a company-level petty cash carries none; a till carries its branch); a BANK account
  carries no branch (company-level). The books stay company-level (gl.md NFR-GL-01); branch is scope/analysis
  on the account. *Decider:* owner. *Blocks ADR-0016:* **NO** — the nullable-branch-on-CASH default stands.
- **OQ-CASH-04 — Multi-currency / foreign-currency bank accounts.** v1 is **base currency** (BR-CASH-11);
  a foreign-currency bank account + FX revaluation are deferred to X.6. *Recommended default:* base-currency
  cash/bank accounts in v1; multi-currency bank accounts land with the FX cross-cutting item. *Decider:*
  owner. *Blocks ADR-0016:* **NO** — deferred, not precluded (NFR-CASH-08).
- **OQ-CASH-05 — Deposit slips / batched lodgements.** Grouping several AR receipts into one bank deposit
  (a deposit batch clearing as one statement line). *Recommended default:* route each receipt to a chosen
  account directly in v1; deposit batching is a later additive convenience that reconciles onto the same
  per-account statement. *Decider:* owner. *Blocks ADR-0016:* **NO** — additive.
- **OQ-CASH-06 — Reconciliation reversal / un-reconcile.** Unwinding a **completed** reconciliation to fix a
  mistake. *Recommended default:* v1 corrects via a reversing entry / a new reconciliation (cleared flags on
  reconciled transactions are immutable — BR-CASH-07); an explicit un-reconcile workflow is a later additive
  slice. *Decider:* owner. *Blocks ADR-0016:* **NO** — additive.
- **OQ-CASH-07 — How the existing `gl_configs` `CASH` maps to the new default cash/bank account.** Cash &
  Bank replaces the single `CASH` account with named accounts. *Recommended default:* the **company default
  cash/bank account is the resolution of `CASH`**, so AR/AP callers that do not name an account keep working
  (the default account's linked GL account becomes the cash leg). *Decider:* architect (ADR-0016, with the
  AR/AP additive touch). *Blocks ADR-0016:* **NO for the requirement** — it is exactly the design decision
  ADR-0016 makes; the requirement fixes the *behaviour* (default if unspecified, BR-CASH-09).
- **OQ-CASH-08 — One-to-one cash/bank account ⇄ GL account.** Whether two cash/bank accounts may share a
  linked GL account. *Recommended default:* **one GL account per cash/bank account** (so each account's
  balance reconciles cleanly to a distinct GL account, BR-CASH-02); a shared-GL-account configuration is not
  built. *Decider:* owner + architect. *Blocks ADR-0016:* **NO** — the one-to-one default stands.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Confirm rounding mode (half-up vs banker's) and
  TZS decimal places (0 in practice) — transfer legs, direct-entry legs, the AR/AP cash legs, and the
  reconciliation balance check must round identically to the cash/bank book balance and the linked GL
  account (NFR-CASH-02). *Recommended default:* half-up, TZS = 0 dp. *Decider:* owner (finance input).
  *Blocks ADR-0016:* **NO** for the model; **confirm before go-live**.

## 12. Out of scope for v1 (deferred — restated)

Bank **statement file import** (CSV / MT940 / BAI2 / OFX) + auto-matching (OQ-CASH-01 — v1 reconciliation is
manual); **online / API bank feeds** (open-banking / direct bank integration); **cheque printing** (depends
on the cross-cutting PDF capability X.1, OQ-CASH-02 — the register is in v1); **multi-currency /
foreign-currency bank accounts + FX revaluation** (X.6 / gl.md §10.5, ADR-0005 D-8, OQ-CASH-04); **deposit
slips / batched lodgements** (OQ-CASH-05); a **typed payment-method / instrument tender** on AR/AP beyond the
cash/bank-account choice + the cheque register (accounts-payable.md OQ-AP-03); a **reconciliation reversal /
un-reconcile** workflow (OQ-CASH-06 — v1 corrects via reversing entries); and the **cash-flow statement /
cash-position dashboard / treasury forecast** (Reporting, T2.3 — Cash & Bank provides the per-account
statement + balance they read). Each is tracked for a later increment; none is precluded by the v1 model
(NFR-CASH-08).

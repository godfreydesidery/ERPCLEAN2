# Requirements — Accounts Receivable (AR) (who owes us — the customer sub-ledger)

> Status: **RATIFIED (owner-confirmed 2026-06-09).** The owner answered all five AR scoping forks —
> open-item creation (a finalised **credit** sale auto-creates an AR open item; a **cash** sale creates
> none); ageing buckets (Current, 1–30, 31–60, 61–90, 90+ by due date); receipts & allocation
> (auto-allocate **oldest-open-first** by default, manual override allowed, **on-account / unapplied**
> receipts allowed); credit limit (**warn + allow with a permission**, audited, at the Sales finalise
> path); and the v1 feature set (customer **statements**, **write-offs + credit notes**, **opening
> balances**). Each is reflected below as a fixed v1 requirement; everything not chosen has moved to the
> **Deferred** list (§2). **No ADR-0014-blocking open question remains.**
>
> Author: system-analyst · Domain: `ar` (financial / sub-ledger). Business-level spec only.
> **No schema, no API shapes, no tables/columns, no code** — those are the solutions-architect's, in
> **ADR-0014** (next step). Do not infer a data model from this document.
>
> **This is AR — half of Increment 2 of the full-ERP roadmap (docs/ROADMAP.md T1.2 / §5 Increment 2),
> built in parallel with [Accounts Payable](accounts-payable.md) (T1.3).** AR is the **customer
> sub-ledger**: the per-customer detail (open invoices, receipts, allocations, balances, ageing) behind
> the GL **`1200 Accounts Receivable`** control account. It does **not** rebuild the books; it records the
> detail the books summarise and posts the *new* financial events (receipts, write-offs) that GL did not
> already post.
>
> **Depends on:** **GL** (the books — ADR-0013 / V10; the `ACCOUNTS_RECEIVABLE` control account + the
> `gl_configs` map + `GLPostingService` + the outbox; **the SalesPostingHandler ALREADY debits AR control
> on a credit sale**, so AR must NOT re-post it — §3, BR-AR-02); **Sales** (the `SALE.FINALISED` /
> `SALE.VOIDED` events + credit-vs-cash via `customerKind`, net/vat/gross totals, the customer link —
> ADR-0008); **Parties** (Customer carries `credit_limit_amount` + currency, per-company + multi-branch
> scope — ADR-0006); the transactional **outbox** (ADR-0009 — AR is a **consumer** of `SALE.FINALISED`,
> DTO-only); **Money** (ADR-0005); and `code_sequence` (receipt numbering). All shipped.

## 1. Business context & why now

The books exist. GL (Increment 1) keeps the company's double-entry ledger and **already posts a credit
sale**: when a credit-account sale finalises, the `SalesPostingHandler` posts **DR `1200 Accounts
Receivable` / CR `4100 Sales Revenue` / CR `2200 VAT Payable`** (it picks `ACCOUNTS_RECEIVABLE` vs `CASH`
by the sale kind — gl.md FR-GL-10, ADR-0013 D-6). So the *total* a customer owes is already in the
control account. **What the books do not hold is the detail:** which customer owes it, against which
invoice, due when, and what has been received against it. The trial balance shows `1200` at, say,
12,400,000 TZS; it cannot tell you that is Mwanza Traders 8,000,000 (two invoices, one overdue) + Coastal
Supplies 4,400,000. **AR is that detail** — the **customer sub-ledger** behind the AR control account.

Accounts Receivable answers the questions a controller asks every day and the GL cannot: **who owes us,
how much, since when, and how overdue.** It does this by recording, per customer:

- an **open item** for every credit sale (the unpaid invoice, drawn from `SALE.FINALISED` for credit
  sales only — a cash sale is settled at the till and creates **no** receivable);
- the **receipts** customers pay against those open items, and how each receipt is **allocated** (which
  invoices it settled);
- **credit notes** that reduce a receivable, and **write-offs** that remove a bad debt;
- a per-customer **balance** and an **ageing** breakdown (Current / 1–30 / 31–60 / 61–90 / 90+);
- a customer **statement** — the open items + ageing for a customer, to view or print.

AR is also where the **credit limit** earns its keep. The Customer master already carries a
`credit_limit_amount` (Parties V2) that has never been enforced. AR makes it live: when a credit sale
would push a customer's **current AR balance + the new sale** over their limit, the Sales finalise path
**warns and allows the sale only with a permission** (`SALES.CREDIT.OVERRIDE`), and the override is
audited. This is an **additive cross-module touch to Sales** — a check inserted into the finalise path,
exactly the way `products.vat_status` was an additive touch when Sales landed (sales.md §1).

### The reconciliation rule (read this before anything else)

The **AR sub-ledger is the detail behind the GL `1200 Accounts Receivable` control account.** The two
must agree **at all times**: the sum of every customer's open-item balance **equals** the GL AR control
account balance. This single integrity rule shapes every posting decision in this module and is the chief
acceptance bar (BR-AR-02, NFR-AR-01). The non-obvious consequence — the thing that causes double-counted
receivables if got wrong — is **who posts what to GL**:

- A **credit sale** already posted **DR AR control** to GL (the `SalesPostingHandler`, gl.md FR-GL-10).
  So when AR consumes `SALE.FINALISED` and **creates the open item, it must NOT post to GL again** — the
  control account was already debited by Sales' GL handler. AR records **only the sub-ledger detail**.
  Posting again would double the receivable on the books. **(BR-AR-02 — no double-post.)**
- A **receipt** is a **new financial event** GL has not seen. So recording a receipt **does** post to GL:
  **DR `1000 Cash` / `1100 Bank` (CR side of the customer's debt is cleared) → CR `1200 Accounts
  Receivable`**, reducing the control account by the amount received, while AR records the receipt + its
  allocation in the sub-ledger. The sub-ledger drop and the control-account credit are the **same
  amount**, so the two stay reconciled.
- A **write-off** is a new event too: **DR `bad-debt expense` / CR `1200 Accounts Receivable`** — the
  sub-ledger open item is closed and the control account is reduced by the same amount.
- A **credit note** reduces a receivable: it reverses (part of) a sale, so it both reduces the sub-ledger
  open item **and** (DR Sales Revenue / DR VAT Payable / CR AR control) reduces the control account — in
  v1 a credit note may ride the existing `SALE.VOIDED` reversal path (Sales T2.1 returns, deferred) or be
  entered directly in AR; the reconciliation rule is the same either way (§3.4, OQ-AR-04).

> **Flag for the architect (ADR-0014):** the **GL-posting mechanism** for a receipt / write-off — a
> **synchronous `GLPostingService` call** in the same transaction as the sub-ledger write, **or** an
> **outbox event** (`AR.RECEIPT.RECORDED` / `AR.WRITTEN_OFF`) GL consumes — is an ADR decision (both
> reconcile; the synchronous call is simplest and keeps sub-ledger + control atomic; the outbox decouples
> but adds the in-flight gap). The Cash/Bank GL account a receipt's debit lands on comes from `gl_configs`
> (`CASH`); the **full Cash & Bank reconciliation module is roadmap T1.4** — for v1, an AR receipt may
> post its cash leg **directly against a Cash/Bank GL account** from `gl_configs` (the simple bridge GL
> §10.3 already anticipated). State the dependency; do not build a bank-rec system in AR.

### Vocabulary (read this first)

- **Accounts Receivable (AR)** — money customers owe us for credit sales not yet paid; **the customer
  sub-ledger** — the per-customer detail behind the GL `1200 Accounts Receivable` control account.
- **Sub-ledger** — a detailed ledger for one control account: AR holds, per customer, the open items and
  receipts whose **net balance equals** the GL AR control-account balance. The sub-ledger is the *detail*;
  the GL control account is the *summary*.
- **Control account** — the GL account whose balance summarises a sub-ledger. `1200 Accounts Receivable`
  is the control account for the AR sub-ledger (gl.md glossary). The sub-ledger must **reconcile** to it.
- **Open item** — an unpaid (or partly paid) receivable: one credit-sale invoice's outstanding amount in
  the sub-ledger. Created when a credit sale finalises; reduced by receipts / credit notes; closed when
  fully settled or written off. A **cash sale creates no open item** (settled at the till).
- **Receipt** — money received from a customer against their account (distinct from a Sales **receipt** of
  money at the till — see word discipline). Numbered `RCT-####`. A receipt is **allocated** to open items.
- **Allocation** — applying a receipt (or a credit note) to specific open items, reducing each by the
  applied amount. v1 **auto-allocates oldest-open-first** by default; the operator may **manually
  override** (re-pick which invoices a receipt settles). The sum allocated may be **less** than the
  receipt (the remainder is on-account) but **never more** than the receipt (over-allocation rejected).
- **On-account / unapplied (unallocated) receipt** — a receipt (or part of one) **not yet allocated** to
  any open item: a **credit balance** sitting on the customer's account, applied to a future invoice
  later. Allowed in v1 (a customer who pays in advance or over-pays).
- **Ageing bucket** — a band of how overdue an open item is, by **due date**: **Current** (not yet due),
  **1–30**, **31–60**, **61–90**, **90+** days overdue. The customer balance is reported split across
  these buckets.
- **Due date** — when an open item falls due, derived from the **customer's payment terms** applied to the
  invoice date; if no terms are defined, the v1 default is **net-on-receipt (0 days)** so due date =
  invoice date (OQ-AR-01).
- **Statement** — a per-customer document listing the customer's **open items + ageing** (and recent
  activity), to **view or print**. v1 statements are read/print only (no emailing, no dunning).
- **Write-off** — removing an uncollectable open item as a **bad debt**: it closes the open item in the
  sub-ledger and posts **DR bad-debt expense / CR AR control** to GL (the receivable leaves the books).
- **Credit note** — a document that **reduces a receivable** (goods returned, an over-charge corrected):
  it reduces the open item in the sub-ledger and the AR control on the books.
- **Opening balance (AR)** — a customer's pre-existing receivable at go-live, entered so AR starts from
  the business's actual debtor position (the sub-ledger side of the GL opening-balance journal).
- **Credit limit** — the maximum a credit customer may owe (`customers.credit_limit_amount`, Parties V2).
  v1 **warns and allows with a permission** when a credit sale would push current AR balance + the new
  sale over it; the override is audited (the check lives in the Sales finalise path).
- **`SALES.CREDIT.OVERRIDE`** — the IAM permission that lets an operator finalise a credit sale that
  exceeds the customer's credit limit (the audited override).

> **Word discipline (carried into the glossary):** an AR **receipt** (`RCT-####`, money received against a
> customer's account) is **not** the Sales **receipt** (the till slip evidencing money taken on a
> paid-at-sale invoice, sales.md). A **customer** (a party) is **not** an **account** (a GL bucket) — the
> AR control *account* `1200` summarises what *customers* owe. An **open item** (an unpaid receivable in
> the sub-ledger) is **not** a **journal entry** (the GL posting) — the open item is the detail; the GL
> entry already on the books is the summary leg. **Allocation** applies money to open items; it is not a
> **posting** (allocation may post nothing new to GL — the cash leg posts once when the receipt is
> recorded).

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-09). This is **AR Increment 2 (T1.2)**:
> the customer sub-ledger — open items from credit sales, receipts + allocation, balances + ageing,
> statements, write-offs, credit notes, opening balances, and the credit-limit check on the Sales finalise
> path. AR posts the *new* financial events (receipt, write-off) to the existing GL and **reconciles** to
> the GL AR control account.

### In scope (v1 — "track who owes us, take and allocate receipts, age the debt, and reconcile to GL")

- **Open items from credit sales (system-driven).** Finalising a **credit-account** sale (consuming
  `SALE.FINALISED` for credit sales) **auto-creates an AR open item** for the invoice's gross amount,
  idempotently (one open item per invoice). A **cash sale creates NO AR open item** (it is settled at the
  till). AR **does not re-post to GL** — the credit sale's `SalesPostingHandler` already debited the AR
  control account (BR-AR-02).
- **Customer receipts + allocation.** Record a **receipt** of money from a customer (`RCT-####`),
  **auto-allocated oldest-open-first by default**, with a **manual override** (the operator re-picks which
  open items the receipt settles), and **on-account (unallocated) receipts allowed** (a credit balance
  applied to a later invoice). A receipt posts **DR Cash/Bank / CR AR control** to GL (the cash side; the
  Cash/Bank account from `gl_configs`, full Cash&Bank module deferred to T1.4).
- **Balances + ageing.** A per-customer **AR balance** and an **ageing** breakdown by due date —
  **Current / 1–30 / 31–60 / 61–90 / 90+** days — read across the customer's open items.
- **Customer statements (view / print).** A per-customer statement = the customer's open items + ageing
  (and recent activity), to view or print. Read-only output; no emailing / dunning in v1.
- **Write-offs (bad-debt).** Write off an uncollectable open item (permissioned, audited): close the open
  item in the sub-ledger and post **DR bad-debt expense / CR AR control** to GL.
- **Credit notes (reduce a receivable).** A credit note reduces a customer's open item (and the AR control
  on the books) — for a return / over-charge correction. v1 enters the credit note as an AR document (or
  rides the Sales `SALE.VOIDED` reversal path where the underlying sale is voided); reconciliation is the
  same (§3.4, OQ-AR-04).
- **Opening balances (AR).** Enter customers' **pre-existing receivables at go-live** as opening open
  items, so AR starts from the actual debtor position; the GL side is the opening-balance journal (gl.md
  FR-GL-13). The sum of AR opening open items must equal the AR control's opening balance (BR-AR-02).
- **Credit-limit check on the Sales finalise path (additive cross-module touch).** When a credit sale
  would push **current AR balance + the new sale** over the customer's `credit_limit_amount`, the finalise
  path **warns and allows the sale only with `SALES.CREDIT.OVERRIDE`**; the override is **audited**. An
  additive insertion into Sales' finalise path (flagged for the architect — §3.5).
- **Permissions** — `AR.VIEW` (read sub-ledger / balances / ageing), `AR.INVOICE.VIEW` (read open items),
  `AR.RECEIPT.RECORD` (record a receipt), `AR.RECEIPT.ALLOCATE` (allocate / re-allocate a receipt),
  `AR.WRITEOFF` (write off a bad debt), `AR.STATEMENT.VIEW` (view/print a statement), `AR.OPENING.SET`
  (enter opening balances), plus **`SALES.CREDIT.OVERRIDE`** for the credit-limit override; per-company
  scope; `assertCanActIn` on **every read path**; audit on **every mutation**.
- **Receipt numbering** via the generic `code_sequence` (`RCT-####`, per company), the same
  concurrency-safe mechanism Sales/Purchases/GL use; a credit note from its own series (e.g. `CRN-####`,
  the architect's `entity_kind`).

### Deferred (recognised, NOT built in v1 — separate later increments)

- **Cash & Bank module / bank reconciliation** (ROADMAP T1.4) — multiple cash/bank accounts, deposit
  batching, bank-statement reconciliation. v1 posts a receipt's cash leg **directly to a Cash/Bank GL
  account** from `gl_configs` (the simple bridge, gl.md §10.3); the full module is T1.4. The AR allocation
  detail is built so Cash/Bank settles cleanly onto it later.
- **Customer payment terms master / multiple term schemes** — v1 derives due date from the customer's
  terms if defined, else net-on-receipt (0 days) (OQ-AR-01). A rich terms master (net-30, 2/10 net-30
  early-payment discounts, instalment schedules) is deferred.
- **Dunning / reminders / statement emailing** — automated overdue reminders, dunning letters, and
  emailing statements are deferred; v1 statements are view/print only.
- **Interest / finance charges on overdue balances** — computing and posting late-payment interest is
  deferred.
- **Customer deposits / advances as a distinct liability** — v1 treats an on-account (unapplied) receipt
  as a credit balance in AR; modelling customer deposits as a separate liability account is deferred.
- **Multi-currency AR / FX revaluation of open foreign receivables** — v1 is base-currency (TZS); FX
  revaluation of open foreign-currency receivables at period close is deferred (gl.md §10.5, ADR-0005 D-8).
- **Full returns / credit-note machinery in Sales** (ROADMAP T2.1) — partial returns, restocking, refund
  tenders. v1 AR accepts a credit note that reduces a receivable; the rich return flow is the Sales T2.1
  increment (a credit note flows into AR as a negative open item — ROADMAP T2.1).
- **Write-off approval workflow / allowance-for-doubtful-debts provisioning** — v1 write-off is a
  permissioned, audited single act (OQ-AR-03); a multi-step approval and a doubtful-debt provision /
  allowance account are deferred.
- **Statement format / branding / period-statement vs open-item-statement options** — v1 ships one
  statement layout (open items + ageing); format options are deferred (OQ-AR-02).

### Explicitly NOT this module

- **The General Ledger itself** — GL (ADR-0013) owns the books and the AR **control account**; AR holds
  the **sub-ledger detail** and posts the *new* events (receipt, write-off) to GL. AR never edits a posted
  journal; corrections are reversals / credit notes (BR-AR-09).
- **The sale document** — Sales owns the invoice and emits `SALE.FINALISED` / `SALE.VOIDED`; AR
  **consumes** the event (DTO-only) and creates the open item. AR never owns or edits a sales invoice.
- **The customer master** — Parties owns the Customer (and `credit_limit_amount`); AR consumes its DTO and
  reads the limit. AR never defines a customer.
- **Cash & bank accounts / reconciliation** — the Cash & Bank module (T1.4). AR posts the cash leg of a
  receipt to a Cash/Bank GL account; it is not a bank-reconciliation system.
- **Accounts Payable** — who *we* owe is the sibling [AP module](accounts-payable.md) (T1.3). AR is
  customers-who-owe-us only.
- **Financial statements & analytics** — P&L, balance sheet, debtor dashboards are Reporting (T2.3); AR
  provides the sub-ledger detail and the ageing they read.

## 3. The sub-ledger: open items, receipts, allocation, and the GL posting split

### 3.1 The AR sub-ledger (the detail behind the control account)

The AR sub-ledger holds, per **customer**, the **open items** (one per credit-sale invoice) and the
**receipts** taken against them, with their **allocations**. A customer's **AR balance** = the sum of its
open items' outstanding amounts **minus** any on-account (unapplied) receipt credit. The **sum of every
customer's AR balance == the GL `1200 Accounts Receivable` control-account balance** — the reconciliation
invariant (BR-AR-02). The sub-ledger is company-scoped; receipts and allocations carry the originating
branch as an analysis tag (consistent with GL keeping the books at company level — gl.md NFR-GL-01).

### 3.2 Open item from a credit sale (system-driven — and the no-double-post rule)

When a sale **finalises**, Sales emits `SALE.FINALISED` (the same event GL's `SalesPostingHandler`
consumes). AR consumes it **for credit-account sales only** (`customerKind = CREDIT_ACCOUNT`, ADR-0008)
and **creates an AR open item** for the invoice's gross amount, due per the customer's terms (OQ-AR-01),
**idempotently** (one open item per invoice uid; a redelivered event creates no second item). A **cash
sale** (`CASH_WALK_IN`) is **skipped** — it created no receivable.

**AR does NOT post to GL on open-item creation** (BR-AR-02). The credit sale's GL effect — **DR AR control
/ CR Revenue / CR VAT** — was **already posted by Sales' `SalesPostingHandler`** (gl.md FR-GL-10). AR
creating the open item is recording the **sub-ledger detail** of that same debit, not a second debit.
This is the central no-double-post rule: **one credit sale = one AR control debit (by GL) + one AR open
item (by AR), the same amount.** They reconcile by construction.

### 3.3 Receipt → allocate → post (the new financial event)

A **receipt** is money GL has not seen, so recording one **does** post to GL. The flow:

1. Record a **receipt** (`RCT-####`) from a customer for an amount in the sale currency (base in practice).
2. **Allocate** it — **auto, oldest-open-first by default** (the receipt pays the oldest open items until
   exhausted), with a **manual override** (the operator re-picks which open items it settles). The amount
   allocated must be **≤ the receipt** (over-allocation rejected — BR-AR-04); any remainder is **on-account
   (unapplied)**, a credit balance for later (allowed — BR-AR-05).
3. **Post the cash leg to GL once:** **DR Cash/Bank** (from `gl_configs` `CASH`) **/ CR `1200 Accounts
   Receivable`** for the **receipt amount** — reducing the control account. The sub-ledger open items drop
   by the **allocated** amount and the on-account credit rises by the **unallocated** remainder; the total
   sub-ledger reduction (= receipt amount) equals the control-account credit, so the two stay reconciled.
4. The receipt and its allocation are recorded; the post is audited as the operator's act.

> Re-allocating an existing receipt (moving its applied amount from one open item to another) is a
> **sub-ledger-only** change — it posts **nothing** to GL (the cash leg was posted once at step 3). Only
> the *new event* (the receipt itself) hits GL. This is why allocation and posting are kept distinct.

### 3.4 Write-off and credit note (the other new events)

- **Write-off** (`AR.WRITEOFF`): an uncollectable open item is closed in the sub-ledger and GL posts
  **DR bad-debt expense (from `gl_configs`) / CR `1200 Accounts Receivable`** — the receivable leaves the
  books and the sub-ledger together, reconciled (BR-AR-06).
- **Credit note**: reduces a customer's open item; on the books it reduces the AR control (DR Sales
  Revenue / DR VAT Payable / CR AR control for the credited portion). In v1 a credit note is either (a)
  the consequence of a **voided sale** (Sales emits `SALE.VOIDED`; GL's `SaleVoidingHandler` already posts
  the reversing entry, so AR just **closes / reduces the open item** — no second GL post, BR-AR-02), or
  (b) entered directly in AR as a standalone credit note that **does** post the reduction to GL. Which of
  (a)/(b) is the v1 path (and whether v1 needs standalone credit notes at all, given Sales returns are
  T2.1) is **OQ-AR-04** — recommended default: ride `SALE.VOIDED` for voided sales, allow a standalone AR
  credit note for non-void corrections; both reconcile.

### 3.5 The credit-limit check (additive touch to the Sales finalise path)

The credit limit lives on the Customer (`credit_limit_amount`, Parties V2) and is enforced **at the Sales
finalise path** (not inside a sale already done). When a **credit** sale is finalised:

1. The finalise path computes **(the customer's current AR balance) + (this sale's gross)**.
2. If that exceeds the customer's `credit_limit_amount`, the operator is **warned**.
3. The sale is **allowed only if the operator holds `SALES.CREDIT.OVERRIDE`** — and the override is
   **audited** (customer, current balance, limit, sale amount, operator, time). Without the permission,
   the credit sale is **blocked** until the balance is reduced (a receipt) or the limit raised.

This is an **additive cross-module touch to Sales** — a check inserted into the finalise path that reads
the AR current balance (a DTO call into AR or a balance projection) and the customer's limit. It is the
same kind of additive change `products.vat_status` was for Sales.

> **Flag for the architect (ADR-0014 + the additive Sales touch):** the credit-limit check makes **Sales
> read AR at finalise** (current balance) — a new cross-module read. Whether Sales calls an `ArBalance`
> DTO synchronously, or AR maintains a balance the Sales path reads, is an ADR decision; keep the
> direction Sales→AR (AR must not depend back on Sales' entities — gl.md NFR-GL-07 module-boundary
> discipline). Also note: a cash sale never checks the limit (no receivable arises).

## 4. Actors / personas

- **Credit controller** — owns the receivables: reads the sub-ledger, balances, and **ageing**; chases
  overdue customers; views/prints **statements**; decides and records **write-offs** and **credit notes**;
  may hold `SALES.CREDIT.OVERRIDE`. Holds `AR.VIEW` / `AR.STATEMENT.VIEW` / `AR.WRITEOFF` (and usually
  `AR.RECEIPT.ALLOCATE`).
- **Cashier / receipts clerk** — records **customer receipts** (`AR.RECEIPT.RECORD`) and allocates them
  (`AR.RECEIPT.ALLOCATE`), takes the money against the customer's account.
- **Accountant / bookkeeper** — enters AR **opening balances** at go-live (`AR.OPENING.SET`); reconciles
  the AR sub-ledger to the GL AR control account; reads the sub-ledger (`AR.VIEW`).
- **SYSTEM (the open-item creator)** — **not a human.** The outbox consumer (an `ArOpenItemHandler`, the
  AR analogue of GL's `SalesPostingHandler`) that consumes `SALE.FINALISED` and **creates the AR open
  item for a credit sale** automatically, under the originating event's company/branch context, with no
  permission check (the producing sale was already permissioned — ADR-0009 D-9). Most open items in a live
  deployment are its work; it is a first-class actor here, like GL's auto-poster.
- **Sales clerk / cashier (at finalise)** — not an AR persona per se, but the operator whose credit-sale
  finalise triggers the **credit-limit check** (§3.5) and who needs `SALES.CREDIT.OVERRIDE` to exceed a
  limit.

## 5. Functional requirements

> IDs are `FR-AR-NN`. Each is a crisp, testable, **ratified** statement. "Open item" = an unpaid
> credit-sale receivable in the sub-ledger; "post to GL" = a `GLPostingService` posting (mechanism is the
> architect's, §3 flag); "the control account" = GL `1200 Accounts Receivable`.

### Open items (the sub-ledger detail)

- **FR-AR-01** When a **credit-account** sale **finalises** (`SALE.FINALISED`, `customerKind =
  CREDIT_ACCOUNT`), the system **auto-creates an AR open item** for the customer, for the invoice's
  **gross** amount, with an **invoice date** and a **due date** (§FR-AR-03), carrying the customer, the
  source invoice reference, and the company/branch context. The open item is the sub-ledger detail of the
  receivable.
- **FR-AR-02** A **cash / walk-in** sale (`customerKind = CASH_WALK_IN`) **creates NO AR open item** — it
  is settled at the till (sales.md §6). AR consumes `SALE.FINALISED` for **credit sales only**.
- **FR-AR-03** Each open item has a **due date** derived from the **customer's payment terms** applied to
  the invoice date; if the customer has **no terms defined**, the due date defaults to **net-on-receipt
  (0 days)** so due date = invoice date (OQ-AR-01). The due date drives ageing (FR-AR-08).
- **FR-AR-04** Open-item creation is **idempotent**: one open item per source invoice uid per company; a
  **redelivered `SALE.FINALISED`** creates **no second open item** (consumer-side dedupe via the outbox
  marker, ADR-0009 D-6). Creating it twice yields one open item (BR-AR-08).
- **FR-AR-05** AR creating an open item **posts nothing to GL** — the credit sale's `SalesPostingHandler`
  already debited the AR control account (gl.md FR-GL-10). AR records **only** the sub-ledger detail
  (BR-AR-02 — no double-post). *(The reconciliation invariant FR-AR-18 holds because the control debit and
  the open item are the same amount.)*

### Receipts & allocation

- **FR-AR-06** A user with `AR.RECEIPT.RECORD` may **record a customer receipt** (`RCT-####` from
  `code_sequence`, per company) for an amount in the customer's sale currency, against the customer's
  account. Recording a receipt **posts to GL once**: **DR Cash/Bank** (the `gl_configs` `CASH` account)
  **/ CR the AR control account** for the receipt amount (FR-AR-16).
- **FR-AR-07** A receipt is **allocated to open items**: **auto, oldest-open-first by default** (the
  receipt pays the oldest-due open items until exhausted). A user with `AR.RECEIPT.ALLOCATE` may **manually
  override** the allocation (re-pick which open items the receipt settles, in any split). Allocation
  reduces each targeted open item by the applied amount; a fully-allocated open item is **closed**.
- **FR-AR-08** The system reports a per-customer **AR balance** and an **ageing** breakdown by **due
  date**: **Current** (not yet due), **1–30**, **31–60**, **61–90**, and **90+** days overdue, computed
  across the customer's open items as at a chosen date.
- **FR-AR-09** An **on-account (unapplied) receipt** is allowed: the amount of a receipt **not** allocated
  to any open item stands as a **credit balance** on the customer's account, applied to a future open item
  later (BR-AR-05). A receipt may be recorded with **no** allocation (fully on-account).
- **FR-AR-10** The system **rejects over-allocation**: the total allocated across a receipt's open items
  must **not exceed** the receipt amount (BR-AR-04). The remainder (receipt − allocated) is on-account
  (FR-AR-09), never negative.
- **FR-AR-11** **Re-allocating** a receipt (moving its applied amount between open items) is a
  **sub-ledger-only** change (`AR.RECEIPT.ALLOCATE`) that **posts nothing further to GL** — the cash leg
  was posted once when the receipt was recorded (FR-AR-06). It is audited.

### Statements, write-offs, credit notes

- **FR-AR-12** A user with `AR.STATEMENT.VIEW` may **view or print a customer statement**: the customer's
  **open items + ageing** (and recent receipts/credit notes) as at a date, scoped to their company. v1
  statements are read/print only (no emailing / dunning — deferred).
- **FR-AR-13** A user with `AR.WRITEOFF` may **write off** an uncollectable open item (a bad debt): the
  open item is **closed** in the sub-ledger and GL posts **DR bad-debt expense** (from `gl_configs`) **/ CR
  the AR control account** for the written-off amount. The write-off is audited (BR-AR-06).
- **FR-AR-14** A **credit note** **reduces** a customer's open item (a return / over-charge correction).
  Where the credit note follows a **voided sale** (`SALE.VOIDED`), GL's `SaleVoidingHandler` already posts
  the reversing entry, so AR **reduces/closes the open item without a second GL post** (BR-AR-02); where a
  **standalone** AR credit note is entered, AR **posts the reduction** (DR Sales Revenue / DR VAT Payable /
  CR AR control). Either path keeps the sub-ledger and the control account reconciled (OQ-AR-04).

### Opening balances

- **FR-AR-15** A user with `AR.OPENING.SET` may **enter AR opening balances at go-live**: a pre-existing
  open item per customer (amount, invoice date, due date) so AR starts from the actual debtor position.
  The **sum of AR opening open items must equal the AR control account's opening balance** (the GL side is
  the opening-balance journal, gl.md FR-GL-13) — the reconciliation invariant holds from day one (BR-AR-02).

### GL posting & reconciliation

- **FR-AR-16** Recording a **receipt** posts **DR Cash/Bank / CR AR control** to GL for the receipt amount
  (the cash account from `gl_configs` `CASH`); a **write-off** posts **DR bad-debt expense / CR AR control**;
  a **standalone credit note** posts **DR Sales Revenue + DR VAT Payable / CR AR control**. **Open-item
  creation from a credit sale posts nothing** (already posted by Sales' handler, FR-AR-05). The GL-posting
  mechanism (synchronous `GLPostingService` call vs an outbox event) is the architect's (§3 flag).
- **FR-AR-17** Every GL posting AR makes obeys the GL invariants (balanced, open period, active accounts —
  gl.md BR-GL-01/03/04). A receipt / write-off whose **posting date falls in a closed period** is handled
  per the GL closed-period policy (gl.md OQ-GL-01); a missing required `gl_configs` mapping (CASH,
  bad-debt) **fails the operation** rather than mis-posting (gl.md BR-GL-10).
- **FR-AR-18** The system maintains the **reconciliation invariant**: the **sum of all customers' AR
  sub-ledger balances equals the GL `1200 Accounts Receivable` control-account balance** at all times
  (BR-AR-02, NFR-AR-01). A reconciliation read (sub-ledger total vs control balance) is available to
  finance; a discrepancy is a finance-grade defect.

### Credit limit (additive Sales touch)

- **FR-AR-19** When a **credit** sale is finalised, the Sales finalise path **checks the credit limit**:
  if **(the customer's current AR balance) + (this sale's gross)** exceeds the customer's
  `credit_limit_amount`, the operator is **warned** and the sale is **allowed only with
  `SALES.CREDIT.OVERRIDE`**; the override is **audited** (customer, balance, limit, amount, operator,
  time). Without the permission the credit sale is **blocked**. A **cash** sale is never checked
  (FR-AR-02). This is an **additive insertion into the Sales finalise path** (§3.5; flagged for ADR-0014
  and the Sales touch).

### Scope & permissions

- **FR-AR-20** AR is **scoped per company**; every open item, receipt, allocation, write-off, credit note,
  and opening balance belongs to exactly one company; no read or balance crosses company scope.
  `assertCanActIn` guards **every read path** (BR-AR-07, NFR-AR-01). Receipts/allocations may carry the
  originating **branch** as an analysis tag (the sub-ledger, like the books, is kept at company level —
  gl.md NFR-GL-01).
- **FR-AR-21** All AR operations are **gated by IAM permissions**: `AR.VIEW`, `AR.INVOICE.VIEW`,
  `AR.RECEIPT.RECORD`, `AR.RECEIPT.ALLOCATE`, `AR.WRITEOFF`, `AR.STATEMENT.VIEW`, `AR.OPENING.SET`, plus
  `SALES.CREDIT.OVERRIDE` (the credit-limit override). Exact codes are seeded with the module (FR-IAM-11).
  Per-company scope; **audit on every mutation** (NFR-AR-03). The **SYSTEM open-item creator** runs under
  no user permission (the producing sale was already permissioned) but is bounded by the event's company
  context.

## 6. Business rules (invariants)

> Ratified. These are the AR invariants; a violation that breaks reconciliation is a finance-grade defect
> (a release blocker).

- **BR-AR-01 — Open item only for a credit sale.** A finalised **credit-account** sale creates exactly
  **one** AR open item; a **cash** sale creates **none** (it is settled at the till). AR consumes
  `SALE.FINALISED` for credit sales only (FR-AR-01/02).
- **BR-AR-02 — Sub-ledger reconciles to the GL control account / NO double-post.** The **sum of all
  customers' AR balances equals the GL `1200 Accounts Receivable` control-account balance at all times**.
  Because the credit sale's `SalesPostingHandler` **already** debited the AR control, **AR must NOT post
  to GL when it creates the open item** — it records only the sub-ledger detail. A **receipt**, a
  **write-off**, and a **standalone credit note** are *new* events AR **does** post to GL (the cash /
  bad-debt / revenue-VAT legs against the AR control). Double-posting the receivable, or a sub-ledger that
  drifts from the control account, is a **release blocker** (FR-AR-05/16/18, NFR-AR-01).
- **BR-AR-03 — Allocation: oldest-open-first by default, manual override allowed.** A receipt
  auto-allocates **oldest-due open item first** unless an operator with `AR.RECEIPT.ALLOCATE` overrides
  the allocation (re-picks the open items) (FR-AR-07).
- **BR-AR-04 — Over-allocation rejected.** The total allocated across a receipt's open items must **never
  exceed** the receipt amount; a remainder is on-account (BR-AR-05), never a negative or phantom amount
  (FR-AR-10).
- **BR-AR-05 — On-account (unapplied) receipts allowed.** A receipt (or the unallocated part of one) may
  sit **on-account** as a customer credit balance, applied to a later open item; a customer may pay in
  advance or over-pay (FR-AR-09).
- **BR-AR-06 — Write-off posts to a bad-debt expense via GL.** Writing off an open item closes it in the
  sub-ledger and posts **DR bad-debt expense / CR AR control** to GL; the receivable leaves the books and
  the sub-ledger together (FR-AR-13).
- **BR-AR-07 — One company's receivables are isolated.** Every open item, receipt, allocation, write-off,
  credit note, and opening balance **belongs to exactly one company**; no read or balance crosses company
  scope. Cross-company AR leakage is a **release blocker** (NFR-AR-01).
- **BR-AR-08 — Idempotent open-item creation.** Processing the same `SALE.FINALISED` twice creates **one**
  open item (consumer-side dedupe, ADR-0009 D-6). Violation double-counts a receivable — a finance
  release blocker (FR-AR-04).
- **BR-AR-09 — Append-only; correct via credit note / reversal, not edit.** A recorded receipt /
  allocation / posted AR event is **not silently edited or deleted**; a correction is a **reversal** (a
  cancelling receipt / re-allocation) or a **credit note** — mirroring the GL append-only rule
  (PROJECT-CONVENTIONS §3.6, gl.md BR-GL-02). The sub-ledger keeps a full history.
- **BR-AR-10 — Credit-limit: warn + allow with permission, audited.** A credit sale that would push
  **current AR balance + the new sale** over the customer's `credit_limit_amount` is **warned** and
  **allowed only with `SALES.CREDIT.OVERRIDE`**, the override **audited**; without the permission it is
  **blocked**. A cash sale is never checked (FR-AR-19, §3.5).
- **BR-AR-11 — Base-currency reconciliation (v1).** AR balances and the GL control account are in the
  company **base currency** (TZS in practice); a foreign-currency receivable is converted at entry
  (mirrors gl.md BR-GL-06); FX revaluation of open foreign receivables is deferred (§2).
- **BR-AR-12 — A receipt's GL cash leg posts once.** Recording a receipt posts **DR Cash/Bank / CR AR
  control** exactly once for the receipt amount; **re-allocating** that receipt posts **nothing** further
  to GL (FR-AR-11). The new event (the receipt) hits GL; allocation detail does not.

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Credit sale finalises → AR open item (system-driven, no GL double-post) — happy path
1. A **credit-account** sale finalises (Sales, already permissioned) → emits `SALE.FINALISED`; GL's
   `SalesPostingHandler` posts **DR AR control / CR Revenue / CR VAT** (gl.md §7.2).
2. The **AR open-item handler** consumes the **same** `SALE.FINALISED` (credit sales only, under the
   event's company/branch context), reads the invoice gross + customer + invoice date (DTO-only), computes
   the **due date** (customer terms, else net-on-receipt — FR-AR-03), and **creates the AR open item**.
3. AR **posts nothing to GL** (the control was already debited by Sales' handler — BR-AR-02). The
   sub-ledger now carries the detail; the customer's AR balance and ageing reflect it.
4. The reconciliation invariant holds: the new control debit and the new open item are the **same amount**
   (FR-AR-18).

### 7.2 Record a receipt → allocate → post — happy path
1. A cashier (`AR.RECEIPT.RECORD`, active company) records a **receipt** (`RCT-####`) from a customer for
   an amount in the sale currency.
2. The system **auto-allocates oldest-open-first** (the receipt pays the oldest-due open items until
   exhausted); the clerk may **manually override** (`AR.RECEIPT.ALLOCATE`) to re-pick open items.
3. The system validates: total allocated **≤ receipt** (over-allocation rejected, BR-AR-04); any remainder
   is **on-account** (a customer credit, BR-AR-05).
4. The system **posts the cash leg to GL once**: **DR Cash/Bank** (`gl_configs` `CASH`) **/ CR AR control**
   for the receipt amount; the sub-ledger open items drop by the allocated amount, the on-account credit
   rises by the remainder — total reduction = receipt = control credit (reconciled, FR-AR-16/18).
5. The receipt + allocation are recorded and **audited**; the customer's balance/ageing update.

### 7.3 Write off a bad debt — happy path
1. A credit controller (`AR.WRITEOFF`) selects an uncollectable open item and confirms a write-off (with
   a reason).
2. The open item is **closed** in the sub-ledger; GL posts **DR bad-debt expense (`gl_configs`) / CR AR
   control** for the written-off amount (BR-AR-06).
3. The write-off is **audited**; the customer's balance drops and the control account drops by the same
   amount (reconciled).

### 7.4 View / print a customer statement — happy path
1. A credit controller (`AR.STATEMENT.VIEW`) opens a customer's **statement** as at a date.
2. The statement lists the customer's **open items + ageing** (Current / 1–30 / 31–60 / 61–90 / 90+) and
   recent receipts/credit notes (FR-AR-12); it is viewed or printed (no email/dunning in v1).

### 7.5 Enter AR opening balances — happy path
1. At go-live an accountant (`AR.OPENING.SET`) enters each customer's **pre-existing receivable** (amount,
   invoice date, due date) as an opening open item (FR-AR-15).
2. The **sum of opening open items equals the AR control account's opening balance** (the GL side is the
   opening-balance journal, gl.md FR-GL-13); reconciliation holds from day one (BR-AR-02).

### 7.6 Credit sale over the credit limit — finalise path (warn + override)
1. A clerk finalises a **credit** sale; the finalise path computes **current AR balance + this sale's
   gross** and compares it to the customer's `credit_limit_amount` (§3.5).
2. If over the limit, the clerk is **warned**; if the clerk holds **`SALES.CREDIT.OVERRIDE`**, the sale is
   **allowed** and the override is **audited**; otherwise the sale is **blocked** (FR-AR-19, BR-AR-10).

### 7.7 Main unhappy paths
- **Redelivered `SALE.FINALISED`** (7.1.2) → the idempotency marker short-circuits; **no second open item**
  is created (BR-AR-08, FR-AR-04).
- **`SALE.FINALISED` for a CASH sale** (7.1) → AR **skips** it; no open item, no posting (FR-AR-02).
- **Over-allocation of a receipt** (7.2.3) → **rejected**; the operator reduces the allocation or leaves
  the remainder on-account (BR-AR-04).
- **Receipt with no allocation** (7.2.2) → accepted as a fully **on-account** credit (BR-AR-05).
- **Re-allocating an existing receipt** (any) → a **sub-ledger-only** change; **nothing** new posts to GL
  (FR-AR-11, BR-AR-12).
- **Receipt / write-off would post into a CLOSED period** (7.2.4 / 7.3.2) → handled per the GL
  closed-period policy (gl.md OQ-GL-01); finance reopens or moves the date (FR-AR-17).
- **Missing required `gl_configs` mapping** (CASH or bad-debt) (7.2.4 / 7.3.2) → the operation **fails**
  rather than mis-posting; finance sets the mapping (`GL.MANAGE`), then retries (gl.md BR-GL-10, FR-AR-17).
- **Attempt to edit a posted receipt / closed open item** (any) → **refused**; correct via a cancelling
  receipt / re-allocation / credit note (append-only, BR-AR-09).
- **Sub-ledger total ≠ GL control balance** (reconciliation read) → a **finance-grade defect** surfaced for
  investigation (FR-AR-18, NFR-AR-01).

## 8. Non-functional

- **NFR-AR-01 — Reconciliation integrity & tenant isolation.** The **AR sub-ledger total must equal the GL
  `1200 Accounts Receivable` control-account balance** for every company at all times (BR-AR-02); a drift
  is a **release blocker**. Every AR row is scoped by `company_id` through the tenant-predicate repository
  base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS §3.2); `assertCanActIn` guards **every read path**.
  Cross-company AR leakage is a **release blocker**, as for GL/Sales.
- **NFR-AR-02 — Money correctness.** Every amount is a `Money` (amount + currency, ADR-0005) in the
  company base currency; allocations and the GL cash/write-off legs sum **exactly** (no float, `BigDecimal`
  compare, rounding per ADR-0005 D-2 / gl.md NFR-GL-02, OQ-CUR-03). A receipt whose allocation + on-account
  remainder does not equal the receipt amount is a defect, not a tolerance.
- **NFR-AR-03 — Audit.** Every **mutation** — open-item creation (SYSTEM), receipt record, allocation /
  re-allocation, write-off, credit note, opening-balance entry, and the **credit-limit override** — is
  written to the IAM append-only audit trail with actor (or SYSTEM), action, target, timestamp, and
  company/branch context (mirrors NFR-GL-06).
- **NFR-AR-04 — Idempotency.** Open-item creation from `SALE.FINALISED` is **exactly-effect-once**: a
  redelivered event creates no second open item (BR-AR-08, ADR-0009 D-6). An integration test must deliver
  the same event twice and assert one open item.
- **NFR-AR-05 — Numbering concurrency.** Two clerks recording receipts simultaneously get distinct
  `RCT-####` numbers (the `code_sequence` row-locked allocation — ADR-0007 D-6).
- **NFR-AR-06 — DTO-only consumption.** AR consumes `SALE.FINALISED` / `SALE.VOIDED` as event payloads /
  DTOs and reads the customer (`credit_limit_amount`) and invoice totals through DTOs; it **never imports a
  Sales or Parties entity** (ADR-0009 D-1; `ModuleBoundaryTest`). The Sales→AR credit-limit read keeps the
  dependency direction Sales→AR (AR does not depend back on Sales).
- **NFR-AR-07 — Timestamps** are UTC, displayed per company time zone (Africa/Dar_es_Salaam default,
  iam.md locale). The open item's **due date** and a receipt's **value date** are business dates, distinct
  from the posting timestamp; ageing is computed from the **due date** (FR-AR-08).
- **NFR-AR-08 — Forward-compatibility.** The v1 model must not preclude the later increments that build on
  AR: the **Cash & Bank** module settling AR allocations and owning the cash leg (T1.4); a **payment-terms**
  master; **dunning / statement emailing**; **interest on overdue**; **multi-currency AR / FX revaluation**
  (gl.md §10.5); and the full **returns / credit-note** flow (Sales T2.1). Building these is deferred;
  precluding them is a defect.

## 9. Assumptions

- The dependency platform exists and is consumed as designed: **GL** (ADR-0013 / V10 — the
  `ACCOUNTS_RECEIVABLE` control account, `gl_configs` `CASH` + bad-debt mapping, `GLPostingService`, the
  outbox) is shipped; **Sales** emits `SALE.FINALISED` / `SALE.VOIDED` with the invoice uid +
  customer-kind (credit vs cash) + net/vat/gross (ADR-0008); **Parties** Customer carries
  `credit_limit_amount` + currency (V2); **Money** (ADR-0005) and `code_sequence` are in place. All
  shipped.
- The **credit-vs-cash signal** AR uses to consume only credit sales is the sale's `customerKind`
  (`CREDIT_ACCOUNT` vs `CASH_WALK_IN`, ADR-0008). Note: sales.md v1 was **paid-at-sale only** (credit
  sales deferred, OQ-SALES-06); AR's open-item path goes live **as credit sales land** — the AR machinery
  is built now so credit-sale receivables flow the moment the credit-sale finalise path exists (this is
  the AR increment's reason to build). The cash path (no open item) is the v1 reality until then.
- The **GL AR control account is `1200`** per the seeded TZ CoA + `gl_configs` `ACCOUNTS_RECEIVABLE`
  mapping (gl.md §3.1, ADR-0013); AR resolves the control account through `gl_configs`, never a hard-coded
  code.
- **Document currency = company base (TZS)** in practice for v1; the convert-at-entry shape supports a
  foreign-currency receivable but FX depth is deferred (BR-AR-11).
- **A bad-debt expense account exists** in the seeded CoA (or is mapped via `gl_configs`) for write-offs;
  the exact account/mapping is the architect's (ADR-0014) — the requirement fixes only that a write-off
  posts to a bad-debt expense.

## 10. ACCEPTED RISK & accepted scope boundary — what AR v1 deliberately does NOT do (owner-accepted 2026-06-09)

> **Read this before building or consuming AR.** AR v1 delivers the **customer sub-ledger** (open items
> from credit sales, receipts + allocation, balances + ageing, statements, write-offs, credit notes,
> opening balances) + the **credit-limit check on the Sales finalise path**, reconciling to the GL AR
> control account. The following are **deliberate boundaries**, owner-accepted.

1. **AR does NOT post the receivable to GL on a credit sale — by design (the no-double-post rule).** The
   credit sale's `SalesPostingHandler` already debited the AR control account; AR creating the open item
   posts **nothing** to GL (BR-AR-02). This is the chief correctness rule, not a gap.

2. **Cash & Bank reconciliation is a SEPARATE increment (T1.4).** v1 posts a receipt's cash leg **directly
   to a Cash/Bank GL account** from `gl_configs` (the simple bridge, gl.md §10.3). A full cash/bank module
   (multiple accounts, deposit batching, bank-statement reconciliation) is deferred. The AR allocation
   detail is built so Cash/Bank settles onto it later (NFR-AR-08).

3. **Payment terms are minimal in v1.** Due date = customer terms if defined, else **net-on-receipt
   (0 days)** (OQ-AR-01). A rich terms master (net-30, early-payment discounts, instalments) is deferred.

4. **No dunning / reminders / statement emailing / overdue interest in v1.** Statements are **view/print
   only**; automated chasing and finance charges are deferred.

5. **Multi-currency AR / FX revaluation DEFERRED.** v1 reconciles in the **base currency** (BR-AR-11);
   revaluing open foreign receivables at period close is out of scope (gl.md §10.5), not precluded
   (NFR-AR-08).

6. **Full returns / credit-note machinery is the Sales T2.1 increment.** v1 AR accepts a credit note that
   **reduces** a receivable (riding `SALE.VOIDED` or as a standalone AR credit note — OQ-AR-04); the rich
   partial-return / restocking / refund-tender flow is Sales T2.1.

All are additive by design (NFR-AR-08); none is precluded by the v1 model.

## 11. Open questions — status after ratification (2026-06-09)

> The **five AR scoping forks** the owner answered (open-item creation; ageing buckets; receipts &
> allocation; credit limit; v1 feature set) are **RESOLVED** (recorded in
> `docs/requirements/open-questions.md` under AR). **No ADR-0014-blocking open question remains.** What
> stays open is **non-blocking** detail with a recommended default that stands unless the owner overrides
> — confirm during build / before go-live, not before ADR-0014.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0014)

- **OQ-AR-01 — Customer payment terms / due-date default.** Ageing is by **due date**; the due date comes
  from the customer's payment terms if defined, **else net-on-receipt (0 days)** (due date = invoice
  date). A terms master / per-customer term scheme is not yet defined. *Recommended default:* derive due
  date from a simple per-customer net-days field if present, else **0 days**; a richer terms master is a
  later additive slice. *Decider:* owner (finance). *Blocks ADR-0014:* **NO** — the default stands; the
  due-date field + ageing buckets are fixed.
- **OQ-AR-02 — Statement format.** v1 ships **one** statement layout (open items + ageing + recent
  activity, view/print). *Recommended default:* an open-item statement with the five ageing buckets;
  period-statement / branded / emailed variants are later additive options. *Decider:* owner. *Blocks
  ADR-0014:* **NO** — additive presentation detail.
- **OQ-AR-03 — Write-off approval.** v1 write-off is a **permissioned, audited single act** (`AR.WRITEOFF`).
  *Recommended default:* permission + audit, no approval workflow and no doubtful-debt allowance/provision
  account in v1; a write-off approval threshold and an allowance account are later additive slices.
  *Decider:* owner (finance). *Blocks ADR-0014:* **NO** — additive.
- **OQ-AR-04 — Credit note: standalone vs ride the void path.** A credit note reduces a receivable; in v1
  a **voided sale** rides `SALE.VOIDED` (GL already reverses — AR closes the open item with no second GL
  post), while a non-void correction may be a **standalone AR credit note** (which posts the reduction).
  *Recommended default:* ride `SALE.VOIDED` for voided sales; allow a standalone AR credit note for
  non-void corrections; both reconcile (BR-AR-02). Whether v1 needs the standalone path at all (given full
  returns are Sales T2.1) is the open part. *Decider:* owner. *Blocks ADR-0014:* **NO** — both paths
  reconcile; the standalone path is additive if deferred.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Confirm rounding mode (half-up vs banker's) and
  TZS decimal places (0 in practice) — allocations and the GL legs must round identically to the AR
  balance and the control account (NFR-AR-02). *Recommended default:* half-up, TZS = 0 dp. *Decider:*
  owner (finance input). *Blocks ADR-0014:* **NO** for the model; **confirm before go-live**.

## 12. Out of scope for v1 (deferred — restated)

Cash & Bank module / bank reconciliation (T1.4 — v1 posts the receipt cash leg directly to a Cash/Bank GL
account); a customer **payment-terms master** / multiple term schemes (OQ-AR-01); **dunning / reminders /
statement emailing** and **overdue interest / finance charges**; customer **deposits/advances** as a
distinct liability; **multi-currency AR / FX revaluation** of open foreign receivables (gl.md §10.5,
ADR-0005 D-8); the full **returns / credit-note machinery** in Sales (T2.1 — v1 AR reduces a receivable
via `SALE.VOIDED` or a standalone credit note, OQ-AR-04); **write-off approval workflow / doubtful-debt
allowance** (OQ-AR-03); **statement format** variants (OQ-AR-02); and the **financial statements**
themselves — P&L / Balance Sheet / debtor dashboards (Reporting, T2.3 — AR provides the sub-ledger + ageing
they read). Each is tracked for a later increment; none is precluded by the v1 model (NFR-AR-08).

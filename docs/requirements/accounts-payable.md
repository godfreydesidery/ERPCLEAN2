# Requirements — Accounts Payable (AP) (who we owe — the supplier sub-ledger)

> Status: **RATIFIED (owner-confirmed 2026-06-09).** The owner answered all AP scoping forks — AP is
> **bill-entry-driven** (an operator enters a supplier bill; it is **3-way matched** against the PO and
> the Goods Receipt — quantity AND price — within a **tolerance**; a matched bill becomes a payable that
> posts to GL; **a goods receipt alone does NOT create a payable** — no GRN accrual in v1, an accepted
> trade-off); and the v1 feature set (supplier **payment runs**, single bill payment, **debit
> notes/adjustments**, **opening balances**). Each is reflected below as a fixed v1 requirement;
> everything not chosen has moved to the **Deferred** list (§2). **No ADR-0015-blocking open question
> remains.**
>
> Author: system-analyst · Domain: `ap` (financial / sub-ledger). Business-level spec only.
> **No schema, no API shapes, no tables/columns, no code** — those are the solutions-architect's, in
> **ADR-0015** (next step). Do not infer a data model from this document.
>
> **This is AP — half of Increment 2 of the full-ERP roadmap (docs/ROADMAP.md T1.3 / §5 Increment 2),
> built in parallel with [Accounts Receivable](accounts-receivable.md) (T1.2).** AP is the **supplier
> sub-ledger**: the per-supplier detail (supplier bills, payables, payments, balances) behind the GL
> **`2100 Accounts Payable`** control account. Unlike a credit sale (where Sales' GL handler already
> debited the AR control), **a goods receipt did NOT post to GL** — so the **AP bill match is the FIRST
> GL posting for the purchase** (state this clearly; it is the AP analogue of "AR must not double-post").
>
> **Depends on:** **GL** (the books — ADR-0013 / V10; the `ACCOUNTS_PAYABLE` control account + the
> `gl_configs` map + `GLPostingService` + the outbox; the `INVENTORY` / purchases-expense roles seeded
> but not yet posted — gl.md §10.2); **Purchases** (PO → Goods Receipt; GR lines carry
> `unit_cost_amount` / `qty_in_base`; the `STOCK.RECEIVED` event — ADR-0011, V8); **Parties** (Supplier
> master, goods/service sub-kinds, per-company + multi-branch scope — ADR-0006); the transactional
> **outbox** (ADR-0009); **Money** (ADR-0005); and `code_sequence` (bill / payment-run numbering). All
> shipped. **Note: a goods receipt currently posts to STOCK only, NOT to GL** (ADR-0011 / gl.md §1) — so
> AP's bill match is where the purchase first hits the books.

## 1. Business context & why now

The company buys to sell. Purchases (V8) raises a **Purchase Order**, then records a **Goods Receipt
(GR/GRN)** against it; the GR pushes stock **IN** via the `STOCK.RECEIVED` event (purchases.md §3). But
Purchases v1 deliberately created **no payable** — it records what goods cost on the PO/GR and owes
nothing (purchases.md §10.3, OQ-PURCH-05). And GL Increment 1 posts **nothing** for a purchase: the goods
receipt hits **Stock only**, not the books (the COGS/inventory posting is deferred to T2.2 — gl.md §10.2).
So today: stock rises, but **the liability to the supplier is nowhere on the books, and there is no record
of what we owe whom**. AP closes that gap.

Accounts Payable answers the questions a payables clerk and a controller ask: **who do we owe, how much,
for which bill, due when, and what have we paid.** It does this by recording, per supplier:

- a **supplier bill** (the supplier's invoice / demand for payment) the operator **enters**;
- a **3-way match** of that bill against the **PO** and the **Goods Receipt** — quantity **and** price —
  within a **tolerance**; a bill that matches **within tolerance** becomes a **payable** and **posts to
  GL** (the first GL posting for the purchase); a bill **outside tolerance** is **held for review**;
- **payments** (single, or a **payment run** that batch-selects due/matched bills into one payment) that
  settle payables and post the cash side to GL;
- **debit notes / adjustments** against open payables (a supplier credit, an over-charge correction);
- a per-supplier **balance** and **opening balances** entered at go-live.

**AP is bill-entry-driven, not receipt-driven (the owner's ruling).** The operator enters the supplier's
bill; the goods receipt alone does **not** create a payable. The accepted consequence — stated as a known
trade-off (§10) — is that **between receiving the goods and entering the bill, the liability is not on the
books** (no GRNI accrual in v1). This is the classic bill-driven-AP gap; the owner has accepted it for v1.

### The reconciliation rule (read this before anything else)

The **AP sub-ledger is the detail behind the GL `2100 Accounts Payable` control account.** The two must
agree **at all times**: the sum of every supplier's open-payable balance **equals** the GL AP control
account balance (BR-AP-02, NFR-AP-01). The non-obvious consequence — and the difference from AR — is
**when the purchase first hits the books**:

- A goods receipt **did NOT post to GL** (Stock only — gl.md §1/§10.2). So the **AP bill match is the
  FIRST GL posting for the purchase.** When a bill matches within tolerance, AP posts **DR
  Inventory-or-Purchases / DR VAT input (if any) → CR `2100 Accounts Payable` control** — creating the
  liability on the books and recording the payable in the sub-ledger, the same amount, reconciled. (This
  is the mirror of the AR no-double-post rule, stated from the other side: AR must *not* re-post because
  Sales already did; AP *must* post because the GR did *not*.)
- A **payment** is a new event: **DR `2100 Accounts Payable` control / CR Cash/Bank** — the payable leaves
  the sub-ledger and the control account drops by the same amount.
- A **debit note / adjustment** reduces a payable: it reduces the sub-ledger payable **and** the control
  account (DR AP control / CR Inventory-or-Purchases-or-VAT) by the same amount.

> **Flag for the architect (ADR-0015):** two ADR decisions. (1) **The AP bill debit account** — whether
> the bill debits **inventory value** (`gl_configs` `INVENTORY`) or a **purchases / GRNI-clearing expense
> account** — is an ADR call. Because **inventory VALUATION + COGS is DEFERRED (roadmap T2.2)**, v1 AP
> books the purchase to **inventory-or-expense per `gl_configs` WITHOUT a COGS roll-up** (no cost-layer,
> no COGS posting — that is T2.2). State this clearly: v1 AP creates the *liability* and books the
> *debit* per config, but does **not** value inventory or compute COGS. (2) **The GL-posting mechanism**
> for a bill match / payment / debit note — a **synchronous `GLPostingService` call** in the same
> transaction as the sub-ledger write, **or** an **outbox event** (`AP.BILL.POSTED` / `AP.PAYMENT.MADE`)
> GL consumes — is an ADR decision (both reconcile). The Cash/Bank GL account a payment's credit lands on
> comes from `gl_configs`; the **full Cash & Bank module is roadmap T1.4** — for v1, an AP payment may
> post its bank leg **directly against a Cash/Bank GL account** from `gl_configs` (the simple bridge).

### Vocabulary (read this first)

- **Accounts Payable (AP)** — money we owe suppliers for bills not yet paid; **the supplier sub-ledger** —
  the per-supplier detail behind the GL `2100 Accounts Payable` control account.
- **Sub-ledger** — a detailed ledger for one control account: AP holds, per supplier, the open payables
  whose **net balance equals** the GL AP control-account balance. The sub-ledger is the *detail*; the GL
  control account is the *summary*.
- **Control account** — the GL account whose balance summarises a sub-ledger. `2100 Accounts Payable` is
  the control account for the AP sub-ledger (gl.md glossary). The sub-ledger must **reconcile** to it.
- **Supplier bill / supplier invoice** — the supplier's **demand for payment** (their invoice), the AP
  document the operator **enters** (`BILL-####`). Distinct from the PO (our order) and the GR (our record
  of receipt) — purchases.md vocabulary. A bill is **3-way matched**, and a matched bill becomes a
  **payable**.
- **Payable** — an entered, matched bill that we **owe**: the supplier-sub-ledger open item, reduced by
  payments and debit notes, closed when fully paid. Created when a bill **matches within tolerance** and
  posts to GL.
- **3-way match** — reconciling the **supplier bill** against the **PO** and the **Goods Receipt** before
  it becomes a payable: **quantity** (bill qty vs received qty vs ordered qty) **and** **price** (bill
  unit cost vs PO unit cost) agree **within a tolerance**. (Purchases v1 built the **two-way** PO↔GR match
  — ordered-vs-received; AP adds the **third leg**, the bill — purchases.md §3.)
- **Tolerance** — the allowed difference, on the price (and/or quantity), within which a bill is matched
  automatically; a bill **outside** tolerance is **held for review**. v1 default (recommended) is **price
  within a small percentage (e.g. 2%) or a small absolute amount**, whichever the owner confirms
  (OQ-AP-01). A concept v1 introduces.
- **GRNI (Goods Received Not Invoiced)** — the liability for goods received but not yet billed. In v1
  there is **NO GRNI accrual**: the goods receipt does not post a liability, so between receipt and bill
  entry the liability is **not on the books** (the accepted bill-driven-AP gap, §10).
- **Payment** — money paid to a supplier against open payables. v1 supports a **single bill payment** and
  a **payment run** (batch). Settling a payment posts **DR AP control / CR Cash/Bank** to GL.
- **Payment run** — a batch operation (`PAYRUN-####`) that **selects due / matched bills** (by supplier,
  due date, etc.) and pays them in **one payment**, settling many payables at once.
- **Debit note / adjustment** — a document that **reduces an open payable** (a supplier credit, an
  over-charge / short-delivery correction); it reduces the sub-ledger payable and the AP control account.
- **Opening balance (AP)** — a supplier's pre-existing payable at go-live, entered so AP starts from the
  actual creditor position (the sub-ledger side of the GL opening-balance journal).

> **Word discipline (carried into the glossary):** a **supplier bill** (the supplier's invoice we enter)
> is **not** a **PO** (our order) and **not** a **Goods Receipt** (our record of arrival) — three distinct
> documents the 3-way match reconciles (purchases.md). A **supplier** (a party) is **not** an **account**
> (a GL bucket) — the AP control *account* `2100` summarises what *suppliers* are owed. A **payable** (an
> open bill in the sub-ledger) is **not** a **journal entry** (the GL posting) — the payable is the
> detail; the GL CR-AP-control leg is the summary. A **debit note** here reduces what *we owe a supplier*
> (the AP side); do not confuse it with a customer **credit note** (the AR side, accounts-receivable.md).

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-09). This is **AP Increment 2 (T1.3)**: the
> supplier sub-ledger — bill entry, 3-way match within tolerance, payables that post to GL, single
> payment + payment runs, debit notes/adjustments, opening balances. AP posts the *purchase liability and
> its settlement* to the existing GL and **reconciles** to the GL AP control account.

### In scope (v1 — "enter a supplier bill, 3-way match it, owe it on the books, and pay it")

- **Bill-entry-driven AP (the owner's ruling).** An operator **enters a supplier bill** (`BILL-####`)
  against a supplier (and, for a goods bill, against a PO / its Goods Receipts). **A goods receipt alone
  does NOT create a payable** — no GRN accrual in v1 (BR-AP-01, §10).
- **3-way match within tolerance.** An entered bill is **3-way matched** against the **PO** and the
  **Goods Receipt** — **quantity** (bill vs received vs ordered) **and** **price** (bill unit cost vs PO
  unit cost) — within a **tolerance**. A bill that matches **within tolerance** becomes a **payable**; a
  bill **outside tolerance** is **held for review** (an operator with permission accepts the variance or
  rejects the bill). The tolerance value is `OQ-AP-01` (recommended default below).
- **Matched bill posts to GL (the FIRST GL posting for the purchase).** A matched bill posts **DR
  Inventory-or-Purchases (per `gl_configs`) [+ DR VAT input if captured] → CR `2100 Accounts Payable`
  control** — creating the liability on the books and the payable in the sub-ledger (BR-AP-03). v1 books
  the debit to **inventory-or-expense per config WITHOUT a COGS roll-up** (inventory valuation + COGS are
  T2.2, deferred — §10, the architect's flag).
- **Supplier payments — single + payment run.** Pay a **single** matched/due bill, or run a **payment run**
  (`PAYRUN-####`) that **batch-selects due / matched bills** (by supplier, due date) and pays them in
  **one payment**. A payment settles the payable(s) and posts **DR AP control / CR Cash/Bank** to GL (the
  Cash/Bank account from `gl_configs`; full Cash&Bank module deferred to T1.4).
- **Debit notes / adjustments.** A **debit note** (or adjustment) **reduces an open payable** (a supplier
  credit, an over-charge / short-delivery correction): it reduces the sub-ledger payable and posts
  **DR AP control / CR Inventory-or-Purchases-or-VAT** to GL.
- **Per-supplier balances.** A per-supplier **AP balance** read across the supplier's open payables.
- **Opening balances (AP).** Enter suppliers' **pre-existing payables at go-live** as opening payables, so
  AP starts from the actual creditor position; the GL side is the opening-balance journal (gl.md FR-GL-13).
  The sum of AP opening payables must equal the AP control's opening balance (BR-AP-02).
- **Permissions** — `AP.VIEW` (read sub-ledger / balances), `AP.BILL.ENTER` (enter a supplier bill),
  `AP.BILL.MATCH` (run / accept the 3-way match, accept an over-tolerance variance), `AP.PAYMENT.RUN`
  (single payment + payment run), `AP.DEBITNOTE` (raise a debit note / adjustment), `AP.OPENING.SET`
  (enter opening balances); per-company scope; `assertCanActIn` on **every read path**; audit on **every
  mutation**.
- **Numbering** via the generic `code_sequence`, per company: supplier bill `BILL-####`, payment run
  `PAYRUN-####` (and a single payment / debit-note series, the architect's `entity_kind`s) — the same
  concurrency-safe mechanism Purchases/Sales/GL use.

### Deferred (recognised, NOT built in v1 — separate later increments)

- **GRNI accrual (goods-received-not-invoiced posting).** v1 does **NOT** accrue a liability on goods
  receipt; the liability appears only when the bill is entered + matched (BR-AP-01, the accepted gap
  §10.1). A GRNI-clearing account that accrues on receipt and clears on bill is a later additive slice.
- **Inventory valuation + COGS roll-up** (ROADMAP T2.2) — v1 AP books the purchase debit to
  **inventory-or-expense per `gl_configs`** but **does NOT** value inventory (cost layers) or post COGS;
  that is the Stock-valuation increment (gl.md §10.2). The AP bill debit is the input the valuation work
  later builds on (NFR-AP-08).
- **Cash & Bank module / bank reconciliation** (ROADMAP T1.4) — multiple cash/bank accounts, payment
  method selection, bank-statement reconciliation. v1 posts a payment's bank leg **directly to a Cash/Bank
  GL account** from `gl_configs` (the simple bridge); the full module is T1.4. The payment-run detail is
  built so Cash/Bank settles onto it later (OQ-AP-03).
- **Purchase / input VAT recovery + the VAT return** (ROADMAP T1.5; purchases.md OQ-PURCH-04) — Purchases
  GR lines carry cost but **no VAT** (V8). v1 AP captures the bill's VAT **if the bill states it** for the
  payable amount, but the **periodic input-vs-output VAT return** is T1.5. Whether v1 AP captures input
  VAT on the bill at all is `OQ-AP-04` (recommended: capture the bill total incl. any stated VAT for the
  payable; full input-VAT accounting with T1.5).
- **Supplier payment terms master / early-payment discounts** — a rich terms master (net-30, 2/10 net-30,
  instalments) is deferred; v1 derives a bill due date from the bill's stated terms / a simple net-days
  default (OQ-AP-02).
- **Returns to supplier driving the debit note automatically** (purchases.md OQ-PURCH-06, deferred) — v1
  AP raises a debit note as an AP document; the goods-return flow that auto-generates it is deferred.
- **Multi-currency AP / FX revaluation of open foreign payables** — v1 is base-currency (TZS); FX
  revaluation of open foreign payables at period close is deferred (gl.md §10.5, ADR-0005 D-8).
- **Payment approval workflow / multi-level authorisation of a payment run** — v1 payment run is a
  permissioned, audited act; a multi-step approval (X.5) is deferred (OQ-AP-03).
- **Landed cost apportionment** (purchases.md OQ-PURCH-07) — v1 AP books the supplier's line cost; freight
  / duty apportionment to a true landed cost is deferred (tied to valuation, T2.2).

### Explicitly NOT this module

- **The General Ledger itself** — GL (ADR-0013) owns the books and the AP **control account**; AP holds
  the **sub-ledger detail** and posts the *new* events (bill match, payment, debit note) to GL. AP never
  edits a posted journal; corrections are reversals / debit notes (BR-AP-09).
- **The PO and the Goods Receipt** — Purchases owns the PO/GR (ADR-0011) and the PO↔GR two-way match; AP
  **reads** them (DTO-only) to perform the **3-way** match (adding the bill leg). AP never owns or edits a
  PO/GR.
- **The supplier master** — Parties owns the Supplier; AP consumes its DTO. AP never defines a supplier.
- **Cash & bank accounts / reconciliation** — the Cash & Bank module (T1.4). AP posts the bank leg of a
  payment to a Cash/Bank GL account; it is not a bank-reconciliation system.
- **Stock valuation / COGS** — the Stock-valuation increment (T2.2). AP books the purchase liability +
  debit per config; it does not value inventory or roll up COGS.
- **Accounts Receivable** — who owes *us* is the sibling [AR module](accounts-receivable.md) (T1.2). AP is
  suppliers-we-owe only.
- **Financial statements & analytics** — P&L, balance sheet, creditor dashboards are Reporting (T2.3); AP
  provides the sub-ledger detail they read.

## 3. The sub-ledger: bills, 3-way match, payables, payments, and the GL posting

### 3.1 The AP sub-ledger (the detail behind the control account)

The AP sub-ledger holds, per **supplier**, the **payables** (one per matched bill) reduced by payments and
debit notes. A supplier's **AP balance** = the sum of its open payables. The **sum of every supplier's AP
balance == the GL `2100 Accounts Payable` control-account balance** — the reconciliation invariant
(BR-AP-02). The sub-ledger is company-scoped; bills and payments carry the originating branch as an
analysis tag (consistent with GL keeping the books at company level — gl.md NFR-GL-01).

### 3.2 Enter a bill → 3-way match within tolerance → post (the first GL posting for the purchase)

1. An AP clerk (`AP.BILL.ENTER`) **enters a supplier bill** (`BILL-####`): the supplier, the bill's lines
   (product, qty, unit cost), the bill total (and any stated VAT — OQ-AP-04), and the **PO / Goods
   Receipts** it bills against (for a goods bill).
2. The system runs the **3-way match** (`AP.BILL.MATCH`): for each line it reconciles **quantity** (bill
   qty vs the GR's received qty vs the PO's ordered qty) **and** **price** (bill unit cost vs the PO unit
   cost), within the **tolerance** (OQ-AP-01).
3. **Within tolerance** → the bill **matches**, becomes a **payable**, and **posts to GL** (the first GL
   posting for the purchase): **DR Inventory-or-Purchases (`gl_configs`) [+ DR VAT input if captured] → CR
   `2100 Accounts Payable` control** for the bill total. The sub-ledger payable and the control credit are
   the **same amount**, reconciled (BR-AP-03).
4. **Outside tolerance** → the bill is **held for review** (a price or quantity variance): an operator with
   `AP.BILL.MATCH` either **accepts the variance** (audited — the bill then posts as in step 3) or
   **rejects** the bill (no payable, no posting) (BR-AP-04). Nothing posts while a bill is held.

> v1 books the debit to **inventory-or-expense per `gl_configs` WITHOUT a COGS roll-up** — it creates the
> liability and the debit, but does **not** value inventory (cost layers) or post COGS (that is T2.2,
> deferred — §10, the architect's flag).

### 3.3 Pay a bill → settle the payable → post (single payment + payment run)

- **Single payment** (`AP.PAYMENT.RUN`): pay one matched/due bill; the payable is settled (or partly
  settled) and GL posts **DR `2100 Accounts Payable` control / CR Cash/Bank (`gl_configs`)** for the paid
  amount.
- **Payment run** (`PAYRUN-####`, `AP.PAYMENT.RUN`): **batch-select** due / matched bills (by supplier,
  due date) and pay them in **one payment**; each selected payable is settled and the GL posts **DR AP
  control / CR Cash/Bank** for the total (the per-bill split recorded in the sub-ledger). **No payable is
  paid twice** (a paid/settled payable is excluded from a later run — BR-AP-06).

The cash/bank account a payment credits comes from `gl_configs`; the **full Cash & Bank module is T1.4** —
v1 posts the bank leg directly to that GL account (the simple bridge).

### 3.4 Debit note / adjustment (reduce an open payable)

A **debit note** (`AP.DEBITNOTE`) **reduces** an open payable (a supplier credit, an over-charge or
short-delivery correction): it reduces the sub-ledger payable and posts **DR `2100 Accounts Payable`
control / CR Inventory-or-Purchases-or-VAT** for the credited amount — the payable and the control account
drop by the same amount, reconciled (BR-AP-02). (The goods-return flow that would auto-generate a debit
note is deferred — purchases.md OQ-PURCH-06.)

### 3.5 Opening balances (AP)

At go-live an accountant (`AP.OPENING.SET`) enters each supplier's **pre-existing payable** as an opening
payable; the **sum of opening payables equals the AP control account's opening balance** (the GL side is
the opening-balance journal, gl.md FR-GL-13). Reconciliation holds from day one (BR-AP-02).

## 4. Actors / personas

- **AP clerk / payables clerk** — **enters supplier bills** (`AP.BILL.ENTER`), runs/accepts the **3-way
  match** (`AP.BILL.MATCH`, including accepting an over-tolerance variance), raises **debit notes**
  (`AP.DEBITNOTE`); reads the sub-ledger (`AP.VIEW`).
- **Payments officer / treasurer** — runs **single payments and payment runs** (`AP.PAYMENT.RUN`),
  selecting due/matched bills and paying them; reconciles the AP sub-ledger to the GL AP control account.
- **Accountant / bookkeeper** — enters AP **opening balances** at go-live (`AP.OPENING.SET`); reconciles
  the AP sub-ledger to the GL AP control account; reviews held (over-tolerance) bills.
- **Buyer / storekeeper (upstream, not an AP persona)** — owns the PO and the Goods Receipt (Purchases);
  AP reads their PO/GR (DTO-only) to match the bill. Named here only for the match handoff.
- *(No SYSTEM auto-creator on the bill side — AP is **bill-entry-driven**: the operator enters the bill;
  the goods receipt does NOT auto-create a payable, BR-AP-01. This is the deliberate contrast with AR,
  whose open item is system-created from `SALE.FINALISED`.)*

## 5. Functional requirements

> IDs are `FR-AP-NN`. Each is a crisp, testable, **ratified** statement. "Payable" = a matched supplier
> bill we owe; "post to GL" = a `GLPostingService` posting (mechanism is the architect's, §1 flag); "the
> control account" = GL `2100 Accounts Payable`.

### Bill entry & the no-accrual rule

- **FR-AP-01** A user with `AP.BILL.ENTER` may **enter a supplier bill** (`BILL-####` from `code_sequence`,
  per company): the supplier, the bill lines (product, quantity, unit cost), the bill total (and any
  stated VAT — OQ-AP-04), a bill date and due date (OQ-AP-02), and — for a goods bill — the **PO and its
  Goods Receipt(s)** the bill is matched against.
- **FR-AP-02** **A Goods Receipt alone does NOT create a payable** — AP is **bill-entry-driven**. No
  liability is recorded, and **nothing posts to GL**, until a bill is **entered and matched** (BR-AP-01,
  §10.1 accepted gap). The goods receipt continues to post **Stock only** (purchases.md / gl.md §10.2);
  AP does not consume `STOCK.RECEIVED` to create a payable.

### 3-way match & tolerance

- **FR-AP-03** An entered goods bill is **3-way matched** against the **PO** and the **Goods Receipt**:
  for each line the system reconciles **quantity** (bill qty vs received qty vs ordered qty) **and**
  **price** (bill unit cost vs PO unit cost), within a configured **tolerance** (OQ-AP-01). AP reads the
  PO/GR as **DTOs** (it never imports a Purchases entity — NFR-AP-06).
- **FR-AP-04** A bill that matches **within tolerance** becomes a **payable** and **posts to GL**
  (FR-AP-06). A bill **outside tolerance** is **held for review**: a user with `AP.BILL.MATCH` either
  **accepts the variance** (audited — the bill then posts) or **rejects** the bill (no payable, no
  posting). **Nothing posts while a bill is held** (BR-AP-04).
- **FR-AP-05** The **tolerance** is a configured price (and/or quantity) variance; the recommended v1
  default is **price within a small percentage (e.g. 2%) or a small absolute amount, whichever is
  greater** (OQ-AP-01). The exact value/shape is confirmed before go-live; the requirement fixes that a
  tolerance exists and an over-tolerance bill is held, not auto-posted.

### Payable posting & reconciliation (the first GL posting for the purchase)

- **FR-AP-06** A **matched bill** posts to GL — **the FIRST GL posting for the purchase** (the goods
  receipt did not post to GL — gl.md §10.2): **DR Inventory-or-Purchases** (the `gl_configs` account —
  `INVENTORY` or a purchases/GRNI-clearing/expense account, the architect's choice) **[+ DR VAT input if
  captured] → CR the AP control account** for the bill total. v1 books the debit **per `gl_configs`
  WITHOUT a COGS roll-up** (inventory valuation + COGS are T2.2, deferred). The GL-posting mechanism
  (synchronous `GLPostingService` call vs an outbox event) is the architect's (§1 flag).
- **FR-AP-07** Every GL posting AP makes obeys the GL invariants (balanced, open period, active accounts —
  gl.md BR-GL-01/03/04). A bill match / payment / debit note whose **posting date falls in a closed
  period** is handled per the GL closed-period policy (gl.md OQ-GL-01); a missing required `gl_configs`
  mapping (the bill debit account, Cash/Bank, the AP control) **fails the operation** rather than
  mis-posting (gl.md BR-GL-10).
- **FR-AP-08** The system maintains the **reconciliation invariant**: the **sum of all suppliers' AP
  sub-ledger balances equals the GL `2100 Accounts Payable` control-account balance** at all times
  (BR-AP-02, NFR-AP-01). A reconciliation read (sub-ledger total vs control balance) is available to
  finance; a discrepancy is a finance-grade defect.

### Payments — single + payment run

- **FR-AP-09** A user with `AP.PAYMENT.RUN` may **pay a single** matched/due bill: the payable is settled
  (fully or partly) and GL posts **DR the AP control account / CR Cash/Bank** (`gl_configs`) for the paid
  amount.
- **FR-AP-10** A user with `AP.PAYMENT.RUN` may run a **payment run** (`PAYRUN-####`): **batch-select due /
  matched bills** (by supplier, due date) and pay them in **one payment**; each selected payable is
  settled and GL posts **DR AP control / CR Cash/Bank** for the total, the per-bill split recorded in the
  sub-ledger.
- **FR-AP-11** **No payable is paid twice.** A fully-settled payable is **excluded** from a later single
  payment or payment run; a partly-paid payable shows its **remaining** balance and is payable only up to
  that remainder (BR-AP-06). Over-payment of a payable is rejected.

### Debit notes & opening balances

- **FR-AP-12** A user with `AP.DEBITNOTE` may raise a **debit note / adjustment** against an open payable
  (a supplier credit, an over-charge / short-delivery correction): it **reduces** the sub-ledger payable
  and posts **DR the AP control account / CR Inventory-or-Purchases-or-VAT** for the credited amount,
  audited (BR-AP-07).
- **FR-AP-13** A user with `AP.OPENING.SET` may **enter AP opening balances at go-live**: a pre-existing
  payable per supplier (amount, bill date, due date) so AP starts from the actual creditor position. The
  **sum of AP opening payables must equal the AP control account's opening balance** (the GL side is the
  opening-balance journal, gl.md FR-GL-13) — reconciliation holds from day one (BR-AP-02).

### Scope & permissions

- **FR-AP-14** AP is **scoped per company**; every bill, payable, payment, payment run, debit note, and
  opening balance belongs to exactly one company; no read or balance crosses company scope.
  `assertCanActIn` guards **every read path** (BR-AP-08, NFR-AP-01). Bills/payments may carry the
  originating **branch** as an analysis tag (the sub-ledger, like the books, is kept at company level —
  gl.md NFR-GL-01).
- **FR-AP-15** All AP operations are **gated by IAM permissions**: `AP.VIEW`, `AP.BILL.ENTER`,
  `AP.BILL.MATCH`, `AP.PAYMENT.RUN`, `AP.DEBITNOTE`, `AP.OPENING.SET`. Exact codes are seeded with the
  module (FR-IAM-11). Per-company scope; **audit on every mutation** (NFR-AP-03).

## 6. Business rules (invariants)

> Ratified. These are the AP invariants; a violation that breaks reconciliation is a finance-grade defect
> (a release blocker).

- **BR-AP-01 — Bill-driven; a Goods Receipt does NOT create a payable.** No payable exists, and nothing
  posts to GL, until an operator **enters a supplier bill** and it **matches**. The goods receipt posts
  Stock only. The accepted consequence — **the liability is not on the books between receipt and bill
  entry** (no GRNI accrual in v1) — is an **owner-accepted risk** (§10.1), not a defect.
- **BR-AP-02 — Sub-ledger reconciles to the GL control account.** The **sum of all suppliers' AP balances
  equals the GL `2100 Accounts Payable` control-account balance at all times**. Because **no GL posting
  happened on the goods receipt**, the **AP bill match is the FIRST GL posting for the purchase** (CR AP
  control); a **payment** debits the control, a **debit note** reduces it. A sub-ledger that drifts from
  the control account is a **release blocker** (FR-AP-06/08, NFR-AP-01).
- **BR-AP-03 — A matched bill posts CR AP control to GL.** Within-tolerance match → **DR
  Inventory-or-Purchases (per `gl_configs`) [+ DR VAT input if captured] → CR AP control** for the bill
  total, booking the liability on the books and the payable in the sub-ledger, the same amount (FR-AP-06).
  v1 books the debit **without a COGS roll-up** (valuation/COGS is T2.2).
- **BR-AP-04 — 3-way match within tolerance; over-tolerance held.** A bill matches only when **quantity
  and price** agree with the PO/GR within the configured **tolerance**; an over-tolerance bill is **held
  for review** (accept-variance — audited — or reject), and **nothing posts while held** (FR-AP-03/04).
- **BR-AP-05 — A payment posts DR AP control / CR Cash/Bank.** Settling a payable (single or via a payment
  run) posts **DR the AP control account / CR Cash/Bank** (`gl_configs`) for the paid amount, dropping the
  sub-ledger payable and the control account by the same amount (FR-AP-09/10).
- **BR-AP-06 — No double-pay.** A fully-settled payable is **excluded** from a later payment / payment run;
  a partly-paid payable is payable only up to its **remaining** balance; over-payment is rejected
  (FR-AP-11).
- **BR-AP-07 — A debit note reduces a payable via GL.** A debit note reduces the open payable in the
  sub-ledger and posts **DR AP control / CR Inventory-or-Purchases-or-VAT** for the credited amount, the
  same on both sides (FR-AP-12).
- **BR-AP-08 — One company's payables are isolated.** Every bill, payable, payment, payment run, debit
  note, and opening balance **belongs to exactly one company**; no read or balance crosses company scope.
  Cross-company AP leakage is a **release blocker** (NFR-AP-01).
- **BR-AP-09 — Append-only; correct via debit note / reversal, not edit.** A posted bill match / payment /
  debit note is **not silently edited or deleted**; a correction is a **reversal** (a cancelling payment /
  a reversing match) or a **debit note** — mirroring the GL append-only rule (PROJECT-CONVENTIONS §3.6,
  gl.md BR-GL-02). The sub-ledger keeps a full history.
- **BR-AP-10 — Base-currency reconciliation (v1).** AP balances and the GL control account are in the
  company **base currency** (TZS in practice); a foreign-currency payable is converted at entry (mirrors
  gl.md BR-GL-06); FX revaluation of open foreign payables is deferred (§2).
- **BR-AP-11 — No COGS / inventory valuation in v1.** AP books the purchase **liability** and a **debit**
  to inventory-or-expense per `gl_configs`, but **computes no cost layer and posts no COGS** (inventory
  valuation + COGS are T2.2, deferred — gl.md §10.2). No consumer may assume v1 AP valued the inventory it
  booked.

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Enter a bill → 3-way match → post (the first GL posting for the purchase) — happy path
1. An AP clerk (`AP.BILL.ENTER`, active company) **enters a supplier bill** (`BILL-####`): supplier, lines
   (product, qty, unit cost), total, bill/due dates, and the **PO + Goods Receipt(s)** it bills against.
2. The system runs the **3-way match** (`AP.BILL.MATCH`): per line, **quantity** (bill vs received vs
   ordered) **and** **price** (bill unit cost vs PO unit cost) within the **tolerance** (OQ-AP-01).
3. **Within tolerance** → the bill **matches**, becomes a **payable**, and **posts to GL**: **DR
   Inventory-or-Purchases (`gl_configs`) [+ DR VAT input if any] → CR AP control** for the bill total
   (BR-AP-03). The sub-ledger payable and the control credit are the same amount (reconciled, FR-AP-08).
4. The bill entry + match + post are **audited**; the supplier's AP balance reflects the new payable.

### 7.2 Bill outside tolerance → held for review — happy path
1. The match (7.1.2) finds a **price or quantity variance beyond tolerance**.
2. The bill is **held for review**; **nothing posts** (BR-AP-04).
3. An operator with `AP.BILL.MATCH` either **accepts the variance** (audited — the bill then posts as
   7.1.3) or **rejects** the bill (no payable, no posting).

### 7.3 Pay matched bills — single payment & payment run — happy path
1. A payments officer (`AP.PAYMENT.RUN`) pays a **single** matched/due bill, **or** runs a **payment run**
   (`PAYRUN-####`) that **batch-selects** due/matched bills (by supplier, due date).
2. Each selected payable is **settled** (fully or partly); GL posts **DR AP control / CR Cash/Bank**
   (`gl_configs`) for the paid amount/total (the per-bill split recorded in the sub-ledger).
3. A fully-settled payable is **excluded** from future runs (no double-pay, BR-AP-06); the payment is
   **audited**; supplier balances and the control account drop by the same amount (reconciled).

### 7.4 Debit note against an open payable — happy path
1. An AP clerk (`AP.DEBITNOTE`) raises a **debit note** against an open payable (a supplier credit /
   over-charge correction) with a reason.
2. The payable is **reduced** in the sub-ledger; GL posts **DR AP control / CR Inventory-or-Purchases-or-
   VAT** for the credited amount (BR-AP-07); the debit note is **audited**.

### 7.5 Enter AP opening balances — happy path
1. At go-live an accountant (`AP.OPENING.SET`) enters each supplier's **pre-existing payable** (amount,
   bill date, due date) as an opening payable (FR-AP-13).
2. The **sum of opening payables equals the AP control account's opening balance** (the GL side is the
   opening-balance journal, gl.md FR-GL-13); reconciliation holds from day one (BR-AP-02).

### 7.6 Main unhappy paths
- **Bill outside tolerance** (7.1.2) → **held for review**; nothing posts until accepted or rejected
  (BR-AP-04, §7.2).
- **Bill with no matching PO/GR** (a service bill, or a bill that references no goods receipt) → v1 path is
  the architect's (a non-goods/expense bill may post without the goods 3-way match) — **OQ-AP-04 / the
  service-bill question** (recommended: v1 focuses on the goods 3-way match; a pure expense bill is a
  later additive slice or posts to an expense account without GR matching).
- **Partial bill vs partial GR** (a bill for some of the received quantity, or a GR not yet fully billed)
  → the match handles the billed portion; the rest stays open — the exact partial-match policy is
  **OQ-AP-05** (recommended: match per line up to the received-not-yet-billed quantity; over-billing the
  received quantity is held as a variance).
- **Payment / bill match / debit note would post into a CLOSED period** → handled per the GL closed-period
  policy (gl.md OQ-GL-01); finance reopens or moves the date (FR-AP-07).
- **Missing required `gl_configs` mapping** (the bill debit account, Cash/Bank, the AP control) → the
  operation **fails** rather than mis-posting; finance sets the mapping (`GL.MANAGE`), then retries
  (gl.md BR-GL-10, FR-AP-07).
- **Attempt to pay a payable twice** (7.3) → the settled payable is **excluded** / over-payment rejected
  (BR-AP-06, FR-AP-11).
- **Attempt to edit a posted bill / payment** (any) → **refused**; correct via a cancelling payment /
  reversing match / debit note (append-only, BR-AP-09).
- **Sub-ledger total ≠ GL control balance** (reconciliation read) → a **finance-grade defect** surfaced for
  investigation (FR-AP-08, NFR-AP-01).

## 8. Non-functional

- **NFR-AP-01 — Reconciliation integrity & tenant isolation.** The **AP sub-ledger total must equal the GL
  `2100 Accounts Payable` control-account balance** for every company at all times (BR-AP-02); a drift is
  a **release blocker**. Every AP row is scoped by `company_id` through the tenant-predicate repository
  base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS §3.2); `assertCanActIn` guards **every read path**.
  Cross-company AP leakage is a **release blocker**, as for GL/Purchases.
- **NFR-AP-02 — Money correctness.** Every amount is a `Money` (amount + currency, ADR-0005) in the
  company base currency; the bill total, the GL legs, and payment allocations sum **exactly** (no float,
  `BigDecimal` compare, rounding per ADR-0005 D-2 / gl.md NFR-GL-02, OQ-CUR-03). A matched bill whose GL
  debit + AP-control credit do not balance is a defect.
- **NFR-AP-03 — Audit.** Every **mutation** — bill entry, the 3-way match (incl. accept-variance and
  reject), payable creation, single payment, payment run, debit note, and opening-balance entry — is
  written to the IAM append-only audit trail with actor, action, target, timestamp, and company/branch
  context (mirrors NFR-GL-06).
- **NFR-AP-04 — No-double-pay integrity.** A payable's settled / remaining amount must serialise under
  concurrency so a single payment and a concurrent payment run cannot pay the same payable twice or
  over-pay it (BR-AP-06, FR-AP-11). The mechanism is the architect's; the requirement is consistent
  remaining-balance under concurrency.
- **NFR-AP-05 — Numbering concurrency.** Two clerks entering bills, or two officers running payments,
  simultaneously get distinct `BILL-####` / `PAYRUN-####` numbers (the `code_sequence` row-locked
  allocation — ADR-0007 D-6).
- **NFR-AP-06 — DTO-only consumption.** AP reads the **PO / Goods Receipt** (for the 3-way match) and the
  **Supplier** through DTOs; it **never imports a Purchases or Parties entity** (ADR-0009 D-1;
  `ModuleBoundaryTest`). It posts to GL through the `GLPostingService` / outbox boundary, not by importing
  GL entities beyond the posting contract.
- **NFR-AP-07 — Timestamps** are UTC, displayed per company time zone (Africa/Dar_es_Salaam default,
  iam.md locale). The payable's **due date** and a payment's **value date** are business dates, distinct
  from the posting timestamp.
- **NFR-AP-08 — Forward-compatibility.** The v1 model must not preclude the later increments that build on
  AP: **inventory valuation + COGS** consuming the AP bill cost (T2.2); a **GRNI-clearing accrual** on
  receipt; the **Cash & Bank** module owning the payment's bank leg (T1.4); **input-VAT recovery + the
  VAT return** (T1.5); a **supplier payment-terms** master; a **payment approval workflow** (X.5); and
  **multi-currency AP / FX revaluation** (gl.md §10.5). Building these is deferred; precluding them is a
  defect.

## 9. Assumptions

- The dependency platform exists and is consumed as designed: **GL** (ADR-0013 / V10 — the
  `ACCOUNTS_PAYABLE` control account, `gl_configs` `INVENTORY`/purchases + Cash/Bank mappings,
  `GLPostingService`, the outbox) is shipped; **Purchases** owns the PO → Goods Receipt with GR lines
  carrying `unit_cost_amount` / `qty_in_base` (ADR-0011, V8); **Parties** Supplier master (V2); **Money**
  (ADR-0005) and `code_sequence` are in place. All shipped.
- **A goods receipt posts to STOCK only, not GL** (ADR-0011 / gl.md §1/§10.2) — confirmed; this is why the
  **AP bill match is the first GL posting for the purchase** (BR-AP-02). AP does **not** rely on any GL
  posting having happened on receipt.
- **The GL AP control account is `2100`** per the seeded TZ CoA + `gl_configs` `ACCOUNTS_PAYABLE` mapping
  (gl.md §3.1, ADR-0013); AP resolves the control account and the bill debit account through `gl_configs`,
  never a hard-coded code.
- **The AP bill debit account choice** (inventory value vs a purchases / GRNI-clearing expense account)
  is the **architect's** (ADR-0015, §1 flag); v1 books per `gl_configs` **without a COGS roll-up**
  (BR-AP-11). The requirement fixes the *liability* (CR AP control) and that a debit is booked per config,
  not the exact debit account.
- **Purchases GR lines carry cost but no VAT** (V8 — purchases.md FR-PURCH-13); v1 AP captures the bill's
  VAT **if the bill states it** for the payable total (OQ-AP-04), but full input-VAT accounting + the VAT
  return are T1.5.
- **Document currency = company base (TZS)** in practice for v1; the convert-at-entry shape supports a
  foreign-currency payable but FX depth is deferred (BR-AP-10).

## 10. ACCEPTED RISK & accepted scope boundary — what AP v1 deliberately does NOT do (owner-accepted 2026-06-09)

> **Read this before building or consuming AP.** AP v1 delivers the **supplier sub-ledger** (bill entry,
> 3-way match within tolerance, payables that post to GL, single payment + payment runs, debit notes,
> opening balances), reconciling to the GL AP control account. The following are **deliberate
> boundaries**, owner-accepted.

1. **The liability is NOT on the books between goods receipt and bill entry — ACCEPTED RISK (the
   bill-driven-AP gap).** AP is **bill-entry-driven**: the goods receipt creates **no payable** and posts
   **no liability** (no GRNI accrual in v1). Between receiving the goods and entering the supplier's bill,
   the amount owed is **not reflected on the books**. The owner has signed off this classic bill-driven-AP
   trade-off for v1 (BR-AP-01). A GRNI-clearing accrual that closes the gap is a later additive slice.

2. **The AP bill match is the FIRST GL posting for the purchase.** Because the goods receipt posts Stock
   only (gl.md §10.2), AP's matched bill is where the purchase first hits the books (CR AP control). This
   is by design, not a gap — the mirror of AR's no-double-post rule.

3. **No COGS / inventory valuation in v1.** AP books the purchase **liability** and a **debit** to
   inventory-or-expense per `gl_configs`, but **computes no cost layer and posts no COGS** — inventory
   valuation + COGS are the Stock-valuation increment (T2.2, gl.md §10.2). The AP bill cost is the input
   that work later builds on (NFR-AP-08).

4. **Cash & Bank reconciliation is a SEPARATE increment (T1.4).** v1 posts a payment's bank leg **directly
   to a Cash/Bank GL account** from `gl_configs` (the simple bridge). A full cash/bank module (multiple
   accounts, payment-method selection, bank-statement reconciliation) is deferred. The payment-run detail
   is built so Cash/Bank settles onto it later (OQ-AP-03).

5. **Input-VAT recovery + the VAT return are T1.5.** v1 captures the bill total (incl. any stated VAT) for
   the payable; the periodic input-vs-output VAT return is deferred (OQ-AP-04).

6. **Multi-currency AP / FX revaluation DEFERRED.** v1 reconciles in the **base currency** (BR-AP-10);
   revaluing open foreign payables at period close is out of scope (gl.md §10.5), not precluded
   (NFR-AP-08).

All are additive by design (NFR-AP-08); none is precluded by the v1 model.

## 11. Open questions — status after ratification (2026-06-09)

> The **AP scoping forks** the owner answered (bill-entry-driven AP; 3-way match within tolerance; no GRN
> accrual; payment runs + single payment; debit notes; opening balances) are **RESOLVED** (recorded in
> `docs/requirements/open-questions.md` under AP). **No ADR-0015-blocking open question remains.** What
> stays open is **non-blocking** detail with a recommended default that stands unless the owner overrides
> — confirm during build / before go-live, not before ADR-0015.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0015)

- **OQ-AP-01 — Tolerance value/shape.** A tolerance exists and an over-tolerance bill is held (BR-AP-04);
  the **value** is open. *Recommended default:* **price within 2% OR a small absolute amount (whichever is
  greater), per line**, owner-set; quantity must match the received quantity (no quantity tolerance by
  default). *Decider:* owner (finance). *Blocks ADR-0015:* **NO** — the tolerance *concept* is fixed; the
  value is a configurable setting confirmed before go-live.
- **OQ-AP-02 — Bill due date / supplier terms.** A bill carries a due date; v1 derives it from the bill's
  stated terms / a simple net-days default. *Recommended default:* due date from the bill's stated terms
  if present, else a per-supplier net-days field, else net-on-receipt (0 days); a rich terms master is a
  later additive slice. *Decider:* owner. *Blocks ADR-0015:* **NO** — additive.
- **OQ-AP-03 — Payment method / bank selection & payment approval.** v1 posts the payment's bank leg to a
  Cash/Bank GL account from `gl_configs` (full Cash&Bank is T1.4). *Recommended default:* one default
  Cash/Bank GL account per company from `gl_configs`; payment-method (cash / bank transfer / mobile money)
  selection and a payment-approval workflow are later additive slices (Cash&Bank T1.4 / Approvals X.5).
  *Decider:* owner. *Blocks ADR-0015:* **NO** — additive.
- **OQ-AP-04 — Input VAT on the bill / service (non-goods) bills.** Whether v1 AP captures input VAT on the
  bill (for the eventual VAT return) and whether a **pure expense / service bill** (no goods receipt to
  match) is in v1. *Recommended default:* capture the bill total **incl. any stated VAT** for the payable
  (the VAT *return* is T1.5); v1 focuses on the **goods 3-way match** — a pure expense/service bill posts
  to an expense account **without** the goods 3-way match (a thin additive path) or is deferred, owner's
  call. *Decider:* owner. *Blocks ADR-0015:* **NO** — the goods 3-way match (the core) is fixed.
- **OQ-AP-05 — Partial bill vs partial GR.** How the match handles a bill for some of the received
  quantity (or a GR not yet fully billed). *Recommended default:* match **per line up to the
  received-not-yet-billed quantity**; the remainder stays open for a later bill; over-billing the received
  quantity is **held as a variance** (BR-AP-04). *Decider:* owner. *Blocks ADR-0015:* **NO** — the
  recommended per-line partial-match default stands.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Confirm rounding mode (half-up vs banker's) and
  TZS decimal places (0 in practice) — the bill total, the GL legs, and payment allocations must round
  identically to the AP balance and the control account (NFR-AP-02). *Recommended default:* half-up,
  TZS = 0 dp. *Decider:* owner (finance input). *Blocks ADR-0015:* **NO** for the model; **confirm before
  go-live**.

## 12. Out of scope for v1 (deferred — restated)

GRNI accrual on goods receipt (the accepted bill-driven gap §10.1 — no liability between receipt and bill
in v1); **inventory valuation + COGS roll-up** (T2.2 — v1 books the bill debit per `gl_configs` without
COGS); **Cash & Bank module / bank reconciliation** (T1.4 — v1 posts the payment bank leg directly to a
Cash/Bank GL account); **input-VAT recovery + the VAT return** (T1.5, OQ-AP-04); a **supplier
payment-terms master** / early-payment discounts (OQ-AP-02); **returns-to-supplier auto-generating the
debit note** (purchases.md OQ-PURCH-06 — v1 raises a debit note as an AP document); **multi-currency AP /
FX revaluation** of open foreign payables (gl.md §10.5, ADR-0005 D-8); a **payment approval workflow** /
multi-level authorisation (X.5, OQ-AP-03); **landed-cost apportionment** (purchases.md OQ-PURCH-07, tied
to valuation); and the **financial statements** themselves — P&L / Balance Sheet / creditor dashboards
(Reporting, T2.3 — AP provides the sub-ledger they read). Each is tracked for a later increment; none is
precluded by the v1 model (NFR-AP-08).

# Requirements — VAT Return / Tax (the periodic VAT obligation — output vs input, filed to TRA)

> Status: **RATIFIED (owner-confirmed 2026-06-09).** The owner answered all VAT-return scoping forks —
> **MONTHLY returns** (one per calendar month, due the **20th of the next month**) on an **invoice/accrual
> basis** (output VAT from sales **FINALISED** in the month, input VAT from supplier bills **DATED** in the
> month — payment-independent); **net VAT = output VAT − input VAT + adjustments** (net positive = **VAT
> PAYABLE** to TRA; net negative = a **CREDIT** that **carries forward** to the next period, not a cash
> refund in v1); a return lifecycle **DRAFT → FILED** where **filing posts a synchronous GL journal** that
> settles the period's VAT control accounts and **LOCKS** the return (figures frozen, period closed,
> immutable — corrected via the next period's adjustments, not edited); **manual VAT adjustment lines** on a
> DRAFT (bad-debt VAT relief, prior-period corrections, credit/debit-note VAT — reason + amount + sign,
> audited); and **withholding tax (WHT) IN v1** — captured at the point of an AP payment (and/or AR receipt),
> booking a WHT liability/receivable, reducing the cash paid/received, with a WHT register/return and WHT
> certificates. **TRA EFD/VFD fiscalisation / direct e-filing stays DEFERRED** (the return is computed + a
> filing record is kept; no TRA integration in v1) — an accepted boundary. Each is reflected below as a
> fixed v1 requirement; everything not chosen has moved to the **Deferred** list (§2). **No
> ADR-0017-blocking open question remains** (the new `VAT_INPUT` GL account + the AP-input-VAT-booking seam
> is an ADR *decision*, not a requirements blocker — flagged below for the architect).
>
> Author: system-analyst · Domain: `vat` / `tax` (financial / statutory). Business-level spec only.
> **No schema, no API shapes, no tables/columns, no code** — those are the solutions-architect's, in
> **ADR-0017** (next step). Do not infer a data model from this document.
>
> **This is the VAT Return / Tax module — the LAST Tier-1 finance piece (docs/ROADMAP.md T1.5 /
> docs/PATH-TO-FULL-ERP.md Phase A).** It does **not** rebuild the books or the sub-ledgers; it is a
> **periodic computation + filing record** that aggregates the VAT the system already captured per
> transaction — **output VAT** from sales and **input VAT** from supplier bills — into one monthly return,
> nets them, posts the settlement to GL on filing, and carries a net credit forward. It is the bridge from
> "VAT is computed on every invoice and bill" to "the company files one VAT return a month and knows what it
> owes TRA."
>
> **Depends on:** **Sales** (the **output VAT** source — ADR-0008 / V5: `sales_invoices.vat_total_amount`
> + the per-band `tax_summary` JSONB, `finalised_at`, and the `tax_rates` master STANDARD 18 / ZERO_RATED 0
> / EXEMPT; VAT is computed **per invoice** already, tax-exclusive — sales.md FR-SALES-10/11); **Accounts
> Payable** (the **input VAT** source — ADR-0015 / V12: `supplier_bills.vatAmount`, the VAT a bill states,
> with `bill_date`; AP is **bill-driven**, accounts-payable.md §3.2); **GL** (the books — ADR-0013 / V10:
> `gl_configs` maps posting roles to accounts; **`VAT_PAYABLE` → `2200`** exists for **output** VAT, but
> there is **NO VAT input/recoverable account yet** — the filing posting needs one, flagged below; the
> **synchronous `GLPostingService.post`** AR/AP/Cash precedent; `fiscal_periods`; append-only/reversing);
> **Cash & Bank** (ADR-0016 / V13 — the WHT touch rides the **AP payment** / **AR receipt** cash legs
> Cash & Bank routes); **Money** (ADR-0005 — base currency only in v1); **RBAC / `ScopeGuard` / audit** (the
> platform spine); and `code_sequence` (return / WHT numbering). All shipped. **The central integration:**
> the VAT return is a **read-and-net over Sales output VAT + AP input VAT**, posting **one settlement
> journal on filing** — and WHT is an **additive touch to the AP-payment / AR-receipt cash legs** (§3.7,
> flagged for the architect).

## 1. Business context & why now

The company is VAT-registered in Tanzania. Every sale already computes **output VAT** (the VAT we charge
customers — `sales_invoices.vat_total_amount`, broken out by band in `tax_summary`, sales.md FR-SALES-11);
every supplier bill already captures **input VAT** (the VAT we were charged on purchases —
`supplier_bills.vatAmount`, accounts-payable.md OQ-AP-04). And on filing, output VAT already has a home on
the books — the GL **`2200 VAT Payable`** control account that the sales auto-poster credits (gl.md §3.1,
ADR-0013 D-13). But **nothing brings the two sides together into the monthly obligation TRA actually
demands.** There is no record of, for a month: **how much output VAT we charged, how much input VAT we may
recover, what the net is, whether we owe TRA or carry a credit, and that we filed.** The output VAT sits on
`2200`; the **input VAT has no recoverable account at all** (gl.md `gl_configs` has `VAT_PAYABLE` but **no
`VAT_INPUT`**, ADR-0013 D-13) — so today input VAT is not even separable on the books. The company cannot
produce the **VAT return** Tanzanian law requires every month.

**The VAT Return / Tax module closes that gap.** Tanzanian VAT is filed **monthly**, due the **20th of the
month following** the return period (the standard rate is **18%**, administered by the **Tanzania Revenue
Authority — TRA**). The module produces, **per company per month**:

- a **return period** (one calendar month) that **computes** the period's VAT;
- **output VAT** — the sum of `sales_invoices.vat_total_amount` (by tax band, from `tax_summary`) for
  invoices **FINALISED** in the period;
- **input VAT** — the sum of `supplier_bills.vatAmount` for bills **DATED** in the period (matched /
  approved);
- **manual VAT adjustments** on the DRAFT (bad-debt VAT relief, prior-period corrections, credit/debit-note
  VAT) — each a reason + amount + sign, audited;
- the **net VAT** (= output − input + adjustments + any **opening credit carried forward**): net positive
  = **VAT payable to TRA**; net negative = a **credit** that **carries forward** to offset next period;
- a **filing** (DRAFT → FILED) that records a **filing reference** + a **filing date**, **posts a
  synchronous GL settlement journal**, and **LOCKS** the return (figures frozen, the VAT period closed).

And alongside the return, the module tracks **withholding tax (WHT)** — the Tanzanian **withholding on
certain payments** (e.g. the 2% withholding, and **withholding VAT**), captured at the point an **AP
payment** withholds a percentage the company must remit to TRA separately (and/or an **AR receipt** where a
customer withholds on paying us). WHT **reduces the cash paid/received**, **books a WHT liability /
receivable**, produces **WHT certificates**, and feeds a **WHT register / return** for remittance.

### The reconciliation rule (read this before anything else)

The VAT return is a **reconciliation of the VAT control accounts to TRA.** The two sides the system already
posted must be brought together and settled, and the non-obvious consequences are:

- **Output VAT is already on the books** — the sales auto-poster credited **`2200 VAT Payable`** on every
  finalised sale (gl.md §3.1). The return's **output figure is the period's movement on that control
  account** (or, equivalently, the per-band sum of `sales_invoices.vat_total_amount` for the period's
  finalised invoices — the two must agree, BR-VAT-08).
- **Input VAT has NO recoverable control account yet** — `gl_configs` has `VAT_PAYABLE` (output) but **no
  `VAT_INPUT`** (ADR-0013 D-13). **This is the ADR-0017 integration seam:** ADR-0017 must introduce a new
  CoA **"VAT Input / Recoverable"** account (a `1xxx` asset / contra-liability) + a `gl_configs` **`VAT_INPUT`**
  key, and clarify **how AP bills' input VAT relates to it** — i.e. whether AP today books the bill's input
  VAT to that recoverable account **separately**, or whether the bill VAT is currently **embedded in the
  Purchases/Inventory debit** (accounts-payable.md FR-AP-06 books the debit "[+ DR VAT input if captured]").
  The requirement fixes the **behaviour** (input VAT is recoverable and nets against output on filing); the
  **account + the AP-booking mechanism** is the architect's (§1 flag, OQ-VAT-01).
- **Filing settles both control accounts to a VAT-due liability.** On **FILE**, a synchronous GL journal
  **clears the period's output VAT** (DR `2200 VAT Payable`) **and the period's input VAT** (CR the
  `VAT_INPUT` recoverable account) and books the **net to a VAT-due / VAT-payable-to-TRA liability** (the
  net the company will pay TRA), per double-entry, balanced (BR-VAT-06). A **net credit** (input > output)
  is **not** a cash-refund claim in v1 — it **carries forward** as an opening credit on the next period
  (BR-VAT-03).

> **Flag for the architect (ADR-0017):** the load-bearing decision is the **`VAT_INPUT` account + the
> AP-input-VAT-booking seam** (OQ-VAT-01). ADR-0017 must (1) add a CoA **"VAT Input / Recoverable"** account
> + a `gl_configs` **`VAT_INPUT`** key (the IN-list widens additively, as ADR-0013 D-13 anticipated); (2)
> decide how the AP bill's input VAT reaches that account — **either** AP starts booking the bill's stated
> VAT to `VAT_INPUT` at bill-match time (a small additive change to the AP posting, accounts-payable.md
> FR-AP-06's "[+ DR VAT input if captured]" becomes live), **or** the VAT return is computed from
> `supplier_bills.vatAmount` as a **read** and the filing journal is the first time input VAT is separated
> onto the books — both reconcile, the architect chooses; (3) define the **filing journal** precisely (clear
> `2200` output, clear `VAT_INPUT`, book net to a **VAT-due liability** — the architect names the account /
> `gl_configs` key, e.g. `VAT_DUE` or reuse `2200`'s sub-structure); (4) the **carry-forward** mechanism for
> a net credit (an opening credit on the next period); (5) the **WHT touch** on the AP-payment / AR-receipt
> cash legs (a WHT liability/receivable account + `gl_configs` keys); and (6) `ScopeGuard` gains a new
> **`vatreturn`** target type (and the WHT targets the architect names). State these; do not design the
> tables here. **None blocks the requirements** — the behaviour is fixed; the accounts/mechanism are the
> ADR's to choose.

### Vocabulary (read this first)

- **VAT (Value Added Tax)** — the Tanzanian consumption tax (standard rate **18%**), charged on taxable
  sales (**output**) and incurred on taxable purchases (**input**), administered by **TRA**. A
  VAT-registered company **collects** output VAT for TRA and may **recover** input VAT against it,
  remitting the **net** monthly.
- **Output VAT** — the VAT the company **charges customers** on taxable sales — the sum of
  `sales_invoices.vat_total_amount` (by tax band, from `tax_summary`) for invoices **FINALISED** in the
  period (sales.md FR-SALES-11). Already credited to GL **`2200 VAT Payable`** by the sales auto-poster
  (gl.md §3.1). A **liability** to TRA.
- **Input VAT** — the VAT the company **was charged by suppliers** on taxable purchases — the sum of
  `supplier_bills.vatAmount` for bills **DATED** in the period (matched / approved — accounts-payable.md
  FR-AP-01). **Recoverable** against output VAT. Needs a **VAT Input / Recoverable** account that does not
  exist yet (OQ-VAT-01).
- **Net VAT** — **output VAT − input VAT + adjustments + opening credit carried forward**, for the period.
  **Net positive** = **VAT payable** to TRA; **net negative** = a **VAT credit**.
- **VAT payable (to TRA)** — a **net positive** return: the amount the company owes TRA for the period,
  due the **20th of the next month**. A liability the filing journal books.
- **VAT credit** — a **net negative** return (input > output): a credit balance. In v1 it is **NOT** claimed
  as a cash refund — it **carries forward** as an opening credit on the next period (BR-VAT-03).
- **Return period** — one **calendar month** of VAT, **per company**, on an **invoice/accrual basis**
  (output from FINALISED sales in the month, input from supplier bills DATED in the month — payment-
  independent). The unit of the return. **Due the 20th of the following month.**
- **Accrual basis (invoice basis)** — VAT is recognised when the **invoice / bill is dated**, **not** when
  the money moves: output VAT enters the period a sale is **FINALISED**, input VAT enters the period a bill
  is **DATED**. (Cash-basis VAT — recognising on payment — is **deferred**, OQ-VAT-04.)
- **Filing reference** — the reference recorded when a return is **FILED** (the TRA acknowledgement /
  submission reference, **entered by the operator** in v1 — there is no TRA e-filing integration). With the
  **filing date**, it evidences the period was filed.
- **Credit carry-forward** — when a period nets to a **credit** (input > output), that credit becomes the
  **opening credit** on the **next** period's return, offsetting its net (BR-VAT-03). Not a cash refund in
  v1.
- **VAT adjustment** — a **manual line** on a **DRAFT** return that **affects the net**: a reason + an
  amount + a **sign** (increase or decrease net VAT) — bad-debt VAT relief, a prior-period correction, the
  VAT on a credit / debit note. Audited; only on a DRAFT, before filing (BR-VAT-09).
- **Withholding tax (WHT)** — tax the **payer** withholds from a payment and remits to TRA **on behalf of**
  the payee (e.g. the Tanzanian **2% withholding** on certain payments). When **we pay a supplier**, we may
  **withhold** a percentage — paying the supplier **less** and owing the withheld amount to TRA (a **WHT
  liability**). When a **customer pays us**, they may withhold — we **receive less** and hold a claim
  against our own tax (a **WHT receivable / asset**). v1 **captures + tracks** WHT with a register/return;
  it is **not** the full WHT-type matrix (OQ-VAT-02).
- **Withholding VAT (WHT-VAT)** — a specific WHT regime where VAT is withheld at payment (a TRA mechanism).
  In v1 it is modelled as a **WHT type/rate** on the same capture-and-track machinery (not a separate
  engine).
- **WHT certificate** — the document evidencing tax withheld (issued to the supplier when we withhold;
  received from the customer when they withhold), the supporting record for the WHT register / remittance.
- **WHT register / return** — the list of WHT amounts withheld (and received) in a period, the basis for
  **remitting** withheld tax to TRA — a **sibling** to the VAT return, distinct from it (WHT is a separate
  remittance, not part of the output−input net).

> **Word discipline (carried into the glossary):** **output VAT** (we charged customers, a liability on
> `2200`) is **not** **input VAT** (we were charged by suppliers, recoverable) — the return **nets** them.
> **Net VAT** (output − input + adjustments) is **not** the same as **VAT payable** — net *positive* is
> payable, net *negative* is a **credit** that carries forward. A **VAT return** (the periodic output−input
> obligation) is **not** a **WHT return** (a separate withholding remittance) — two distinct statutory
> filings on shared machinery. **Withholding** (tax the payer holds back and remits **for** the payee) is
> **not** VAT (a tax on the supply) — a WHT withheld on a supplier payment reduces the **cash paid**, not
> the bill's VAT. A **filing reference** (TRA's acknowledgement) is **not** a **document number** (our
> internal `VATR-####`). A return is **FILED** (locked, period closed), never "edited" — a posted/filed
> return is corrected via the **next period's adjustments**, mirroring the GL append-only rule.

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-09). This is the **VAT Return / Tax module —
> the last Tier-1 finance piece (T1.5 / Phase A)**: a monthly, accrual-basis VAT return that nets output
> (Sales) against input (AP), takes manual adjustments, files (DRAFT → FILED) with a GL settlement posting +
> credit carry-forward, **plus** withholding-tax capture + a WHT register. It **reads** the VAT already
> computed per transaction and posts **one settlement journal on filing**; it never recomputes per-invoice
> VAT.

### In scope (v1 — "compute the monthly return, net output vs input, adjust, file to a locked GL settlement, carry a credit forward, and track WHT")

- **Monthly return period (accrual basis), per company.** Open / compute **one VAT return per company per
  calendar month** (BR-VAT-01), **due the 20th of the next month**. **Invoice/accrual basis**: output from
  sales **FINALISED** in the month, input from supplier bills **DATED** in the month — **payment-
  independent** (BR-VAT-04/05).
- **Output VAT from finalised sales.** Compute output VAT as the **sum of `sales_invoices.vat_total_amount`
  (by tax band, from the per-band `tax_summary` JSONB)** for invoices **FINALISED** in the period
  (sales.md FR-SALES-11; only FINALISED, never DRAFT/void — BR-VAT-05). Broken out by band (STANDARD 18 /
  ZERO_RATED 0 / EXEMPT) for the return face.
- **Input VAT from supplier bills.** Compute input VAT as the **sum of `supplier_bills.vatAmount`** for
  bills **DATED** in the period that are **matched / approved** (a HELD / over-tolerance bill that has not
  posted is excluded — accounts-payable.md BR-AP-04; BR-VAT-04). Needs a **VAT Input / Recoverable** account
  (OQ-VAT-01, the ADR-0017 seam).
- **Manual VAT adjustments on a DRAFT.** Add **adjustment lines** to a **DRAFT** return — each a **reason**
  + an **amount** + a **sign** (increase / decrease net) — for **bad-debt VAT relief**, **prior-period
  corrections**, and **credit / debit-note VAT**. Each is **audited**; adjustments **affect the net**; they
  may be added/removed only **while DRAFT** (BR-VAT-09).
- **Net VAT + credit carry-forward.** Compute **net VAT = output − input + adjustments + opening credit
  carried forward**. **Net positive** = **VAT payable** to TRA; **net negative** = a **credit** that becomes
  the **opening credit on the next period** (BR-VAT-03) — **not** a cash refund in v1.
- **Return lifecycle DRAFT → FILED (the lock).** A return is **DRAFT** (computed, **recomputable** as more
  invoices / bills land in the period) until **FILED**. **FILE** records a **filing reference** + a **filing
  date**, **posts the GL settlement journal** (below), and **LOCKS** the return — figures **frozen**,
  **immutable**, the **period closed for VAT** (BR-VAT-02). A filed return is **never edited**; corrections
  go to the **next period's adjustments** (append-only — BR-VAT-10).
- **GL settlement posting on filing (synchronous).** On **FILE**, post **one synchronous GL journal** (via
  `GLPostingService.post`, the AR/AP/Cash precedent — ADR-0014 D-4) that **settles the period's VAT control
  accounts**: **clear output VAT** (DR `2200 VAT Payable`), **clear input VAT** (CR the `VAT_INPUT`
  recoverable account), and book the **net to a VAT-due / -payable-to-TRA liability** (net positive) — or
  leave a **carried-forward credit** (net negative), per double-entry, **balanced** (BR-VAT-06). The exact
  accounts are ADR-0017 (the `VAT_INPUT` + VAT-due `gl_configs` keys — §1 flag).
- **Withholding tax (WHT) — capture + track + register (IN v1).** Capture WHT **at the point of an AP
  payment** (we withhold a % from a supplier payment — paying the supplier less, owing TRA a **WHT
  liability**) **and/or an AR receipt** (a customer withholds on paying us — we receive less, hold a **WHT
  receivable/asset**). Each WHT capture: a **WHT rate / type** (incl. withholding VAT as a type), the
  **withheld amount**, the **party** and the **payment/receipt** it rode, and a **WHT certificate** record.
  A **WHT register / return** lists the period's withheld (and received) amounts as the basis for
  **remittance** to TRA. WHT **reduces the cash paid/received** and **books a WHT liability/receivable** —
  an **additive touch** to the AP-payment / AR-receipt cash legs (§3.7, flagged for the architect). Keep v1
  **lean but real** (capture + track + register); the **full WHT-by-type matrix** and **e-filing** are
  deferred (OQ-VAT-02).
- **Reports / reads.** The **VAT return face** (output by band, input, adjustments, opening credit, net,
  payable-or-credit) — view / print; the **WHT register** for a period — view / print; per-company,
  scoped.
- **Permissions** — `VAT.VIEW` (read returns / the WHT register), `VAT.RETURN.PREPARE` (open / compute /
  recompute a DRAFT return), `VAT.RETURN.FILE` (file a return — the lock + the GL settlement post),
  `VAT.ADJUST` (add / remove adjustment lines on a DRAFT), `WHT.VIEW` (read WHT), `WHT.MANAGE` (capture /
  manage WHT, issue certificates); per-company scope; `assertCanActIn` on **every read path**; **audit** on
  **prepare / file / adjust / WHT capture**; `ScopeGuard` gains a new **`vatreturn`** target type (+ WHT
  targets — a note for ADR-0017).
- **Numbering** via the generic `code_sequence`, per company: VAT return `VATR-####`, WHT (certificate /
  register entry) `WHT-####` — the same concurrency-safe mechanism GL/AR/AP/Cash use.

### Deferred (recognised, NOT built in v1 — separate later increments)

- **TRA EFD/VFD fiscalisation + direct e-filing.** v1 **computes** the return and keeps a **filing record**
  (an operator-entered filing reference + date); it does **NOT** integrate the TRA portal, EFD/VFD devices,
  or e-file the return / WHT to TRA. This stays the same separable integration Sales deferred
  (sales.md §10, OQ-SALES-03) — an **accepted boundary** (§10.1). The filing-record model is built so an
  e-filing connector feeds onto it later.
- **Cash-basis VAT.** v1 is **accrual/invoice basis** only (output on finalise, input on bill date). A
  cash-basis scheme (recognise VAT on payment) is deferred (OQ-VAT-04) — the accrual model does not preclude
  it.
- **VAT refund claims (cash refund of a net credit).** v1 **carries a net credit forward** (BR-VAT-03); a
  formal **refund claim** to TRA (and its receivable / repayment) is deferred — the carry-forward model is
  built so a refund path is additive.
- **Full WHT-by-type matrices + WHT e-filing.** v1 WHT is **lean** (a configurable rate/type + capture +
  track + register + certificate). The **full Tanzanian WHT matrix** (every payment type × rate × resident/
  non-resident × treaty), automated WHT **return e-filing**, and per-type statutory schedules are deferred
  (OQ-VAT-02) — the capture model does not preclude them.
- **Multi-rate / historical VAT-rate changes.** v1 reads the `tax_rates` master (STANDARD 18 / ZERO_RATED 0
  / EXEMPT — sales.md). A change to the standard rate over time, special schemes (e.g. a different rate per
  supply category beyond the three bands), and rate-effective-dating are deferred to a richer tax-code
  scheme (sales.md OQ-PROD-05 note) — OQ-VAT-05.
- **Partial-exemption / input-VAT apportionment.** v1 recovers input VAT in full from matched bills. The
  partial-exemption apportionment (recovering only the taxable-supply proportion of input VAT for a mixed
  business) is deferred — OQ-VAT-06.
- **Automated bad-debt VAT relief.** v1 takes bad-debt VAT relief as a **manual adjustment** (with AR
  write-off as its source, accounts-receivable.md). Auto-deriving the relief from AR write-offs is a later
  additive convenience (folds into OQ-VAT-03's adjustment-source work).
- **Reporting / dashboards (VAT analytics, liability trend).** Reading returns into a VAT-liability trend, a
  tax dashboard, or the financial statements is **Reporting** (T2.3); v1 delivers the return face + the WHT
  register reads they consume.
- **Multi-currency VAT / FX on the return.** v1 is **base currency (TZS)**; a foreign-currency VAT return /
  FX treatment of VAT is deferred to the FX cross-cutting item (X.6 / gl.md §10.5) — OQ-VAT-07.

### Explicitly NOT this module

- **The per-invoice / per-bill VAT computation** — **Sales** computes output VAT per line/invoice
  (ADR-0008, tax-exclusive — sales.md FR-SALES-11), and **AP** captures the bill's input VAT
  (`supplier_bills.vatAmount` — ADR-0015). The VAT return **reads and aggregates** what they computed; it
  **never** recomputes a line's VAT.
- **The General Ledger itself** — **GL** (ADR-0013) owns the books, `2200 VAT Payable`, and the new
  `VAT_INPUT` account ADR-0017 adds; the VAT return **posts** the **filing settlement** to those accounts
  and **reads** the period's control-account movement. It never edits a posted journal; corrections are the
  next period's adjustments / reversing entries (BR-VAT-10).
- **The AR / AP sub-ledgers + Cash & Bank** — AR/AP own the receipts/payments and Cash & Bank owns the
  money locations; the VAT return / WHT **rides** the AP-payment / AR-receipt event (the WHT touch, §3.7) —
  it does not own a receipt, a payable, or a cash/bank account.
- **The tax-rate master** — the **STANDARD 18 / ZERO_RATED 0 / EXEMPT** `tax_rates` master is owned with
  Sales (V5); the VAT return **reads** it (the bands on the return face). WHT rates/types are this module's
  (a lean WHT-rate setting), distinct from VAT rates.
- **Financial statements & analytics** — P&L, balance sheet, VAT-liability trend dashboards are Reporting
  (T2.3); the VAT module provides the return face + WHT register they read.

## 3. The VAT return: period, output, input, adjustments, net, filing, carry-forward — and WHT

### 3.1 The return period (one company-month, accrual basis)

A VAT return is opened **per company per calendar month** (`VATR-####`), on an **invoice/accrual basis**.
Exactly **one** return exists per company per month (BR-VAT-01). It is **due the 20th of the following
month** (a due date the return carries for the operator; v1 does not auto-remind — Notifications X.2). The
period window is the calendar month: output VAT comes from sales **FINALISED** within it; input VAT from
supplier bills **DATED** within it — **payment-independent** (BR-VAT-04/05). The return is **company-scoped**
(VAT is a company-level obligation; the books are kept at company level — gl.md NFR-GL-01).

### 3.2 Output VAT — from finalised sales (read)

The return's **output VAT** is the **sum of `sales_invoices.vat_total_amount`**, **by tax band** from the
per-band `tax_summary` JSONB, for invoices **FINALISED in the period** (sales.md FR-SALES-11). Only
**FINALISED** invoices count — a DRAFT or voided invoice contributes nothing (BR-VAT-05). The output figure
is broken out by band (STANDARD 18 / ZERO_RATED 0 / EXEMPT) for the return face, and **must agree** with the
period's movement on the GL **`2200 VAT Payable`** control account that the sales auto-poster credited
(BR-VAT-08, the reconciliation bar). The VAT return **reads** Sales as a **DTO / scalar-id projection** — it
never imports a Sales entity (NFR-VAT-06).

### 3.3 Input VAT — from supplier bills (read)

The return's **input VAT** is the **sum of `supplier_bills.vatAmount`** for bills **DATED in the period**
that are **matched / approved** (a posted payable — accounts-payable.md FR-AP-04/06). A bill **held for
review** (over-tolerance, not posted — accounts-payable.md BR-AP-04) is **excluded** until it matches and
falls into a period by its bill date (BR-VAT-04). Input VAT is **recoverable** and needs the **VAT Input /
Recoverable** account ADR-0017 adds (OQ-VAT-01, §1 flag). The VAT return **reads** AP as a **DTO** — never
an AP entity (NFR-VAT-06).

### 3.4 VAT adjustments (manual lines on a DRAFT)

A user with `VAT.ADJUST` adds **adjustment lines** to a **DRAFT** return: each a **reason** (bad-debt VAT
relief / prior-period correction / credit-note VAT / debit-note VAT / other), an **amount**, and a **sign**
(increase or decrease the net). Adjustments **affect the net** (FR-VAT-04) and are **audited**. They may be
added or removed **only while the return is DRAFT** — once **FILED**, the return is **locked** and a needed
correction goes to the **next period's adjustment** (BR-VAT-09/10). (Auto-deriving bad-debt VAT relief from
AR write-offs is deferred — OQ-VAT-03.)

### 3.5 Net VAT + the credit carry-forward

The return computes **net VAT = output VAT − input VAT + adjustments + opening credit carried forward**.

- **Net positive** → **VAT payable to TRA** for the period (due the 20th of next month). The filing journal
  books this as a **VAT-due liability** (§3.6).
- **Net negative** → a **VAT credit**. In v1 it is **NOT** a cash refund — it becomes the **opening credit
  on the next period's return**, offsetting that period's net (BR-VAT-03). A refund **claim** is deferred
  (§2).

### 3.6 File the return → lock + post the GL settlement (DRAFT → FILED)

A user with `VAT.RETURN.FILE` **files** a DRAFT return:

1. The return's figures are **frozen** (output, input, adjustments, net) and a **filing reference** + a
   **filing date** are recorded (the operator enters the TRA acknowledgement reference — no e-filing in v1).
2. A **synchronous GL settlement journal** posts (via `GLPostingService.post`, the AR/AP/Cash precedent —
   ADR-0014 D-4) that **settles the period's VAT control accounts**: **DR `2200 VAT Payable`** (clear the
   period's output), **CR the `VAT_INPUT` recoverable account** (clear the period's input), and book the
   **net to a VAT-due / -payable-to-TRA liability** (net positive) — or carry the **credit** (net negative),
   per double-entry, **balanced** (BR-VAT-06). The exact accounts are ADR-0017 (§1 flag).
3. The return is **LOCKED** — **immutable**, the **VAT period closed**; it **cannot be filed twice**
   (BR-VAT-11). A net **credit carries forward** to the next period (BR-VAT-03).
4. The file (with the GL post + the carry-forward) is **audited** (NFR-VAT-03).

A filed return is **never edited**; corrections go to the **next period's adjustments** (append-only,
BR-VAT-10) — and if the GL post itself must be undone, it is a **reversing entry**, never an edit (gl.md
BR-GL-02).

### 3.7 Withholding tax (WHT) on a payment / receipt (the additive touch)

WHT is captured **at the point of money movement**:

- **WHT on an AP payment (we withhold).** When AP pays a supplier (single or payment run —
  accounts-payable.md §3.3, routed to a cash/bank account by Cash & Bank — cash-and-bank.md §3.2), the
  payment **may withhold a % (a WHT rate/type)**: the supplier is **paid less** by the withheld amount, and
  the company **owes the withheld amount to TRA** — a **WHT liability** booked to GL, and a **WHT
  certificate** issued to the supplier. The cash actually paid = bill amount − WHT withheld; the WHT
  liability is remitted to TRA separately (the WHT return). An **additive touch** to the AP-pay cash leg
  (§1 flag).
- **WHT on an AR receipt (a customer withholds).** When AR records a receipt and the customer **withheld**
  on paying us, the receipt is **less** than the invoice by the withheld amount; the shortfall is a **WHT
  receivable / asset** (a claim against our own tax), supported by the customer's **WHT certificate**. An
  additive touch to the AR-receipt cash leg.

Each WHT capture records the **rate/type** (incl. withholding VAT as a type), the **withheld amount**, the
**party**, the **payment/receipt** it rode, and a **WHT certificate**. The **WHT register / return** lists
the period's WHT for **remittance** to TRA — a **sibling** to the VAT return, **not** part of the output−input
net (BR-VAT-12). WHT books a **liability** (we withheld) or a **receivable** (we were withheld from) and
**reduces the cash paid/received**; the GL accounts + `gl_configs` keys are ADR-0017's (§1 flag).

> **Flag for the architect (ADR-0017):** the WHT touch makes **AP-pay and AR-receipt resolve a WHT
> rate/type and book a WHT liability/receivable**, reducing the cash leg by the withheld amount. Mirror the
> Cash & Bank additive AR/AP touch (cash-and-bank.md §3.6) — keep the direction AP→VAT/WHT and AR→VAT/WHT
> (the VAT/WHT module must not import AR/AP entities, NFR-VAT-06). Decide the WHT liability/receivable
> account(s) + `gl_configs` keys, and whether WHT capture is a field on the existing AP-payment / AR-receipt
> request (recommended — the leanest additive shape) or a separate WHT-capture step the payment references.
> Keep v1 lean (OQ-VAT-02).

## 4. Actors / personas

- **Accountant / tax accountant** — **prepares** the monthly return (`VAT.RETURN.PREPARE` — opens /
  computes / recomputes the DRAFT as more invoices/bills land), **adds adjustments** (`VAT.ADJUST` —
  bad-debt relief, prior-period corrections), **captures WHT** (`WHT.MANAGE`), and reads the return + WHT
  register (`VAT.VIEW` / `WHT.VIEW`). The day-to-day operator of the module.
- **Tax officer / tax compliance officer** — reviews the computed return against source (output vs the
  `2200` movement; input vs the supplier bills), reconciles, and prepares the **WHT register** for
  remittance; reads VAT + WHT (`VAT.VIEW` / `WHT.VIEW`), manages WHT (`WHT.MANAGE`).
- **Financial controller** — **files** the return (`VAT.RETURN.FILE` — the lock + the GL settlement post),
  records the filing reference, and owns the VAT obligation; the senior authority who closes the VAT period.
- **AP payments officer / AR cashier (at settlement)** — not a VAT persona per se, but the operators whose
  **AP payment / AR receipt now captures WHT** (§3.7, the additive touch) — they settle in AP/AR; the
  VAT/WHT module provides the withholding capture + the liability/receivable booking.
- *(No SYSTEM auto-creator on the return side — a VAT return is **prepared by a human** (`VAT.RETURN.PREPARE`)
  and **filed by a human** (`VAT.RETURN.FILE`); the **filing GL post is synchronous in-request**, not an
  outbox auto-post — unlike GL's sales auto-poster. The WHT capture is likewise an in-request act on the
  AP-payment / AR-receipt.)*

## 5. Functional requirements

> IDs are `FR-VAT-NN`. Each is a crisp, testable, **ratified** statement. "Output VAT" = the period's sum of
> `sales_invoices.vat_total_amount` for FINALISED invoices; "input VAT" = the period's sum of
> `supplier_bills.vatAmount` for matched bills DATED in the period; "post to GL" = a synchronous
> `GLPostingService.post` in the same transaction (the AR/AP/Cash precedent, ADR-0014 D-4); "the period" =
> one company-month.

### Period open / compute (accrual basis)

- **FR-VAT-01** A user with `VAT.RETURN.PREPARE` may **open / compute a VAT return for a company-month**
  (`VATR-####` from `code_sequence`, per company): exactly **one** return per company per calendar month
  (BR-VAT-01), carrying the period (month), a **due date = the 20th of the following month**, and a status
  of **DRAFT**. The return is computed on an **invoice/accrual basis** — output from FINALISED sales in the
  month, input from supplier bills dated in the month, **payment-independent** (BR-VAT-04/05).
- **FR-VAT-02** A **DRAFT** return is **recomputable**: re-running the computation **refreshes** output and
  input as **more invoices are finalised / more bills are dated/matched** within the period, until the
  return is filed. Recompute does **not** post to GL (only filing does — FR-VAT-08).

### Output VAT (from finalised sales)

- **FR-VAT-03** The return computes **output VAT** as the **sum of `sales_invoices.vat_total_amount`, by tax
  band (from the per-band `tax_summary` JSONB)**, for invoices **FINALISED in the period** (sales.md
  FR-SALES-11). Only **FINALISED** invoices count (DRAFT / voided contribute nothing — BR-VAT-05). The
  output figure is broken out by band (STANDARD 18 / ZERO_RATED 0 / EXEMPT) and **reconciles** to the
  period's GL `2200 VAT Payable` movement (BR-VAT-08). Sales is read as a **DTO / scalar-id projection**,
  never an entity (NFR-VAT-06).

### Input VAT (from supplier bills)

- **FR-VAT-04** The return computes **input VAT** as the **sum of `supplier_bills.vatAmount`** for bills
  **DATED in the period** that are **matched / approved** (a posted payable — accounts-payable.md FR-AP-06);
  a bill **held for review** (over-tolerance, not posted — accounts-payable.md BR-AP-04) is **excluded**
  (BR-VAT-04). Input VAT is **recoverable** and requires the **VAT Input / Recoverable** account +
  `gl_configs` `VAT_INPUT` key ADR-0017 adds (OQ-VAT-01). AP is read as a **DTO**, never an entity
  (NFR-VAT-06).

### Adjustments

- **FR-VAT-05** A user with `VAT.ADJUST` may **add or remove adjustment lines on a DRAFT** return — each a
  **reason** (bad-debt VAT relief / prior-period correction / credit-note VAT / debit-note VAT / other), an
  **amount**, and a **sign** (increase / decrease the net). Each is **audited** (NFR-VAT-03) and **affects
  the net** (FR-VAT-06). Adjustments may be added/removed **only while DRAFT**; a FILED return takes no
  adjustment — a needed correction goes to the **next period** (BR-VAT-09/10).

### Net + carry-forward

- **FR-VAT-06** The return computes **net VAT = output VAT − input VAT + adjustments + opening credit
  carried forward**. **Net positive** = **VAT payable to TRA** for the period; **net negative** = a **VAT
  credit** (BR-VAT-03).
- **FR-VAT-07** A **net credit** (input + carried credit + reducing adjustments > output) **carries
  forward** as the **opening credit on the next period's return**; it is **NOT** a cash refund in v1
  (BR-VAT-03). A refund claim is deferred (§2).

### File → lock + GL settlement

- **FR-VAT-08** A user with `VAT.RETURN.FILE` may **file** a DRAFT return: the figures are **frozen**, a
  **filing reference** + a **filing date** are recorded, and a **synchronous GL settlement journal** posts —
  **DR `2200 VAT Payable`** (clear the period's output), **CR the `VAT_INPUT` recoverable account** (clear
  the period's input), and book the **net to a VAT-due / -payable-to-TRA liability** (net positive) or carry
  the **credit** (net negative), per double-entry, **balanced** (BR-VAT-06). The exact accounts are ADR-0017
  (§1 flag). Filing moves the return **DRAFT → FILED** and **LOCKS** it.
- **FR-VAT-09** A **FILED** return is **immutable and the VAT period is closed**: it **cannot be edited**,
  re-computed, or **filed twice** (BR-VAT-11); a correction is the **next period's adjustment** (BR-VAT-10).
  Every GL posting the file makes obeys the GL invariants (balanced, an OPEN period, active accounts — gl.md
  BR-GL-01/03/04); a file whose posting date falls in a **closed GL period** is handled per the GL
  closed-period policy (gl.md OQ-GL-01); a missing required `gl_configs` mapping (`VAT_PAYABLE`, the new
  `VAT_INPUT`, the VAT-due account) **fails the file** rather than mis-posting (gl.md BR-GL-10).

### Withholding tax (WHT)

- **FR-VAT-10** A user with `WHT.MANAGE` may **capture WHT on an AP payment** (we withhold): a **WHT
  rate/type** (incl. withholding VAT as a type), applied to a supplier payment so the supplier is **paid
  less** by the withheld amount; the system **books a WHT liability** to GL, **reduces the cash paid** by
  the withheld amount, records the **party** + the **payment** it rode, and produces a **WHT certificate**
  (`WHT-####`). An **additive touch** to the AP-pay cash leg (§3.7; flagged for ADR-0017).
- **FR-VAT-11** A user with `WHT.MANAGE` may **capture WHT on an AR receipt** (a customer withheld): the
  receipt is **less** than the invoice by the withheld amount; the system **books a WHT receivable/asset**,
  records the **party** + the **receipt** it rode, and the customer's **WHT certificate**. An additive touch
  to the AR-receipt cash leg.
- **FR-VAT-12** A user with `WHT.VIEW` may read the **WHT register / return** for a period — the list of WHT
  **withheld** (a liability to remit to TRA) and **received** (a receivable) — the basis for **remitting**
  withheld tax to TRA. The WHT register is a **sibling** to the VAT return and is **NOT** part of the
  output−input net (BR-VAT-12).

### Reports / reads

- **FR-VAT-13** A user with `VAT.VIEW` may read the **VAT return face** — output VAT by band, input VAT,
  adjustments, opening credit carried forward, **net**, and **payable-or-credit** — for any period
  (DRAFT or FILED), and the FILED return's **filing reference** + **filing date**; view / print, scoped to
  their company. No read crosses company scope (BR-VAT-07, NFR-VAT-01).

### Scope & permissions

- **FR-VAT-14** The VAT / WHT module is **scoped per company**; every return, adjustment, filing record, and
  WHT capture belongs to exactly one company; no read crosses company scope. `assertCanActIn` guards
  **every read path** (BR-VAT-07, NFR-VAT-01). The return is kept at **company level** (VAT is a company
  obligation; the books are company-level — gl.md NFR-GL-01).
- **FR-VAT-15** All VAT / WHT operations are **gated by IAM permissions**: `VAT.VIEW`, `VAT.RETURN.PREPARE`,
  `VAT.RETURN.FILE`, `VAT.ADJUST`, `WHT.VIEW`, `WHT.MANAGE`. Exact codes are seeded with the module
  (FR-IAM-11). Per-company scope; **audit on every mutation** — prepare / file / adjust / WHT capture
  (NFR-VAT-03); `ScopeGuard` gains a new **`vatreturn`** target type (+ WHT targets the architect names) —
  a note for ADR-0017.

## 6. Business rules (invariants)

> Ratified. These are the VAT-return / WHT invariants; a violation that breaks the VAT control-account
> reconciliation or files a period twice is a finance-grade defect (a release blocker).

- **BR-VAT-01 — One return per company per month.** Exactly **one** VAT return exists per company per
  calendar month; a second open for the same company-month is **rejected** (FR-VAT-01). The period is the
  unit of the obligation.
- **BR-VAT-02 — A FILED return is immutable; the period is locked.** Filing **freezes** the figures and
  **closes the VAT period**: a filed return is **never edited or re-computed** (FR-VAT-08/09). Corrections
  are the next period's adjustments (BR-VAT-10) — mirroring the GL append-only rule (PROJECT-CONVENTIONS
  §3.6, gl.md BR-GL-02).
- **BR-VAT-03 — A net credit carries forward (no cash refund in v1).** When a period nets to a **credit**
  (input + carried credit + reducing adjustments > output), the credit becomes the **opening credit on the
  next period's return** (FR-VAT-06/07). v1 does **not** claim a cash refund (§2, OQ deferred).
- **BR-VAT-04 — Input VAT only from matched/approved bills DATED in the period.** Input VAT counts a
  supplier bill's `vatAmount` only when the bill is **matched/approved** (a posted payable —
  accounts-payable.md FR-AP-06) and **dated within the period**; a **held** (over-tolerance, unposted) bill
  is **excluded** until it matches (FR-VAT-04). Accrual basis: bill **date**, not payment.
- **BR-VAT-05 — Output VAT only from FINALISED invoices in the period.** Output VAT counts a sale's
  `vat_total_amount` only when the invoice is **FINALISED** and **finalised within the period**
  (`finalised_at`); a DRAFT or **voided** invoice contributes nothing (FR-VAT-03). Accrual basis: finalise
  **date**, not payment.
- **BR-VAT-06 — Filing posts a balanced GL settlement entry.** Filing posts **one balanced** journal that
  **clears the period's output VAT** (DR `2200 VAT Payable`), **clears the period's input VAT** (CR the
  `VAT_INPUT` recoverable account), and books the **net to a VAT-due / -payable-to-TRA liability** (or
  carries a credit), Σ debits == Σ credits (FR-VAT-08). The accounts are ADR-0017's; the **balance** and the
  **settlement intent** are fixed.
- **BR-VAT-07 — Per-company isolation.** Every return, adjustment, filing record, and WHT capture **belongs
  to exactly one company**; no read or figure crosses company scope. Cross-company VAT/WHT leakage is a
  **release blocker** (NFR-VAT-01), as for GL/AR/AP/Cash.
- **BR-VAT-08 — The filed return reconciles to GL.** A **filed** return's figures reconcile to the books:
  the period's **output VAT == the period's `2200 VAT Payable` movement** from sales auto-posting, the
  **input VAT == the period's `VAT_INPUT` movement**, and the **filing settlement entry's net == the
  return's net** (FR-VAT-03/08). A return whose net disagrees with its GL settlement entry is a
  finance-grade defect (the reconciliation bar).
- **BR-VAT-09 — Adjustments only on a DRAFT, audited, signed.** A VAT adjustment is a **reason + amount +
  sign** line added/removed **only while the return is DRAFT**, always **audited**, and it **affects the
  net** (FR-VAT-05). A FILED return takes no adjustment.
- **BR-VAT-10 — Append-only; correct via the next period, not an edit.** A FILED return and its GL
  settlement posting are **never silently edited or deleted**; a correction is the **next period's
  adjustment** (bad-debt relief, prior-period correction) and, if the GL post must be undone, a **reversing
  entry** (gl.md BR-GL-02). The module keeps a full history.
- **BR-VAT-11 — A period cannot be filed twice.** A return that is already **FILED** cannot be filed again
  (FR-VAT-09); the VAT period is closed. A re-file attempt is **rejected**.
- **BR-VAT-12 — WHT books a liability/receivable and reduces cash; it is separate from the VAT net.**
  Withholding on an **AP payment** books a **WHT liability** (to remit to TRA) and **reduces the cash paid**;
  withholding on an **AR receipt** books a **WHT receivable** and **reduces the cash received** (FR-VAT-10/11).
  WHT is a **separate remittance** (the WHT register/return) and is **NOT** part of the output−input VAT net
  (FR-VAT-12). A WHT capture whose cash leg + liability/receivable do not balance is a defect.
- **BR-VAT-13 — Base-currency VAT (v1).** Returns, the VAT control accounts, the net, and WHT are in the
  company **base currency** (TZS in practice; ADR-0005 D-4 / gl.md BR-GL-06); a foreign-currency VAT return /
  FX treatment is deferred (§2, OQ-VAT-07).

## 7. Process flows (happy path + main unhappy paths), ratified v1

### 7.1 Compute draft → review → adjust → file → GL post → carry-forward — happy path
1. An accountant (`VAT.RETURN.PREPARE`, active company) **opens / computes** the VAT return for a
   company-month (`VATR-####`, status **DRAFT**, due the 20th of next month): the system sums **output VAT**
   (FINALISED sales' `vat_total_amount`, by band, in the period — FR-VAT-03) and **input VAT** (matched
   supplier bills' `vatAmount`, dated in the period — FR-VAT-04), and carries any **opening credit** from
   the prior period (FR-VAT-07).
2. The accountant **reviews** the draft: output by band vs the `2200` movement, input vs the bills
   (FR-VAT-13). As more invoices finalise / bills land, the draft is **recomputed** (FR-VAT-02).
3. The accountant **adds adjustment lines** if needed (`VAT.ADJUST`) — bad-debt VAT relief, a prior-period
   correction, credit/debit-note VAT — each a reason + amount + sign, audited (FR-VAT-05); the **net**
   updates (FR-VAT-06).
4. The controller **files** the return (`VAT.RETURN.FILE`): the figures **freeze**, a **filing reference** +
   **filing date** are recorded, and the **synchronous GL settlement journal** posts — DR `2200` (clear
   output), CR `VAT_INPUT` (clear input), book the **net to the VAT-due liability** (FR-VAT-08, BR-VAT-06).
5. The return is **LOCKED** (FILED, period closed — BR-VAT-02); the file + GL post + carry-forward are
   **audited** (NFR-VAT-03). The filed return's **net == its GL settlement entry** (BR-VAT-08, the
   reconciliation bar).
6. If the period netted to a **credit**, that credit becomes the **opening credit on the next period's
   return** (FR-VAT-07, BR-VAT-03).

### 7.2 WHT on a supplier payment — happy path
1. An AP payments officer pays a supplier (single or payment run — accounts-payable.md §3.3, routed to a
   cash/bank account — cash-and-bank.md §3.2) and (`WHT.MANAGE`) **applies a WHT rate/type** (e.g. 2%
   withholding) on the payment (FR-VAT-10).
2. The supplier is **paid less** by the withheld amount (cash paid = amount − WHT); the system **books a WHT
   liability** to GL and **reduces the cash leg** by the withheld amount (BR-VAT-12); a **WHT certificate**
   (`WHT-####`) is produced for the supplier.
3. The WHT appears on the period's **WHT register** for **remittance to TRA** (FR-VAT-12); it is **separate**
   from the VAT output−input net (BR-VAT-12). The capture is **audited** (NFR-VAT-03).

### 7.3 WHT on a customer receipt (a customer withholds) — happy path
1. An AR cashier records a receipt where the customer **withheld** on paying us; (`WHT.MANAGE`) **captures
   the withheld amount** against the receipt (FR-VAT-11).
2. The receipt is **less** than the invoice by the withheld amount; the system **books a WHT
   receivable/asset** and records the customer's **WHT certificate** (BR-VAT-12).
3. The WHT receivable appears on the **WHT register** as tax withheld from us (a claim against our own tax);
   the capture is **audited**.

### 7.4 Main unhappy paths
- **A second return opened for a company-month already having one** (7.1.1) → **rejected** (BR-VAT-01); the
  existing return is recomputed instead (FR-VAT-02).
- **Attempt to add an adjustment to a FILED return** (7.1.3) → **refused**; the return is locked — the
  correction goes to the **next period's adjustment** (BR-VAT-09/10).
- **Attempt to file a return twice / edit a FILED return** (7.1.4) → **rejected**; the VAT period is closed
  (BR-VAT-11, BR-VAT-02). If the GL post must be undone, it is a **reversing entry** (gl.md BR-GL-02).
- **Filing would post into a CLOSED GL period** (7.1.4) → handled per the GL closed-period policy (gl.md
  OQ-GL-01); finance reopens / moves the date (FR-VAT-09).
- **Missing required `gl_configs` mapping** (`VAT_PAYABLE`, the new `VAT_INPUT`, or the VAT-due account)
  (7.1.4) → the **file fails** rather than mis-posting; finance sets the mapping (`GL.MANAGE`), then retries
  (gl.md BR-GL-10, FR-VAT-09).
- **A held / over-tolerance supplier bill in the period** (7.1.1) → **excluded** from input VAT until it
  matches and posts (BR-VAT-04); once matched, its `vatAmount` enters the period of its bill date.
- **A voided / draft sales invoice in the period** (7.1.1) → contributes **no** output VAT (BR-VAT-05); a
  sale voided **after** a return was filed for its period is corrected via the **next period's adjustment**
  (BR-VAT-10).
- **The filed return's net disagrees with its GL settlement entry** (reconciliation read) → a **finance-grade
  defect** surfaced for investigation (BR-VAT-08, NFR-VAT-01).
- **A WHT capture whose cash leg + liability/receivable do not balance** (7.2 / 7.3) → a defect; the cash
  reduction must equal the withheld liability/receivable (BR-VAT-12, NFR-VAT-02).

## 8. Non-functional

- **NFR-VAT-01 — Reconciliation integrity & tenant isolation.** A **filed** return must reconcile to the
  books: the period's **output VAT == the `2200 VAT Payable` movement**, the **input VAT == the `VAT_INPUT`
  movement**, and the **filing settlement entry's net == the return's net** (BR-VAT-08); a disagreement is a
  **release blocker**. Every VAT/WHT row is scoped by `company_id` through the tenant-predicate repository
  base (ARCHITECTURE.md §5, PROJECT-CONVENTIONS §3.2); `assertCanActIn` guards **every read path**.
  Cross-company VAT/WHT leakage is a **release blocker**, as for GL/AR/AP/Cash.
- **NFR-VAT-02 — Money correctness.** Every amount is a `Money` (amount + currency, ADR-0005) in the company
  base currency; the output sum, the input sum, adjustments, the carried credit, the net, the GL settlement
  legs, and the WHT cash-reduction-vs-liability/receivable sum **exactly** (no float, `BigDecimal` compare,
  rounding per ADR-0005 D-2 / gl.md NFR-GL-02, OQ-CUR-03 — **VAT rounding per band is the sensitive case**,
  OQ-VAT-08). A filing journal whose debits and credits do not balance, or a WHT capture whose legs do not
  net, is a defect.
- **NFR-VAT-03 — Audit.** Every **mutation** — return open/compute/recompute (prepare), each **adjustment**
  add/remove, the **file** (with its GL settlement post + the carry-forward), and each **WHT capture** /
  certificate — is written to the IAM append-only audit trail with actor, action, target, timestamp, and
  company context (mirrors NFR-GL-06 / NFR-AP-03).
- **NFR-VAT-04 — Synchronous-posting atomicity.** The **filing GL settlement** commits in **one transaction**
  with the return moving to FILED (BR-VAT-06, the AR/AP/Cash precedent — ADR-0014 D-4): the return is locked
  and the books move together or not at all — no eventual-consistency gap. Likewise a WHT capture and its
  GL liability/receivable + cash reduction commit atomically with the payment/receipt.
- **NFR-VAT-05 — Numbering concurrency.** Two accountants opening returns, or two officers capturing WHT,
  simultaneously get distinct `VATR-####` / `WHT-####` numbers (the `code_sequence` row-locked allocation —
  ADR-0007 D-6).
- **NFR-VAT-06 — DTO-only consumption / boundary direction.** The VAT/WHT module **reads** Sales
  (`sales_invoices` output VAT + `tax_summary`) and AP (`supplier_bills.vatAmount`) as **DTOs / scalar-id
  projections**, and the `tax_rates` master, **never importing a Sales / AP / Parties entity** (ADR-0009
  D-1; `ModuleBoundaryTest`). It posts to GL through the `GLPostingService` / `GLConfigResolver` boundary,
  not by importing GL entities beyond the posting contract. The **WHT touch** keeps the direction AP→VAT/WHT
  and AR→VAT/WHT (the VAT/WHT module is not imported into AP/AR entities; AP/AR resolve the WHT capture as a
  DTO/service call — gl.md NFR-GL-07).
- **NFR-VAT-07 — Timestamps & business dates.** Timestamps are UTC, displayed per company time zone
  (Africa/Dar_es_Salaam default, iam.md locale). The return **period** (calendar month), the **due date**
  (the 20th of the next month), the **filing date**, the source **finalised_at** / **bill_date**, and a WHT
  **certificate date** are **business dates**, distinct from the posting timestamp.
- **NFR-VAT-08 — Forward-compatibility.** The v1 model must not preclude the later increments that build on
  the VAT return: **TRA EFD/VFD fiscalisation + e-filing** feeding the filing record (§10.1, OQ-SALES-03);
  **cash-basis VAT** (OQ-VAT-04); **VAT refund claims** replacing the carry-forward where chosen; the
  **full WHT-by-type matrix + WHT e-filing** (OQ-VAT-02); **partial-exemption / input-VAT apportionment**
  (OQ-VAT-06); **multi-rate / historical rate changes** (OQ-VAT-05); **automated bad-debt VAT relief from AR
  write-offs** (OQ-VAT-03); **VAT reporting / dashboards** (T2.3); and **multi-currency VAT** (X.6 / gl.md
  §10.5, OQ-VAT-07). Building these is deferred; precluding them is a defect.

## 9. Assumptions

- The dependency platform exists and is consumed as designed: **Sales** computes per-invoice output VAT
  (tax-exclusive — sales.md FR-SALES-11) and exposes `sales_invoices.vat_total_amount` + the per-band
  `tax_summary` + `finalised_at` + the `tax_rates` master STANDARD 18 / ZERO_RATED 0 / EXEMPT (ADR-0008,
  V5); **AP** captures `supplier_bills.vatAmount` + `bill_date` on matched/approved bills (ADR-0015, V12);
  **GL** (ADR-0013 / V10) is shipped with `2200 VAT Payable` (`gl_configs` `VAT_PAYABLE`), the synchronous
  `GLPostingService.post`, `fiscal_periods`, and `GLConfigResolver`; **Cash & Bank** (ADR-0016 / V13) routes
  AP-payment / AR-receipt cash legs; **Money** (ADR-0005) + `code_sequence` are in place. All shipped.
- **There is NO `VAT_INPUT` / VAT-recoverable account yet** — `gl_configs` has `VAT_PAYABLE` (2200, output)
  but no input-VAT key (ADR-0013 D-13). ADR-0017 **adds** the CoA "VAT Input / Recoverable" account + the
  `gl_configs` `VAT_INPUT` key (and the VAT-due liability account/key), and decides **how the AP bill's
  input VAT relates to it** (booked separately at bill-match, or read from `supplier_bills.vatAmount` and
  separated at filing) — the **integration seam** (OQ-VAT-01, §1 flag). The requirement fixes the
  *behaviour* (input VAT recoverable, nets against output on filing), not the account/mechanism.
- **Output VAT is already on the books** (`2200`) from the sales auto-poster (gl.md §3.1); the return reads
  it / agrees to it (BR-VAT-08). **Input VAT is captured per bill** (`supplier_bills.vatAmount`) but not yet
  separated on the books — that separation is ADR-0017's (OQ-VAT-01).
- **VAT is monthly, 18% standard, due the 20th of the next month, TRA** — the Tanzanian regime
  (PROJECT-CONVENTIONS context); the rate is **maintained data** in `tax_rates`, never hard-coded
  (sales.md FR-SALES-10).
- **The return is computed + a filing record kept; no TRA e-filing in v1** — the filing reference is
  operator-entered (the TRA acknowledgement); EFD/VFD/e-filing is the deferred separable integration Sales
  also defers (§10.1).
- **Document currency = company base (TZS)** for v1; the model supports a future foreign-currency treatment
  but FX depth is deferred (BR-VAT-13).

## 10. ACCEPTED RISK & accepted scope boundary — what VAT v1 deliberately does NOT do (owner-accepted 2026-06-09)

> **Read this before building or consuming the VAT module.** VAT v1 delivers a **monthly, accrual-basis VAT
> return** (output from finalised sales, input from supplier bills, manual adjustments, net + credit
> carry-forward, DRAFT → FILED with a synchronous GL settlement + the period lock) **plus** withholding-tax
> capture + a WHT register, reconciling the VAT control accounts to TRA. The following are **deliberate
> boundaries**, owner-accepted.

1. **No TRA EFD/VFD fiscalisation / e-filing — ACCEPTED BOUNDARY.** v1 **computes** the return and keeps a
   **filing record** (an operator-entered filing reference + date); it does **NOT** integrate the TRA
   portal, EFD/VFD devices, or e-file the VAT / WHT return. This is the same separable integration Sales
   deferred (sales.md §10, OQ-SALES-03). The filing-record model is built so an e-filing connector feeds
   onto it later (NFR-VAT-08).

2. **A net credit carries forward — NOT a cash refund (v1).** When a period nets to a credit (input >
   output), v1 **carries the credit forward** to the next period (BR-VAT-03); it does **not** file a refund
   claim with TRA. A refund path is additive (NFR-VAT-08).

3. **WHT is lean: capture + track + register, not the full matrix.** v1 WHT captures a configurable
   rate/type (incl. withholding VAT) on AP payments / AR receipts, books the liability/receivable, reduces
   the cash, issues a certificate, and lists a WHT register for remittance — **real, but minimal**. The
   **full Tanzanian WHT-by-type matrix** (every payment type × rate × residency × treaty) and **WHT
   e-filing** are deferred (OQ-VAT-02). WHT is the biggest scope item; v1 is deliberately the lean slice.

4. **Accrual basis only.** v1 recognises output VAT on **finalise** and input VAT on **bill date** —
   **payment-independent** (BR-VAT-04/05). A **cash-basis** scheme is deferred (OQ-VAT-04).

5. **Input VAT recovered in full from matched bills.** v1 has **no partial-exemption apportionment**
   (recovering only the taxable proportion for a mixed business) — deferred (OQ-VAT-06).

6. **The `VAT_INPUT` account + the AP-input-VAT-booking mechanism is an ADR decision, not a v1 gap.** That
   the input-VAT recoverable account does not exist yet (ADR-0013 D-13) is the **ADR-0017 integration seam**
   (OQ-VAT-01) — the requirement fixes the behaviour; the architect chooses the account + the AP-booking
   mechanism. This is **not** an accepted *risk*; it is the next design step.

All are additive by design (NFR-VAT-08); none is precluded by the v1 model.

## 11. Open questions — status after ratification (2026-06-09)

> The **VAT-return scoping forks** the owner answered (monthly accrual basis; output−input net + adjustments;
> DRAFT → FILE lock + the GL settlement post; net-credit carry-forward; manual adjustments; WHT in v1; TRA
> e-filing deferred; the permission set) are **RESOLVED** (recorded in
> `docs/requirements/open-questions.md` under VAT). **No ADR-0017-blocking open question remains.** What
> stays open is **non-blocking** detail with a recommended default that stands unless the owner overrides —
> the one architecturally meaty item (the `VAT_INPUT` account + the AP-input-VAT seam, OQ-VAT-01) is the
> **decision ADR-0017 makes**, not a requirements blocker (the *behaviour* is fixed).

### The ADR-0017 design seam (a DECISION the architect makes — does NOT block the requirements)

- **OQ-VAT-01 — The `VAT_INPUT` account + how AP's input VAT relates to it.** There is **no** VAT
  input/recoverable account / `gl_configs` key yet (ADR-0013 D-13). ADR-0017 must add a CoA **"VAT Input /
  Recoverable"** account + a `gl_configs` **`VAT_INPUT`** key (and a **VAT-due** liability account/key for
  the net), and decide **how the AP bill's input VAT reaches it** — **either** AP books the bill's stated
  VAT to `VAT_INPUT` at bill-match (accounts-payable.md FR-AP-06's "[+ DR VAT input if captured]" goes
  live), **or** the return reads `supplier_bills.vatAmount` and the **filing journal** is where input VAT is
  first separated onto the books (both reconcile — BR-VAT-08). *Recommended default:* **book the bill's
  input VAT to `VAT_INPUT` at AP bill-match** (so the books carry input VAT continuously and the return
  simply reconciles to the period movement, mirroring how output VAT already sits on `2200`); the filing
  journal then clears `2200` and `VAT_INPUT` to the VAT-due liability. *Decider:* architect (ADR-0017).
  *Blocks ADR-0017:* **NO** — it **is** the decision ADR-0017 makes; the requirement fixes the behaviour.

### Still open — NON-blocking detail (recommended defaults stand; do NOT block ADR-0017)

- **OQ-VAT-02 — WHT scope depth.** v1 WHT is **lean** (a configurable rate/type incl. withholding VAT +
  capture + track + register + certificate, on AP payments / AR receipts). *Recommended default:* a small
  set of owner-configurable WHT rates/types (e.g. the 2% withholding + withholding VAT) booking a single WHT
  liability + a single WHT receivable account; the **full WHT-by-type matrix** (payment type × residency ×
  treaty) and **WHT e-filing** are deferred. *Decider:* owner (finance / tax). *Blocks ADR-0017:* **NO** —
  the lean capture model is fixed; depth is additive.
- **OQ-VAT-03 — Adjustment sources (bad-debt VAT relief, prior-period).** v1 adjustments are **manual**
  (reason + amount + sign). *Recommended default:* manual adjustment lines in v1; **auto-deriving bad-debt
  VAT relief from AR write-offs** (accounts-receivable.md) and prior-period correction wizards are later
  additive conveniences. *Decider:* owner (finance). *Blocks ADR-0017:* **NO** — manual is fixed; auto is
  additive.
- **OQ-VAT-04 — Cash-basis vs accrual-basis VAT.** v1 is **accrual/invoice basis** (output on finalise,
  input on bill date). *Recommended default:* accrual basis in v1; a cash-basis scheme (recognise on
  payment) is a later additive option for businesses on that scheme. *Decider:* owner. *Blocks ADR-0017:*
  **NO** — accrual is fixed.
- **OQ-VAT-05 — Multi-rate / historical VAT-rate changes.** v1 reads STANDARD 18 / ZERO_RATED 0 / EXEMPT
  from `tax_rates`. *Recommended default:* the current three bands in v1; rate-effective-dating and
  additional schedules ride a richer tax-code scheme later (sales.md OQ-PROD-05 note). *Decider:* owner.
  *Blocks ADR-0017:* **NO** — additive.
- **OQ-VAT-06 — Partial-exemption / input-VAT apportionment.** v1 recovers input VAT **in full** from
  matched bills. *Recommended default:* full recovery in v1; partial-exemption apportionment (recover only
  the taxable-supply proportion) is deferred for mixed businesses. *Decider:* owner (finance). *Blocks
  ADR-0017:* **NO** — additive.
- **OQ-VAT-07 — Multi-currency VAT.** v1 is **base currency (TZS)** (BR-VAT-13). *Recommended default:*
  base-currency VAT in v1; foreign-currency VAT treatment lands with FX (X.6 / gl.md §10.5). *Decider:*
  owner. *Blocks ADR-0017:* **NO** — deferred, not precluded.
- **OQ-VAT-08 — Partial-period bills + VAT rounding per band.** (a) A bill **dated in the period but
  matched later** — does it enter the period of its **bill date** (recommended) or the period it matched in?
  *Recommended default:* the period of its **bill date** (accrual basis, BR-VAT-04); a bill matched after
  that period's return is **filed** is corrected via the **next period's adjustment** (BR-VAT-10). (b) VAT
  **rounding per band** — the per-band output sum must round identically to the source invoices' band
  totals and to the GL movement. *Recommended default:* sum the already-rounded per-invoice band amounts
  (no re-rounding of an already-computed line VAT), half-up, TZS = 0 dp (OQ-CUR-03). *Decider:* owner
  (finance). *Blocks ADR-0017:* **NO** — the bill-date default + sum-of-rounded-amounts stand; confirm
  before go-live.
- **OQ-CUR-03 (carried) — Rounding mode & TZS decimals.** Confirm rounding mode (half-up vs banker's) and
  TZS decimal places (0 in practice) — the output/input sums, the net, the GL settlement legs, and the WHT
  legs must round identically (NFR-VAT-02). *Recommended default:* half-up, TZS = 0 dp. *Decider:* owner
  (finance input). *Blocks ADR-0017:* **NO** for the model; **confirm before go-live**.

## 12. Out of scope for v1 (deferred — restated)

TRA EFD/VFD fiscalisation + direct e-filing of the VAT / WHT return (the separable integration §10.1,
OQ-SALES-03 — v1 keeps an operator-entered filing record); **cash-basis VAT** (OQ-VAT-04 — v1 is accrual);
**VAT refund claims** (v1 carries a net credit forward, BR-VAT-03); the **full WHT-by-type matrix + WHT
e-filing** (OQ-VAT-02 — v1 WHT is lean: capture + track + register + certificate); **multi-rate / historical
VAT-rate changes** (OQ-VAT-05); **partial-exemption / input-VAT apportionment** (OQ-VAT-06 — v1 recovers in
full); **automated bad-debt VAT relief from AR write-offs** (OQ-VAT-03 — v1 takes it as a manual
adjustment); **VAT reporting / dashboards / liability trend** (Reporting T2.3 — VAT provides the return face
+ WHT register reads); and **multi-currency VAT / FX on the return** (X.6 / gl.md §10.5, OQ-VAT-07 — v1 is
base currency). Each is tracked for a later increment; none is precluded by the v1 model (NFR-VAT-08).

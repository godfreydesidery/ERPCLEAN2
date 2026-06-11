# Requirements — Fixed Assets (give the business a depreciating-asset register that posts to the books)

> Status: **DRAFT (architect-authored, owner-style assumptions made; load-bearing OQs flagged §11).**
> This is the v1 business spec for **Fixed Assets** — the asset register, acquisition (from an AP bill or
> manual), asset categories, depreciation (straight-line + reducing-balance) on a **scheduled, GL-posting,
> fiscal-period-gated depreciation run**, disposal/write-off with gain/loss, and a simple revaluation.
> Business-level only. **No schema, no API shapes, no tables/columns, no code** — those are the
> solutions-architect's, in **ADR-0030** (next step). Do not infer a data model from this document.
>
> Author: solutions-architect (standing in for system-analyst on a greenfield extension module) ·
> Domain: a **new module** `com.erp.modules.fixedassets` (the register + categories + depreciation +
> disposal + revaluation), with touches into `gl` (new accounts + `gl_config` keys, the depreciation /
> disposal / revaluation postings — all period-gated) and a **soft, optional** link into `ap`/`purchases`
> (capitalise an asset from a matched supplier bill).
>
> **This is Fixed Assets — Phase C's first extension module (docs/PATH-TO-FULL-ERP.md §3.8, area 11, L).**
> The full Tier-1 finance spine is DONE — GL (ADR-0013/V10), AR (ADR-0014/V11), AP (ADR-0015/V12),
> Cash & Bank (ADR-0016/V13), VAT + WHT (ADR-0017/V14), Reporting (ADR-0018/V15), Year-End Close
> (ADR-0019/V16), Inventory Valuation/COGS (ADR-0020/V17), and Order-to-Cash (ADR-0021/V18-V19) all ship.
> Fixed Assets is **the second live GL-posting module after the financial spine** (depreciation is a
> recurring, scheduled, period-gated journal — the first such automation the platform runs); it depends
> only on shipped capability.
>
> **Depends on:**
> - **GL** (ADR-0013 / V10 — the posting engine): the synchronous `GLPostingService.post(JournalEntryDraft)`;
>   `GLConfigResolver.resolve(companyId, key)` (role→account, throws on missing/inactive — BR-GL-10);
>   `GLPostingSafeInvoker.postInNewTx` (REQUIRES_NEW, null-on-anomaly, for event-driven legs);
>   **fiscal periods** + `FiscalPeriodResolver.resolveOpen(companyId, postingDate)` (the period gate —
>   posting into a closed/absent period is rejected, BR-GL-03); `gl_configs` (new keys for the FA accounts);
>   the seeded chart of accounts. **A depreciation run posts only into an OPEN period.**
> - **Cost-centre dimension (OPTIONAL, may not yet be built):** Fixed Assets wants to tag an asset (and so
>   its depreciation expense) with an optional **cost centre** for management reporting (PATH-TO-FULL-ERP
>   §3.11 — the dimension framework is not yet built). v1 carries an **optional, nullable** cost-centre
>   reference on the asset and depreciation expense line; if the dimension framework is absent at build
>   time the field is a nullable scalar that the future framework activates (design-to-contract, §9, OQ-FA-07).
> - **AP / Purchases (OPTIONAL soft link):** acquire an asset **from a matched supplier bill** (AP ADR-0015 /
>   V12) — capitalise the asset cost from a bill line instead of expensing it. v1 reads the bill by **uid** as
>   a DTO at acquisition time; it does **not** change AP's posting (the GL effect of capitalising-vs-expensing
>   is the architect's, §6, OQ-FA-02). Manual acquisition (no bill) is the always-available path.
> - **IAM / `RequestContext` / `ScopeGuard.assertCanActIn` / RBAC `@perm` / audit / `code_sequence` / Money**
>   (the platform spine). All shipped.
> - **Scheduled-jobs framework (lightweight):** a depreciation run is **operator-initiated** in v1 (a user
>   triggers the run for a period); a `@Scheduled` auto-run is **deferred** (§2, OQ-FA-06) — the platform's
>   only scheduled job today is the outbox poller, and depreciation is finance-grade, so v1 keeps a human in
>   the loop (review → post).

## 1. Business context & why now

The business buys assets that last years — vehicles, equipment, furniture, fittings, computers, buildings —
and the cost of those assets must be **spread over their useful life**, not expensed in the month of
purchase. Today ERPCLEAN2 has **no asset register and no depreciation**: a capital purchase either sits as a
`5150 Purchases` / expense line (wrong — it overstates this period's expense and understates the asset) or is
hand-journalled, with depreciation a manual recurring entry someone must remember to post. The balance sheet
has **no fixed-asset block** and **no accumulated depreciation**; the P&L has **no depreciation expense line**
tied to a schedule.

**Fixed Assets closes that gap.** It gives the business:

- an **asset register** — one row per asset, with its acquisition cost, category, useful life, salvage value,
  acquisition date, depreciation start date, location, optional supplier link, and a derived **net book
  value** (cost − accumulated depreciation);
- **asset categories** — a master that supplies sensible defaults (depreciation method, default useful life,
  the GL accounts the category posts to) so an asset inherits its accounting treatment from its kind;
- **acquisition** — either **manual** (enter the asset directly) or **from a matched AP supplier bill**
  (capitalise the asset from a bill line, so the capital spend lands on the asset block, not the P&L);
- **depreciation** — **straight-line** and **reducing-balance** methods, with a **generated schedule** (one
  planned charge per period over the asset's life) and a **depreciation run** that posts the period's charge
  to GL (**DR Depreciation Expense / CR Accumulated Depreciation**), **gated to an OPEN fiscal period**,
  **idempotent** (a period can be run once — re-running is a no-op), and **reviewable before it posts**;
- **disposal** (sale or write-off) — remove the asset from service, post the gain/loss (proceeds − net book
  value) to GL, and stop further depreciation;
- a simple **revaluation** — adjust an asset's carrying value up or down, posting the difference to a
  revaluation reserve (up) or expense (down).

This is the moment the books carry a **correct fixed-asset block and a scheduled depreciation charge** — the
last structural gap in a small-business balance sheet.

## Vocabulary (read this first)

- **Fixed asset (capital asset)** — a long-lived resource the business owns and uses (not for resale): a
  vehicle, machine, building, computer, fixture. Recorded at **cost**, **depreciated** over its **useful
  life**, and carried on the balance sheet at **net book value**. Distinct from **inventory** (held for sale,
  costed by moving average — ADR-0020) and from an **expense** (consumed in the period).
- **Acquisition cost (capitalised cost)** — the amount the asset is recorded at: the purchase price (plus,
  in a richer model, freight/install/duty — **landed cost on an asset is deferred**, §2). v1 capitalises the
  **bill-line net** (or the manually-entered cost). VAT is **not** capitalised (it is recovered via the VAT
  return, ADR-0017) — only the net cost is the asset.
- **Useful life** — the number of **periods** (months, in this monthly-period system) over which the asset is
  depreciated. Drives the schedule. Sourced from the asset (overriding the category default).
- **Salvage value (residual value)** — the estimated value at the end of useful life; the asset is
  depreciated from cost **down to salvage**, never below.
- **Depreciation** — the systematic allocation of (cost − salvage) over the useful life. Two methods in v1:
  **straight-line** (equal charge each period = (cost − salvage) / life-in-periods) and **reducing-balance**
  (a fixed **rate** applied to the **opening net book value** each period, so the charge falls over time;
  never depreciating below salvage). The method + rate/life come from the asset (defaulted from its category).
- **Depreciation schedule** — the planned list of period charges over the asset's life, generated when the
  asset starts depreciating: per period, the planned charge, the resulting accumulated depreciation, and the
  resulting net book value. The **run** posts against the schedule, period by period.
- **Depreciation run** — the **act** of posting one fiscal period's depreciation for **all eligible assets**
  in a company: it sums each asset's scheduled charge for that period, posts **one GL journal**
  (**DR Depreciation Expense / CR Accumulated Depreciation**, gated to the OPEN period), and records that the
  period was run (so it cannot double-post). `DEPR-####` numbers the run.
- **Net book value (NBV, carrying value)** — **acquisition cost − accumulated depreciation** (± revaluation).
  A **derived** figure (computed from cost + the depreciation charged to date), shown on the register and used
  at disposal to compute gain/loss.
- **Accumulated depreciation** — the running total of depreciation charged against an asset to date — a
  **contra-asset** on the balance sheet (a credit-balance account that reduces the gross asset block).
- **Disposal** — removing an asset from service: by **sale** (proceeds received) or **write-off** (scrapped,
  no proceeds). On disposal the asset's cost and accumulated depreciation are removed and the
  **gain or loss** (proceeds − net book value) is posted; depreciation stops.
- **Gain / loss on disposal** — proceeds − net book value at disposal. **Proceeds > NBV → a gain** (income);
  **proceeds < NBV → a loss** (expense). A write-off (no proceeds) is a loss equal to the full NBV. Posted to
  a single **Gain/Loss on Disposal** account (sign carries gain vs loss).
- **Revaluation** — a change to an asset's **carrying value** without a sale: an **upward** revaluation
  increases the asset and credits a **Revaluation Reserve** (equity); a **downward** revaluation decreases the
  asset and debits an expense (a loss). v1 is a **simple carrying-value revaluation** — full IAS-16
  revaluation-model mechanics (reserve recycling, depreciation on the revalued amount) are flagged (§11,
  OQ-FA-05).
- **CWIP (Capital Work in Progress)** — an asset under construction, accumulating cost before it is "in
  service" and starts depreciating. **Deferred** (§2) — v1 assets are in-service from their depreciation
  start date.

> **Word discipline:** **depreciation** (spreading an asset's cost over its life, DR expense / CR accumulated
> depreciation) is **not** **disposal** (removing the asset, posting gain/loss) and **not** **revaluation**
> (changing carrying value with no sale). **Acquisition cost** (the capitalised net) is **not** the asset's
> **VAT** (recovered separately) and **not** its **net book value** (cost less depreciation). **Accumulated
> depreciation** (the contra-asset, a balance-sheet credit) is **not** **depreciation expense** (the P&L
> charge for the period). A **depreciation run** (the period-posting act) is **not** the **schedule** (the
> plan). **Straight-line** (equal charge) is **not** **reducing-balance** (a rate on the falling NBV).

## 2. Scope

> Every line below is **proposed v1** (architect-authored; owner to ratify §11). This is **Fixed Assets v1** —
> a focused register → depreciate → dispose/write-off → simple revaluate slice, GL-posted and period-gated.

### In scope (v1)

1. **Asset categories master** — per company: name, code, **default depreciation method**, **default useful
   life (periods)**, **default reducing-balance rate**, and the **GL accounts** the category's assets post to
   (asset / accumulated-depreciation / depreciation-expense). `MasterStatus` soft-delete. Defaults flow to an
   asset on create (the asset may override life/method/rate).
2. **Asset register master** — per company/branch: asset number (`FA-####`), name, category, acquisition cost
   (net), salvage value, useful life (periods), depreciation method, reducing-balance rate (if applicable),
   acquisition date, **depreciation start date**, location (free text in v1), optional supplier link, optional
   **cost-centre** tag (nullable, §9), asset tag/serial (free text), status (DRAFT → IN_SERVICE → DISPOSED /
   WRITTEN_OFF). Derived **net book value** + accumulated depreciation on read.
3. **Acquisition — manual** — enter the asset directly with its cost. The **capitalisation GL posting**
   (DR Fixed Asset / CR a credit side) is the architect's call (§6, OQ-FA-02 — recommended: manual acquisition
   posts DR Fixed Asset / CR a clearing or the offset the operator names; the simplest correct v1 is **no
   automatic GL on manual create** — the asset is registered, and a separate manual journal / the bill-sourced
   path carries the capitalisation — flagged).
4. **Acquisition — from a matched AP supplier bill** — capitalise an asset from a bill line: the operator
   picks a matched bill (by uid), names the line to capitalise, and the system creates the asset with that
   net cost and the GL effect that lands the cost on the **Fixed Asset** account rather than the P&L
   (the exact posting is the architect's, §6, OQ-FA-02). The supplier link is recorded on the asset.
5. **Depreciation methods** — **straight-line** and **reducing-balance**, per asset (defaulted from category),
   never depreciating below salvage; the final period's charge is the **plug** that brings NBV exactly to
   salvage (no rounding drift).
6. **Depreciation schedule generation** — on an asset entering IN_SERVICE, generate the full planned schedule
   (period → planned charge → accumulated → NBV) from the start date over the useful life.
7. **Depreciation run (the headline)** — operator-initiated per company per fiscal period: compute each
   eligible asset's charge for that period, **post one GL journal** (DR Depreciation Expense / CR Accumulated
   Depreciation, per category if categories map to different accounts), **gated to the OPEN period**,
   **idempotent** (a period runs once; re-running is a no-op; a **`DEPRECIATION.RUN.EXECUTED`** event is
   emitted for downstream/audit). A run is **previewable** (compute without posting) before it commits.
8. **Disposal (sale or write-off)** — record proceeds (zero for a write-off), compute gain/loss
   (proceeds − NBV), post **the disposal journal** (remove cost + accumulated depreciation, post proceeds to a
   clearing/cash-offset the operator names, post the gain/loss), stop depreciation, set the asset DISPOSED /
   WRITTEN_OFF. Period-gated.
9. **Revaluation (simple)** — adjust an asset's carrying value up/down; post **DR Fixed Asset / CR Revaluation
   Reserve** (up) or **DR Revaluation Loss expense / CR Fixed Asset** (down); regenerate the remaining
   schedule on the new carrying value. Period-gated. Simple carrying-value model (§11, OQ-FA-05).
10. **Net book value** — derived (cost − accumulated depreciation ± revaluation), shown on the register and
    used at disposal.
11. **Asset transfers / relocation** — change an asset's location / branch / cost-centre **without GL**
    (a register edit, audited). (Inter-branch GL effect deferred — §2.)
12. **Asset register reports** — the register (filterable by category/status/location), a depreciation
    schedule per asset, and a **FA-to-GL reconciliation bar** (Σ asset cost == the Fixed Asset GL block;
    Σ accumulated depreciation == the Accumulated Depreciation GL balance — the BR-INV-06 / BR-VAT-08 recon
    discipline). On-screen + export.
13. **GL account mapping** — new `gl_config` keys + chart-of-accounts seed for the FA accounts (architect, §6).
14. **Fiscal-period gating** — every FA GL posting (depreciation, disposal, revaluation, bill-capitalisation)
    resolves and requires an OPEN period (BR-GL-03), reusing `FiscalPeriodResolver`.
15. **FA permissions, scope, and audit** — `FA.*` permissions, `ScopeGuard.assertCanActIn` on every read/write
    path, full audit trail. Append-only postings (corrections are reversing entries).

### Deferred (NOT in v1)

- **Units-of-production depreciation** (and double-declining-balance, sum-of-years-digits) — v1 is
  straight-line + reducing-balance only.
- **Capital Work in Progress (CWIP)** — assets under construction accumulating cost before in-service.
- **Component depreciation** (depreciating parts of an asset on different lives).
- **Asset impairment** (the formal IAS-36 impairment test) — v1 has only the simple revaluation-down.
- **Full IAS-16 revaluation model** — reserve recycling on disposal, depreciation on the revalued amount with
  reserve transfer to retained earnings (v1 is a simple carrying-value revaluation, OQ-FA-05).
- **Landed cost on an asset** (capitalising freight/install/duty into the asset cost) — v1 capitalises the
  bill-line net only.
- **Maintenance schedules**, **insurance tracking**, **periodic physical verification / asset count**,
  **barcode/tag scanning workflows** — register fields exist (tag/serial) but no workflow.
- **Inter-branch transfer with a GL effect** — v1 transfer is a register edit, no GL.
- **Automatic (`@Scheduled`) period-end depreciation run** — v1 is operator-initiated (OQ-FA-06).
- **Asset leasing / IFRS-16 right-of-use assets**, group/consolidated asset registers, multi-currency assets
  (base currency only, ADR-0005), asset financing/loans.

## 3. Actors

- **Asset Manager / Accountant** (`FA.REGISTER.MANAGE`, `FA.DEPRECIATE`, `FA.DISPOSE`) — registers assets,
  runs depreciation, disposes/writes-off, revalues.
- **Accountant / Reviewer** (`FA.VIEW`, `FA.DEPRECIATE`) — previews and posts depreciation runs.
- **Auditor / Verifier** (`FA.VIEW`, `FA.VERIFY`) — views the register, reports, and reconciliation; performs
  the (lightweight) verification flag.
- **Org Admin** — granted all `FA.*` (the migration grant pattern); configures categories + GL mapping.
- **System** — no autonomous FA actor in v1 (the depreciation run is operator-initiated); the
  `DEPRECIATION.RUN.EXECUTED` event is for audit/downstream, not a self-trigger.

## 4. Functional requirements (FR-FA-NN)

**Categories**
- **FR-FA-01** — Create/edit/deactivate an **asset category** per company: name, unique code, default method
  (STRAIGHT_LINE | REDUCING_BALANCE), default useful life (periods, > 0), default reducing-balance rate
  (0–100%, required iff method = REDUCING_BALANCE), and the three GL accounts the category posts to
  (asset / accumulated-depreciation / depreciation-expense). `MasterStatus` soft-delete; a category in use
  cannot be hard-deleted.
- **FR-FA-02** — On asset create, defaults flow from the chosen category; the asset may override life/method/
  rate (not the GL accounts in v1 — the category owns the accounts, OQ-FA-04).

**Register**
- **FR-FA-03** — Register an asset: name, category, acquisition cost (net, > 0), salvage value (≥ 0, < cost),
  useful life, method, rate (if reducing-balance), acquisition date, depreciation start date (≥ acquisition
  date), location, optional supplier, optional cost-centre, tag/serial. Allocated `FA-####`. Created DRAFT.
- **FR-FA-04** — Place an asset **IN_SERVICE** (from DRAFT): validates the depreciation inputs, **generates the
  depreciation schedule**, and (per OQ-FA-02) effects the capitalisation. An IN_SERVICE asset depreciates.
- **FR-FA-05** — Edit an asset's non-financial fields (name, location, tag, cost-centre) at any pre-disposal
  status, audited. Financial fields (cost, life, method, rate, salvage) are editable only while DRAFT
  (changing them post-IN_SERVICE is a **revaluation** or a correction, not a silent edit — BR-FA-09).
- **FR-FA-06** — Show the asset's **derived net book value** and accumulated depreciation on read.

**Acquisition**
- **FR-FA-07** — **Manual acquisition:** register + place IN_SERVICE with an operator-entered cost; the
  capitalisation GL treatment per OQ-FA-02.
- **FR-FA-08** — **Acquisition from an AP bill:** pick a matched supplier bill (by uid) and a bill line;
  create the asset with that line's **net** cost, record the supplier link and the source bill uid; the GL
  effect lands the cost on the Fixed Asset account (per OQ-FA-02). The bill is read as a DTO (no FA→AP entity
  coupling); AP's own posting is **not** changed by v1 (OQ-FA-02 confirms the capitalisation mechanism).

**Depreciation**
- **FR-FA-09** — Generate the **depreciation schedule** for an IN_SERVICE asset over its useful life, by its
  method, never below salvage, with the final period as the exact plug to salvage.
- **FR-FA-10** — **Preview** a depreciation run for a company + fiscal period: compute (do not post) each
  eligible asset's charge and the run total, with per-asset lines, so the operator reviews before posting.
- **FR-FA-11** — **Post** a depreciation run for a company + fiscal period: post **one GL journal**
  (DR Depreciation Expense / CR Accumulated Depreciation; per category if categories map to distinct
  accounts), **gated to the OPEN period** (BR-GL-03), **idempotent** (the same company+period runs once;
  re-running is a no-op, BR-FA-06), record the run (`DEPR-####`), update each asset's accumulated depreciation,
  and emit `DEPRECIATION.RUN.EXECUTED`.
- **FR-FA-12** — A depreciation run **skips** assets that are DRAFT, DISPOSED, WRITTEN_OFF, fully depreciated
  (NBV at salvage), or whose schedule has no charge for that period.

**Disposal & revaluation**
- **FR-FA-13** — **Dispose** an IN_SERVICE asset (sale): record disposal date + proceeds; compute
  gain/loss = proceeds − NBV; post the disposal journal (remove cost + accumulated depreciation, post proceeds
  to the named offset, post gain/loss); stop depreciation; set DISPOSED. Period-gated.
- **FR-FA-14** — **Write off** an IN_SERVICE asset (no proceeds): a disposal with proceeds = 0; the loss = full
  NBV; set WRITTEN_OFF. Period-gated.
- **FR-FA-15** — **Revalue** an IN_SERVICE asset up/down: post the revaluation journal; regenerate the
  remaining schedule on the new carrying value; period-gated; audited.
- **FR-FA-16** — **Transfer / relocate** an asset (location / branch / cost-centre) — a register edit, **no
  GL**, audited.

**Reports**
- **FR-FA-17** — **Asset register report** — filterable by category / status / location / branch; columns
  include cost, accumulated depreciation, NBV.
- **FR-FA-18** — **Depreciation schedule report** — per asset, the planned + posted charges by period.
- **FR-FA-19** — **FA-to-GL reconciliation** — Σ in-service asset cost == the Fixed Asset GL block balance;
  Σ accumulated depreciation == the Accumulated Depreciation GL balance (recon bars, BR-FA-08).

## 5. Business rules (BR-FA-NN)

- **BR-FA-01** — Depreciation never reduces an asset's NBV **below its salvage value**. The final scheduled
  charge is the **plug** that brings NBV to exactly salvage (no over/under-depreciation from rounding).
- **BR-FA-02** — **Straight-line** charge per period = `round((cost − salvage) / life_periods)` with the final
  period taking the residual; **reducing-balance** charge per period =
  `round(opening_NBV × rate)` capped so NBV does not fall below salvage, with the asset fully depreciated to
  salvage at the end of its life (a reducing-balance asset that has not reached salvage by end-of-life takes a
  final plug). All money HALF_UP, base currency (ADR-0005).
- **BR-FA-03** — Every FA GL posting (depreciation, disposal, revaluation, bill-capitalisation) resolves its
  accounts via `GLConfigResolver` / the category's mapped accounts — **never hard-coded codes** — and is
  **gated to an OPEN fiscal period** (`FiscalPeriodResolver.resolveOpen`); a closed/absent period **rejects**
  the post (BR-GL-03).
- **BR-FA-04** — All FA GL postings are **append-only**; a correction is a **reversing entry**, never an edit
  or delete (PROJECT-CONVENTIONS §3.6). A posted depreciation run is immutable; an error is corrected by a
  reversing journal + a re-run, or by a revaluation.
- **BR-FA-05** — Accumulated depreciation is a **contra-asset** (credit normal balance); the Fixed Asset
  account is an asset (debit); Depreciation Expense is an expense (debit); Gain/Loss on Disposal carries the
  sign (income on a gain, expense on a loss); Revaluation Reserve is equity (credit).
- **BR-FA-06** — A depreciation run is **idempotent per (company, fiscal period)**: a period that has already
  been posted cannot be re-posted (re-running is a no-op returning the existing run); this is the structural
  guard against double-charging a period (the `IdempotencyGuard`/unique-key discipline, NFR-FA-02).
- **BR-FA-07** — VAT on a capital purchase is **not capitalised** — only the **net** bill-line cost becomes the
  asset; the VAT is recovered via the VAT return (ADR-0017). (A non-recoverable-VAT capitalisation rule is
  deferred — OQ-FA-08.)
- **BR-FA-08** — **FA reconciles to the GL:** Σ(in-service asset acquisition cost ± revaluation) == the Fixed
  Asset GL block balance, and Σ(accumulated depreciation) == the Accumulated Depreciation GL balance. A
  disagreement is a finance-grade defect (the BR-INV-06 / BR-VAT-08 recon precedent).
- **BR-FA-09** — An IN_SERVICE asset's **financial inputs** (cost, useful life, method, rate, salvage) are
  **immutable** via the ordinary edit path; changing carrying value is a **revaluation** (FR-FA-15) and a
  data-entry error is a **disposal-and-re-register** or a reversing correction — never a silent edit (the
  append-only / no-retroactive-restatement discipline).
- **BR-FA-10** — A disposal / write-off **stops depreciation** from the disposal period onward; the disposal
  period itself takes its (final, pro-rated or full per OQ-FA-03) depreciation charge **before** the disposal
  gain/loss is computed, so NBV at disposal is correct.
- **BR-FA-11** — Depreciation **start date** governs the first period charged: an asset acquired mid-period
  starts depreciating per the start-date policy (full-period-in-month-of-service vs pro-rata — OQ-FA-03;
  recommended **full period from the period containing the start date**, the simplest correct default).
- **BR-FA-12** — Every FA mutation is **company/branch-scoped** (`RequestContext` + `ScopeGuard`) and
  **RBAC-gated** (`@perm`), and every mutation is **audited** (ADR-0004).

## 6. GL postings (the legs — architect ratifies accounts/keys in ADR-0030)

The behaviour is fixed; the **exact accounts + `gl_config` keys** are the ADR's (§9 flags). The legs:

- **Acquisition / capitalisation (from a bill or manual IN_SERVICE):** **DR Fixed Asset (the category's asset
  account) / CR** the offset (for a bill-sourced asset, the credit that clears the spend — architect chooses:
  a GRNI-style asset-clearing, or a direct offset; OQ-FA-02). VAT is **not** part of this leg (BR-FA-07).
- **Depreciation run:** **DR Depreciation Expense / CR Accumulated Depreciation** at the period's charge
  (one journal per run, per-category legs if accounts differ). `sourceType = DEPRECIATION`.
- **Disposal:** remove the asset — **CR Fixed Asset (cost)**, **DR Accumulated Depreciation (to date)**,
  **DR the proceeds offset (cash/clearing) for proceeds**, and the **gain/loss** to **Gain/Loss on Disposal**
  (CR for a gain, DR for a loss). Balanced by construction.
- **Write-off:** disposal with zero proceeds — **CR Fixed Asset / DR Accumulated Depreciation / DR Gain-Loss
  (the full remaining NBV as a loss)**.
- **Revaluation up:** **DR Fixed Asset / CR Revaluation Reserve**. **Revaluation down:** **DR Gain/Loss (or a
  Revaluation Loss expense) / CR Fixed Asset** (OQ-FA-05 — recommended: down to the same Gain/Loss on Disposal
  expense or a dedicated revaluation-loss; architect chooses).

**New GL accounts + `gl_config` keys** (architect ratifies the codes in ADR-0030; the recommended set):
Fixed Asset (asset block, e.g. `1600`), Accumulated Depreciation (contra-asset, e.g. `1700`), Depreciation
Expense (e.g. `5500`), Gain/Loss on Disposal (e.g. `4200`/`5600`), Revaluation Reserve (equity, e.g. `3200`).
Per-category account overrides ride on the category's mapped accounts (the category may point at different
asset/accumulated/expense accounts; the defaults come from the new `gl_config` keys).

## 7. Key flows

### 7.1 Happy — acquire from a bill, depreciate, dispose
1. AP matches a supplier bill for a vehicle (net 20,000,000; VAT recovered separately).
2. Accountant acquires an asset from the bill line: category "Motor Vehicles" (straight-line, 60 periods),
   cost 20,000,000, salvage 2,000,000, start date this period. Asset `FA-0001` created and placed IN_SERVICE;
   capitalisation posts the cost to the Fixed Asset account; the schedule generates (60 charges of 300,000,
   final period plugged).
3. Each month, the accountant **previews** then **posts** the depreciation run for the OPEN period: one
   journal DR Depreciation Expense 300,000 / CR Accumulated Depreciation 300,000 (summed across all assets).
   `DEPR-0001` etc. The asset's accumulated depreciation rises; NBV falls.
4. After 24 periods (accumulated 7,200,000; NBV 12,800,000) the vehicle is **sold** for 14,000,000. The
   disposal posts: CR Fixed Asset 20,000,000 / DR Accumulated Depreciation 7,200,000 / DR Cash-offset
   14,000,000 / CR Gain on Disposal 1,200,000. Asset → DISPOSED; depreciation stops.
5. The FA register report shows the asset gone from in-service; the FA-to-GL recon bar still ties.

### 7.2 Happy — manual asset, reducing-balance, revalue
1. Register a machine manually: category "Plant" (reducing-balance 20%/period... per useful life), cost
   5,000,000, salvage 500,000, IN_SERVICE. Schedule generates on the reducing-balance curve.
2. After some periods, the machine is **revalued up** to reflect a market increase: DR Fixed Asset / CR
   Revaluation Reserve; remaining schedule regenerates on the new carrying value.
3. Monthly runs continue on the revalued schedule.

### 7.3 Unhappy — closed period
- An accountant tries to post a depreciation run into a **closed** fiscal period → rejected ("no OPEN fiscal
  period for date", BR-GL-03). They open the period (or post into the next open one per policy) and re-run.

### 7.4 Unhappy — double-run
- An accountant posts the run for period 2026-03, then tries to run 2026-03 again → the second run is a
  **no-op** returning the existing `DEPR-####` (BR-FA-06, idempotent per company+period). No double charge.

### 7.5 Unhappy — write-off
- A computer is stolen; the accountant **writes it off** (proceeds 0). The disposal posts the full remaining
  NBV as a loss to Gain/Loss on Disposal; asset → WRITTEN_OFF; depreciation stops.

### 7.6 Unhappy — salvage floor
- A straight-line asset reaches its final period; the scheduled charge would take NBV below salvage → the
  final charge is **plugged** to land NBV exactly at salvage (BR-FA-01). Further runs skip the asset.

## 8. Non-functional requirements (NFR-FA-NN)

- **NFR-FA-01** — All FA money is **base currency**, `NUMERIC(19,4)`/`Money`, HALF_UP (ADR-0005). Depreciation
  arithmetic must not drift: the schedule sums **exactly** to (cost − salvage) over the life (the final-period
  plug, BR-FA-01).
- **NFR-FA-02** — The depreciation run is **idempotent and crash-safe per (company, period)** — a partial
  failure leaves no double-charge; the run records the period once and the GL post is the atomic unit.
- **NFR-FA-03** — Depreciation-schedule generation and the run are **bounded and paginated-friendly**: a run
  over thousands of assets posts one journal with summarised legs (not one journal per asset); the asset
  register lists are paginated.
- **NFR-FA-04** — Every read/write path calls `ScopeGuard.assertCanActIn` (the #1 anti-regression guard); FA
  is **company/branch-scoped**; cross-company access denies.
- **NFR-FA-05** — FA is **append-only on the books**: no FA posting is ever updated/deleted; corrections are
  reversing entries (BR-FA-04).
- **NFR-FA-06** — FA imports **no GL/AP entity** — it posts via `GLPostingService` and reads a bill via a DTO
  (the module-boundary discipline; ArchUnit no-cycle, §9).
- **NFR-FA-07** — All numbering (`FA-####`, `DEPR-####`) is concurrency-safe via the shipped `code_sequence`
  mechanism.
- **NFR-FA-08** — v1 is single-location-agnostic, base-currency, two depreciation methods; everything in §2
  Deferred is additive (no v1 column/flow precludes it) — CWIP, units-of-production, components, impairment,
  the full revaluation model.

## 9. Cross-module touch points & contracts to honour

- **GL (post + resolve + period-gate):** FA posts through `GLPostingService.post(JournalEntryDraft)` for the
  operator-initiated, synchronous postings (depreciation run, disposal, revaluation, bill-capitalisation) —
  these are human acts and a missing `gl_config` / closed period **must fail the command** (the ADR-0020 D-1
  "synchronous human-act posts directly, not via the safe-invoker" stance). Accounts via `GLConfigResolver`
  (new keys) or the category's mapped accounts. Period via `FiscalPeriodResolver.resolveOpen`.
- **AP/Purchases (soft, optional):** acquisition-from-bill reads the supplier bill by **uid** as a DTO (FA →
  `ap.service` returning a DTO, no AP entity import — the `ap → gl` cross-service precedent in the same
  direction). v1 does **not** change AP's posting (OQ-FA-02).
- **Cost-centre dimension (optional, design-to-contract):** the asset + depreciation expense carry a
  **nullable** cost-centre reference. If the dimension framework (PATH-TO-FULL-ERP §3.11) is not built at FA
  build time, this is a nullable scalar reserved for it (no FK to a non-existent table); when the framework
  lands, it activates the tag and the per-cost-centre depreciation grouping — additive, not precluded.
- **Outbox:** the depreciation run emits **`DEPRECIATION.RUN.EXECUTED`** (audit/downstream; no consumer is
  required in v1 — it is the seam a future notifications / management-reporting module consumes). The run's
  GL posting is **synchronous** (not via the outbox) — depreciation is an operator act, posted in the
  operator's transaction (consistent with the manual-journal posting model).

## 10. Accepted boundary (what v1 deliberately does NOT do)

- No CWIP, no units-of-production, no component depreciation, no impairment test, no full IAS-16 revaluation
  model, no landed cost on assets, no maintenance/insurance/verification workflows, no inter-branch GL
  transfer, no automatic scheduled run, no asset leasing/IFRS-16, no multi-currency assets. (§2 Deferred.)
- The capitalisation GL treatment of a **manual** acquisition is deliberately minimal (OQ-FA-02): the simplest
  correct v1 may register the asset with **no automatic capitalisation journal** (the operator posts the
  capital journal manually, or sources from a bill), leaving the bill-sourced path as the GL-effecting one.
  The architect ratifies.

## 11. Open questions (OQ-FA-NN — load-bearing flagged; architect resolves in ADR-0030)

- **OQ-FA-01 (load-bearing) — depreciation run granularity + idempotency mechanism.** One journal per run with
  per-category legs (recommended) vs per-asset journals; and the idempotency key — a unique
  `(company_id, fiscal_period_id)` on a `depreciation_runs` row + the `IdempotencyGuard` discipline. **The
  architect's decision; the behaviour (idempotent per company+period, one review-then-post) is fixed.**
- **OQ-FA-02 (load-bearing) — the capitalisation posting (bill-sourced vs manual).** What exactly does
  acquisition post? For a bill-sourced asset: does AP's posting change (capitalise to Fixed Asset at bill-match
  via a goods/asset predicate, like the GRNI swap) **or** does FA post a separate capitalisation journal
  reading the bill net (recommended — keeps AP unchanged, FA owns its posting)? For a manual asset: an
  automatic DR Fixed Asset / CR (a clearing the operator names) **or** no automatic journal (the simplest v1)?
  **The architect decides; the requirement is only that a capital spend lands on the asset block, not the P&L.**
- **OQ-FA-03 — first/last period convention.** Full period in the period of the start date (recommended,
  simplest) vs pro-rata by day. And the disposal period's charge (full vs pro-rata). Affects schedule math
  only; no schema impact.
- **OQ-FA-04 — per-asset GL account override.** v1: the **category** owns the GL accounts (asset/accumulated/
  expense); an asset inherits them. Confirm an asset cannot override its GL accounts in v1 (recommended —
  keeps the recon clean; per-asset overrides are additive later).
- **OQ-FA-05 — revaluation model depth.** Simple carrying-value revaluation (recommended v1: up → reserve,
  down → expense, regenerate remaining schedule) vs full IAS-16 (reserve recycling, depreciation on revalued
  amount, reserve→retained-earnings on disposal). v1 simple; full model deferred.
- **OQ-FA-06 — scheduled vs operator-initiated run.** v1 operator-initiated (recommended — human review before
  a finance-grade post). An automatic month-end `@Scheduled` run is deferred (needs the general scheduler).
- **OQ-FA-07 — cost-centre dimension availability.** If the dimension framework is not built when FA builds,
  the cost-centre field is a reserved nullable scalar (recommended) — confirm FA does not block on the
  dimension framework.
- **OQ-FA-08 — non-recoverable VAT capitalisation.** v1 capitalises net only (BR-FA-07). Confirm there is no
  v1 requirement to capitalise non-recoverable input VAT into the asset cost (deferred if so).

> **None of these blocks the requirements** — the *behaviour* (register → depreciate on a schedule → run posts
> to GL period-gated and idempotent → dispose with gain/loss → simple revalue) is fixed. OQ-FA-01 and OQ-FA-02
> are the load-bearing **design** decisions (run mechanism + capitalisation posting) the architect makes in
> ADR-0030.

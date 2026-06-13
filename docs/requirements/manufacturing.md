# Requirements — Manufacturing / Production (turn raw materials into finished goods, and cost them on the books)

> Status: **DRAFT (architect-authored, owner-style assumptions made; load-bearing OQs flagged §11).**
> This is the v1 business spec for **Manufacturing / Production** — the **work order** that consumes
> components (a BOM explosion) at moving-average cost, holds them as **Work-In-Progress (WIP)**, and
> **receives a finished good** back into stock at the rolled-up component cost (plus optional labour /
> overhead), with the matching **WIP accounting** posted to the books. Business-level only.
> **No schema, no API shapes, no tables/columns, no code** — those are the solutions-architect's, in
> **ADR-0035** (next step). Do not infer a data model from this document.
>
> Author: solutions-architect (standing in for system-analyst on a greenfield extension module) ·
> Domain: a **new module** `com.erp.modules.manufacturing` (the work-order header + lines + lifecycle +
> the costing/receipt orchestration), reading the **BOM** (`com.erp.modules.products`, ADR-0026), driving
> stock through the shipped **valuation engine** (`com.erp.modules.stock`, ADR-0020), and posting WIP /
> finished-goods journals through **GL** (`com.erp.modules.gl`, ADR-0013).
>
> **This is Manufacturing — Phase C's third extension module (docs/PATH-TO-FULL-ERP.md §3.6, area 9, XL),
> and the last Wave-1 design (ADR-0035 / V74–V80) — its Wave-1 generation failed (socket error) and is
> re-run here.** Every operational and financial dependency is shipped: GL (ADR-0013/V10), Inventory
> Valuation + COGS (ADR-0020/V17 — the moving-average engine), and **multi-level BOM (ADR-0026/V30 — BUILT,
> on `develop`)**. Manufacturing is a **leaf consumer**: it reads the BOM, drives the valuation engine, and
> posts GL; nothing downstream depends on it.
>
> **Depends on (all shipped):**
> - **Multi-level BOM** (ADR-0026 / V30 — BUILT): `products.service.BomExplosionService.explode(...)` (the
>   recursive explode-to-all-levels + flattened **leaf summary** of net component requirement, scrap/yield
>   compounded per level, max-depth bounded); `BomCostRollUpService.rollUp(parentUid, branchUid, outputQty)`
>   (the derived standard-cost from `avg_cost`, branch-scoped, with an `incompleteLeaves` flag); `BomDto` /
>   `BomComponentDto`. A work order **selects an ACTIVE BOM version** (or a pinned version, to reproduce an
>   old run) and explodes it for the planned output. Read DTO/service over the boundary
>   (`manufacturing → products`), no back-edge.
> - **Inventory Valuation + COGS** (ADR-0020 / V17 — the moving-average engine): `StockPostingService.post(...)`
>   (the single quantity primitive — appends a movement + upserts on-hand, now cost-carrying);
>   `InventoryValuationService.costIssue(companyId, branchId, productId, qty)` (debit on-hand value at the
>   current `avg_cost`, returns the issued value, null if `avg_cost` not established);
>   `recomputeOnReceipt(...)` (the weighted-average recompute the finished-goods receipt drives);
>   `reverseIssue(...)` / `reverseReceipt(...)` (exact-cost reversal on cancel). The component issue **is** a
>   stock issue at moving-average; the finished-goods receipt **is** a stock receipt at the computed unit cost.
> - **GL** (ADR-0013 / V10 — the posting engine): `GLPostingService.post(JournalEntryDraft)` (synchronous,
>   for the human-act work-order postings); `GLPostingSafeInvoker.postInNewTx` (REQUIRES_NEW, null-on-anomaly,
>   for any event-driven leg); `GLConfigResolver.resolve(companyId, key)` (role→account, throws on
>   missing/inactive — BR-GL-10); `FiscalPeriodResolver.resolveOpen(companyId, postingDate)` (the period
>   gate — issue / completion posts only into an OPEN period, BR-GL-03); `gl_configs` (new WIP / finished-goods
>   / variance keys); the seeded chart of accounts.
> - **Cost-centre dimension (OPTIONAL, ADR-0025 / V27–V29 — may not yet be integrated):** a work order may
>   carry an **optional, nullable** cost centre so its WIP / variance P&L legs are management-reportable.
>   The shipped `LineDraft` already carries the four nullable dimension slots; v1 passes them through. If
>   the framework is absent at build time the field is a nullable scalar the future framework activates
>   (design-to-contract, §9, OQ-MFG-07).
> - **Approvals (OPTIONAL soft gate, ADR-0022 / V20–V22):** releasing a high-value work order MAY route
>   through the approvals engine. v1 ships an **in-module permission gate** (`WORKORDER.RELEASE`) and does
>   **not** hard-depend on approvals (the soft-gate posture the coordination plan adopts for procurement).
> - **IAM / `RequestContext` / `ScopeGuard.assertCanActIn` / RBAC `@perm` / audit / `code_sequence` /
>   transactional outbox + `IdempotencyGuard` / Money** (the platform spine). All shipped.

## 1. Business context & why now

The business does not only buy and resell — it **makes** things. A bakery turns flour, sugar, and labour
into bread. An assembler turns sub-assemblies and fasteners into a finished unit. A blender turns bulk
inputs into packaged product. Today ERPCLEAN2 can describe **what** a product is made of (the multi-level
BOM, ADR-0026) and can cost **what it would cost** (the standard-cost roll-up) — but there is **no act of
production**. There is no document that says "make 100 of this", consumes the components out of stock,
holds the value in progress, and puts the finished good back on the shelf at its real cost. The inventory
valuation engine (ADR-0020) costs **purchases** (goods receipt) and **sales** (COGS at issue) but has no
**manufacture** path: a made good never gets an `avg_cost`, so it cannot be sold at a correct COGS, and the
balance sheet shows the raw materials but never the finished-goods value or the work in progress.

**Manufacturing closes that gap.** It gives the business:

- a **work order** — one document per production run: "make quantity Q of finished product P, using BOM
  version V, at branch B", with a lifecycle (plan it → release it → start it → complete it → close it, or
  cancel it), a human-readable number (`WO-####`), and a full audit trail;
- **component issue** — when the run starts (or progressively as it consumes), the BOM is **exploded to its
  leaf components** and those quantities are **issued out of stock at the current moving-average cost**
  (reusing the shipped valuation engine), with the **value moved into WIP** on the books
  (**DR WIP / CR Inventory**);
- **WIP accounting** — the value of issued components (and optional applied labour / overhead) sits in a
  **Work-In-Progress** asset account while the run is in progress; nothing is double-counted, and the WIP
  balance reconciles to the open work orders;
- **finished-goods receipt** — when the run completes, the finished product is **received back into stock**
  at its **computed unit cost** (rolled-up component cost + applied labour/overhead ÷ good quantity
  produced), which **establishes / moves the finished good's moving-average cost** (so it can later be sold
  at a correct COGS), with the WIP value **cleared into finished-goods inventory** on the books
  (**DR Inventory (finished good) / CR WIP**);
- **variance handling** — when the actual cost accumulated in WIP differs from the value relieved at the
  computed finished-goods cost (e.g. an over/under issue, a scrapped batch, a rounding residue at close), the
  residual WIP is cleared to a **Manufacturing Variance** account at work-order close, so WIP nets to zero
  per closed order and the books balance;
- **light routing / operations (OPTIONAL, v1)** — a work order MAY carry an ordered list of **operations**
  (a routing) describing the steps (mix → bake → pack), each with an optional applied labour/overhead amount
  feeding the finished cost; v1 treats routing as **descriptive + a cost-input vehicle**, not a scheduled,
  capacity-planned shop floor.

**Why now:** ADR-0026 (multi-level BOM) is built and on `develop`, and ADR-0020 (the moving-average
valuation/COGS engine) is shipped — the two gating prerequisites (PATH-TO-FULL-ERP §4 #5 + #2) are both
cleared. Manufacturing is the module those two were built *for*. With it, ERPCLEAN2 can cost a made good
end-to-end: raw materials in (purchase) → consumed into WIP (production issue) → finished good out
(production receipt at computed cost) → sold at correct COGS (the shipped sale path). The balance sheet
finally shows **inventory in all three states** (raw, WIP, finished), and the P&L's COGS is correct for
manufactured products.

## 2. In scope (v1) / Deferred

### In scope — v1

1. **Work order header + lifecycle** — create (DRAFT/PLANNED) → release → start (in progress) → complete →
   close; plus cancel. One finished product, one quantity, one branch, one BOM version per order.
2. **Work order component lines** — the planned + actual component consumption, materialised from the BOM
   explosion at release (the *plan*) and recorded as issued (the *actual*).
3. **BOM-driven component issue** — explode the chosen BOM version to its leaf components for the planned
   output; issue those quantities out of stock at **moving-average cost** via the shipped valuation engine;
   post **DR WIP / CR Inventory**. Supports a **single bulk issue at start** (the v1 default) with the model
   open to **progressive / partial issue** (allowed, not the default flow).
4. **WIP accounting** — every component issue debits WIP; every applied labour/overhead amount debits WIP;
   the finished-goods receipt credits WIP; close clears any residual WIP to variance. WIP balance per open
   order is the running accumulated cost.
5. **Finished-goods receipt at computed cost** — on completion, compute the finished-good **unit cost** =
   (Σ WIP accumulated cost) ÷ (good quantity produced); receive the finished product back into stock at that
   unit cost (which **drives the finished good's moving-average recompute** via the shipped engine); post
   **DR Inventory (finished good) / CR WIP**. Supports **partial / multiple completions** against one order
   (each receipt relieves its share of WIP), with the model open to it; v1 default is **one completion at
   the planned quantity**.
6. **Computed cost = rolled-up component cost + optional labour + optional overhead** — the finished cost is
   the actual component cost issued to the order **plus** any labour and overhead amounts applied (a flat
   amount per order or per operation in v1 — no labour-rate / time-tracking engine).
7. **Scrap / yield at completion** — a run may produce **less good output than planned** (yield loss); the
   computed unit cost divides the WIP by the **good** quantity (so scrap correctly inflates the unit cost of
   the good output). A separately-recorded **scrap quantity** is informational + drives the good/planned split.
8. **Manufacturing variance** — the residual WIP at close (actual accumulated cost − value relieved to
   finished goods) is cleared **DR/CR Manufacturing Variance**, so WIP nets to zero per closed order.
9. **Light routing / operations (OPTIONAL)** — an ordered operation list per order (sequence, description,
   optional work-centre label, optional applied labour amount, optional applied overhead amount). Descriptive
   + cost-input only in v1.
10. **GL postings** — WIP/Inventory/Finished-Goods/Variance legs, period-gated, base currency, HALF_UP,
    through the shipped GL engine; new `gl_config` keys + CoA accounts for WIP, Finished-Goods (reuses
    Inventory unless distinguished), and Manufacturing Variance.
11. **Numbering** — `WO-####` per company at create (the shipped `code_sequence` mechanism).
12. **Events** — `WORKORDER.RELEASED` / `WORKORDER.COMPLETED` (+ aggregate type) on the transactional outbox
    for downstream consumers (notifications, reporting) — informational; the GL/stock effects post
    **synchronously** in the work-order command (not eventually, mirroring the operator-act posting posture).
13. **Multi-tenant + RBAC + audit** — company/branch scoped, `@perm`-gated (`MANUFACTURING.*` /
    `WORKORDER.*`), `ScopeGuard.assertCanActIn` on every read + write path, fully audited.
14. **A work-order cost report + a WIP reconciliation** — view a work order's planned-vs-actual component
    consumption, accumulated WIP cost, computed finished cost, and variance; a WIP recon bar
    (Σ open-order WIP balances == the WIP GL account balance), the same finance-grade self-check the
    inventory valuation / AR / AP recons use.

### Deferred (not in v1 — none precluded by the data model)

- **MRP** (demand explosion / netting / suggested orders), **MPS** (master production schedule),
  **production scheduling** (forward/backward, capacity, work-centre load), **manufacturing lead-time data**.
- **Labour time-tracking** (operator clock-in/out, labour rates by employee/work-centre, time per operation)
  — v1 applies a **flat labour/overhead amount**, not a computed time × rate. (Hooks into HR/Payroll, ADR-0032.)
- **Overhead absorption rates / cost pools** (predetermined overhead rate × driver) — v1 applies a flat amount.
- **Quality control / inspection** (QC hold, accept/reject, inspection results) — `WORKORDER.QC` perm is
  reserved for the hook, but no QC workflow ships.
- **By-products / co-products** (a run yielding multiple outputs with cost apportionment).
- **Batch / lot / serial production tracking** (the made good's batch — gated on ADR-0028 batch/serial,
  built but not wired here in v1).
- **Subcontracting / outsourced production**, **rework / re-manufacturing**, **phantom blow-through at the
  production level** (the BOM resolver supports phantom plumbing; v1 production issues leaf components).
- **Production variance reporting by category** (material vs labour vs overhead vs yield variance split) —
  v1 has a single Manufacturing Variance account; the split is a reporting depth item.
- **Standard-costing with a separate standard vs actual** — v1 costs **actual** (moving-average component
  cost + applied amounts). A persisted standard cost + purchase/usage variance is deferred.
- **Engineering-vs-manufacturing BOM split**, **approval-on-release as a hard dependency** (soft gate only).

## 3. Actors

- **Production Planner / Manager** — creates work orders, selects the BOM version + output quantity, releases
  the order (`WORKORDER.RELEASE`), reviews planned-vs-actual cost. (`MANUFACTURING.VIEW`, `WORKORDER.MANAGE`,
  `WORKORDER.RELEASE`.)
- **Shop-floor Operator / Supervisor** — starts a released order (issues components), records actual
  consumption / scrap, records completion (finished-goods quantity + good/scrap split), applies
  labour/overhead amounts. (`WORKORDER.MANAGE`; issue/complete are consequences of those acts.)
- **Cost Accountant / Finance** — reviews the WIP balance and the WIP recon bar, reviews work-order variance,
  closes completed orders (`WORKORDER.CLOSE`), confirms the GL postings tie out. (`MANUFACTURING.VIEW`,
  `WORKORDER.CLOSE`, plus GL reporting perms.)
- **System (outbox dispatcher)** — dispatches the informational `WORKORDER.*` events; **not** the GL/stock
  poster (those are synchronous in the command, like the operator-act adjustment/opening paths in ADR-0020).
- **Administrator / ORG_ADMIN** — granted all `MANUFACTURING.*` / `WORKORDER.*` perms by the migration seed.

## 4. Functional requirements (FR-MFG-NN)

### Work order header + lifecycle

- **FR-MFG-01** — Create a work order: select a **finished product** (must be stockable; should have an
  ACTIVE BOM, or a pinned BOM version), a **planned output quantity** (> 0, base units), a **branch**, an
  optional **planned/scheduled date**, an optional **cost centre** (dimension), and optional **notes**. The
  order is created in status **PLANNED** (DRAFT-equivalent), allocated a `WO-####` number, audited.
- **FR-MFG-02** — A work order references **exactly one BOM version**. By default the **ACTIVE** version of
  the finished product at create/release time; the planner MAY **pin a specific version** (to reproduce an
  old run, ADR-0026 supports any-status explosion by `bomUid`). The pinned version is frozen on the order.
- **FR-MFG-03** — Lifecycle: **PLANNED → RELEASED → IN_PROGRESS → COMPLETED → CLOSED**, plus **CANCELLED**
  from any pre-COMPLETED state. The transitions:
  - **Release** (`WORKORDER.RELEASE`): explode the BOM for the planned output, materialise the planned
    component lines (the *plan*), validate the BOM is resolvable + acyclic (the engine guards this), emit
    `WORKORDER.RELEASED`. No GL/stock effect yet.
  - **Start / issue** (transitions RELEASED → IN_PROGRESS on first issue): issue components (FR-MFG-05).
  - **Complete** (`WORKORDER.MANAGE`): receive the finished good at computed cost (FR-MFG-07), set COMPLETED.
  - **Close** (`WORKORDER.CLOSE`): clear residual WIP to variance (FR-MFG-09), set CLOSED (terminal).
  - **Cancel** (`WORKORDER.MANAGE`): from PLANNED/RELEASED/IN_PROGRESS; **reverses any posted issues**
    (restore stock + WIP at original cost, ADR-0020 `reverseIssue`), sets CANCELLED (terminal).
- **FR-MFG-04** — A PLANNED order is freely editable (output qty, BOM version, branch, dates, notes); a
  RELEASED+ order is frozen on the load-bearing fields (output qty, BOM version, branch) — a change is a
  cancel + new order (the BOM ACTIVE-freeze discipline of ADR-0026, applied to the order).

### Component issue → WIP

- **FR-MFG-05** — Issue components: explode the order's BOM version to its **leaf components** for the
  (remaining) planned output via `BomExplosionService.explode(...)`; for each leaf, issue the net required
  quantity **out of stock at the current moving-average cost** via the shipped valuation engine
  (`StockPostingService.post(... PRODUCTION_ISSUE ...)` + `InventoryValuationService.costIssue(...)`); record
  the **actual issued quantity + value** on the order's component line. v1 default: **one bulk issue at
  start** for the full planned quantity; the model permits **partial / progressive** issue.
- **FR-MFG-06** — Each component issue posts **DR WIP / CR Inventory** at the issued value (Σ over leaf
  components, one journal per issue, per-leaf legs), period-gated. If a leaf's `avg_cost` is not established
  (never received/opened), the **costed WIP leg for that leaf is skipped with a WARN + anomaly** (the
  ADR-0020 D-2 edge — the quantity still issues; the next receipt of that component establishes its cost).
  This is an **incomplete-cost** state surfaced on the order (the work order cannot complete cleanly until
  every issued leaf has a cost — see BR-MFG-06).

### Finished-goods receipt → cost

- **FR-MFG-07** — Complete (receive finished goods): record the **good quantity produced** (≤ planned, or
  with an over-run allowance per BR-MFG-08) and an optional **scrap quantity**. Compute the finished-good
  **unit cost** = (accumulated WIP cost relievable to this completion) ÷ (good quantity); **receive the
  finished product into stock** at that unit cost via the shipped engine
  (`StockPostingService.post(... PRODUCTION_RECEIPT ...)` + `InventoryValuationService.recomputeOnReceipt(...)`)
  — which **establishes / moves the finished good's moving-average cost**; post **DR Inventory (finished
  good) / CR WIP** at the relieved value, period-gated.
- **FR-MFG-08** — Applied labour / overhead: the planner/operator MAY apply a **flat labour amount** and/or a
  **flat overhead amount** to the order (or per operation, FR-MFG-12). Each applied amount posts **DR WIP /
  CR (labour-applied / overhead-applied clearing)** when applied, increasing the WIP that the finished-goods
  receipt then relieves into the finished cost. v1 keeps labour/overhead as **flat applied amounts**, not
  time × rate.
- **FR-MFG-09** — Close: when an order is COMPLETED (all planned output received, or the operator declares it
  done), **close** it — compute the residual WIP (accumulated DR − relieved CR) and clear it to
  **Manufacturing Variance** (DR Variance / CR WIP if WIP positive; the reverse if negative), so WIP nets to
  **zero** for the closed order. Set CLOSED (terminal), audited.

### Scrap / yield / variance

- **FR-MFG-10** — Record **scrap** at completion (a quantity not usable as good output). Scrap reduces the
  good quantity the WIP divides by, correctly **inflating the good unit cost**. Scrap is informational on the
  order (no separate scrap-expense posting in v1 — its cost is absorbed into the good unit cost, the standard
  treatment; a dedicated scrap account is deferred).
- **FR-MFG-11** — Variance: surface the order's variance = (accumulated WIP cost) − (value relieved to
  finished goods) on the order cost report; it is cleared to the Manufacturing Variance account at close
  (FR-MFG-09). A single variance account in v1 (no material/labour/overhead split).

### Routing / operations (OPTIONAL)

- **FR-MFG-12** — A work order MAY carry an ordered list of **operations** (line no / sequence, description,
  optional work-centre label, optional applied labour amount, optional applied overhead amount). Operations
  are **descriptive** (the steps) + a **cost-input vehicle** (their applied amounts feed FR-MFG-08). v1 does
  **not** schedule, sequence-enforce, or capacity-plan operations. An order with no operations is valid
  (labour/overhead applied at the header instead).

### Reporting + reconciliation

- **FR-MFG-13** — Work-order cost report: per order, show planned-vs-actual component consumption (qty +
  value), applied labour/overhead, accumulated WIP, computed finished unit cost, finished quantity, scrap,
  and variance.
- **FR-MFG-14** — WIP reconciliation: Σ (accumulated WIP cost of all open / not-yet-closed orders) **==** the
  WIP GL account balance — a finance-grade self-check surfaced on screen (a red bar is a bug), the ADR-0020
  recon-bar pattern.
- **FR-MFG-15** — List / filter work orders by status, finished product, branch, date; paginated;
  company/branch scoped.

## 5. Business rules (BR-MFG-NN)

- **BR-MFG-01** — A work order's finished product **must be stockable** (you cannot receive a non-stockable
  product into stock). It **should** have a resolvable BOM version (ACTIVE or pinned) at **release**; release
  is rejected if the BOM is unresolvable / has no components.
- **BR-MFG-02** — The work order's BOM version is **frozen at release** (the pinned `bomUid` or the
  then-ACTIVE version's id is stamped on the order). A later BOM change / new version does **not** affect a
  released order — it reproduces the structure it was released against (ADR-0026 versioning is the point).
- **BR-MFG-03** — Component issue uses the **current moving-average cost** of each leaf component at issue
  time (the shipped `InventoryValuationService.costIssue` reads `avg_cost`). The valuation method is **not**
  reselected here — manufacturing **consumes** the ADR-0020 engine; it does not reimplement costing.
- **BR-MFG-04** — The finished-good **unit cost** = (WIP cost relievable to the completion) ÷ (good quantity
  produced), HALF_UP at the 4-dp internal cost scale (ADR-0020 D-11). The receipt **drives the shipped
  weighted-average recompute** on the finished good's on-hand row — manufacturing does not write `avg_cost`
  directly; it calls `recomputeOnReceipt` with the computed unit cost (one source of truth for the average).
- **BR-MFG-05** — **WIP nets to zero per closed order** (BR — the structural invariant): Σ DR WIP (component
  issues + applied labour/overhead) − Σ CR WIP (finished receipts) − variance-clear at close = 0. The WIP
  recon bar (FR-MFG-14) is the guardrail.
- **BR-MFG-06** — A leaf component with **no established `avg_cost`** at issue produces an **incomplete-cost**
  work order: the quantity issues, the costed WIP leg is skipped (ADR-0020 D-2 edge), and the order is
  flagged. **Completion is allowed but warned** (the finished cost will under-count that component); the
  recommended operator action is to establish the component's cost (receive it / set opening valuation) and
  re-issue. v1 does **not** block completion (it would strand a physical run); it surfaces the gap loudly.
- **BR-MFG-07** — All postings are **base currency** (ADR-0005, BR-INV-11), HALF_UP, posted **only into an
  OPEN fiscal period** (BR-GL-03). A work-order action whose posting date falls in a closed/absent period is
  rejected (the operator picks an open date) — the strict policy, consistent with the ratified period stance.
- **BR-MFG-08** — Over-run / under-run: the good quantity received MAY differ from planned. v1 allows
  receiving **up to the planned quantity by default**; an **over-run** (good > planned) is allowed only with
  an explicit flag (`allowOverRun`) — it simply relieves more WIP per unit at the computed cost. An under-run
  (good < planned) leaves residual WIP that close clears to variance (FR-MFG-09).
- **BR-MFG-09** — **Cancel** reverses every posted component issue **at the original issue cost** (ADR-0020
  `reverseIssue` — exact, no phantom gain/loss) and reverses any applied labour/overhead and any finished
  receipt at original cost, returning stock + WIP + finished-good `avg_cost` to their pre-order state. Cancel
  is rejected once an order is **CLOSED** (terminal — use a corrective adjustment).
- **BR-MFG-10** — Every work order, line, issue, completion, application, and close is **company + branch
  scoped** and **audited**; cross-company access is denied by `ScopeGuard.assertCanActIn` on every path.
- **BR-MFG-11** — A finished-goods receipt's quantity is **non-negative**; the computed unit cost is
  **non-negative** (a negative WIP at completion — pathological — is clamped to a zero unit cost with a WARN,
  the residual cleared to variance at close; mirrors the ADR-0020 zero/negative-cost guards).
- **BR-MFG-12** — Idempotency: the synchronous postings ride the work-order command transaction (one
  command → one set of postings); the GL poster's `(companyId, sourceType, sourceRef)` existence check is the
  belt-and-braces guard; the `WORKORDER.*` outbox events are deduped by the consumer's `IdempotencyGuard`.
  A re-submitted complete/close on an already-completed/closed order is rejected by the lifecycle guard.

## 6. Key flows

### Happy path — make 100 loaves (single issue + single completion)

1. Planner **creates** a work order: finished product *Bread*, planned qty *100*, branch *Main*. Status
   PLANNED, `WO-0001` allocated.
2. Planner **releases** it (`WORKORDER.RELEASE`): the engine explodes Bread's ACTIVE BOM for 100 →
   leaf requirement (e.g. 50 kg flour, 5 kg sugar, 2 kg yeast). Planned component lines materialised. BOM
   version stamped. `WORKORDER.RELEASED` emitted. Status RELEASED.
3. Operator **starts / issues**: the 50/5/2 leaf quantities are issued out of stock at their current
   moving-average cost (say total **180,000**). `PRODUCTION_ISSUE` movements posted; on-hand reduced;
   **DR WIP 180,000 / CR Inventory 180,000**. Status IN_PROGRESS. WIP = 180,000.
4. Operator **applies labour** 20,000 + **overhead** 10,000: **DR WIP 30,000 / CR Labour-Applied 20,000 +
   Overhead-Applied 10,000**. WIP = 210,000.
5. Operator **completes**: good qty *100*, scrap *0*. Unit cost = 210,000 ÷ 100 = **2,100**. Bread received
   into stock at 2,100/unit → finished-good moving-average recomputed; **DR Inventory(Bread) 210,000 /
   CR WIP 210,000**. Status COMPLETED. WIP = 0.
6. Finance **closes**: residual WIP = 0, no variance leg. Status CLOSED.
7. Later, a sale of Bread issues at COGS = its new `avg_cost` (the shipped sale path) — the made good is now
   correctly costed end-to-end. The WIP recon bar shows 0 open WIP == WIP GL balance 0.

### Happy path with yield loss + variance

- As above, but at completion good qty = *95*, scrap = *5*. Unit cost = 210,000 ÷ 95 = **2,210.5263**; 95
  Bread received at that cost; **DR Inventory 210,000 / CR WIP 210,000** (all WIP relieved into the good
  output — scrap absorbed into the good unit cost). Close: residual WIP 0, no variance. (Scrap inflated the
  good unit cost, as intended — FR-MFG-10.)
- Alternative: operator declares the order done after receiving only 90 good (abandoning the rest), relieving
  210,000 × (90/100 of computed)… in practice the operator completes at the cost-per-good and **closes** with
  a residual — that residual WIP is cleared **DR Manufacturing Variance / CR WIP** at close. WIP nets to 0.

### Unhappy paths

- **Component with no cost** (BR-MFG-06): yeast was never received/opened → `avg_cost` NULL. The yeast
  quantity still issues (on-hand goes negative), the **WIP leg for yeast is skipped** with a WARN + anomaly,
  the order is flagged **incomplete-cost**. Operator receives yeast (establishes cost) and re-issues, or
  completes with the warning (the finished cost under-counts yeast). Surfaced loudly on the order.
- **Posting into a closed period** (BR-MFG-07): an issue/complete dated into a closed period is **rejected**;
  the operator picks an open date. No partial posting (the command is atomic).
- **Cancel after issue** (BR-MFG-09): components were issued (DR WIP 180,000). Cancel reverses each issue at
  the **original cost** (restore stock + value), reverses applied labour/overhead, sets CANCELLED. WIP back
  to 0, stock restored exactly — no phantom gain/loss.
- **Missing `gl_config` (WIP / Variance not mapped)** (BR-GL-10): a synchronous work-order posting whose key
  resolves to no account **fails the operator's command** (the human-act posture — like ADR-0020 adjustment/
  opening); the company's CoA + gl_configs must be seeded (the migration + the new-company seeder do this).
- **Releasing with an unresolvable / empty BOM** (BR-MFG-01): release is rejected with a clear message; fix
  the BOM (activate a version with components) and re-release.

## 7. Non-functional requirements (NFR-MFG-NN)

- **NFR-MFG-01 (finance-grade integrity)** — WIP nets to zero per closed order; the WIP recon bar
  (Σ open-order WIP == WIP GL balance) is exact (`compareTo == 0`). A red bar is a finance-grade defect.
  Tests assert the WIP tie after every path (issue, apply, complete, close, cancel).
- **NFR-MFG-02 (one valuation engine, one explosion)** — Manufacturing **reuses** `InventoryValuationService`
  for all costing and `BomExplosionService` for all explosion. It introduces **no** second moving-average
  implementation and **no** second BOM explosion. (The top design risk, mirroring ADR-0026 NFR-BOM-04 +
  ADR-0020.)
- **NFR-MFG-03 (precision / rounding)** — costs carried at the ADR-0020 4-dp internal scale; the computed
  finished unit cost divides HALF_UP to 4 dp; GL legs rounded HALF_UP to the base-currency posting scale.
- **NFR-MFG-04 (atomicity)** — a work-order command (issue / complete / close / cancel) and its GL posting +
  stock movement + cost recompute commit **in one transaction** (the operator-act synchronous posture — no
  eventual-consistency gap between the physical act and its books, mirroring ADR-0020's adjustment/opening).
- **NFR-MFG-05 (concurrency)** — component issue + finished receipt drive the shipped on-hand optimistic-lock
  recompute (ADR-0020 NFR-INV-05); the work-order header carries `@Version`. No new locking mechanism.
- **NFR-MFG-06 (scope on every path)** — `ScopeGuard.assertCanActIn` on every read + write (the #1
  anti-regression guard); per-company/branch isolation tested.
- **NFR-MFG-07 (performance)** — the explosion is O(levels) batched (the shipped engine); the WIP recon
  aggregates in SQL (not row-by-row); work-order lists paginated. A deep BOM does not table-scan.
- **NFR-MFG-08 (additive + non-destructive)** — additive on the frozen V1–V19 and the in-flight V20–V73:
  new tables, new `gl_config` keys + CoA accounts, new `JournalSourceType` tokens, two new `MovementType`
  values admitted by the CHECK, two new `DomainEventType` values, new perms, new `code_sequence` kind, new
  `ScopeGuard` cases. No edit to any prior migration; no behaviour change to any shipped sale/purchase path.

## 8. User stories (US-MFG-NN)

- **US-MFG-01** — As a planner, I create a work order for a finished product and a quantity so the shop floor
  knows what to make. *(FR-MFG-01/02)*
- **US-MFG-02** — As a planner, I release a work order and see the exact component requirement exploded from
  the BOM so I know what stock the run needs. *(FR-MFG-03/05)*
- **US-MFG-03** — As an operator, I issue the components to a work order and the system deducts them from
  stock at their real cost and holds that cost as WIP. *(FR-MFG-05/06)*
- **US-MFG-04** — As an operator, I record completion and the finished good lands back in stock at its
  computed cost so it can be sold at the right COGS. *(FR-MFG-07)*
- **US-MFG-05** — As an operator, I apply a labour and overhead amount so the finished cost reflects the work,
  not just the materials. *(FR-MFG-08/12)*
- **US-MFG-06** — As a cost accountant, I see each work order's planned-vs-actual cost and its variance so I
  can investigate over/under consumption. *(FR-MFG-11/13)*
- **US-MFG-07** — As a cost accountant, I confirm that total WIP on the books equals the sum of open work
  orders' WIP so I trust the manufacturing books. *(FR-MFG-14)*
- **US-MFG-08** — As a planner, I cancel a work order that won't proceed and the system cleanly reverses any
  stock and WIP it already moved. *(FR-MFG-03 / BR-MFG-09)*

## 9. Design-to-contract notes (for the ADR, not requirements)

- The **cost-centre dimension** field on the work order is a nullable scalar; if ADR-0025's framework is
  integrated, the WIP/variance P&L legs pass the resolved dimension ids through the shipped `LineDraft`
  dimension slots; if not, it is inert until activated (OQ-MFG-07).
- **Approvals** on release is a **soft gate** (`WORKORDER.RELEASE` permission) in v1; the approvals engine
  (ADR-0022) MAY wrap the release transition later without a schema change (the release transition is the seam).
- **Finished-goods account**: v1 MAY post finished-goods receipts to the **existing Inventory (1300)**
  account (raw + WIP + finished all roll into the inventory asset, distinguished by the stock valuation
  report's per-product rows) OR to a **distinct Finished-Goods Inventory** account. The ADR decides (see
  OQ-MFG-01); the requirement is only that the made good's value lands on the balance sheet and the WIP
  clears.

## 10. Accepted boundary (what v1 deliberately does NOT do)

- No MRP / MPS / scheduling / capacity planning — work orders are created manually, one at a time.
- No labour time-tracking / rates — labour and overhead are flat applied amounts.
- No QC workflow, no by-products/co-products, no subcontracting, no rework, no batch/lot/serial of the made
  good, no engineering-vs-manufacturing BOM, no material/labour/overhead variance split.
- No change to any shipped sale or purchase costing path — manufacturing **adds** a production path; it does
  not alter how purchases or sales are costed.

## 11. Open questions (OQ-MFG-NN — recommended defaults adopted; the load-bearing ones flagged)

- **OQ-MFG-01 (LOAD-BEARING) — Finished-Goods account: distinct or reuse Inventory 1300?** *Recommended
  default: reuse `INVENTORY` (1300) for the finished-goods receipt leg in v1* — raw/WIP/finished are all the
  inventory asset, distinguished per-product in the stock valuation report; this keeps the existing inventory
  recon (Σ on_hand_value == 1300) **whole** and avoids splitting the asset before there is a reporting need
  for it. **A distinct `FINISHED_GOODS` (1350) account is the alternative** (clearer balance-sheet split, but
  it forks the inventory recon and the made good's on-hand row would have to know which account it values
  against). *Flagged for owner (finance) confirmation.* The ADR designs the key (`FINISHED_GOODS`) but
  recommends mapping it to **1300** in v1 so it is a one-line change to split later. **This is the decision
  that most shapes the chart of accounts.**
- **OQ-MFG-02 (LOAD-BEARING) — WIP relief on partial completion + the cost-per-good basis.** *Recommended
  default: relieve WIP at (accumulated WIP ÷ good qty) × received qty per completion, residual cleared at
  close* — simple, WIP-nets-to-zero-at-close. The alternative (relieve at a pre-computed standard and book
  variance per completion) needs a standard cost (deferred). *Flagged — confirms the costing arithmetic.*
- **OQ-MFG-03 — Issue timing: bulk-at-start vs progressive vs backflush-at-completion.** *Recommended:
  support bulk-at-start (default) + explicit partial issue; **backflush** (auto-issue at completion) is a
  fast-follow on the same primitive.* Not load-bearing (the model carries actual issued lines either way).
- **OQ-MFG-04 — Scrap treatment: absorb into good unit cost (v1) vs separate scrap-expense posting.**
  *Recommended: absorb (v1) — scrap inflates the good unit cost, no separate account.* A dedicated
  `SCRAP_EXPENSE` account is deferred (reuses `STOCK_ADJUSTMENT`/5160 if ever needed, no new key).
- **OQ-MFG-05 — Labour/overhead source: flat applied amount (v1) vs time × rate.** *Recommended: flat amount
  (v1); time × rate hooks into HR/Payroll later.* The labour/overhead clearing accounts are the seam.
- **OQ-MFG-06 — Routing: descriptive + cost-input (v1) vs scheduled/sequenced.** *Recommended: descriptive +
  cost-input, optional.* Scheduling is deferred (MRP/MPS round).
- **OQ-MFG-07 — Cost-centre on the WO: nullable scalar now, framework-activated later.** *Recommended:
  carry the nullable dimension ref; pass through `LineDraft` slots if the framework is present.*
- **OQ-MFG-08 — `WORKORDER.QC` perm: reserve now (no workflow) vs add when QC ships.** *Recommended: reserve
  the perm so the nav/hook exists; no QC workflow in v1.* (Owner may drop it to avoid a dead permission.)
- **OQ-MFG-09 — Outbox events synchronous-post vs event-driven post.** *Recommended: GL/stock effects post
  **synchronously** in the command (operator-act posture, ADR-0020 adjustment/opening precedent); the
  `WORKORDER.*` events are **informational** for notifications/reporting only.* (A future move to event-driven
  posting is possible but reintroduces an eventual-consistency gap the recon would then have to tolerate.)

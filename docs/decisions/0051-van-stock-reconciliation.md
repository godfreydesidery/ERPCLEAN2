# ADR-0051: Van-stock reconciliation (route sales day-end)

- **Status:** Proposed (2026-07-04) — awaits owner sign-off on the `V85` DDL (migration-approval standing rule) and on the flagged decisions below.
- **Deciders:** Owner + Solutions Architect
- **Deferred item:** D-8 (`docs/DEFERRED-ITEMS.md`). **Effort:** L. **Migration:** `V85` (provisional — re-verify next-free vs `origin/develop` at build; current max applied = `V79`; D-1 `V80/V81`, D-6 `V82`, D-7 `V83/V84` land first per the agreed build order).
- **Related:** ADR-0028 (inventory depth — `stock_locations`, `LocationType.VAN`, `StockTransfer`, `StockCount`, the `LocationResolver` default-location seam), ADR-0020 (moving-average valuation + COGS), ADR-0010 (stock movement ledger + posting primitive), ADR-0021 (SO → delivery issue path), ADR-0050 / ADR-0049 / ADR-0048 (recent deferred-item ADRs — the record-only vs auto-post pattern), ADR-0043 (schema freeze / durable DB — additive-only), ADR-0046 (provisioning over data-migrations).

## Context

Route agents (persona **Hamisi**) load stock onto a van, sell off it on the round, and must **reconcile
van stock** (loaded − sold − returned = should-be-on-van) at day end, then physically count the van and
account for any shortfall. There is no reconciliation flow today.

`LocationType.VAN` already exists (`LocationType.java`), so **no location-type migration is needed**. The
question that defines the whole feature is: *how are loaded / sold / returned quantities SOURCED per product
for a van on a business date — derived from the ledger, or entered?*

### The determinant fact: route sales do NOT issue stock from a van (verified 2026-07-04)

This is the load-bearing finding and it drives every decision below.

1. **Sale/delivery/POS issues post against the branch DEFAULT (WAREHOUSE) location, never a van.**
   - `SaleIssueStockHandler.processSimpleLine` (line 228) calls the **location-unaware**
     `posting.post(companyId, branchId, productId, …)` overload (`StockPostingService.post`, the
     `@Deprecated` 13-arg overload at lines 76–81).
   - `DeliveryIssueStockHandler.processSimpleLine` (line 225) does the same (SO-sourced route sales go
     through the delivery path, ADR-0021 D-6).
   - `StockPostingServiceImpl.post` (line 105) sees `locationId == null` and resolves it to
     `locationResolver.defaultLocationId(companyId, branchId)` (line 106), which returns the branch
     `is_default = true` WAREHOUSE location (`LocationResolver.defaultLocationId`, line 41).
   - The sales and POS create paths carry **no** location concept at all (grep of
     `com.erp.modules.sales` for `location`: only unrelated AR-void comments).

   **Therefore "sold from van" is NOT derivable from the ledger.** A day of route sales decrements the
   *warehouse* default location, not the van.

2. **Transfers ARE location-aware, so LOADED and RETURNED *are* derivable.**
   - Loading a van = a `StockTransfer` WAREHOUSE → VAN. `TransferReceiveStockHandler` (lines 101–108)
     posts `TRANSFER_IN` at `destLocationId` (the van) — the van's `stock_on_hand` is credited.
   - Returning to store = a `StockTransfer` VAN → WAREHOUSE. `TransferDispatchStockHandler` (lines 89–96)
     posts `TRANSFER_OUT` (negative) at `sourceLocationId` (the van).
   - So per product: `loaded = Σ TRANSFER_IN at the van in the window`, `returned = −Σ TRANSFER_OUT at
     the van in the window`. **No such aggregate query exists yet** on `StockMovementRepository` — it must
     be added (`sumByLocationTypeAndWindow`).

3. **Consequence — the van's `stock_on_hand` is NOT a truthful "expected on van".** Because loads credit
   the van but sales never debit it, the van on-hand equals `loaded − returned` and **overstates the true
   on-van by exactly `sold`**. Worked example: load 100, sell 70 (debits the warehouse), return 25 →
   van on-hand = 75, but the truck really holds `100 − 70 − 25 = 5`. The correct expected-on-van is
   `loaded − sold − returned`, which the ledger **cannot** produce because `sold` isn't recorded against
   the van.

### What this means for the design

- **`sold` must be ENTERED** (the round total per product), not derived — unless the whole
  route-sales-issue-from-van path is first wired (a large prerequisite; see Out of Scope).
- **A plain `StockCount` of the van is WRONG here.** `StockCountServiceImpl.post` (line 228) computes
  `variance = counted − live_on_hand_at_location`. Against the (untruthful) van on-hand of 75, it would
  book a −70 adjustment — **double-counting the COGS already posted at the warehouse issue**. Reusing the
  count machinery as-is would corrupt inventory value and the GL. The reconciliation math must be
  `expected = loaded − sold − returned`, `variance = physical − expected`, which is a **different
  computation** from `StockCount`'s `counted − on_hand`. This is why a **standalone aggregate** (not a
  `StockCount` subtype) is required — for correctness, not preference.

## Decision

### D-8.1 — SOURCING: loaded/returned DERIVED (prefilled, editable); sold + physical ENTERED

The reconciliation is a **day-end worksheet** keyed on `(van_location, agent, business_date)`:

| Column        | Source                                                                                   |
|---------------|------------------------------------------------------------------------------------------|
| `loaded_qty`  | **Derived** — `Σ TRANSFER_IN` at the van location in the business-day window; prefilled, **editable** (agent can correct for a manual load). |
| `returned_qty`| **Derived** — `−Σ TRANSFER_OUT` at the van location in the window; prefilled, **editable**. |
| `sold_qty`    | **Entered** — the round's sold total per product (not derivable; see Context). |
| `expected_qty`| **Computed** — `loaded − sold − returned`. Stored (immutable snapshot). |
| `physical_qty`| **Entered** — the physical count on the van at day end. |
| `variance_qty`| **Computed** — `physical − expected` (positive = surplus on van / under-reported sales; negative = shortage: theft, breakage, miscount, unrecorded sale). |

`loaded`/`returned` are derived-if-available-else-entered because the transfer-based loading may or may not
have been used; the derivation prefills them and the agent confirms/overrides. `sold` and `physical` are
always entered. This is the honest expression of what the system can and cannot know today.

### D-8.2 — Variance is RECORD-ONLY (no stock posting, no GL) in this slice

The worksheet **records** the variance; it does **not** post a stock adjustment. Rationale:

1. **Correctness.** The van's `stock_on_hand` is not truthful (D-8.1 / Context §3). Posting an
   `ADJUSTMENT` against it would move a fictional balance and re-book COGS that the warehouse issue already
   booked. There is no defensible ledger seam to post against until route-sales-issue-from-van exists.
2. **Blast radius.** Record-only keeps the change to the two new tables. Auto-posting would drag in the
   valuation engine (`InventoryValuationService.revalueAdjustment`), the `InventoryGlPoster` adjustment
   seam, and account 1300 reconciliation — none of which can be made correct under current wiring.
3. **It is still a real control.** The variance is an **accountability / cash-integrity** figure for the
   agent's round (did the truck return what the paperwork says?). That is the persona's actual need. A
   genuine *stock* correction, if one is warranted, is booked through the existing `STOCK.ADJUST` or a
   `StockCount` flow, separately and deliberately.

The line stores a nullable `unit_cost_amount` (snapshot avg) and `variance_value` for a shortage-value
report column — data captured, ledger untouched. `reconcile()` freezes the worksheet, audits, and emits an
**informational** `VAN.RECONCILED` outbox event for reporting; it posts nothing.

> If the owner later wants the variance to correct stock, that is a fast-follow that **presupposes**
> route-sales-issue-from-van (Out of Scope below) so the van on-hand becomes truthful; only then can a
> `StockCount`-style variance post safely.

### D-8.3 — A standalone `van_reconciliations` aggregate, NOT a `StockCount` subtype

Model as `van_reconciliations` + `van_reconciliation_lines` (the proposal), **not** a specialized
`StockCount` of the van. Justification is in Context §3 / D-8.1: the reconciliation identity is
`variance = physical − (loaded − sold − returned)`, which is structurally different from `StockCount`'s
`variance = counted − live_on_hand`, and the van on-hand is untruthful. Forcing this into `StockCount`
would either post a wrong variance or require special-casing the count engine to ignore its own on-hand
input — more coupling for negative value. The standalone aggregate also carries the `loaded/sold/returned`
breakdown, which `StockCount` has no place for.

### D-8.4 — Van ↔ agent link: nullable scalar FK on `stock_locations`, one live van per agent

- Add nullable `stock_locations.agent_id` (real DB FK → `agents(id)`; NULL for fixed locations). It is set
  **only** on `VAN`-type locations (service-enforced). Stored as a scalar and resolved cross-module via the
  existing `parties.AgentService.getByUid` (DTO-level read) — the same scalar-FK-to-another-module idiom as
  `StockMovement.projectId` / `costCentreValueId`.
- **`Agent` is company-scoped, not branch-scoped** (`Agent extends PartyBase`; `PartyBase` has
  `company_id`, no `branch_id`). The van is branch-scoped. The assignment endpoint therefore validates only
  that the agent and the van share the **company** (an agent may run a van in any branch of the company).
- **Uniqueness:** a **partial unique index** `uq_stock_location_agent_active` on `(agent_id)`
  `WHERE agent_id IS NOT NULL AND status = 'ACTIVE'` — at most **one active van per agent** (reassignment is
  possible after the old van is deactivated). This mirrors the existing `uq_stock_location_one_default`
  partial-unique idiom (ADR-0028 D-4) and keeps the invariant in the DB, not just the service.
- Assignment rides the **existing** location-management endpoint: `CreateStockLocationRequest` /
  `UpdateStockLocationRequest` gain an optional `agentUid` (gated by `STOCK.LOCATION.MANAGE`). No new
  endpoint.

### D-8.5 — Migration (`V85`, additive, plain transactional — no `CONCURRENTLY`)

Per the D-1/D-6/D-7 lesson this repo has **no non-transactional migration mode**; all index builds are
plain. Single transaction, additive. `stock_locations.agent_id` is added **nullable** to a populated table
(all-NULL → FK validates instantly); the two new tables are empty.

```sql
-- ============================================================================
-- DEFERRED ITEM D-8: van-stock reconciliation (route sales day-end worksheet).
-- Additive only. LocationType.VAN already exists (no type migration).
-- Depends on: companies, branches (V1); stock_locations, agents, products.
-- Record-only: no GL / no stock posting (ADR-0051 D-8.2).
-- New endpoints seed STOCK.VAN_RECON.MANAGE/.VIEW in R__seed_permissions.sql.
-- ============================================================================

-- 1) Bind a VAN stock-location to the route agent that runs it (nullable additive).
ALTER TABLE stock_locations
    ADD COLUMN agent_id BIGINT NULL;
ALTER TABLE stock_locations
    ADD CONSTRAINT fk_stock_location_agent
        FOREIGN KEY (agent_id) REFERENCES agents (id);
-- At most one ACTIVE van per agent (reassignment allowed after deactivation).
CREATE UNIQUE INDEX uq_stock_location_agent_active
    ON stock_locations (agent_id)
    WHERE agent_id IS NOT NULL AND status = 'ACTIVE';

-- 2) Day-end reconciliation worksheet for a van.
CREATE TABLE van_reconciliations (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    uid             VARCHAR(26)   NOT NULL,
    company_id      BIGINT        NOT NULL,
    branch_id       BIGINT        NOT NULL,
    recon_number    VARCHAR(30)   NOT NULL,                 -- VR-#### per company
    van_location_id BIGINT        NOT NULL,                 -- stock_locations.id (VAN type)
    agent_id        BIGINT,                                 -- agents.id (route agent), nullable
    business_date   DATE          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',  -- OPEN|RECONCILED|CANCELLED
    notes           VARCHAR(500),
    reconciled_at   TIMESTAMPTZ,
    reconciled_by   BIGINT,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,

    CONSTRAINT uq_van_reconciliation_uid      UNIQUE (uid),
    CONSTRAINT uq_van_reconciliation_number   UNIQUE (company_id, recon_number),
    CONSTRAINT fk_van_reconciliation_company  FOREIGN KEY (company_id)      REFERENCES companies (id),
    CONSTRAINT fk_van_reconciliation_branch   FOREIGN KEY (branch_id)       REFERENCES branches (id),
    CONSTRAINT fk_van_reconciliation_location FOREIGN KEY (van_location_id) REFERENCES stock_locations (id),
    CONSTRAINT fk_van_reconciliation_agent    FOREIGN KEY (agent_id)        REFERENCES agents (id),
    CONSTRAINT chk_van_reconciliation_status  CHECK (status IN ('OPEN','RECONCILED','CANCELLED'))
);
-- One live (non-cancelled) worksheet per van per day; a fresh one is allowed after cancel.
CREATE UNIQUE INDEX uq_van_reconciliation_van_date
    ON van_reconciliations (van_location_id, business_date)
    WHERE status <> 'CANCELLED';
CREATE INDEX ix_van_reconciliation_company_date
    ON van_reconciliations (company_id, branch_id, business_date);

-- 3) Per-product reconciliation line.
CREATE TABLE van_reconciliation_lines (
    id                    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    van_reconciliation_id BIGINT        NOT NULL,
    line_no               SMALLINT      NOT NULL,
    product_id            BIGINT        NOT NULL,
    product_code          VARCHAR(60),                       -- denormalised snapshot (immutability)
    product_name          VARCHAR(200),
    loaded_qty            NUMERIC(19,6) NOT NULL DEFAULT 0,   -- derived (TRANSFER_IN), editable
    sold_qty              NUMERIC(19,6) NOT NULL DEFAULT 0,   -- entered
    returned_qty          NUMERIC(19,6) NOT NULL DEFAULT 0,   -- derived (-TRANSFER_OUT), editable
    expected_qty          NUMERIC(19,6) NOT NULL DEFAULT 0,   -- loaded - sold - returned
    physical_qty          NUMERIC(19,6),                      -- entered day-end count (NULL until entered)
    variance_qty          NUMERIC(19,6) NOT NULL DEFAULT 0,   -- physical - expected
    unit_cost_amount      NUMERIC(19,4),                      -- snapshot avg (report only; no GL)
    variance_value        NUMERIC(19,4),                      -- variance_qty * unit_cost (report only)
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_at            TIMESTAMPTZ,
    updated_by            BIGINT,

    CONSTRAINT fk_van_recon_line_recon   FOREIGN KEY (van_reconciliation_id) REFERENCES van_reconciliations (id),
    CONSTRAINT fk_van_recon_line_product FOREIGN KEY (product_id)            REFERENCES products (id),
    CONSTRAINT uq_van_recon_line         UNIQUE (van_reconciliation_id, product_id)
);
```

Changes vs the `docs/proposed-migrations/V85` draft: added `recon_number` (+ per-company unique) matching the
`WarehouseNumberGenerator` idiom; added `CANCELLED` status + a live-per-van-per-day partial unique + a
reporting index; added the `uq_stock_location_agent_active` partial unique; and **corrected the line model** —
the draft's single `variance_qty (= loaded − sold − returned)` conflated *expected* with *variance*. The line
now separates `expected_qty` (= loaded − sold − returned) from `physical_qty` (entered) and
`variance_qty` (= physical − expected), plus denormalised product code/name (immutability, per
`StockCountLine`) and nullable cost/value columns for a shortage-value report.

### D-8.6 — Derivation query (new, on `StockMovementRepository`)

```java
@Query("""
    SELECT m.productId AS productId, COALESCE(SUM(m.quantity), 0) AS qty
    FROM StockMovement m
    WHERE m.companyId = :companyId AND m.branchId = :branchId
      AND m.locationId = :locationId
      AND m.movementType = :type
      AND m.occurredAt >= :from AND m.occurredAt < :to
    GROUP BY m.productId
    """)
List<VanMovementSumRow> sumByLocationTypeAndWindow(Long companyId, Long branchId, Long locationId,
                                                   MovementType type, Instant from, Instant to);
```

`loaded = Σ(TRANSFER_IN)`; `returned = −Σ(TRANSFER_OUT)`. The `[from, to)` window is the `business_date` at
UTC day boundaries (consistent with the existing handlers' `ZoneOffset.UTC` date arithmetic).

### D-8.7 — Permissions (module `stock`)

Add to `R__seed_permissions.sql` (auto-granted to `ORG_ADMIN` by the existing CROSS JOIN):
- `STOCK.VAN_RECON.MANAGE` — create/enter/reconcile/cancel a van reconciliation.
- `STOCK.VAN_RECON.VIEW` — view van reconciliations.

The `STOCK.*` family prefix matches `STOCK.COUNT.*` / `STOCK.TRANSFER.*` / `STOCK.LOCATION.*` (D-8 note said
`VAN_RECON.MANAGE`; the `STOCK.` prefix keeps the family consistent). Register a `ScopeGuard` target type
`vanreconciliation` → `vanReconciliations.findCompanyIdByUid(uid)` for the uid-scoped GET. Angular
`requirePermission()` route-guard codes must equal these exactly (route-guard ↔ endpoint parity).

### D-8.8 — Explicitly OUT of scope (follow-ups)

- **Route-sales-issue-from-van** — threading a source location through the sale/delivery/POS issue path so
  route sales debit the van's `stock_on_hand`. This is the large prerequisite that would make `sold`
  derivable **and** the van on-hand truthful. It touches `SaleIssueStockHandler` /
  `DeliveryIssueStockHandler` / `PosSaleServiceImpl` + the `SaleFinalised`/`DeliveryConfirmed` payloads +
  the sales create forms. Its own ADR.
- **Variance → stock/GL posting** — deferred with the above; only safe once the van on-hand is truthful.
  `unit_cost_amount`/`variance_value`/the outbox event are reserved so the fast-follow is additive.
- **Auto-derived `sold`** — impossible without route-sales-from-van; entered for now.
- **Multi-van-per-agent / van handover between agents mid-day** — one active van per agent (D-8.4).
- **Reconciliation approval workflow** (supervisor sign-off before RECONCILE) — two-state OPEN→RECONCILED
  now; a `COUNTED`/approval state is an additive follow-up.
- **Route-module coupling** (tie the reconciliation to a `route` + planned call list) — the aggregate lives
  in `stock`; a route dimension is a later enrichment.

### D-8.9 — Adversarial-review disposition (pre-merge)

A 4-lens adversarial review (14 findings, 9 confirmed) ran before merge. **Fixed in this PR:**

- **Sold-only line data-loss (minor).** `saveLines` submitted only lines with a *physical* count and
  the request required `physicalQty` — so a typed `sold` with no physical count yet was silently
  dropped on the next re-seed. Fixed both ends: `physicalQty` is nullable on `LineEntry` (variance
  stays null until a physical count exists), and the web grid now saves exactly the lines the agent
  **edited this session** (dirty-tracking), sending `physicalQty: null` when the count is blank.
- **Freeze-under-concurrency (minor).** `enterLines` checked `status == OPEN` but never touched the
  header, and lines carry no `@Version`, so a racing `reconcile()`/`cancel()` could freeze the
  worksheet between the check and the line saves. `enterLines` now version-touches + saves the header,
  so a racing freeze surfaces as an optimistic-lock failure instead of a silent post-freeze mutation.
- **`cancel()` re-cancel (nit).** `cancel()` only blocked `RECONCILED`; a second cancel re-stamped
  `cancelled_at/by` and emitted a duplicate audit event. Now guards `status != OPEN` (mirrors
  `reconcile()`).
- **`reconciledAt` raw ISO string (nit).** Now rendered through the `date` pipe.

**Accepted as follow-ups (minor/nit, backend already supports the eventual fix):**

- **`productUids` not wired into the create form (minor).** The create request accepts an optional
  `productUids` subset (to seed a line for a product with *zero* van movement — a non-transfer load),
  but the web create form doesn't yet expose a product multi-picker. The primary flow (transfer-derived
  lines with **editable** loaded/returned) is unaffected; only the zero-movement anomaly needs the
  picker. Deferred to a UI follow-up.
- **List scoped to the active branch (nit).** A recon is created on its **van's** branch; the list is
  active-branch-scoped with no branch filter, so a recon created while the global branch selector sits
  on another branch is reachable by direct URL / post-create navigation but not visible in the list
  until the branch is switched. A list branch-filter (as `stock-locations` has) is the follow-up.
- **`list()` per-row enrichment (nit).** `toDto` does a per-row `StockLocation` + `Agent` lookup (N+1)
  for the paged list (lines are empty for list rows, so no product lookup). Acceptable for a
  low-traffic admin list; batch-load if it ever shows on a hot path.
- **`TenantScopingRulesTest` red locally** is the documented Windows freeze-attribution artifact — the
  new code adds **zero** tenant-scoping violations (all finders are company-scoped); confirmed green in
  CI, freeze store not regenerated locally (per the `archunit-freeze-local-vs-CI` lesson).

## Consequences

- **Positive:** Hamisi gets a day-end van worksheet with loaded/returned **prefilled from the transfer
  ledger**, sold + physical entered, and an automatic expected/variance — an honest accountability control
  with **zero** blast radius on valuation/GL. No new module; no boundary-rule change; no CHECK widen; the
  only populated-table touch is a nullable `agent_id` column.
- **Honest about a real limitation (flagged):** the variance is a *control figure*, not a stock correction —
  van `stock_on_hand` stays untruthful until route-sales-from-van is built (D-8.8). This must be understood
  by operations: reconciling the truck ≠ correcting inventory value.
- **Derivation depends on transfer-based loading.** If an agent loads the van by some other means, `loaded`
  prefills to 0 and must be entered — hence loaded/returned are editable, not locked.
- **Contract additions:** new `stock` DTOs/endpoints (see the plan); `StockMovementRepository`
  `sumByLocationTypeAndWindow`; `StockLocation`/`StockLocationDto`/create+update requests gain `agentUid`;
  web `van-reconciliation` feature + service/models; two permission codes; one `ScopeGuard` target type; a
  `WarehouseNumberGenerator.nextVanRecon` (`VR-####`, entity_kind `VAN_RECON`).
- **Cross-module read:** the van-recon service resolves/enriches the agent via `parties.AgentService.getByUid`
  (DTO-level) — permitted (`ModuleBoundaryTest` forbids only controller↛repository, controller placement,
  service↛controller, audit-append-only), consistent with ADR-0048's sales→products precedent.

## Decisions requiring the owner

1. **SOURCING (D-8.1) — the feature-defining call.** Recommended: **loaded/returned DERIVED** from van
   transfer movements (prefilled, editable), **sold + physical ENTERED**, `expected = loaded − sold −
   returned`, `variance = physical − expected`. This is forced by the fact that route sales issue from the
   warehouse, not the van (Context) — `sold` is not derivable without the out-of-scope route-sales-from-van
   work.
2. **GL/stock stance (D-8.2).** Recommended: **RECORD-ONLY** (no stock adjustment, no GL) — the van on-hand
   is untruthful, so posting would double-count COGS. Alternative (rejected for this slice): auto-post,
   which presupposes route-sales-from-van.
3. **Model (D-8.3).** Recommended: **standalone `van_reconciliations` aggregate**, not a `StockCount`
   subtype — required for correctness, not preference.
4. **Van↔agent link (D-8.4).** Recommended: nullable `stock_locations.agent_id` FK, set only on VAN
   locations, **one active van per agent** via a partial unique index; agent validated to the same company;
   assignment via the existing location-management endpoint (`agentUid`).
5. **Approve the `V85` DDL + version (D-8.5)** before the migration file is created (migration-approval
   standing rule). Confirm next-free is `V85` at build time (after D-1/D-6/D-7 land).
6. **Permission codes (D-8.7).** Recommended: `STOCK.VAN_RECON.MANAGE` / `STOCK.VAN_RECON.VIEW` (module
   `stock`), vs the D-8 note's bare `VAN_RECON.*`.
7. **Scope cut (D-8.8).** Confirm route-sales-issue-from-van, variance stock/GL posting, auto-derived
   `sold`, multi-van-per-agent, and the approval workflow are all OUT of this slice.

## Alternatives considered

- **Reuse `StockCount` of the VAN location** — rejected (D-8.3): `StockCount.post` books
  `counted − live_on_hand`; the van on-hand overstates by `sold`, so it would post a ~`−sold` adjustment and
  double-count COGS. Correct only after route-sales-from-van makes the van on-hand truthful.
- **Derive `sold` from `SALE_ISSUE` movements** — impossible: sale/delivery issues carry the warehouse
  default location, never the van (Context §1). There is no van-attributable sold quantity in the ledger.
- **Auto-post the variance as a stock adjustment now** — rejected (D-8.2): no correct ledger seam under
  current wiring; drags in valuation + GL for a figure that can't be made right.
- **Enter loaded/returned too (fully manual worksheet)** — rejected: the transfer ledger *can* supply
  loaded/returned truthfully; deriving them removes transcription error. Kept editable for the manual-load
  edge.
- **Build route-sales-issue-from-van as part of D-8** — rejected: it is a large cross-module change
  (issue-path source-location threading across sales/delivery/POS + payloads + forms) and belongs in its own
  ADR; D-8 delivers the reconciliation control that the persona asked for without it.

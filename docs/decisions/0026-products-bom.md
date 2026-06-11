# 0026 — Multi-level Bill of Materials data model: versioned, effectivity-dated BOM **headers** + **components** in `com.erp.modules.products`, make/buy sourcing per line, a recursive **explosion-to-all-levels** + **where-used** resolver that subsumes the shipped single-level `RecipeExplosionResolver` (one explosion implementation, not two), a transitive **cycle guard** (BR-BOM-01) replacing the degenerate `chk_product_component_not_self`, a derived **standard-cost roll-up** that reads ADR-0020 `avg_cost` (read-only — no GL, no stock, no events), and a `boms`/`bom_components` schema additive as `V30__products_bom.sql` (+ `V31` lazy code-sequence kind, optional) on the frozen V1–V19

- **Status:** Accepted
- **Date:** 2026-06-11
- **Deciders:** solutions-architect (consuming the owner-style requirements in [docs/requirements/products-bom.md](../requirements/products-bom.md) — FR-BOM-01..21, BR-BOM-01..12, NFR-BOM-01..07, §7 flows, §11 OQ log. The recommended defaults in §11 are adopted as the decisions of this ADR; the load-bearing OQs — single-level-recipe coexistence (OQ-BOM-01), scrap/yield arithmetic (OQ-BOM-02), and the no-change-to-sale-time-COGS stance (OQ-BOM-05) — are resolved here, not deferred.)
- **Context source:** [products-bom.md](../requirements/products-bom.md) (the ground truth for every rule below). Verified against the **shipped** code:
  - **Products** ([ADR-0007](0007-products-data-model.md) / [V3__products.sql](../../backend/src/main/resources/db/migration/V3__products.sql)): `Product` (`products` — `id`, `uid` VARCHAR(26), `company_id`, `code`, `name`, `type` CHECK `IN ('GOODS','SERVICE')`, `sellable`/`stockable`, `base_unit`, `cost_amount`/`cost_currency`, `status` `MasterStatus`, `@Version`); the **single-level recipe** `ProductComponent` (`product_components` — `composed_product_id`, `component_product_id`, `quantity` NUMERIC(19,6) `CHECK > 0`, `uq_product_component (composed_product_id, component_product_id)`, `chk_product_component_not_self`, `chk_product_component_qty` — **no uid, no version, no make/buy, no effectivity**); `ProductComponentRepository.findByComposedProductId` / `findByComposedProductIdAndComponentProductId`; `ProductCompositionGuard.assertCanAddComponent` (BR-PROD-05/06 — same-company + not-archived, the guard this ADR's cycle check extends); `ProductService.listComponents(uid)→List<ProductComponentDto>` / `getByUid(uid)→ProductDto` (the DTO reads the resolver uses); `ProductDto(id, uid, ..., stockable, ...)`; `ProductCodeGenerator` over `code_sequence` (ADR-0007 D-6, `entity_kind` discriminator).
  - **Stock single-level explosion** ([ADR-0010](0010-stock-data-model.md) D-8 / [RecipeExplosionResolver](../../backend/src/main/java/com/erp/modules/stock/service/RecipeExplosionResolver.java)): `explode(composedProductUid, lineQtyInBase)→List<ExplosionLine(productId, signed qty)>` + `isComposed(productUid)` — **explicitly non-recursive** (its Javadoc: "Single-level only. No recursion. Components that are themselves composed are treated as simple products and deducted directly — multi-level BOM is deferred"). It reads `ProductService.listComponents` + `getByUid` (the N+1 it flags). **This is the path ADR-0020 D-2 `SaleIssueStockHandler` + `DeliveryIssueStockHandler` (ADR-0021 D-6) drive COGS from — it MUST keep its single-level default behaviour byte-for-byte (OQ-BOM-05).**
  - **Inventory Valuation** ([ADR-0020](0020-inventory-valuation-data-model.md) / V17): `StockOnHand.avg_cost` NUMERIC(19,4) nullable (NULL = no cost established), per (company, branch, product) (`uq_stock_on_hand_scope`); `StockOnHandRepository` (the read the cost roll-up uses, by scalar projection — **no Stock entity import into products**). The roll-up is **read-only**; it posts no GL and writes no stock (the explicit non-goal here).
  - **GL** ([ADR-0013](0013-general-ledger-data-model.md)): cited only to state the **negative**: BOM posts **nothing** to GL. No `GlConfigKey`, no `JournalSourceType`, no `gl_config` seed is added by this ADR (the standard-cost roll-up is a derived read, OQ-BOM-02; Manufacturing — the gated module — will own WIP/COGS-from-production postings, ADR-future).
  - **Outbox / idempotency** ([ADR-0009](0009-transactional-outbox.md)): cited only to state the **negative**: BOM is master-data structure and emits **no** `DomainEventType` and registers **no** handler. (Manufacturing will emit `PRODUCTION.*` events; BOM does not.)
  - **Security spine**: `@perm.has` / `@perm.scoped` (`PermissionChecks`), `ScopeGuard.companyIdOf` target-type switch + `assertCanActIn` (the read-path guard), `MasterStatus` soft-delete, `MasterStatus`-aligned audit (`AuditService`).
  - [[db-naming-convention]] verified against V1–V19 (plural masters/owned-children `boms`/`bom_components`; singular constraint roots `uq_`/`fk_`/`chk_` on the singular entity; plural `ix_` indexes; `uid VARCHAR(26)` ULID; `company_id` BIGINT scalar denormalised onto children; `@Version` on the header; additive `CREATE`/`ALTER`). **ISSUES-REGISTER #12:** every per-company CROSS-JOIN seed-uid MUST be `'XX' || lpad(company_id::text,6,'0') || substr(md5(key),1,12)` (≤26 chars) — **never** `|| key`. **Latest shipped migration is `V19__sales_returns.sql`; this module is assigned `V30__products_bom.sql` (+ optional `V31`) by the coordinator** (additive; V1–V19 FROZEN). **This ADR is 0026.**

This ADR is the **technical data model + integration design** for Multi-level BOM (Products depth, ROADMAP §3.6 build-first; PATH-TO-FULL-ERP §4 critical-dependency #5 — the gate for Manufacturing). It translates the ratified spec into: the two new tables (`boms` header + `bom_components` child) in `com.erp.modules.products`, the version/effectivity lifecycle enums + transitions, the make/buy sourcing model + defaulting rule, **the recursive explosion + where-used resolver that subsumes the shipped single-level `RecipeExplosionResolver` (one implementation, NFR-BOM-04)**, the transitive cycle guard (BR-BOM-01), the scrap/yield arithmetic (OQ-BOM-02), the derived read-only standard-cost roll-up (no GL/stock/events), the API surface, the perms + `ScopeGuard` case, the Angular nav routes, the ArchUnit edges, and the `V30` migration ordering with #12-safe seeds. It is **concrete enough that the backend engineer writes `V30` + the BOM entities + the lifecycle service + the multi-level resolver + the where-used + the cost roll-up without guessing a business rule.** It writes **no production code, no entities, no migration SQL** — that is the engineer's next step. Nothing ratified is re-litigated.

## Context

Products v1 shipped a flat single-level recipe (`product_components`) and a deliberately non-recursive explosion (`RecipeExplosionResolver`) — fine for "Ugali Meat = 1 Ugali + 1 Meat", inadequate for manufacturing where a finished good explodes through sub-assemblies to raw materials, the structure is versioned (engineering changes the recipe and old runs must reproduce), and each input is classified make-vs-buy so a planner knows what to manufacture and what to purchase. Manufacturing (ROADMAP §3.6) is gated on this. The work is to add a **versioned, effectivity-dated, multi-level, make/buy BOM** as a structure master alongside the products catalogue, **without** breaking the shipped single-level COGS path and **without** introducing a second explosion implementation. The forces:

- **One explosion implementation, not two (NFR-BOM-04 — the top design risk).** The shipped `RecipeExplosionResolver` is the live COGS path (`SaleIssueStockHandler`, `DeliveryIssueStockHandler`). If the BOM module adds its own recursive explosion, the system has two divergent definitions of "what a product is made of" — a guaranteed drift bug. The resolver must be **upgraded in place** to recurse, with its single-level behaviour preserved as the default the sale path keeps using (OQ-BOM-05), and the new BOM-aware multi-level expansion as an opt-in mode. Resolved in **D-7**.

- **The structure must be acyclic, and the cycle check is now transitive (BR-BOM-01).** The v1 `chk_product_component_not_self` catches only the one-level self-reference. With nesting, A→B→A is a cycle a single-row CHECK cannot see (it is a graph reachability question across rows). The DB keeps the cheap self-CHECK as a backstop; the **service** runs a transitive cycle check (does the candidate child's fully-exploded structure contain the parent?) before persisting a component. Resolved in **D-3 / D-6**.

- **Versioning + effectivity is the spine of "reproduce an old run" (FR-BOM-01..05, BR-BOM-03/04/05).** A parent has many BOM headers over time; at most one ACTIVE; activation supersedes the prior ACTIVE atomically; effectivity windows of one parent must not overlap; an ACTIVE version's component set is frozen (change = new version). The DB enforces what it cheaply can (one-active partial unique index, single-child-per-version unique); the service enforces the cross-row transition + non-overlap + freeze rules. Resolved in **D-2 / D-3**.

- **Make/buy is a per-line classification with a sensible default (FR-BOM-08, BR-BOM-07).** A component is MAKE (recurse — it has its own BOM) or BUY (leaf — purchased/raw, never exploded further). The flag **defaults** from the child (child has an ACTIVE BOM ⇒ MAKE; else BUY) and is **overridable**; the line's classification wins for that usage even if the child happens to have a BOM. Resolved in **D-3 / D-6**.

- **The cost roll-up is a derived read, not a posting (FR-BOM-16, BR-BOM-09 — the scope boundary).** The standard-cost roll-up sums leaf `avg_cost` (ADR-0020) × rolled-up leaf quantity, branch-scoped; a leaf with no `avg_cost` makes the result *incomplete* (flagged, never silently zero). It writes nothing, posts no GL, emits no event. This is the bright line between BOM (structure) and Manufacturing (the act of production that *does* post WIP/COGS). Resolved in **D-8**.

- **No money, no events, no GL config (the deliberate non-goals).** Unlike every recent ADR (0020/0021), this module introduces **no** `GlConfigKey`, **no** `JournalSourceType`, **no** new CoA account, **no** `DomainEventType`, **no** outbox handler. It is master data. Saying this explicitly is part of the design (the coordinator's collision check must see an empty set, not an omission). Resolved in **D-9 / Consequences**.

- **Schema freeze / direction.** IAM=V1 … Sales-Returns=V19, all frozen. Multi-level BOM is additive `V30__products_bom.sql`: two new tables, the partial-unique one-active index, the seed of two permissions + the `ORG_ADMIN` grant. It ALTERs nothing shipped (the v1 `product_components` is **left intact** — coexistence per OQ-BOM-01/D-5). The resolver upgrade (D-7) is code-only (no migration). Optional `V31` exists only if a `BOM` `code_sequence` kind is wanted (D-2 default: BOMs are identified by `(parent, version_no)`, **no document number**, so V31 is typically unused).

## Decision

### D-1 — Module placement: BOM lives in `com.erp.modules.products` (it owns the catalogue + the single-level recipe it extends); the resolver upgrade lives where it already lives (`stock.service`)

The BOM header/component tables, lifecycle service, explosion, and where-used live in **`com.erp.modules.products`** — it owns `products`, `product_components`, `ProductCompositionGuard`, and the `ProductService` DTO reads the resolver already consumes. A separate `bom` or `manufacturing` module would re-read the product master, re-own the composition guard, and split the recipe across two modules. Reject. BOM is **Products depth**, exactly as the requirements (Products area, FR-PROD-16 was its placeholder) and PATH-TO-FULL-ERP §3.6 ("V3 ships single-level `product_components`; multi-level expansion deferred") frame it.

The **resolver upgrade (D-7)** stays in **`com.erp.modules.stock.service.RecipeExplosionResolver`** where it ships — it is the COGS-path component and moving it would ripple into `SaleIssueStockHandler`/`DeliveryIssueStockHandler`. It gains a dependency on a new `products`-owned `BomExplosionService` (read via DTOs), the **same direction** it already imports `products.domain.dto` + `ProductService` (verified). No new boundary precedent — `stock → products` (DTO/service read) is shipped.

Internal layout (additive to the shipped `products` package):

```
com.erp.modules.products
├── domain.entity   Bom (boms), BomComponent (bom_components)
├── domain.dto      BomDto / CreateBomRequest / UpdateBomRequest,
│                   BomComponentDto / AddBomComponentRequest / UpdateBomComponentRequest,
│                   ActivateBomRequest (effectivity), CloneBomRequest,
│                   BomExplosionRequest (parentUid|bomUid, outputQty, branchUid?, multiLevel),
│                   BomExplosionNodeDto (the indented tree), BomExplosionLeafDto (the flattened leaf summary),
│                   BomExplosionResultDto (tree + leaves + costRollUp? + incompleteCostLeaves),
│                   WhereUsedRowDto (single-level), WhereUsedTreeDto (full implosion),
│                   BomCostRollUpDto (derived standard cost + incompleteLeaves flag)
├── domain.enums    BomStatus (DRAFT|ACTIVE|ARCHIVED), ComponentSourcing (MAKE|BUY)
├── repository      BomRepository, BomComponentRepository
└── service         BomService(+Impl)              — header lifecycle: create/clone/activate/supersede/archive (D-2/D-3)
                    BomComponentService(+Impl)      — add/update/remove components on a DRAFT (D-3)
                    BomCycleGuard                    — the transitive acyclic check (D-6, extends ProductCompositionGuard)
                    BomExplosionService(+Impl)       — recursive explode-to-all-levels + leaf summary (D-6)
                    BomWhereUsedService(+Impl)       — single-level + full implosion (D-6)
                    BomCostRollUpService(+Impl)      — derived standard-cost from avg_cost, branch-scoped (D-8)
```

Controllers stay flat in `com.erp.api`: `BomController` (header CRUD + activate/supersede/archive + explode + where-used + cost-roll-up). It touches only services (`ModuleBoundaryTest`).

**Boundary note (D-10):** `products` reads its own entities; the cost roll-up reads `StockOnHand.avg_cost` via a **scalar projection** on `StockOnHandRepository` (`findAvgCost(companyId, branchId, productId)→BigDecimal|null`) — products imports **no Stock entity**. `BomCostRollUpService` is the only `products → stock.repository` read; it is a read-only projection, the lean inverse of the shipped `stock → products` DTO read (D-7). No `products → stock.service`, no `products → gl`, no `products → ar`. (See D-10 for why this projection read is allowed and does not form a cycle.)

### D-2 — `boms` (header) table + the `BomStatus` lifecycle

`boms`: plural name; `id` BIGINT IDENTITY PK; `uid` VARCHAR(26) ULID `uq_bom_uid`; `company_id` BIGINT NOT NULL (tenant); `@Version`; standard audit cols. **No document number / `code_sequence`** — a BOM is identified by `(parent_product_id, version_no)`, not a sequence (D-12; this is why `V31` is normally unused). Quantities `NUMERIC(19,6)` (the shipped quantity scale); percents `NUMERIC(9,4)`.

| column | type | null | notes |
|---|---|---|---|
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | `uq_bom_uid` |
| `company_id` | BIGINT | NO | tenant; `fk_bom_company` |
| `parent_product_id` | BIGINT | NO | scalar FK → `products(id)`; the product this BOM produces; `fk_bom_parent` |
| `version_no` | INTEGER | NO | 1,2,3…; `uq_bom_parent_version UNIQUE (parent_product_id, version_no)` (BR-BOM — a parent's versions are distinct) |
| `status` | VARCHAR(20) | NO | `BomStatus`; DEFAULT `'DRAFT'`; `chk_bom_status CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED'))` |
| `output_qty` | NUMERIC(19,6) | NO | DEFAULT 1; `chk_bom_output_qty CHECK (output_qty > 0)`; how many parent base-units one execution produces (FR-BOM-05, BR-BOM-06) |
| `yield_percent` | NUMERIC(9,4) | NO | DEFAULT 100; `chk_bom_yield CHECK (yield_percent > 0 AND yield_percent <= 100)`; header yield (BR-BOM-08) |
| `effective_from` | DATE | YES | the effectivity window start; NULL while DRAFT, set at activate (D-3) |
| `effective_to` | DATE | YES | window end (exclusive); NULL = open-ended/current; `chk_bom_effectivity CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)` |
| `source_bom_uid` | VARCHAR(26) | YES | if cloned from a prior version (OQ-BOM-06), the version it was cloned from |
| `notes` | VARCHAR(500) | YES | |
| `activated_at` / `archived_at` | TIMESTAMPTZ | YES | transition stamps |
| `version` (`@Version`) + audit cols | | | |

Constraints + indexes:
- `uq_bom_uid`, `uq_bom_parent_version`, `fk_bom_company`, `fk_bom_parent`, the four CHECKs above.
- **`uq_bom_one_active` — partial unique index** `CREATE UNIQUE INDEX uq_bom_one_active ON boms (parent_product_id) WHERE status = 'ACTIVE'` — the DB backstop for **BR-BOM-04 (at most one ACTIVE version per parent)**. The service still enforces the supersede transition with a friendly error; the partial index is the race-safe guard (a concurrent double-activate is rejected by the DB).
- `ix_boms_company (company_id)`, `ix_boms_parent (parent_product_id)`, `ix_boms_parent_status (parent_product_id, status)`.

**`BomStatus` lifecycle** (FR-BOM-01/03/06, BR-BOM-03/04):

```
DRAFT ──activate──▶ ACTIVE ──supersede (a newer version activates)──▶ ARCHIVED   (terminal)
  │                   │
  │                   └──archive (decommission with no successor)──▶ ARCHIVED
  └──(hard delete OR archive allowed while DRAFT — consumed no version slot beyond version_no)
```

- A DRAFT version's component set is **freely editable** (`BomComponentService`); an **ACTIVE** version's component set is **frozen** (BR-BOM-03 — a change is a new DRAFT version, then activate/supersede). Notes/metadata on ACTIVE are editable (OQ-BOM-03 default), audited.
- **Activate** (`BomService.activate(bomUid, ActivateBomRequest{effectiveFrom})`): validates the structure (every component resolvable, acyclic via `BomCycleGuard` over the whole tree, ≥1 component), then in **one transaction**: if a prior ACTIVE version of the parent exists, **supersede** it (set its `effective_to = effectiveFrom`, `status = ARCHIVED`, `archived_at = now()`), then set this version `status = ACTIVE`, `effective_from = effectiveFrom`, `effective_to = NULL`, `activated_at = now()`. The non-overlap (BR-BOM-05) is guaranteed because supersede closes the prior window exactly at the new window's start. Audited.
- **Archive** without a successor decommissions the ACTIVE version (no parent then has an ACTIVE BOM until a new one is activated).

### D-3 — `bom_components` (child) table + the make/buy + scrap model

`bom_components`: `id`, `uid` (`uq_bom_component_uid`), `bom_id` (FK → `boms(id)`), `company_id` (denormalised, set-once — the tenant-predicate-without-join pattern), `line_no`, `component_product_id` (scalar FK → `products(id)`), snapshots, quantities, sourcing, scrap, audit.

| column | type | null | notes |
|---|---|---|---|
| `id` / `uid` | | NO | `uq_bom_component_uid` |
| `bom_id` | BIGINT | NO | FK → `boms(id)`; `fk_bom_component_bom` |
| `company_id` | BIGINT | NO | denormalised tenant; `fk_bom_component_company` |
| `line_no` | SMALLINT | NO | ordering; `uq_bom_component_line_no UNIQUE (bom_id, line_no)` |
| `component_product_id` | BIGINT | NO | scalar FK → `products(id)`; `fk_bom_component_product`; the child input |
| `component_product_code` / `component_product_name` | VARCHAR | NO | snapshot (display without a join; mirrors the sales-line snapshot pattern) |
| `qty_per` | NUMERIC(19,6) | NO | `chk_bom_component_qty CHECK (qty_per > 0)`; child base-units per ONE `output_qty` of the parent (BR-BOM-06) |
| `sourcing` | VARCHAR(10) | NO | `ComponentSourcing`; `chk_bom_component_sourcing CHECK (sourcing IN ('MAKE','BUY'))`; MAKE ⇒ recurse, BUY ⇒ leaf (FR-BOM-08, BR-BOM-07) |
| `scrap_percent` | NUMERIC(9,4) | NO | DEFAULT 0; `chk_bom_component_scrap CHECK (scrap_percent >= 0 AND scrap_percent < 100)`; line scrap (BR-BOM-08) |
| `reference` | VARCHAR(120) | YES | reference designator / note (FR-BOM-07) |
| audit cols | | | |

Constraints:
- `uq_bom_component_uid`, `uq_bom_component_line_no`, the three FKs, the three CHECKs above.
- **`uq_bom_component_child UNIQUE (bom_id, component_product_id)`** — **BR-BOM-02** (a child appears at most once per version; combine quantities). The DB enforces it; the service gives the friendly error.
- **No `chk_bom_component_not_self` is needed as the *only* cycle defence** — the self-reference is the degenerate case the **transitive `BomCycleGuard`** (D-6) catches, but a cheap one-row backstop is added anyway: `chk_bom_component_not_parent` is **not expressible** as a pure single-row CHECK (it needs `boms.parent_product_id`), so the self-check is enforced in `BomCycleGuard` (service) which compares `component_product_id` to the header's `parent_product_id` as its base case. (Unlike `product_components`, `bom_components` does not carry the parent id on the row, so a single-row CHECK cannot see it — the guard is the home for BR-BOM-01.)

Indexes: `ix_bom_components_bom (bom_id)` (the explosion's per-level child read), `ix_bom_components_child (component_product_id)` (**where-used / implosion** — FR-BOM-14/15), `ix_bom_components_company (company_id)`.

**Make/buy defaulting (BR-BOM-07), in `BomComponentService.add`:** if the request omits `sourcing`, default it: `componentProduct` has an ACTIVE `boms` row ⇒ `MAKE`; else `BUY`. Overridable explicitly. The **explosion (D-6) honours the line's stored `sourcing`** — a `BUY` line is a leaf even if the child later gains a BOM (the usage's classification wins; re-classifying requires a new version, consistent with BR-BOM-03).

**Add-component guards (`BomComponentService.add`, on a DRAFT header only — BR-BOM-03):** (1) header is DRAFT (else reject "ACTIVE BOM frozen"); (2) `ProductCompositionGuard`-style same-company + not-archived (BR-BOM-10/12 — reuse/extend the shipped guard); (3) duplicate-child rejected (BR-BOM-02, DB-backed); (4) **`BomCycleGuard.assertNoCycle(parentProductId, candidateChildProductId)`** (BR-BOM-01, D-6).

### D-4 — Scrap/yield arithmetic + precision (OQ-BOM-02, BR-BOM-08) — fixed exactly

Quantities are carried at the shipped **6-dp** scale (`NUMERIC(19,6)`); intermediate explosion math is `BigDecimal`, HALF_UP, **rounded only on presentation/leaf-aggregation boundaries** (NFR-BOM-02). The applied (effective) quantity of a component for a requested parent output `Q` is:

```
parentMultiplier   = Q / bom.output_qty            // how many BOM executions the request needs
headerInflate      = 100 / bom.yield_percent       // header yield (BR-BOM-08); = 1 when yield = 100
lineScrapInflate   = 100 / (100 − component.scrap_percent)   // line scrap; = 1 when scrap = 0
effectiveChildQty  = qty_per × parentMultiplier × headerInflate × lineScrapInflate
```

- **Convention fixed:** scrap is the **fraction lost**, so the requirement is `÷ (1 − scrap)` (you must input more to net the nominal output), and yield is the **fraction of good output**, so `÷ yield`. Both default to no inflation (scrap 0, yield 100). This is the divide-by convention (OQ-BOM-02 recommended default), documented to prevent a `× (1 + scrap)` mis-implementation.
- **Multi-level:** inflation **compounds per level** — a level-2 leaf's quantity is its `effectiveChildQty` computed against the *already-inflated* level-1 quantity flowing down. The recursion (D-6) passes the running multiplier down; the leaf summary sums the per-branch leaf quantities.
- **Rounding:** carry full `BigDecimal` precision through the recursion; round HALF_UP to 6 dp only when writing a `BomExplosionNodeDto.quantity` / aggregating a `BomExplosionLeafDto.totalQuantity`. (Display dp is presentation, OQ-CUR-03 carried.)

### D-5 — Coexistence with the single-level `product_components` recipe (OQ-BOM-01) — BOM-header-authoritative, no destructive migration

**Decision (OQ-BOM-01 recommended default adopted): the v1 `product_components` table is left intact and untouched by `V30`; resolution dispatches on BOM-header presence.** For a parent product:
- has an **ACTIVE `boms`** row ⇒ **the BOM is authoritative** for explosion (multi-level, D-6/D-7).
- has **no `boms`** row but has `product_components` rows ⇒ resolves as the **legacy single-level recipe** (the shipped `RecipeExplosionResolver` behaviour, unchanged).
- has neither ⇒ simple product (no explosion).

`V30` performs **no** drop, no rewrite, no data migration of `product_components` (NFR — non-destructive). A convenience operation `BomService.promoteRecipeToDraftBom(parentUid)` (optional, OQ-BOM-01) copies the parent's `product_components` rows into a new DRAFT `boms` v1 + `bom_components` (defaulting make/buy per D-3), **leaving the recipe rows in place** — the administrator activates it deliberately. The coexistence is surfaced on `ProductDto`/`BomDto` reads (a "legacy recipe present" flag) so migration is intentional, never silent (FR-BOM-18, §9).

### D-6 — The multi-level explosion, where-used, and cycle guard (the algorithmic core)

**`BomExplosionService.explode(BomExplosionRequest{parentUid | bomUid, outputQty, branchUid?, multiLevel=true})→BomExplosionResultDto`:**

1. **Resolve the BOM version.** If `bomUid` is given, use it (any status — supports reproducing an old run). Else resolve the parent's **applicable** version: the ACTIVE version (or, if an `asOfDate` is supplied, the version whose effectivity window contains it). If none, fall back to the legacy `product_components` (D-5) treated as a single implicit level.
2. **Recurse depth-first**, carrying a running multiplier (D-4). For each `bom_components` line of the current node:
   - compute `effectiveChildQty` (D-4);
   - emit a `BomExplosionNodeDto(level, componentProductUid, code, name, sourcing, qtyAtLevel, effectiveQty, isLeaf)`;
   - if `sourcing == MAKE` **and** `multiLevel` **and** the child has a resolvable ACTIVE BOM ⇒ **recurse** into the child (level + 1, multiplier × `effectiveChildQty`);
   - else (`BUY`, or no child BOM, or `multiLevel == false`) ⇒ the child is a **leaf**; accumulate into the leaf summary keyed by `componentProductId` (Σ `effectiveQty` across all branches — FR-BOM-13).
3. **Bound the recursion (BR-BOM-11, NFR-BOM-03):** a `maxDepth` guard (config `bom.max-explosion-depth`, default 20) fails fast with a clear error if exceeded — the acyclic invariant guarantees termination, the guard defends against a mid-edit/imported-bad structure.
4. **Return** `BomExplosionResultDto{ tree: List<BomExplosionNodeDto> (indented, ordered by level then line_no), leaves: List<BomExplosionLeafDto> (the flattened net requirement of each BUY/raw leaf), costRollUp?: BomCostRollUpDto (D-8, only if branchUid given + requested), incompleteCostLeaves: List<uid> }`.

**N+1 / performance (NFR-BOM-02):** batch the per-level child reads — `BomComponentRepository.findByBomIdIn(...)` and a batch ACTIVE-BOM lookup `BomRepository.findActiveByParentProductIdIn(companyId, childIds)` per level — so the recursion is O(levels) queries, not O(nodes). This subsumes and fixes the `RecipeExplosionResolver` N+1 the shipped Javadoc flags.

**`BomCycleGuard.assertNoCycle(parentProductId, candidateChildProductId)` (BR-BOM-01):** base case — `candidateChildProductId == parentProductId` ⇒ cycle (the self-reference). Else explode the candidate child's structure (its ACTIVE BOM, recursively, bounded by `maxDepth`) and reject if `parentProductId` appears anywhere in it (the candidate already depends on the parent, so adding it under the parent would close a loop). Runs **before persisting** any `bom_components` add and again as a whole-tree validation at **activate** (D-2). Extends `ProductCompositionGuard` (same-company/not-archived) rather than replacing it.

**`BomWhereUsedService` (FR-BOM-14/15):**
- **single-level** `whereUsed(componentUid)→List<WhereUsedRowDto>`: `bom_components.findByComponentProductId` joined to `boms` ⇒ the parents (with version, status, qty_per) that consume the component directly. The `ix_bom_components_child` index serves this.
- **full implosion** `whereUsedTree(componentUid)→WhereUsedTreeDto` (OQ-BOM-04 — best-effort): invert the traversal, walking up parents-of-parents to top-level finished goods, bounded by `maxDepth`, with the path + effective qty-per along each branch. Single-level is the guaranteed minimum; full is the target.

### D-7 — Upgrade the shipped `RecipeExplosionResolver` to be BOM-aware — ONE explosion implementation (NFR-BOM-04)

**Decision: do not write a second explosion. Upgrade `RecipeExplosionResolver` (in `stock.service`) to delegate to the new `products`-owned `BomExplosionService` when a BOM exists, and keep its single-level `product_components` behaviour as the exact default the sale path uses.** The resolver gains an injected `BomExplosionService` (the `stock → products` service read it is already adjacent to — it injects `ProductService` today). Its `explode(composedProductUid, lineQtyInBase)` becomes:
- if the product has an **ACTIVE BOM** ⇒ call `BomExplosionService.explode(parentUid=composedProductUid, outputQty=lineQtyInBase, multiLevel=<sale-explosion policy>)` and map the **leaf summary** to the existing `ExplosionLine(productId, signed qty)` shape (negate for SALE_ISSUE) — the consumer (`SaleIssueStockHandler` / `DeliveryIssueStockHandler`) is **unchanged**;
- else ⇒ the **existing single-level `product_components`** path, byte-for-byte unchanged.

**The sale-explosion depth policy (OQ-BOM-05) is fixed: single-level by default — `multiLevel = false` for the sale path in v1.** A sale of a composed product deducts its **immediate** components exactly as today (today's COGS preserved precisely); multi-level sale-explosion is a **separate, deliberate Stock/Sales decision** the upgraded resolver now *supports* (the flag exists) but this module does **not** flip. This is the load-bearing safety property: shipping multi-level BOM does **not** silently change what any sale deducts or what COGS posts (NFR-BOM-04 + §9). The flag's flip is a future ADR with its own COGS-impact analysis.

`isComposed(productUid)` extends to "has an ACTIVE BOM **or** has `product_components`". No new movement type, no new event, no GL change — the resolver is a pure structure-resolution helper; the COGS posting it feeds (ADR-0020 D-2) is untouched.

### D-8 — Derived standard-cost roll-up — read-only, no GL, no stock, branch-scoped (FR-BOM-16, BR-BOM-09)

**`BomCostRollUpService.rollUp(parentUid | bomUid, branchUid, outputQty)→BomCostRollUpDto`:**
1. explode (D-6) to the leaf summary (the net BUY/raw leaf requirement);
2. for each leaf, read `avg_cost` via `StockOnHandRepository.findAvgCost(companyId, branchId, productId)` (the scalar projection, D-10);
3. `standardCost = Σ (leaf.totalQuantity × leaf.avgCost)` for leaves with a non-NULL `avg_cost`;
4. a leaf with `avg_cost IS NULL` ⇒ added to `incompleteLeaves` and **excluded** from the sum; `complete = incompleteLeaves.isEmpty()` (**BR-BOM-09 — flagged, never silently zero**);
5. return `BomCostRollUpDto{ parentUid, branchUid, outputQty, standardCostAmount, currency (base, ADR-0005), complete, incompleteLeaves: List<{uid, name}> }`.

**This writes nothing, posts no GL, emits no event** — it is a `@Transactional(readOnly = true)` report with `ScopeGuard.assertCanActIn` on the read path. The bright line: BOM computes a *what-it-would-cost* number from current average; Manufacturing (the gated module) is what *actually posts* WIP/COGS when a production order runs. (When a persisted standard-costing module lands, it consumes this roll-up — additive, not now.)

### D-9 — No GL config, no events, no CoA accounts — the deliberate empty sets (collision-check explicit)

This ADR introduces, by design:
- **0 new `GlConfigKey`** — the cost roll-up is derived (D-8); Manufacturing owns production postings.
- **0 new `JournalSourceType`** — BOM posts no journals.
- **0 new CoA account codes** — no accounts.
- **0 new `DomainEventType`** — BOM is master-data structure; no outbox events, no handlers. (Manufacturing will emit `PRODUCTION.*`; not here.)
- **0 new outbox handlers / payloads.**

Stated explicitly so the coordinator's cross-module collision detection sees an intentional empty set on each of these axes, not an omission.

### D-10 — ArchUnit edges (no cycle)

- **`products.service` → `stock.repository`** (`StockOnHandRepository.findAvgCost` scalar projection, D-8) — the **only** new products→stock read; a read-only scalar projection, the lean inverse of the shipped `stock → products` DTO read. **Allowed.** It does **not** form a cycle at the *module* level only if the existing `stock → products` edge and this `products → stock` edge are both permitted; **two-way module dependency is a cycle ArchUnit forbids.** **Decision to avoid the cycle:** the avg-cost read is exposed by a **`stock`-owned read service `InventoryCostQuery.avgCost(companyId, branchId, productId)→BigDecimal|null`** that `BomCostRollUpService` calls as `products → stock.service` *only if* no reverse edge exists — but `stock → products` already exists (the resolver). **Therefore the cost roll-up must NOT create a `products → stock` edge.** Resolution: **the cost roll-up is computed where the cost lives — `stock` exposes `InventoryCostQuery`, and `BomCostRollUpService` lives in `products` but receives the avg-cost values by calling a `stock`-side batch query through a narrow port, OR the roll-up endpoint is served by a thin `stock`-side facade.** *Final decision (boring, cycle-free):* the **leaf-cost lookup is injected as a functional port** `LeafCostResolver` (interface declared in `products`, implemented in `stock` reading `StockOnHandRepository`) — `stock` already depends on `products`, so `stock` implementing a `products`-declared interface keeps the dependency arrow `stock → products` (the impl depends on the port), and `products` depends only on its own port interface. **No `products → stock` edge; no cycle.** (This is the dependency-inversion port pattern; the ArchUnit rule that forbids `products → stock` stays green.)
- **`stock.service.RecipeExplosionResolver` → `products.service.BomExplosionService`** (D-7) — the **same direction** the resolver already imports `ProductService` + `products.domain.dto`. **Allowed** (shipped pattern).
- **`products` → `iam`/`platform.security`** (ScopeGuard, perms) — shipped, unchanged.
- **No edge `products → gl`, `products → ar`, `products → sales`, `products → manufacturing`.** Manufacturing (future) depends on `products` (reads the BOM via DTOs + the explosion service) — direction `manufacturing → products`, leaf-consumer, no back-edge.
- The shipped `ModuleBoundaryTest` (controller↛repository, service↛controller, no module cycles) — the port pattern (D-10 above) is the mechanism that keeps `products`/`stock` acyclic. **A `ModuleBoundaryTest` case asserting no `products → stock` import** (only the port interface, implemented in stock) is the regression guard.

### D-11 — Perms + `ScopeGuard` case

Two new permissions (module `products`), seeded in `V30` `ON CONFLICT (code) DO NOTHING` + granted to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN (the V3/V7/V17 pattern):
- `BOM.VIEW` — view / list / explode / where-used / cost-roll-up.
- `BOM.MANAGE` — create / edit-draft / add-remove-component / activate / supersede / archive / clone / promote-recipe.

Gating (`@perm.has` for list/create, `@perm.scoped(#uid,'bom',...)` for uid-addressed ops; **never `hasAuthority`**). `ScopeGuard.companyIdOf` gains **`case "bom" -> boms.findCompanyIdByUid(uid)`** (new `BomRepository.findCompanyIdByUid`), mirroring every other target type. `ScopeGuard.assertCanActIn` is called on **every read path** (explode, where-used, cost-roll-up are reads — `assertCanActIn` via `companyId` resolved from the parent/bom uid, the AR-report precedent that uses `recordIndependent` for the root-bypass audit in read-only TX).

### D-12 — Numbering: no document number for a BOM (optional `V31` only if wanted)

A BOM is identified by `(parent_product, version_no)` + its `uid` — it is **not a document with a human number** like an invoice/SO/delivery, so **no `code_sequence` kind is needed** (D-2). `version_no` is allocated by the service as `max(version_no for parent) + 1` under the parent's row context (the `uq_bom_parent_version` constraint is the race backstop; a concurrent double-create retries). **`V31` is reserved but normally empty** — it exists only if the owner later wants a printable `BOM-####` document number, at which point it adds a `BOM` `code_sequence` kind (lazily, the shipped mechanism) and a `bom_number` column; not in v1.

## Cross-module touch-points

1. **`stock.service.RecipeExplosionResolver` upgrade (D-7)** — gains a `BomExplosionService` dependency; delegates to the BOM when an ACTIVE BOM exists, keeps single-level `product_components` as the unchanged default; **sale-explosion stays single-level (`multiLevel=false`)** so COGS is byte-for-byte unchanged. This is the only edit to shipped code; it is in `stock`, additive, behaviour-preserving by default.
2. **`stock` implements the `products`-declared `LeafCostResolver` port (D-10)** — exposes `avg_cost` to the cost roll-up without creating a `products → stock` module edge (dependency inversion; keeps the graph acyclic).
3. **`ScopeGuard.companyIdOf` gains `case "bom"` (D-11)** — one switch arm + one `BomRepository` field, the shipped pattern.
4. **Manufacturing (future, gated) — designed-to contract:** Manufacturing reads the BOM via `BomDto`/`BomComponentDto` and drives production planning via `BomExplosionService.explode(...)` (DTO/service over the boundary, `manufacturing → products`, no back-edge). It is Manufacturing — not this module — that posts WIP/COGS on a production run, consuming the explosion + the cost roll-up. This ADR designs to that expected contract; Manufacturing is not built here.

## Consequences

**Positive**
- A real versioned, effectivity-dated, multi-level, make/buy BOM ships on the products spine; the gate for Manufacturing (PATH-TO-FULL-ERP §4 #5) is cleared.
- **Exactly one explosion implementation (NFR-BOM-04):** the shipped `RecipeExplosionResolver` is upgraded in place to delegate to `BomExplosionService`; no second, divergent definition of "what a product is made of". The N+1 the old resolver flagged is fixed by per-level batch reads.
- **COGS is byte-for-byte unchanged (the load-bearing safety property):** the sale path keeps single-level deduction (`multiLevel=false`, D-7/OQ-BOM-05). Shipping multi-level BOM changes no sale's stock deduction and no COGS posting until a deliberate future decision flips the sale-explosion flag.
- **No money, no events, no GL config (D-9):** the module is master-data structure; the cost roll-up is a derived read. The bright line to Manufacturing (which *does* post WIP/COGS) is explicit.
- Non-destructive on the v1 recipe (D-5): `product_components` is left intact; coexistence is BOM-header-authoritative and surfaced for intentional migration.
- Additive on frozen V1–V19: two tables, one partial-unique index, two perms; no ALTER of any shipped table; #12-safe (no per-company seed-uid inserts — the only seed is the uid-less permission grant).

**Negative / costs**
- The transitive cycle guard (D-6) is a graph reachability check run on every component-add and at activate — O(tree) per add. Bounded by the acyclic invariant + max-depth (BR-BOM-11); acceptable for catalogue-scale BOMs, but the engineer must batch the reads (D-6 N+1 note) so a deep tree does not table-scan.
- The `products`↔`stock` relationship now has edges in both *conceptual* directions (resolver reads BOM; cost roll-up reads avg-cost). The **dependency-inversion port** (D-10) is the discipline that keeps the *module* graph acyclic — the engineer MUST implement the `LeafCostResolver` port in `stock`, not add a `products → stock.repository` import, or `ModuleBoundaryTest` (and a future cycle) breaks. This is the single subtlety the engineer must get right; it is called out as a `ModuleBoundaryTest` regression case.
- The single-level recipe and the BOM coexist for a transition period (D-5); the resolution dispatch (header-present ⇒ BOM) must be implemented in exactly one place (the upgraded resolver + `BomService`) so it cannot drift. Tests must assert a parent with both resolves via the BOM.
- ACTIVE-version freeze (BR-BOM-03) means every recipe change is a new version — correct for engineering change control, but the UI must make "new version" the obvious path (clone-on-new-version default, OQ-BOM-06) so users do not perceive it as friction.

**Neutral / deferred**
- Phantom blow-through, by-products/co-products, alternates/substitutes, engineering-vs-manufacturing BOM, approval-on-activation, persisted standard cost — all deferred (products-bom.md §2), none precluded: `is_phantom`, a co-product table, a substitute table, an `approval_status` column, and a standard-cost-snapshot all slot in additively. The activate transition (D-2) is the seam a future approvals engine wraps without schema change.
- Multi-level **sale** explosion is supported by the resolver but **not enabled** (OQ-BOM-05); enabling it is a future ADR with its own COGS-impact analysis.

## Alternatives considered

- **One explosion implementation (upgrade the resolver) vs a separate BOM explosion + leave the recipe resolver alone.** *Decided: upgrade in place (D-7).* Two explosions = two definitions of product structure = guaranteed drift between what a sale deducts and what a planner explodes. Upgrading the shipped resolver to delegate keeps a single source of truth and fixes its N+1; the cost is one careful edit to live COGS code, mitigated by the `multiLevel=false` default (no behaviour change). Rejecting the two-implementation path.
- **`products → stock.repository` direct read vs a dependency-inversion port for the cost roll-up.** *Decided: the `LeafCostResolver` port (D-10).* A direct `products → stock` import plus the existing `stock → products` resolver edge is a **module cycle** ArchUnit forbids. The port (interface in `products`, impl in `stock`) inverts the dependency so the arrow stays `stock → products` only. The boring, cycle-free choice. (Alternative — moving the whole cost roll-up endpoint to `stock` — was rejected because the explosion lives in `products`; the roll-up belongs next to the structure.)
- **Sale explodes multi-level by default vs single-level default.** *Decided: single-level default (D-7, OQ-BOM-05).* Flipping COGS for every composed sale as a side effect of shipping BOM is unacceptable; the safe default preserves today's behaviour exactly and makes multi-level a deliberate, separately-analysed decision. The flag exists; this module does not flip it.
- **Destructive migrate `product_components` → BOM in `V30` vs non-destructive coexistence.** *Decided: non-destructive, BOM-header-authoritative (D-5, OQ-BOM-01).* Auto-rewriting the recipe on migrate risks data loss and surprises live single-level COGS; coexistence with a deliberate `promoteRecipeToDraftBom` convenience is safe and reversible.
- **BOM document number (`BOM-####`) vs identify by `(parent, version_no)`.** *Decided: `(parent, version_no)` + uid, no number (D-12).* A BOM is a structure, not a printed document; a sequence adds a `code_sequence` kind and a column for no v1 value. `V31` reserved if a printable number is ever wanted. Rejecting the number now.
- **Two orthogonal status enums (lifecycle × effectivity) vs one `BomStatus` + effectivity dates.** *Decided: one `BomStatus` (DRAFT/ACTIVE/ARCHIVED) + `effective_from/to` dates + the partial-unique one-active index (D-2).* The status is the filterable headline; effectivity is the date overlay; the partial-unique index is the race-safe one-active guard. Two enums would force every read to combine them.

## Open items (OQ-BOM — recommended defaults adopted; none blocks the build)

- **OQ-BOM-01 — single-level recipe coexistence:** adopted **BOM-header-authoritative, non-destructive, optional `promoteRecipeToDraftBom`** (D-5). Settled.
- **OQ-BOM-02 — scrap/yield arithmetic:** adopted **scrap `÷ (1 − scrap%)`, yield `÷ yield%`, compounding per level, 6-dp carry, round on presentation** (D-4). Settled.
- **OQ-BOM-05 — sale explodes multi-level?** adopted **NO — single-level default (`multiLevel=false`); COGS byte-for-byte unchanged** (D-7). The load-bearing decision. Settled; flipping it is a future ADR.
- **OQ-BOM-03 — ACTIVE-version editability:** adopted **component set frozen; notes editable** (D-2/D-3). Owner may tighten to fully-frozen (a one-line guard).
- **OQ-BOM-04 — where-used depth:** adopted **single-level guaranteed + full implosion best-effort** (D-6). Full may be a fast-follow if time-boxed.
- **OQ-BOM-06 — clone-on-new-version:** adopted **clone the prior ACTIVE version's components by default** (D-2, `CloneBomRequest`). Convenience.
- **OQ-BOM-07 — max-depth guard:** adopted **default 20, config `bom.max-explosion-depth`** (D-6, BR-BOM-11). Not load-bearing.
- **OQ-BOM-08 (deferred):** phantom blow-through, by-products/co-products, alternates/substitutes, engineering-vs-manufacturing BOM, approval-on-activation, persisted standard cost — all deferred (§2), none precluded (NFR-BOM-07).

## V30 migration ordering (additive; V1–V19 FROZEN; #12-safe seeds)

`V30__products_bom.sql`, in order (each block additive; never edits V1–V19 DDL; `product_components` left intact, D-5):
1. **CREATE `boms`** (+ `uq_bom_uid`, `uq_bom_parent_version`, `fk_bom_company`, `fk_bom_parent`, the four CHECKs; D-2).
2. **CREATE `bom_components`** (+ `uq_bom_component_uid`, `uq_bom_component_line_no`, `uq_bom_component_child`, the three FKs, the three CHECKs; D-3).
3. **CREATE partial-unique index `uq_bom_one_active`** `ON boms (parent_product_id) WHERE status = 'ACTIVE'` (BR-BOM-04 backstop, D-2).
4. **Indexes** `ix_boms_company`, `ix_boms_parent`, `ix_boms_parent_status`, `ix_bom_components_bom`, `ix_bom_components_child`, `ix_bom_components_company`.
5. **Permission seed + `ORG_ADMIN` grant** — INSERT `BOM.VIEW`, `BOM.MANAGE` (module `products`) `ON CONFLICT (code) DO NOTHING`; grant both to `ORG_ADMIN` via the `roles × permissions` CROSS JOIN `WHERE r.code='ORG_ADMIN' AND p.code IN ('BOM.VIEW','BOM.MANAGE') ON CONFLICT DO NOTHING` (the V3/V7/V17 pattern). (Permissions have no `uid` — #12 N/A.)

**No `gl_configs`, no `chart_of_accounts`, no `code_sequence`, no per-company seed inserts** ⇒ **V30 has no #12-vulnerable per-company seed-uid** (the only seed is the uid-less permission grant). No `JournalSourceType` widen, no `DomainEventType`, no movement-type change (D-9). `MigrationKeepDataIT` extends to V30 (the two new tables are keep-data-safe; no ALTER, no back-fill). **`V31` reserved, normally empty** (D-12). The resolver upgrade (D-7) and the `LeafCostResolver` port (D-10) are **code-only** — no migration.

## Angular nav routes (D-11)

Under the existing **Products** nav group (shell `MenuGroup` "Products"), additive `available: true` items:
- `/admin/boms` — BOM list (filter by parent product / status / version), gated `BOM.VIEW`. Lazy `bom-list.component`.
- `/admin/boms/uid/:uid` — BOM detail (header + components + activate/supersede/archive + explode + where-used + cost-roll-up tabs), gated `BOM.VIEW`. Lazy `bom-detail.component`.
- `/admin/boms/create` — new DRAFT BOM (with clone-from-version option), gated `BOM.MANAGE`. Lazy `bom-create.component`.

(Explosion, where-used, and cost-roll-up are tabs/panels on the detail screen, not separate routes; they call the `BomController` read endpoints. A "where-used" entry point from a `ProductDetailComponent` action is additive on the existing product detail.)

## API surface (D-1) — `BomController` under `/api/v1/boms` (flat in `com.erp.api`)

| method + path | perm gate | body / params | returns |
|---|---|---|---|
| `GET /api/v1/boms` | `@perm.has('BOM.VIEW')` | `companyId`, `parentProductUid?`, `status?`, `Pageable` | `Page<BomDto>` |
| `GET /api/v1/boms/uid/{uid}` | `@perm.scoped(#uid,'bom','BOM.VIEW')` | — | `BomDto` (header + components) |
| `POST /api/v1/boms` | `@perm.has('BOM.MANAGE')` | `CreateBomRequest{parentProductUid, outputQty, yieldPercent?, notes?, cloneFromBomUid?}` | `BomDto` (DRAFT) |
| `PUT /api/v1/boms/uid/{uid}` | `@perm.scoped(#uid,'bom','BOM.MANAGE')` | `UpdateBomRequest{outputQty?, yieldPercent?, notes?}` (DRAFT, or notes-only on ACTIVE) | `BomDto` |
| `POST /api/v1/boms/uid/{uid}/components` | `@perm.scoped(#uid,'bom','BOM.MANAGE')` | `AddBomComponentRequest{componentProductUid, qtyPer, sourcing?, scrapPercent?, reference?}` (DRAFT only) | `BomComponentDto` |
| `PUT /api/v1/boms/uid/{uid}/components/{componentUid}` | `@perm.scoped(#uid,'bom','BOM.MANAGE')` | `UpdateBomComponentRequest{qtyPer?, sourcing?, scrapPercent?, reference?}` (DRAFT only) | `BomComponentDto` |
| `DELETE /api/v1/boms/uid/{uid}/components/{componentUid}` | `@perm.scoped(#uid,'bom','BOM.MANAGE')` | — (DRAFT only) | `204` |
| `POST /api/v1/boms/uid/{uid}/activate` | `@perm.scoped(#uid,'bom','BOM.MANAGE')` | `ActivateBomRequest{effectiveFrom}` | `BomDto` (ACTIVE; supersedes prior) |
| `POST /api/v1/boms/uid/{uid}/archive` | `@perm.scoped(#uid,'bom','BOM.MANAGE')` | — | `BomDto` (ARCHIVED) |
| `GET /api/v1/boms/explode` | `@perm.scoped(#parentProductUid,'product','BOM.VIEW')` | `parentProductUid` (or `bomUid`), `outputQty`, `branchUid?`, `multiLevel?`, `asOfDate?`, `withCost?` | `BomExplosionResultDto` |
| `GET /api/v1/boms/where-used/{componentProductUid}` | `@perm.scoped(#componentProductUid,'product','BOM.VIEW')` | `full?` | `List<WhereUsedRowDto>` or `WhereUsedTreeDto` |
| `GET /api/v1/boms/cost-roll-up` | `@perm.scoped(#parentProductUid,'product','BOM.VIEW')` | `parentProductUid` (or `bomUid`), `branchUid`, `outputQty` | `BomCostRollUpDto` |
| `POST /api/v1/products/uid/{uid}/promote-recipe-to-bom` | `@perm.scoped(#uid,'product','BOM.MANAGE')` | — (optional convenience, D-5/OQ-BOM-01) | `BomDto` (DRAFT v1) |

All read endpoints call `ScopeGuard.assertCanActIn` on the resolved company (the read-path guard, NFR-BOM-06). Responses are `ApiResponse<T>`-wrapped by `ApiResponseAdvice`.

---

## Summary

ADR-0026 designs **Multi-level BOM** in `com.erp.modules.products`: two new tables (`boms` versioned/effectivity-dated header + `bom_components` make/buy lines), a `BomStatus` (DRAFT/ACTIVE/ARCHIVED) lifecycle with a partial-unique one-active index and an atomic supersede, a transitive `BomCycleGuard` (BR-BOM-01) extending `ProductCompositionGuard`, a recursive **`BomExplosionService`** (explode-to-all-levels + flattened leaf summary, scrap/yield compounding per level, max-depth-bounded) + **where-used** (single-level + best-effort full implosion), and a derived **read-only standard-cost roll-up** that reads ADR-0020 `avg_cost`.

**The load-bearing decisions:** (D-7) the shipped single-level `RecipeExplosionResolver` is **upgraded in place** to delegate to `BomExplosionService` so there is **exactly one explosion implementation** (NFR-BOM-04) — and the **sale path keeps `multiLevel=false`**, so COGS is byte-for-byte unchanged (OQ-BOM-05); (D-5) the v1 `product_components` recipe is **left intact**, BOM-header-authoritative, no destructive migration (OQ-BOM-01); (D-10) the cost roll-up reads `avg_cost` through a **dependency-inversion `LeafCostResolver` port** (interface in `products`, impl in `stock`) so no `products → stock` module edge forms — keeping the graph acyclic; (D-9) the module posts **nothing** — no GL config, no journals, no CoA accounts, no events, no handlers (the bright line to Manufacturing, which owns WIP/COGS).

**Shared-contract identifiers introduced:** perms `BOM.VIEW`, `BOM.MANAGE`; `ScopeGuard` case `"bom"`; nav routes `/admin/boms`, `/admin/boms/uid/:uid`, `/admin/boms/create`; migration `V30__products_bom.sql` (+ reserved-but-empty `V31`). **Empty sets (explicit, D-9):** no new `GlConfigKey`, no new CoA account codes, no new `JournalSourceType`, no new `DomainEventType`, no new outbox handlers. **Cross-module touch list:** (1) `stock.service.RecipeExplosionResolver` upgrade (the only shipped-code edit, behaviour-preserving by default); (2) the `LeafCostResolver` port (`products` declares, `stock` implements — cycle-free); (3) `ScopeGuard.companyIdOf` `case "bom"`. **Gates Manufacturing** (WIP costing) via the `BomDto`/`BomExplosionService` contract; **depends on nothing**. **Additive on frozen V1–V19; #12-safe** (V30 has no per-company seed-uid inserts).

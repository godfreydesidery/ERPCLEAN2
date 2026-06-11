# Requirements — Multi-level Bill of Materials (BOM)

- **Module:** Products / BOM (extends `com.erp.modules.products` — the catalogue + single-level recipe)
- **Status:** Draft for owner review (requirements only — no schema/design here; the data model is ADR-0026)
- **Depends on:** Products v1 (ADR-0007 / V3 — `products`, `product_components`, `code_sequence`,
  `MasterStatus` soft-delete, `ProductType`, `RecipeExplosionResolver`), IAM (RBAC + `ScopeGuard`),
  Stock valuation/COGS (ADR-0020 / V17 — the moving-average `avg_cost` the explosion costs against)
- **This module gates:** Manufacturing (WIP costing — production orders explode the BOM to issue
  components and roll up cost; ROADMAP §3.6 / PATH-TO-FULL-ERP §4 critical-dependency #5)
- **Next step:** solutions-architect turns this into the data model + ADR-0026 (on owner go-ahead)

## 1. Business context & why now

Products v1 (ADR-0007) shipped a **single-level recipe**: a composed product names its immediate
components and quantities (Ugali Meat = 1 Ugali + 1 Meat), captured for display and consumed by the
sale-time `RecipeExplosionResolver` for one-level component stock deduction (ADR-0010 D-8). That
resolver is **explicitly non-recursive** (its Javadoc: "Single-level only. No recursion. Components
that are themselves composed are treated as simple products and deducted directly — multi-level BOM
is deferred"). The deferral was deliberate (products.md §2, §9): nest depth and BOM versioning are
manufacturing concerns, not catalogue concerns.

Manufacturing is now the next extension module on the path (PATH-TO-FULL-ERP §3.6), and its **gating
prerequisite** is a real multi-level BOM (PATH-TO-FULL-ERP §4 #5). A production order for a finished
good must explode the structure **to all levels** to know which raw materials to issue, must know
which sub-assemblies are **made** in-house vs **bought**, and must be able to pin a production run to
a **specific BOM version** (engineering changes a recipe over time; a run started under v2 must keep
costing and consuming v2 even after v3 is released). None of that exists today.

This module turns the flat single-level recipe into a **versioned, multi-level bill of materials**:
a composed product can have one or more **BOM headers** (each a versioned, effectivity-dated
revision), each header lists **components** (which may themselves be composed products), components
carry a **make/buy sourcing** classification, and the system can **explode** a BOM to all levels
(the full raw-material requirement for a given output quantity) and answer **where-used** (which
parent BOMs consume a given component). The single-level `product_components` recipe and the
shipped sale-time explosion **continue to work unchanged** — this module is additive and the old
recipe path is preserved (see §9). No money moves in this module; it is **master data + structure**.

### Vocabulary distinction (read this first)

- **Product / Item** — a catalogue entry (Products v1). Unchanged.
- **BOM (Bill of Materials)** — a structured, versioned definition of what goes into making **one**
  output product: a header (parent product + version + effectivity + status) and its component lines.
  Supersedes "recipe" as the term for the manufacturing structure; the flat single-level recipe
  remains the term for the v1 `product_components` rows.
- **BOM header / version / revision** — one versioned definition of a parent product's structure.
  A parent may have several headers over time (v1, v2, v3); at most one is **ACTIVE** at any moment.
  "Version" and "revision" are used interchangeably; `version_no` is the canonical field.
- **Component (BOM line)** — one input to the parent: a child product + quantity-per + a make/buy
  sourcing flag + optional scrap %. A component product may itself be a BOM parent (nesting).
- **Parent / output product** — the product the BOM produces (the assembled or composed item).
- **Level** — the depth of nesting. The finished good is level 0; its direct components level 1;
  their components level 2; and so on. **Multi-level** means the explosion recurses through all levels.
- **Make / Buy (sourcing)** — whether a component is **manufactured** in-house (it has its own BOM
  and is itself exploded further) or **purchased / supplied** (a leaf — a raw material or bought-in
  part that is consumed as-is and not exploded further).
- **Phantom component** — a non-stocked structural sub-assembly that exists only to group its
  children; on explosion it is "blown through" (its children are pulled up to the parent) and the
  phantom itself is never stocked or issued. (Deferred — see §2.)
- **Explosion** — expanding a BOM for an output quantity into the full requirement of components,
  recursing through make-levels until every leaf (buy/raw) component is reached.
- **Where-used (implosion)** — the inverse: given a component, the list of parent BOMs (and at what
  quantity-per) that consume it. Answers "if this raw material's cost or supply changes, what is
  affected?"
- **Effectivity** — the date window `[effective_from, effective_to)` over which a BOM version is the
  one that applies. A production run picks the version effective on its run date (or the explicitly
  pinned version).
- **Yield / scrap %** — an allowance for expected loss: producing 100 units of a parent at 95% yield
  needs components for ~105 units; a component with 2% scrap needs 2% more than the nominal quantity.

## 2. Scope

### In scope (v1 — "a versioned, multi-level, make/buy bill of materials with explosion and where-used")

- A **BOM header** master per parent product: `version_no`, status (DRAFT / ACTIVE / ARCHIVED —
  `MasterStatus`-aligned lifecycle), effectivity window, output quantity (the base quantity the BOM
  produces), header-level yield %, notes. Create / view / list / activate / supersede (new
  version) / archive.
- **BOM components** (lines) under a header: child product, quantity-per (in the child's base
  units), a **make/buy** sourcing flag, component-level scrap %, a sequence/line number, and an
  optional reference designator / note.
- **Multi-level nesting**: a component product may itself be a BOM parent; the structure nests to
  arbitrary depth, with **cycle prevention** (a product may not appear, directly or transitively, in
  its own BOM).
- **Make/buy classification** per component, defaulting from the child product's nature (a component
  that has its own ACTIVE BOM defaults to MAKE; one that does not defaults to BUY) and overridable
  per line.
- **Explosion to all levels**: given a parent product (or BOM version) and an output quantity, return
  the full multi-level requirement — every component at every level, the rolled-up total quantity of
  each leaf (buy/raw) component, with scrap/yield applied, and the level number on each line.
- **Where-used (single-level and full)**: given a component product, list the parent BOMs that
  consume it directly (single-level) and, optionally, the full set of ancestors up to the
  finished goods (multi-level implosion).
- **Standard-cost roll-up (read-only, derived)**: an explosion can optionally compute the rolled-up
  standard cost of the parent from the moving-average `avg_cost` (ADR-0020) of its leaf components —
  a **derived report**, not a stored or posted figure (no GL).
- **Versioning / effectivity discipline**: at most one ACTIVE version per parent at a time;
  superseding a version activates the new one and archives the prior ACTIVE one; effectivity windows
  must not overlap for ACTIVE versions of the same parent.
- **Reuse / extend the shipped explosion path**: the multi-level resolver subsumes the single-level
  `RecipeExplosionResolver` so the existing sale-time COGS explosion (ADR-0020 D-2) can opt into
  multi-level expansion without a second code path.
- Per-company scope + multi-branch inheritance from the parent product; full audit; RBAC-gated.

### Out of scope for v1 — Deferred (captured, not built)

- **Production orders / WIP costing / finished-goods receipt** — the Manufacturing module (ROADMAP
  §3.6). This module produces the *structure* Manufacturing explodes; it does not run production,
  issue components, post WIP, or receive finished goods. No GL postings of any kind here.
- **Routings / work centres / operations / labour & overhead** — the time-and-resource side of
  manufacturing. A BOM here is materials-only; labour/overhead cost roll-up is Manufacturing.
- **Phantom / blow-through components** — recognised as a concept (§ Vocabulary) but not built in v1;
  every component is a real, stockable-or-service product. The `is_phantom` stance is reserved.
- **By-products / co-products** (a BOM yielding more than one output) — deferred.
- **Alternate / substitute components** (this-or-that component) — deferred.
- **Engineering BOM vs Manufacturing BOM** (two structures for the same product) — deferred; v1 is one
  BOM kind.
- **Quantity-break / level-based sourcing rules, MRP netting, lead-time planning** — Manufacturing/MRP.
- **Approval workflow on BOM activation** — recognised (engineering change control); v1 uses the
  DRAFT→ACTIVE service-guarded transition with `BOM.MANAGE`, and is designed to slot into the future
  approvals engine (X.5) without schema change (see §8 assumption).
- **Cost roll-up persisted as a standard cost on the product** — v1 computes it on demand; it does not
  write a standard-cost column or post a revaluation. (When standard costing lands, it consumes this.)

### Explicitly NOT this module

- **Stock quantities / movements / valuation postings** — Stock (ADR-0010 / ADR-0020). BOM reads
  `avg_cost` for the derived cost roll-up; it never writes stock or GL.
- **The act of producing** — Manufacturing. BOM is referenced by it.
- **The single-level recipe's sale-time deduction behaviour** — unchanged (Stock owns it). BOM only
  upgrades the *resolver* to be multi-level-capable; whether a sale explodes one level or all levels
  is a Stock/Sales decision flagged in §9 / OQ-BOM-05.

## 3. The bill of materials

### 3.1 v1 concepts (built now)

| Concept | One-line definition | Notes |
|---|---|---|
| **BOM header** | One versioned structure for a parent product. | `version_no`, status, effectivity, output qty, yield %. |
| **BOM version / revision** | A point-in-time definition; a parent has many over time. | At most one ACTIVE at any moment (BR-BOM-04). |
| **BOM component (line)** | One input: child product + qty-per + make/buy + scrap %. | The child may itself be a BOM parent (nesting). |
| **Make / buy sourcing** | Whether the component is manufactured or purchased. | Defaults from the child's nature; overridable (BR-BOM-07). |
| **Effectivity** | The date window a version applies over. | ACTIVE windows must not overlap for one parent (BR-BOM-05). |
| **Output quantity** | How many parent units one execution of the BOM produces. | Quantities-per are relative to this (BR-BOM-06). |
| **Yield % / scrap %** | Expected-loss allowances at header and line level. | Inflate the exploded requirement (BR-BOM-08). |
| **Explosion** | Expand to all levels for an output quantity. | Recurses through MAKE components; stops at BUY leaves. |
| **Where-used (implosion)** | Parents that consume a given component. | Single-level and full. |
| **Standard-cost roll-up** | Derived parent cost from leaf `avg_cost`. | Read-only report; no GL, no stored value. |

### 3.2 Deferred concepts (recognised, NOT in v1)

Production orders / WIP costing · routings / work-centres / labour-overhead · phantom blow-through ·
by-products / co-products · alternate/substitute components · engineering-vs-manufacturing BOM ·
MRP / lead-time planning · approval workflow on activation · persisted standard cost / revaluation.

## 4. Actors / personas

- **Production / engineering manager** — defines and versions BOMs; classifies make/buy; activates and
  supersedes versions. Holds `BOM.MANAGE`.
- **Costing / finance analyst** — runs the standard-cost roll-up and where-used impact reports. Holds
  `BOM.VIEW` (and reads stock valuation via the existing `INVENTORY.VALUATION.VIEW`).
- **Production planner (future Manufacturing user)** — explodes a BOM to plan a run. Holds `BOM.VIEW`
  (and, in Manufacturing, the production perms). v1 exposes the explosion read; the run is deferred.
- **Catalogue administrator** — still owns the product master and the v1 single-level recipe; may also
  hold `BOM.MANAGE` to maintain structures.

## 5. Functional requirements

> IDs are `FR-BOM-NN`. Each is a crisp, testable statement. "Parent" = the product a BOM produces;
> "component" = a line/child of a BOM.

### BOM header & versioning

- **FR-BOM-01** The system maintains a **BOM header** for a parent product: create, view, list/search
  (by parent product, by status, by version), activate, supersede (create a new version), and archive.
  A header carries `version_no`, `status` (DRAFT / ACTIVE / ARCHIVED), an effectivity window, an
  output quantity, a header yield %, and notes.
- **FR-BOM-02** A parent product may have **multiple BOM versions** over time. Each version is a
  distinct header with its own components; editing the components of an ACTIVE version is restricted
  (a change is made as a **new version**, not an in-place edit of ACTIVE — see BR-BOM-03), while a
  DRAFT version is freely editable.
- **FR-BOM-03** **At most one BOM version per parent is ACTIVE** at any moment (BR-BOM-04). Activating
  a DRAFT version archives the previously-ACTIVE version of the same parent (supersede), in one
  service-guarded, audited transaction.
- **FR-BOM-04** Each ACTIVE version carries an **effectivity window** `[effective_from, effective_to)`;
  windows of ACTIVE/ARCHIVED versions of the same parent **must not overlap** (BR-BOM-05). An
  open-ended `effective_to = NULL` means "current". The "version applicable on date D" is the one
  whose window contains D.
- **FR-BOM-05** A BOM header defines an **output quantity** (e.g. this BOM produces 1 cake, or 1
  batch of 50 loaves); component quantities-per are expressed relative to this output quantity
  (BR-BOM-06).
- **FR-BOM-06** A DRAFT BOM is **soft-deletable / archivable**; an ARCHIVED BOM is excluded from
  selection and from "current" explosion but remains for historical runs and where-used history
  (`MasterStatus` discipline, mirrors FR-PROD-02).

### BOM components (lines)

- **FR-BOM-07** A BOM header has **one or more components**. Each component names a **child product**
  (an existing, non-archived product of the same company), a **quantity-per** (> 0, in the child's
  base units), a **make/buy** sourcing flag, an optional **scrap %**, a **line number** for ordering,
  and an optional reference/note.
- **FR-BOM-08** A component's **make/buy** flag classifies it as **MAKE** (manufactured — it has, or
  is expected to have, its own BOM and is exploded further) or **BUY** (purchased/raw — a leaf,
  consumed as-is, not exploded). The flag **defaults** from the child product (a child with an ACTIVE
  BOM defaults MAKE; otherwise BUY) and is **overridable** per line (BR-BOM-07).
- **FR-BOM-09** A BOM may **nest to arbitrary depth**: a component child that is itself a parent
  product (has an ACTIVE BOM and is classified MAKE) is expanded during explosion. The structure is
  genuinely multi-level (FR-BOM-12).
- **FR-BOM-10** A product **cannot appear, directly or transitively, in its own BOM** (no cycles,
  BR-BOM-01). Adding a component is rejected if it would create a cycle (the child's BOM, fully
  exploded, contains the parent).
- **FR-BOM-11** Two components of the same BOM version **for the same child product** are not allowed
  (a child appears at most once per version; combine quantities, BR-BOM-02). (Alternates/substitutes
  that would relax this are deferred — §2.)

### Explosion & where-used

- **FR-BOM-12** **Explosion to all levels:** given a parent product (resolving the applicable ACTIVE
  version, or an explicitly named version) and an **output quantity**, the system returns the full
  multi-level component requirement: every component at every level, each with its **level number**,
  its **quantity at that level**, and its **rolled-up total quantity** (for repeated leaves across
  branches), with **scrap and yield applied**. MAKE components recurse; BUY components are leaves.
- **FR-BOM-13** The explosion returns a **flattened leaf summary** (the net requirement of each BUY /
  raw leaf component to produce the requested output quantity) **and** the **indented tree** (the
  structure with levels) — both are first-class outputs (NFR-BOM-02).
- **FR-BOM-14** **Where-used (single-level):** given a component product, the system lists the BOM
  headers (and their parents, version, status, and quantity-per) that consume it directly.
- **FR-BOM-15** **Where-used (full / implosion):** given a component product, the system can return
  the full set of ancestor parents up to top-level finished goods, with the path and effective
  quantity-per (the inverse of explosion). (May be a derived endpoint; single-level FR-BOM-14 is the
  minimum, full is the target — OQ-BOM-04.)
- **FR-BOM-16** **Standard-cost roll-up (derived, read-only):** an explosion may optionally compute
  the parent's rolled-up standard cost = Σ over leaf components of (leaf rolled-up quantity ×
  leaf `avg_cost`), using the moving-average cost from Stock valuation (ADR-0020), scoped to a branch.
  Leaves with no established `avg_cost` are flagged (cost incomplete), not silently zero (BR-BOM-09).
  This is a **report**; it writes nothing and posts nothing.

### Reuse of the shipped recipe / explosion path

- **FR-BOM-17** The multi-level resolver **subsumes the single-level recipe**: a product defined with
  the v1 `product_components` (and no BOM header) continues to resolve as a single-level recipe; a
  product with a BOM header resolves via the BOM. The sale-time explosion path (ADR-0020 D-2 /
  `RecipeExplosionResolver`) continues to work unchanged in its single-level default, with multi-level
  expansion as an opt-in the resolver now supports (the depth policy for sales is OQ-BOM-05). No
  duplicate explosion implementation exists after this module (NFR-BOM-04).
- **FR-BOM-18** Where a parent has **both** a v1 single-level recipe (`product_components`) and a new
  BOM header, the **BOM header is authoritative** for explosion (the recipe is treated as legacy);
  the system surfaces the coexistence so an administrator can migrate (OQ-BOM-01). No automatic
  destructive migration of `product_components` happens in v1.

### Per-company scope & branch

- **FR-BOM-19** Every BOM header and component **belongs to exactly one company** (the parent
  product's company); a BOM is never company-less and its company never changes (BR-BOM-10). All
  reads/writes are company-scoped via `ScopeGuard`.
- **FR-BOM-20** BOM definitions are **company-level master data**; the parent product's branch
  associations (FR-PROD-20) determine where the parent can be produced/sold. The standard-cost
  roll-up (FR-BOM-16) is **branch-scoped** because `avg_cost` is per (company, branch, product).

### Permissions (gating)

- **FR-BOM-21** All BOM operations are gated by IAM permissions: `BOM.VIEW` to view / explode /
  where-used / cost-roll-up; `BOM.MANAGE` to create / edit drafts / add-remove components / activate /
  supersede / archive. Codes are seeded with the module and gated via `@perm.has` / `@perm.scoped`
  (NEVER `hasAuthority`), with `ScopeGuard.assertCanActIn` on every read path.

## 6. Business rules (invariants)

- **BR-BOM-01** A product **cannot appear, directly or transitively, in its own BOM** (acyclic).
  Adding a component is rejected if the candidate child's fully-exploded structure contains the
  parent. (The v1 single-level `chk_product_component_not_self` is the degenerate one-level case;
  multi-level cycle prevention is a service-level transitive check, BR-BOM-01 extends BR-PROD-05.)
- **BR-BOM-02** A child product appears **at most once** per BOM version (no duplicate component
  lines for the same child in one header; combine quantities). Enforced by a unique constraint.
- **BR-BOM-03** An **ACTIVE** BOM version is **structurally immutable** for components (engineering
  change control): adding/removing/changing components of an ACTIVE version is not allowed — make a
  new DRAFT version and activate it (supersede). DRAFT versions are freely editable. (Header notes /
  metadata edits on ACTIVE may be allowed; the component set is frozen — OQ-BOM-03.)
- **BR-BOM-04** **At most one ACTIVE BOM version per parent** at any moment. Activation supersedes
  (archives) the prior ACTIVE version atomically.
- **BR-BOM-05** **ACTIVE/effective effectivity windows of the same parent must not overlap.** A new
  version's `effective_from` must be ≥ the prior version's `effective_to` (or the prior is closed to
  the new one's `effective_from` at supersede). `effective_to = NULL` means open-ended/current.
- **BR-BOM-06** Component **quantity-per is relative to the header's output quantity** and must be
  > 0, expressed in the child's base units. A bulk-pack-expressed input is converted to base
  (mirrors FR-PROD-06).
- **BR-BOM-07** A component's **make/buy** flag defaults from the child (child has an ACTIVE BOM ⇒
  MAKE; else BUY) but is **overridable**; a BUY component is never exploded further even if it happens
  to have a BOM (the line's classification wins for that usage).
- **BR-BOM-08** **Scrap/yield inflate the requirement.** A component-level scrap % `s` makes the
  effective quantity `qtyPer / (1 − s)` (or `qtyPer × (1 + s)` per the chosen convention — fixed in
  ADR-0026); a header yield % `y` inflates the whole explosion by `1/y`. Both default to 0/100%
  (no inflation). The exact arithmetic + rounding is fixed in the ADR (OQ-BOM-02).
- **BR-BOM-09** The **standard-cost roll-up uses the leaf `avg_cost`** (ADR-0020 moving average) per
  (company, branch); a leaf with `avg_cost IS NULL` (no established cost) makes the roll-up
  **incomplete** — it is flagged, the partial total is returned with the incomplete-leaf list, never
  silently treated as zero.
- **BR-BOM-10** A BOM's parent and all component children **belong to the same company** (mirrors
  BR-PROD-06). A component cannot be a product of another company.
- **BR-BOM-11** **Explosion is bounded.** Because the structure is acyclic (BR-BOM-01), explosion
  terminates; the resolver also enforces a **maximum depth** guard (configurable, default e.g. 20
  levels) as a defence against a pathological or mid-edit structure, failing safe with a clear error
  rather than recursing unbounded (NFR-BOM-03).
- **BR-BOM-12** An **archived parent product** cannot have its BOM activated/used for new explosions
  (mirrors BR-PROD-10); a component that is archived cannot be **added** to a new BOM (mirrors
  `ProductCompositionGuard`), though it may remain on an existing ARCHIVED version for history.

## 7. Key flows

### 7.1 Define and activate a multi-level BOM (happy path)

1. Manager opens the parent product (e.g. *Deluxe Cake*) and creates a **DRAFT BOM v1** with output
   quantity = 1 cake.
2. Adds components: 1 × *Sponge Base* (MAKE — it has its own BOM), 0.2 kg × *Frosting* (MAKE),
   2 × *Candle* (BUY), 1 × *Box* (BUY). Each add runs the same-company guard, the not-archived guard,
   the duplicate-child guard (BR-BOM-02), and the **cycle check** (BR-BOM-01).
3. *Sponge Base* itself has an ACTIVE BOM (Flour, Sugar, Eggs — all BUY); *Frosting* has an ACTIVE BOM
   (Sugar, Butter — BUY). The make/buy defaults are applied (Sponge/Frosting ⇒ MAKE; Candle/Box ⇒ BUY).
4. Manager **activates** DRAFT v1: the service validates the structure (acyclic, every component
   resolvable, effectivity non-overlapping), sets `status = ACTIVE`, `effective_from = today`, and —
   since there was no prior ACTIVE — completes. Audited.
5. Manager **explodes** *Deluxe Cake* × 10: the resolver recurses — level 1 = Sponge Base × 10,
   Frosting × 2 kg, Candle × 20, Box × 10; level 2 (under Sponge Base) = Flour, Sugar, Eggs ×10's
   worth; level 2 (under Frosting) = Sugar, Butter. The **leaf summary** aggregates Sugar across both
   sub-assemblies. Scrap/yield applied. Returns the tree + the flattened leaf requirement.
6. Manager runs the **standard-cost roll-up** (branch-scoped): Σ leaf qty × `avg_cost`. One leaf
   (*Eggs*) has no `avg_cost` yet ⇒ the report returns the partial cost and flags *Eggs* as
   cost-incomplete.

### 7.2 Supersede a BOM with a new version (happy path)

1. Engineering changes the recipe: *Deluxe Cake* now needs 3 candles. Manager creates **DRAFT v2**
   (optionally cloned from v1), edits the Candle line to 3.
2. Manager **activates v2** effective next month: the service archives v1 (sets `effective_to` =
   v2's `effective_from`, `status = ARCHIVED`), activates v2 (`status = ACTIVE`,
   `effective_from = next month`), in one transaction. No window overlap (BR-BOM-05). Audited.
3. A production run (future Manufacturing) dated this month still resolves **v1**; a run next month
   resolves **v2**. Historical runs pinned to v1 are unaffected.

### 7.3 Where-used impact (happy path)

1. *Sugar*'s supplier raises its price. Analyst runs **where-used** on *Sugar*: single-level shows
   *Sponge Base BOM* and *Frosting BOM* consume it; full implosion shows the ancestor *Deluxe Cake*
   and any other finished goods up the tree, with effective quantity-per along each path.
2. Analyst re-runs the standard-cost roll-up on affected parents to see the margin impact (derived).

### 7.4 Unhappy paths

- **Cycle attempt (BR-BOM-01):** adding *Deluxe Cake* as a component of *Sponge Base* (which is a
  component of *Deluxe Cake*) is rejected — the candidate's exploded structure contains the parent.
- **Duplicate child (BR-BOM-02):** adding a second *Candle* line to the same version is rejected;
  the manager edits the existing line's quantity instead.
- **Edit an ACTIVE version's components (BR-BOM-03):** rejected with "ACTIVE BOM is frozen; create a
  new version" — the manager creates a DRAFT v(n+1).
- **Overlapping effectivity (BR-BOM-05):** activating v2 effective from a date inside v1's open window
  without superseding v1 is rejected; the activate flow must supersede (close v1) in the same act.
- **Archived component added (BR-BOM-12):** adding an archived child product is rejected (mirrors
  `ProductCompositionGuard`).
- **Cross-company component (BR-BOM-10):** adding a child of another company is rejected by the
  same-company guard.
- **Cost roll-up with missing avg_cost (BR-BOM-09):** the report returns the partial total and the
  incomplete-leaf list; the UI surfaces "cost incomplete: Eggs has no established cost".
- **Pathological depth (BR-BOM-11):** an explosion exceeding the max-depth guard fails with a clear
  "BOM exceeds maximum nesting depth" error rather than recursing unbounded.

## 8. Assumptions

- BOM is **materials-only** master data; labour/overhead/routings and the act of production are
  Manufacturing. No GL postings, no stock movements, no events that drive money originate here.
- The single-level `product_components` recipe and the shipped sale-time deduction **stay working**;
  this module is additive (FR-BOM-17/18). A parent's existing recipe is treated as legacy once a BOM
  header exists for it; no destructive auto-migration in v1.
- Quantities use the existing base-unit + bulk-pack model (ADR-0007); component quantities-per are
  stored in base units.
- The standard-cost roll-up reads the **moving-average `avg_cost`** (ADR-0020); when a true
  standard-costing module lands it will consume this roll-up — v1 does not persist or post a standard
  cost.
- **Approval on activation** is out of v1 (DRAFT→ACTIVE is gated by `BOM.MANAGE`); the activation
  transition is designed so a future approvals engine (X.5) can wrap it (an `approval_status` /
  approver gate slots in additively before the ACTIVE transition) without changing the BOM schema or
  the explosion path.
- Effectivity is **date-based** (DATE, not timestamp); a run's effective version is resolved by its
  business date.

## 9. Accepted risk / assumption — coexistence with the single-level recipe (v1)

For a transition period, a parent product may have **both** a v1 `product_components` recipe and a v1
BOM header. The rule is unambiguous (FR-BOM-18, BR-resolution): **if a BOM header exists for the
parent, the BOM is authoritative for explosion; the `product_components` rows are legacy/display.**
The system surfaces the coexistence so an administrator can migrate intentionally; it does **not**
silently delete or rewrite `product_components` in v1 (OQ-BOM-01). The sale-time explosion stays
single-level by default (preserving today's COGS behaviour exactly); whether and when a sale explodes
multi-level is a deliberate Stock/Sales decision, not a side effect of this module (OQ-BOM-05). No one
should assume that shipping multi-level BOM changes what a sale deducts until that decision is taken.

## 10. Non-functional

- **NFR-BOM-01** All persisted timestamps UTC; all monetary reads (cost roll-up) currency-aware per
  ADR-0005 (base currency, HALF_UP).
- **NFR-BOM-02** Explosion of a realistic structure (hundreds of components, ~10 levels) returns
  promptly; the resolver minimises N+1 (batch component reads per level) and is bounded (BR-BOM-11).
- **NFR-BOM-03** Explosion is **provably terminating**: the acyclic invariant (BR-BOM-01) plus the
  max-depth guard guarantee no infinite recursion even on a mid-edit or imported-bad structure.
- **NFR-BOM-04** **Exactly one** explosion implementation exists after this module: the multi-level
  resolver subsumes `RecipeExplosionResolver`'s single-level case; no second, divergent explosion
  code path (NFR — anti-duplication invariant the architecture must hold, ADR-0026 D-7).
- **NFR-BOM-05** All BOM mutations are **audited** (create/edit-draft/add-remove-component/activate/
  supersede/archive) consistent with the IAM audit trail; the ledger of versions is preserved (an
  ARCHIVED version is never destroyed, supporting historical-run reproducibility).
- **NFR-BOM-06** All reads/writes are **tenant-scoped** (`ScopeGuard` + `assertCanActIn` on every read
  path) and **RBAC-gated** (`@perm.*`); no cross-company leakage; pagination on list endpoints.
- **NFR-BOM-07** The design is **additive on the frozen V1–V19** migrations; the Manufacturing module
  consumes the BOM via DTOs over the module boundary (no Manufacturing→BOM entity coupling) and via
  the explosion service contract — designed to its expected contract though Manufacturing is not built.

## 11. Open questions (recommended defaults; the load-bearing ones flagged ★)

- **★ OQ-BOM-01 — single-level recipe migration:** how do we reconcile a parent that has both
  `product_components` and a BOM header? *Recommended default:* **BOM-header-authoritative, no
  destructive migration in v1** (FR-BOM-18); surface the coexistence; offer a manual "promote recipe
  to BOM v1" convenience that copies `product_components` into a DRAFT BOM header (leaving the recipe
  rows intact). Load-bearing because it sets whether the resolver dispatches on header-presence.
- **★ OQ-BOM-02 — scrap/yield arithmetic & rounding:** the exact formula (divide-by-yield vs
  multiply-by-(1+scrap)) and where rounding lands in a multi-level roll-up. *Recommended default:*
  component scrap inflates as `qtyPer / (1 − scrap%)`, header yield inflates as `÷ yield%`, applied
  per level, with quantities carried at the 6-dp scale (the shipped quantity precision) and rounded
  only on presentation — fixed precisely in ADR-0026 D-4. Load-bearing because it affects every
  exploded quantity and the cost roll-up.
- **★ OQ-BOM-05 — does a SALE explode multi-level?** *Recommended default:* **no change to sale-time
  behaviour in v1** — sales keep single-level deduction (today's COGS exactly); multi-level
  sale-explosion is a separate, deliberate Stock/Sales decision (a flag the upgraded resolver
  supports but sales does not flip in this module). Load-bearing because flipping it silently would
  change COGS for every composed sale.
- **OQ-BOM-03 — ACTIVE-version editability:** are header metadata/notes editable on an ACTIVE version,
  or is the whole header frozen? *Recommended default:* component set frozen (BR-BOM-03); notes/
  description editable (audited). Owner may tighten to fully-frozen.
- **OQ-BOM-04 — where-used depth:** is full multi-level implosion (FR-BOM-15) in v1, or single-level
  (FR-BOM-14) only with full deferred? *Recommended default:* ship single-level where-used as the
  guaranteed minimum and full implosion as a best-effort derived endpoint reusing the explosion
  traversal inverted; mark full as "v1 if time permits, else fast-follow".
- **OQ-BOM-06 — clone-on-new-version:** when creating a new DRAFT version, does the system clone the
  prior ACTIVE version's components by default? *Recommended default:* yes, clone (the common case is
  a small change), with the option to start empty. Convenience only; not load-bearing.
- **OQ-BOM-07 — max-depth guard value:** the configurable maximum explosion depth (BR-BOM-11).
  *Recommended default:* 20 levels, config key `bom.max-explosion-depth`. Not load-bearing.
- **OQ-BOM-08 (deferred):** phantom blow-through, by-products/co-products, alternates/substitutes,
  engineering-vs-manufacturing BOM, approval-on-activation — all deferred (§2), none precluded by the
  v1 schema (NFR-BOM-07).

---

*This document is the business specification for Multi-level BOM. On owner go-ahead it becomes the
context source for ADR-0026 (the data model + the explosion/where-used design + the migration). It
does not specify schema; it fixes the behaviour the architect designs to.*

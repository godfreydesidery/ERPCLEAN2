# Requirements — Routes (sales areas / zones for external field agents)

> Status: **RATIFIED (owner-confirmed 2026-06-08).** The owner answered all eight scoping forks
> (route master shape; route↔customer cardinality; route↔agent cardinality + EXTERNAL-only; route↔branch
> filtering; free-text geography vs geo-hierarchy binding; route captured on the v1 invoice; permission
> set; numbering). Each ruling is reflected below as a fixed v1 requirement; everything not chosen has
> moved to the **Deferred** list (§2). **No ADR-0012-blocking open question remains.**
>
> Author: system-analyst · Domain: `routes` (master data). Business-level spec only. **No schema, no API
> shapes, no tables, no code** — those are the solutions-architect's, in **ADR-0012** (next step), plus a
> small additive Sales-invoice change (the nullable `route_id` snapshot, §9). Do not infer a data model
> from this document.
>
> **Depends on:** IAM (org → company → branch, permissions, `RequestContext`, audit), Parties (Agent —
> specifically the **EXTERNAL** agent kind, FR-PARTY-13; Customer, both sub-kinds; the
> `customer_branch` / `agent_branch` per-company multi-branch association pattern), the generic
> `code_sequence` document/master numbering (entity_kind `ROUTE`). Mirrors the **Products** master pattern
> (master + association + RBAC + audit, products.md / ADR-0007) and the **Parties** tenancy pattern.
> **Touches:** Sales (sales.md / ADR-0008) — adds a **nullable** `route_id` reference to the sales invoice
> (an additive cross-module change exactly like the per-product `vat_status` Sales added to Products, §9).

## 1. Business context & why now

The company sells **on the road**. Beyond counter sales, **external sales agents** (brokers — Parties
FR-PARTY-13) cover **geographic areas**: an agent works "the Kariakoo route", "the Kinondoni route", the
named zone where a cluster of customers physically resides. The business needs to **name those areas as
master data**, **say which customers fall in each**, and **say which external agent(s) cover each**, so
that a field agent's territory is explicit, a customer's area is recorded, and (this round) **the sale can
note which route it came from**.

Routes sit **beside Customer and Agent as a sibling master** (Parties). They are not a new kind of party
— they are a **named physical area/zone** that customers belong to and external agents are assigned to.
The dependency chain is settled: IAM gave the scoping spine (company → branch → permission); Parties gave
the **Customer** (with region/district/physicalAddress) and the **EXTERNAL Agent** that Routes connect;
Sales gave the **invoice** the route is now captured on. So Routes is specified now — after Parties and
Sales, before any route-coverage reporting (deferred) — to close the "which area / which territory" gap
the field-sales model needs.

What Routes is **not**: it is **not** the customer's `region`/`district` (those are existing free-text
contact fields on the Customer, parties.md FR-PARTY-15). A route is a **separate, explicit assignment** —
a customer's region/district describe *where their address is*; a route says *which selling zone they have
been grouped into*. A customer in Kinondoni district may sit on the "Kinondoni A" route, the "Kinondoni
North" route, or both — the route is a curated sales grouping, **not** derived from the address.

### Vocabulary distinction (read this first)

- **Route** — a per-company master record naming a **physical sales area / zone** where customers reside
  and along which external agents sell. A route is **not** a party, **not** a branch, and **not** the
  customer's region/district. Canonical term: *route*.
- **Route ↔ Customer membership** — the **many-to-many** assignment of customers to a route (a customer
  may belong to several routes; a route holds many customers). An explicit, curated grouping — **not**
  auto-derived from the customer's address.
- **Route ↔ Agent assignment** — the **many-to-many** assignment of **EXTERNAL** agents to a route (an
  external agent covers several routes; a route may be covered by several external agents). **INTERNAL
  agents are never route-assigned.**
- **Primary agent (of a route)** — an **optional, advisory** designation of one external agent as the
  route's main coverer. Used to **default the route onto an invoice** from the selling agent (§9). It is a
  hint, not an exclusivity rule — other assigned agents still cover the route.
- **Route ↔ Branch association** — the per-company **multi-branch** association making a route visible/
  usable at given branches (mirrors `customer_branch` / `agent_branch`). A route can **span branches**.
- **Location identifier / area label** — the route's **free-text** description of the area it covers
  (e.g. "Kariakoo market block 3–7, Mchikichini side"). **Not** structurally bound to region/district or
  any geo-hierarchy in v1 (that binding is a deferred additive option, §2).
- **Route on the invoice** — a **nullable** reference recorded on a sales invoice noting which route the
  sale came from; **defaulted from the selling agent's primary route, editable by the operator, optional**
  (a sale is **never** blocked when the route is blank). Captured, **not** validated against the customer
  in v1 (accepted risk, §10).

## 2. Scope

> Every line below is **ratified v1** (owner-confirmed 2026-06-08).

### In scope (v1 — "name the areas, group the customers, assign the field agents, note the route on the sale")

- A **Route** master — create, view, list/search, update, **archive (soft-delete)**, restore — **scoped
  per company** and **associated to branches**, exactly mirroring the Customer/Agent/Product master.
  Carries: a **code** (`ROUTE-####` via `code_sequence`), a **name**, a **free-text location identifier /
  description**, a **MasterStatus** (`ACTIVE` / `INACTIVE` / `ARCHIVED`), and audit.
- **Route ↔ Customer = MANY-TO-MANY.** A customer may belong to **several** routes; a route holds many
  customers. **All customers are routable** — both **cash / walk-in** and **credit / account** sub-kinds
  (Parties FR-PARTY-06). Assign/unassign customers to/from a route.
- **Route ↔ Agent = MANY-TO-MANY, EXTERNAL agents ONLY.** An external agent (Parties FR-PARTY-13) may be
  assigned to several routes; a route may have several external agents. **INTERNAL agents cannot be
  route-assigned** (BR-ROUTE-02). One assigned external agent may be flagged the route's **optional,
  advisory primary agent**.
- **Route ↔ Branch association** — a route is **company-owned and branch-filtered**: it can **span
  branches** and is visible/selectable only at its associated branches, exactly mirroring
  `customer_branch` / `agent_branch` (Parties FR-PARTY-10/11/12). Add/remove a route's branches.
- **Geography = free-text area label / location identifier**, **not** structurally bound to
  region/district or any geo-hierarchy. The customer's existing `region`/`district` fields (Parties
  FR-PARTY-15) are **not** the route — route membership is a **separate explicit assignment**.
- **Route captured on the v1 sales invoice** — a **NEW nullable** `route` reference on the sales invoice,
  **defaulted from the selling agent's primary route, editable by the operator, OPTIONAL/nullable** (the
  sale is never blocked if the route is blank). A small **additive** cross-module change to Sales
  (§9), designed in ADR-0012 + a tiny Sales addition (mirrors how `products.vat_status` was added for
  Sales).
- **Per-company scope + the tenancy invariant on every read path** (`assertCanActIn` / the §3.2 tenant
  predicate), **audit on every mutation**, mirroring Products/Parties.
- **Permissions:** `ROUTE.VIEW` / `ROUTE.MANAGE` / `ROUTE.ASSIGN` (mirroring `PRODUCT.*` and
  `PARTY.BRANCH.ASSIGN`).
- **Numbering:** `ROUTE-####` **per company** via the generic `code_sequence` (entity_kind `ROUTE`).

### Deferred (recognised, NOT built in v1)

- **Enforcing agent-must-match-customer's-route at sale time** — v1 **captures** the route on the invoice
  but does **NOT** validate that the selling agent (or the route) matches the customer's route memberships.
  **Captured-not-validated** is an explicit accepted risk (§10).
- **Route coverage / agent-performance / sales-by-route reporting** — analytics over which routes were
  served, by whom, with what sales. The v1 captured route is the *input* such reporting will later
  consume; the reports themselves are a later round.
- **Route scheduling / visit days / journey plans** — assigning days/frequencies to a route, planning an
  agent's visit calendar.
- **GPS / coordinates / geo-hierarchy + region-district binding (`route_geography`)** — binding a route to
  structured geography (region → district → ward) or to map coordinates. v1 geography is **free-text
  only**; a structured binding is a future **additive** option.
- **Route sequencing / optimisation** — ordering customers within a route for an efficient visit path.
- **Van-stock / route-stock** — stock carried on a vehicle along a route (a Stock-module concern).

### Explicitly NOT this module

- **The customer's address (region / district / physical address)** — owned by Parties (FR-PARTY-15); a
  route is a separate grouping, never derived from these.
- **The agent master itself** (internal/external kind, identity, commission) — owned by Parties
  (FR-PARTY-03/13); Routes only *assigns* existing **external** agents.
- **Commission, sales targets, agent performance** — Sales / Finance; Routes records territory, not pay.
- **Stock carried along a route / vehicle inventory** — a future Stock concern (deferred above).

## 3. The route and its associations

### 3.1 v1 concepts (built now)

| Concept | One-line definition | Notes |
|---|---|---|
| **Route** | A per-company named physical sales area / zone. | Sibling master to Customer/Agent. Code `ROUTE-####`, name, free-text location, MasterStatus, audit. |
| **Location identifier** | The route's free-text description of the area it covers. | Not bound to region/district or any geo-hierarchy in v1. |
| **Route ↔ Customer membership** | The N:M assignment of customers to a route. | All customers routable (cash/walk-in + credit/account). A customer may be on several routes. |
| **Route ↔ Agent assignment** | The N:M assignment of **external** agents to a route. | INTERNAL agents excluded. Optional, advisory **primary agent** per route. |
| **Primary agent** | Optional advisory main coverer of a route. | Used to **default** the route onto an invoice from the selling agent (§9). Not exclusivity. |
| **Route ↔ Branch association** | The N:M association making a route visible/usable at branches. | Mirrors `customer_branch` / `agent_branch`; a route can span branches. |
| **Route on the invoice** | A nullable route reference on a sales invoice. | Defaulted from the selling agent's primary route; editable; optional. Captured, not validated (§10). |

### 3.2 Deferred concepts (recognised, NOT in v1)

Agent-must-match-customer's-route validation at sale time · route coverage / agent-performance /
sales-by-route reporting · route scheduling / visit days / journey plans · GPS/coordinates/geo-hierarchy +
region-district binding (`route_geography`) · route sequencing/optimisation · van-stock / route-stock.
Named here so the vocabulary is captured; **do not build** records or behaviour for them in v1.

## 4. Actors / personas

- **Route administrator / sales manager** — creates and maintains routes, writes the location
  description, **assigns customers** to routes, **assigns external agents** to routes (and flags a route's
  optional primary agent), manages a route's branch associations, archives obsolete routes. Holds
  `ROUTE.MANAGE` (+ `ROUTE.ASSIGN` for the assignment operations). Acts within the company/branch scope
  their IAM roles permit.
- **External field (sales) agent** — the **subject** of route assignment, **not** an operator: an outside
  broker (Parties external agent) who covers one or more routes and sells along them. An external agent
  **does not log in** (Parties FR-PARTY-13). Their assigned routes (and a route's primary-agent flag) drive
  the route default on invoices they sell (§9).
- **Branch operator (sales clerk / cashier)** — selects an existing route when needed on a sale within
  their active branch; sees only routes associated with their active branch; accepts or edits the
  auto-defaulted route on an invoice. Holds `ROUTE.VIEW` (route selection) alongside their `SALES.*`
  permissions.

## 5. Functional requirements

> IDs are `FR-ROUTE-NN`. Each is a crisp, testable, **ratified** statement. "Route" = a route master
> record unless an association is named.

### Core record & lifecycle

- **FR-ROUTE-01** The system maintains a **Route** master: create, view, list/search, update, **archive
  (soft-delete)**, and restore. A route carries a **code**, a **name**, a **free-text location
  identifier / description**, a **status**, and audit — independently of any customer, agent, or
  transaction. Mirrors the Customer/Agent/Product master (parties.md FR-PARTY-01, products.md FR-PROD-01).
- **FR-ROUTE-02** Each route is **soft-deletable**: archiving sets its status `ARCHIVED` (the standard
  `ACTIVE` / `INACTIVE` / `ARCHIVED` MasterStatus lifecycle) without destroying history; an **archived
  route is excluded from selection** on new assignments and on new invoices (BR-ROUTE-07) but remains on
  historical invoices and remains restorable (mirrors BR-PROD-10 / BR-PARTY-09).
- **FR-ROUTE-03** A route carries a **free-text location identifier / description** of the area it covers.
  This is the v1 geography: **not** structurally bound to the customer's region/district nor to any
  geo-hierarchy or coordinates (BR-ROUTE-08; geo-hierarchy binding deferred, §2).

### Route ↔ Customer (many-to-many)

- **FR-ROUTE-04** A route is associated with **zero or more customers**, and a customer may belong to
  **several routes** — a **many-to-many** *business association* (describe as a relationship, not a
  table). **All customers are routable**, both **cash / walk-in** and **credit / account** sub-kinds.
- **FR-ROUTE-05** A route administrator (with `ROUTE.ASSIGN`) can **browse a route's customer memberships
  and add or remove customers** (within the route's company), so a customer becomes grouped into, or
  removed from, the route.
- **FR-ROUTE-06** Route↔customer membership is an **explicit, curated assignment** — it is **NOT**
  auto-derived from the customer's `region`/`district` or address (Parties FR-PARTY-15). Setting a
  customer's address does **not** assign them to any route, and assigning a route does **not** change the
  customer's address.

### Route ↔ Agent (many-to-many, EXTERNAL only)

- **FR-ROUTE-07** A route is assigned **zero or more EXTERNAL agents**, and an external agent may cover
  **several routes** — a **many-to-many** *business association*. **Only agents whose kind is EXTERNAL**
  (Parties FR-PARTY-13) may be assigned to a route; **INTERNAL agents cannot be route-assigned**
  (BR-ROUTE-02).
- **FR-ROUTE-08** A route administrator (with `ROUTE.ASSIGN`) can **browse a route's external-agent
  assignments and add or remove external agents** (within the route's company).
- **FR-ROUTE-09** A route may have **at most one optional, advisory PRIMARY external agent**, chosen from
  the route's assigned external agents. The primary flag is a **hint** (it drives the invoice route
  default, §9 / FR-ROUTE-13); it does **not** make the route exclusive — other assigned external agents
  still cover the route (BR-ROUTE-04).

### Route ↔ Branch (per-company, branch-filtered)

- **FR-ROUTE-10** Every route **belongs to exactly one company** and carries that company association; a
  route is never company-less and its company never changes by edit (BR-ROUTE-01, mirrors BR-PARTY-02 /
  BR-PROD-02). Route lists, searches, and selection are **scoped by company**.
- **FR-ROUTE-11** A route is **associated with one or more branches of its company** — a many-to-many
  association mirroring `customer_branch` / `agent_branch` (Parties FR-PARTY-10). A route can **span
  branches** and is visible/usable only at its associated branches. An administrator can **add or remove a
  route's branches** (within its company).
- **FR-ROUTE-12** Route **selection on a transaction is filtered by the active branch**: a branch operator
  selecting a route on a sale sees **only** routes associated with their active branch (and only their
  company's — FR-ROUTE-10), mirroring Parties FR-PARTY-12 / Products FR-PROD-22.

### Route on the sales invoice (additive Sales touch)

- **FR-ROUTE-13** The **sales invoice gains a NULLABLE route reference** (v1, NEW). When a sale is created,
  the route **defaults from the selling (external) agent's PRIMARY route** — the route on which that agent
  is flagged primary (FR-ROUTE-09). The default is **editable by the operator** and the route is
  **OPTIONAL/nullable**: a sale is **never blocked** when the route is blank (BR-ROUTE-05).
- **FR-ROUTE-14** The invoice route **cannot be auto-derived from the customer** (because route↔customer is
  many-to-many — a customer may be on several routes, FR-ROUTE-04 — so there is no single route to pick).
  It therefore defaults from the **agent's primary route** (FR-ROUTE-13); where the agent has no primary
  route (or is internal, or none is assigned), the invoice route simply defaults to **blank**, which is
  valid.
- **FR-ROUTE-15** v1 **captures** the route on the invoice but does **NOT validate** it against the
  customer's route memberships or against the agent's assigned routes — **captured-not-validated** (an
  explicit accepted risk, §10). The captured route is the input that the deferred route-coverage /
  sales-by-route reporting (§2) will later consume.

### Identification, numbering & search

- **FR-ROUTE-16** Each route has a human-usable **code unique within its company**, **`ROUTE-####`**,
  allocated **per company** from the generic **`code_sequence`** primitive (entity_kind `ROUTE`) — the same
  mechanism Products/Parties/Sales use (BR-ROUTE-06; ADR-0007 D-6). Routes do **not** mint a new
  per-module counter. The system supports **search by code and name**.

### Permissions (gating)

- **FR-ROUTE-17** All route operations are gated by IAM permissions: **`ROUTE.VIEW`** (view / list /
  search / select a route on a sale), **`ROUTE.MANAGE`** (create / edit / archive / restore a route and
  manage its branch associations), and **`ROUTE.ASSIGN`** (assign or unassign customers and external
  agents to/from a route, and set the primary agent) — mirroring `PRODUCT.VIEW` / `PRODUCT.MANAGE` and
  `PARTY.BRANCH.ASSIGN`. Exact codes are seeded with the module; this FR fixes only that route operations
  are permission-gated per IAM (FR-IAM-11).

## 6. Business rules (invariants)

- **BR-ROUTE-01** A route **belongs to exactly one company** and that company never changes by edit
  (re-homing a route is a new record in the other company, not an update). Mirrors BR-PARTY-02 /
  BR-PROD-02. **Route lists/searches/selection are company-scoped**; cross-company route data is
  forbidden (NFR-ROUTE-01).
- **BR-ROUTE-02** A **route-assigned agent must be EXTERNAL** (Parties FR-PARTY-13). An **INTERNAL agent
  cannot be assigned to a route**; attempting to assign one is rejected.
- **BR-ROUTE-03** A route's **assigned customers, assigned external agents, and associated branches must
  all belong to the SAME company as the route.** A route cannot be assigned a customer/agent of another
  company nor associated with a branch of another company (the tenancy invariant — mirrors BR-PARTY-01 and
  BR-PROD-06/09).
- **BR-ROUTE-04** A route has **at most one primary external agent**, and the primary, if set, **must be
  one of the route's assigned external agents**. The primary flag is **advisory** (it defaults the invoice
  route, FR-ROUTE-13) and **non-exclusive** — other assigned external agents still cover the route.
- **BR-ROUTE-05** The **invoice route is OPTIONAL/nullable and never blocks a sale.** It **defaults from
  the selling agent's primary route** (FR-ROUTE-13) and is **operator-editable**; a blank route is valid
  and finalisation must not require it. (It cannot be derived from the customer — FR-ROUTE-14.)
- **BR-ROUTE-06** A route's **code is unique within its company**, `ROUTE-####`, allocated per company from
  `code_sequence` (entity_kind `ROUTE`). Uniqueness is per company, not org-wide (mirrors BR-PARTY-08 /
  BR-PROD-08).
- **BR-ROUTE-07** An **archived (INACTIVE/ARCHIVED) route is not selectable** on new customer/agent
  assignments nor on new invoices, but **remains on historical invoices** and is **restorable** (mirrors
  BR-PARTY-09 / BR-PROD-10).
- **BR-ROUTE-08** A route's **geography is free-text only** in v1 — a location identifier / description,
  **not** structurally bound to the customer's region/district nor to any geo-hierarchy or coordinates. A
  customer's region/district (Parties FR-PARTY-15) is **never** read as, or written from, route membership.
- **BR-ROUTE-09** **A captured invoice route is not validated against the customer or the agent** in v1
  (FR-ROUTE-15) — it is recorded as supplied/defaulted. No code or downstream consumer may assume the
  invoice route matches the customer's or agent's route memberships (accepted risk, §10).

## 7. Process flows — ratified v1

### 7.1 Define a route and populate it (happy path)

1. Administrator (logged in, active branch, `ROUTE.MANAGE`) creates a **new route** in their active
   company: enters a **name** and a **free-text location identifier**; the route is numbered
   **`ROUTE-####`** (per-company `code_sequence`) and saved `ACTIVE`.
2. Administrator **associates the route with one or more branches** of the company (the route can span
   branches) so it becomes visible/selectable there (FR-ROUTE-11).
3. Administrator (with `ROUTE.ASSIGN`) **assigns customers** to the route (any sub-kind; many-to-many,
   FR-ROUTE-04/05) and **assigns EXTERNAL agents** (FR-ROUTE-07/08).
4. Administrator optionally **flags one assigned external agent as the route's primary** (advisory,
   FR-ROUTE-09) so sales by that agent default to this route.
5. **Audit** records the create and each assignment (NFR-ROUTE-03).

### 7.2 Route defaults onto a sale (happy path)

1. Operator starts a sale; the **selling agent** is attached (Sales FR-SALES-14) — for field sales this is
   an **external** agent.
2. If that agent has a **primary route** (FR-ROUTE-09) **associated with the active branch** (FR-ROUTE-12),
   the invoice **route defaults to it** (FR-ROUTE-13).
3. The operator **accepts or edits** the route, or **clears it** — the route is **optional** (BR-ROUTE-05);
   a blank route is valid.
4. The sale finalises **regardless of the route** (a blank or any route never blocks finalisation); the
   route is **captured on the invoice, not validated** (FR-ROUTE-15).

### 7.3 Main unhappy / edge paths

- **Assigning an INTERNAL agent to a route** (7.1 step 3) → **rejected**; only external agents are
  selectable for route assignment (BR-ROUTE-02).
- **Cross-company customer/agent/branch on a route** → **rejected**; all must share the route's company
  (BR-ROUTE-03).
- **Selling agent has no primary route** (or is internal, or none assigned) → the invoice route defaults
  to **blank**, which is valid; the sale proceeds (FR-ROUTE-14, BR-ROUTE-05).
- **Archived route on a new sale or assignment** → **not offered / rejected**; it stays on historical
  invoices and is restorable (BR-ROUTE-07).
- **Route blank at finalise** → **allowed**; finalisation never requires a route (BR-ROUTE-05).
- **Primary not among assigned agents** (e.g. the agent was unassigned) → the primary flag is cleared / the
  set is invalid until corrected (BR-ROUTE-04).

## 8. Non-functional

- **NFR-ROUTE-01** **Tenant isolation:** every route and every route association is scoped by `company_id`
  (and branch associations by branch) and goes through the tenant-predicate repository base / `assertCanActIn`
  on **every read path** (PROJECT-CONVENTIONS §3.2, ARCHITECTURE.md §5). Cross-company / cross-branch route
  leakage is a **release blocker**, as for IAM/Parties/Products.
- **NFR-ROUTE-02** **Responsiveness:** route list/search (by code, name) and the branch-filtered route
  pick-list at sale time must page and remain responsive as the route master grows (indexing detail is the
  architect's; the requirement is fast route lookup at point of sale).
- **NFR-ROUTE-03** **Audit:** route create / update / archive / restore, and **every assignment mutation**
  (add/remove a customer, add/remove an external agent, set/clear the primary agent, add/remove a branch),
  are written to the IAM append-only audit trail with actor, action, target, timestamp, and company/branch
  context (mirrors FR-IAM-23 / NFR-PROD-04). Audit rows are immutable.
- **NFR-ROUTE-04** **Forward-compatibility:** the v1 model must not preclude later (a) a structured
  geo-hierarchy / region-district binding or coordinates on a route (`route_geography`), (b) route
  scheduling/visit-day attributes, or (c) route-coverage / sales-by-route reporting over the captured
  invoice route. Building these is deferred; precluding them is a defect.
- **NFR-ROUTE-05** Timestamps are UTC, displayed per branch/company time zone (Africa/Dar_es_Salaam
  default, iam.md locale); route master screens meet WCAG 2.1 AA (axe gate), consistent with the other
  master-data admin screens.

## 9. Cross-module touch — the nullable invoice route (additive to Sales)

This round adds **one** additive field to the **Sales invoice**: a **nullable route reference**
(FR-ROUTE-13/14/15). This is the **same kind of additive cross-module change** that Sales itself made to
Products (the per-product `vat_status`, OQ-PROD-05) — a small, clean, non-breaking addition to an already
shipped/ratified document, **designed in ADR-0012 + a tiny Sales addition**, not a rework of Sales.

- The field is **nullable** (a sale never requires a route, BR-ROUTE-05) and **defaults from the selling
  agent's primary route** (FR-ROUTE-13), editable by the operator.
- It is a **snapshot reference** on the invoice (consistent with the invoice's other captured references,
  ADR-0008 D-2/D-3): the route the sale was attributed to at sale time, recorded for the future
  sales-by-route reporting (deferred, §2), **not** re-derived on read.
- It is **captured, not validated** against the customer or agent (FR-ROUTE-15, BR-ROUTE-09 — accepted
  risk §10).
- The exact column/snapshot shape, the additive migration, and the default-resolution service seam are the
  **architect's** in ADR-0012 (routes data model, the next migration version `V9`) plus the small Sales
  ALTER. The business contract is fixed here; the realisation is not.

## 10. ACCEPTED RISK — invoice route captured-not-validated in v1 (owner-accepted 2026-06-08)

> **Read this before building or consuming the invoice route.** A deliberate v1 omission, **explicitly
> accepted by the owner on 2026-06-08.** It is not an oversight; nobody may quietly assume otherwise.

**A v1 invoice route is CAPTURED but NOT VALIDATED — ACCEPTED RISK.** The route recorded on a sale is the
agent's primary route (defaulted) or whatever the operator selected; v1 does **NOT** check that this route
contains the sale's customer, nor that the selling agent is assigned to it. A sale may therefore carry a
route the customer is not a member of, or none at all (a blank route is valid). This is acceptable for v1
because the route is **informational** — the input to the **deferred** route-coverage / sales-by-route
reporting (§2), not a control on the sale. **No code, report, or downstream consumer may assume a v1
invoice route is consistent with the customer's or agent's route memberships.** When agent-must-match
validation and route reporting are prioritised (deferred, §2), the captured route is the data they build
on — so capturing it now is the right preparation, with validation deferred. The owner has signed off that
v1 ships with the route captured-not-validated.

This is reversible/additive by design (NFR-ROUTE-04); the v1 model does not preclude later validation.

## 11. Assumptions

- The dependency masters exist and are consumed via the established patterns (ADR-0006/0007): **Customer**
  (both sub-kinds, parties.md), the **EXTERNAL Agent** kind (FR-PARTY-13), the `customer_branch` /
  `agent_branch` multi-branch association pattern, and the generic `code_sequence` numbering primitive.
- The **selling agent on a sale** is the Sales-ratified mandatory agent attachment (Sales FR-SALES-14); the
  invoice-route default keys off that agent's **primary route** (FR-ROUTE-13). For an **internal** selling
  agent (no route assignment, BR-ROUTE-02) the invoice route simply defaults to blank — valid.
- "Branch-filtered, company-owned, can span branches" reuses the **exact** Parties branch-association
  semantics (FR-PARTY-10/11/12); Routes introduces no new branch-scoping concept.
- MasterStatus (`ACTIVE` / `INACTIVE` / `ARCHIVED`) and soft-delete behave as the standard master lifecycle
  (PROJECT-CONVENTIONS §3.2), identical to Parties/Products.

## 12. Open questions — status after ratification (2026-06-08)

> The owner closed all eight scoping forks (§ header). **No ADR-0012-blocking open question remains.** The
> resolved decisions are recorded in `docs/requirements/open-questions.md`. The items below are the only
> remaining detail notes, **all non-blocking** — recommended defaults stand and the architect may proceed
> with ADR-0012 now.

- **OQ-ROUTE-01** — **Primary-route default when an external agent is primary on MORE THAN ONE route.** An
  external agent could conceivably be flagged primary on several routes (the model permits it). Which route
  then defaults onto the invoice? *Recommended default:* if an agent is primary on exactly one route
  **associated with the active branch**, default to it; if zero or several qualify, default to **blank**
  (valid — the operator selects). *Decider:* owner. *Blocks ADR-0012:* **NO** — the blank-on-ambiguity
  default stands; the architect models to it (a one-route-per-agent-primary constraint could be added later
  additively if the owner prefers).
- **OQ-ROUTE-02** — **Route name uniqueness within a company.** The **code** is unique per company
  (BR-ROUTE-06); should the **name** also be unique per company, or may two routes share a name (distinct
  codes)? *Recommended default:* name **not** required unique (code disambiguates), matching Products/Parties
  where the code is the unique key. *Decider:* owner. *Blocks ADR-0012:* **NO** — additive constraint if
  later wanted.
- **OQ-ROUTE-03** — **Geo-hierarchy / region-district binding (`route_geography`).** Confirmed **deferred**
  (free-text only in v1, BR-ROUTE-08); recorded here so the future structured-geography round is a
  conscious decision, not an assumption. *Decider:* owner + architect at that round. *Blocks ADR-0012:*
  **NO** (deferred; not precluded — NFR-ROUTE-04).

## 13. Out of scope for v1 (deferred — restated)

Agent-must-match-customer's-route validation at sale time (route captured-not-validated, §10); route
coverage / agent-performance / sales-by-route reporting; route scheduling / visit days / journey plans;
GPS / coordinates / geo-hierarchy + region-district binding (`route_geography`, OQ-ROUTE-03); route
sequencing / optimisation; van-stock / route-stock. Each tracked for a later round; none precluded by the
v1 model (NFR-ROUTE-04).

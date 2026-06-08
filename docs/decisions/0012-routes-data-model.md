# 0012 — Routes data model: per-company route master, N:M to customers / EXTERNAL agents / branches, primary-route default onto the sales invoice

- **Status:** Accepted
- **Date:** 2026-06-08
- **Deciders:** solutions-architect (owner-ratified Routes requirements 2026-06-08 — all eight scoping forks closed; the
  three remaining OQ-ROUTE are non-blocking with owner-recommended defaults)
- **Context source:** [docs/requirements/routes.md](../requirements/routes.md) (RATIFIED 2026-06-08 — FR-ROUTE-01..17,
  BR-ROUTE-01..09, §10 accepted-risk on the captured-not-validated invoice route, the deferred list §2/§13, the
  non-blocking OQ log §12); [ADR-0006](0006-parties-data-model.md) (THE pattern to mirror — per-company masters,
  singular link tables with `assigned_at`/`assigned_by` and no uid, the `*BranchGuard` company-consistency,
  application-assigned numbering, `ScopeGuard.companyIdOf` target types, audit emits with plural `target_type` table
  names, permission seed + additive ORG_ADMIN grant, additive migration, uid/id + Long-as-string + `PageMeta`, the
  DB-can't / service-must split); [ADR-0007](0007-products-data-model.md) (the master + association recipe, the
  generic `code_sequence` with `entity_kind`, the partial-unique-index pattern `uq_product_barcode_primary`,
  `companyUid`-in-create-body, enriched DTOs); [ADR-0008](0008-sales-data-model.md) (the Sales invoice — `D-2`
  `sales_invoices` columns the `route_id` is ADDED to; the mandatory `agent_id` link; how the invoice resolves the
  agent at create; the additive `products.vat_status` ALTER that this ADR mirrors as the cross-module touch
  precedent); ADR-0005 (`Money`); ADR-0004 (audit emit points, `target_type`, JSONB `detail`); ADR-0002 (RBAC
  permission + scope, `ScopeGuard`); ADR-0001 (D-A tenancy, D-G uid/ULID + internal-table rule);
  [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) §2 (module layout), §3.2 (tenant predicate), §3.3 (uid/id);
  [DATA-MODEL.md](../../DATA-MODEL.md) (table style). Verified against the **shipped** SQL (ground truth, the prose
  doc was stale): [V2__parties.sql](../../backend/src/main/resources/db/migration/V2__parties.sql) (singular junction
  shape `customer_branch`; the partial-unique `uq_*` pattern; the permission seed + ORG_ADMIN grant pattern),
  [V3__products.sql](../../backend/src/main/resources/db/migration/V3__products.sql) (generic `code_sequence`
  `(company_id, entity_kind)`; `uq_product_barcode_primary ON product_barcodes (product_id) WHERE is_primary`),
  [V5__sales.sql](../../backend/src/main/resources/db/migration/V5__sales.sql) (the live `sales_invoices` header the
  `route_id` ALTER targets), and the latest shipped migration **V8__purchases.sql** — Routes therefore lands as
  **`V9__routes.sql`**. The `agents` table / `AgentDto` were confirmed to expose `agent_kind` (`AgentKind` INTERNAL |
  EXTERNAL), so the EXTERNAL-only rule and the per-agent primary route are modellable from the agent DTO.

This ADR is the **technical data model** for the Routes module. It translates the ratified business spec into tables,
columns, types, keys, indexes, constraints, and enforcement placement — concrete enough that the backend engineer
writes **`V9__routes.sql`**, the Routes entities, and the small additive Sales change **without guessing a business
rule**. It does **not** write production code, entities, or the migration — that is the engineer's next step. The
owner's ratified v1 decisions (a per-company route master; N:M route↔customer; N:M route↔EXTERNAL-agent with an
optional advisory primary route; N:M route↔branch mirroring `customer_branch`; free-text geography only; a nullable
invoice `route_id` defaulted from the selling agent's primary route, captured-not-validated; `ROUTE.VIEW`/`MANAGE`/
`ASSIGN`; `ROUTE-####` numbering) are taken as given and designed to exactly. **Nothing ratified is re-litigated.**

## Context

Routes is a **near-twin of Parties** (routes.md §1): a per-company master that sits beside Customer and Agent, names
a physical sales area/zone, groups customers, assigns external field agents, is branch-filtered, and now stamps the
sale with the route it came from. Everything it consumes already exists or is one additive change away: IAM gives the
tenant spine + `code_sequence` + audit + `ScopeGuard`; Parties gives `customers` (both sub-kinds) and the **EXTERNAL**
`agents` (the `agent_kind` discriminator, ADR-0006 D-5) plus the `customer_branch`/`agent_branch` junction pattern to
copy; Sales gives the `sales_invoices` header the route is captured on. The central architectural force is therefore
the same as ADR-0007/0008: **mirror the proven Parties patterns; resolve only the genuinely new modelling questions
Routes introduces.** Those new questions, and the forces around each:

- **Three N:M associations, not branch-only.** Parties has one junction per master (`*_branch`). A route has **three**
  N:M edges — to customers (FR-ROUTE-04), to EXTERNAL agents (FR-ROUTE-07), and to branches (FR-ROUTE-11). Each is a
  singular junction (DATA-MODEL convention), each needs a same-company guard (BR-ROUTE-03), and the agent edge adds
  two rules a plain junction does not have: **EXTERNAL-only** (BR-ROUTE-02) and **at-most-one-primary-route-per-agent**
  (BR-ROUTE-04). Resolved in D-3/D-4/D-5.

- **"Primary" is per-AGENT, not per-route, and it is the invoice-default seam.** The primary flag drives the invoice
  route default (FR-ROUTE-13). The brief and OQ-ROUTE-01 settle that the constraint worth enforcing at the DB is
  **an agent has at most one primary route** (so a single route defaults onto that agent's sales), not "a route has
  at most one primary agent". This is the exact partial-unique pattern `uq_product_barcode_primary` /
  `uq_branch_company_default` ship — applied to `(agent_id) WHERE is_primary`. Resolved in D-4.

- **The cross-module touch is a nullable `route_id` on `sales_invoices`, defaulted-from-agent, captured-not-validated.**
  This is the **same kind of additive change** ADR-0008 D-5 made to `products` (the `vat_status` ALTER): a small,
  clean, non-breaking column on an already-shipped table, plus a tiny service seam at invoice-create and a DTO
  enrichment. The route is **never validated** against the customer in v1 (BR-ROUTE-09, §10 accepted risk) and
  **never blocks a sale** (BR-ROUTE-05). Resolved in D-6.

- **Geography is free-text only.** No `route_geography`, no region/district binding, no coordinates (BR-ROUTE-08,
  OQ-ROUTE-03 deferred). A single free-text `location_identifier` column on the master; the structured-geography
  round is a clean additive future (NFR-ROUTE-04). Resolved in D-2.

- **Numbering reuses the shipped generic `code_sequence`.** `ROUTE-####` per company via `entity_kind = 'ROUTE'`
  (FR-ROUTE-16, BR-ROUTE-06) — no new numbering table, the identical mechanism Products/Sales/Purchases use. Resolved
  in D-7.

- **Schema freeze / migration ordering.** IAM=V1, Parties=V2, Products=V3, Units=V4, Sales=V5, Outbox=V6, Stock=V7,
  Purchases=V8 — all frozen. Routes is a **new** module landing as a purely **additive `V9__routes.sql`**; it must not
  edit V1–V8. The one cross-module additive touch is the nullable `route_id`, which V9 ALTERs onto the shipped
  `sales_invoices` table (D-6, D-11) — exactly the additive-ALTER precedent V5 set with `products.vat_status`.

## Decision

### D-1 — Module placement: one `com.erp.modules.routes` module; controller flat in `com.erp.api`

The route master and its three associations live in a **single** module `com.erp.modules.routes` with the standard
internal layout:

```
com.erp.modules.routes
├── domain.entity   Route, RouteCustomer, RouteAgent, RouteBranch (master + 3 link entities)
├── domain.dto      RouteDto, RouteSummaryDto, CreateRouteRequest, UpdateRouteRequest,
│                   RouteCustomerDto, RouteAgentDto, RouteBranchDto,
│                   AssignRouteCustomerRequest, AssignRouteAgentRequest, AssignRouteBranchRequest, …
├── domain.enums    (none new — reuses MasterStatus from platform.common.domain)
├── repository      RouteRepository, RouteCustomerRepository, RouteAgentRepository, RouteBranchRepository
└── service         RouteService(+Impl), RouteCodeGenerator (D-7, via code_sequence),
                    RouteAssignmentGuard (D-3/D-4/D-5 — same-company + EXTERNAL-only + primary-per-agent)
```

**Why `routes`, not folding it into `parties`:** Routes is a **sibling master** to Customer/Agent (routes.md §1), but
it is **not a party** — it is a named area that parties belong to. Folding it into `parties` would (a) bloat a module
that is already four masters, (b) put a route↔customer and route↔agent junction inside the party module that owns both
sides (a self-referential tangle), and (c) make the future route-coverage reporting (deferred §2) reach into
`parties` for an analytics concern. A dedicated `routes` module that **consumes** `parties.domain.dto` (Customer,
Agent) and IAM `Branch` via the established service/DTO boundary is the boring, boundary-clean choice (the exact
reasoning ADR-0006 D-1 used to keep masters out of Sales/Purchases). Controllers stay flat in `com.erp.api` —
`RouteController` — and touch only services (PROJECT-CONVENTIONS §2; `ModuleBoundaryTest`).

> **Boundary note for `ModuleBoundaryTest` (the cross-module edges, all DTO/scalar-id, no cycle):**
> - `routes` **reads** Parties (`CustomerService`/`AgentService` → `customers`/`agents` DTOs) to validate an
>   assignment target (same-company, agent-is-EXTERNAL) and IAM `Branch` (the existing tenant/scope spine) for
>   branch association. These reads go through **service-layer calls returning `*.domain.dto`** — `routes` never
>   imports `parties.*.entity` / `iam.*.entity` or their repositories. The cross-module references it **persists** are
>   **scalar `Long` id columns** (`customer_id`, `agent_id`, `branch_id`) with real DB FKs — the same
>   SQL-only-FK / no-cross-module-`@ManyToOne` convention `agents.app_user_id`, `sales_invoices.customer_id`, and
>   `audit_logs.actor_user_id` already use. No new boundary-allowlist entry is required (the same posture as Products
>   in ADR-0007 D-1 and Sales in ADR-0008 D-1).
> - **Direction of the Sales↔Routes edge:** **Sales depends on Routes**, not the reverse. The invoice-create default
>   (D-6) makes `sales` read the selling agent's primary route via a Routes service call (`RouteService` returning a
>   DTO / a scalar route id) and persist a scalar `route_id` FK. `routes` does **not** read `sales`. The edge is a
>   one-directional **DTO / scalar-id read** (`sales → routes`), the same shape as `sales → products` and
>   `sales → parties` — **no cycle** is introduced (Routes does not read Sales; Sales already sits above Parties and
>   Products in the dependency order, and Routes slots beside Parties as another master Sales consumes).

### D-2 — One master table `routes` (plural), `UidEntity`-style, per-company

A single master table **`routes`** extending the `UidEntity` shape (id + uid + version + audit columns + `status`),
plural per the shipped entity-table convention. Every row carries `company_id BIGINT NOT NULL` (FR-ROUTE-10,
BR-ROUTE-01) and participates in the §3.2 tenant predicate. There is **no `branch_id` on the master row** — a route
is company-scoped and *associated with many branches* via `route_branch` (D-5); a single `branch_id` would contradict
the many-to-many (FR-ROUTE-11, "a route can span branches"). This is the same per-table stance Parties/Products
documented: **company-scoped at the row, branch-scoped via association.**

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT GENERATED BY DEFAULT AS IDENTITY PK | NO | internal FK target |
| `uid` | VARCHAR(26) | NO | ULID; `uq_route_uid`; URLs address by uid (ADR-0001 D-G) |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant scope (FR-ROUTE-10, BR-ROUTE-01); **never updated** |
| `code` | VARCHAR(20) | NO | per-company sequence value `ROUTE-####` (D-7); `uq_route_company_code` |
| `name` | VARCHAR(200) | NO | route name shown on selection lists / documents (FR-ROUTE-01; search target FR-ROUTE-16) |
| `location_identifier` | VARCHAR(500) | YES | free-text area label / description (FR-ROUTE-03, BR-ROUTE-08); **not** bound to region/district or any geo-hierarchy in v1 |
| `status` | VARCHAR(32) | NO | `MasterStatus` ACTIVE \| INACTIVE \| ARCHIVED; DEFAULT `'ACTIVE'`; archive = soft-delete (FR-ROUTE-02, BR-ROUTE-07) |
| `version` | BIGINT | NO | optimistic lock, DEFAULT 0 |
| `created_at` / `created_by` / `updated_at` / `updated_by` | TIMESTAMPTZ / BIGINT | mixed | standard audit columns (`*_by` → `app_users.id`) |

**Constraints on `routes`:**
- `uq_route_uid UNIQUE (uid)` — ULID, global.
- `uq_route_company_code UNIQUE (company_id, code)` — code unique per company (BR-ROUTE-06); backstop for D-7.
- `fk_route_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `chk_route_status CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))` — `MasterStatus` as VARCHAR, the shipped
  enum-as-string convention (ADR-0006 D-3); the service gives the friendly message on a bad transition.

> **Geography decision (BR-ROUTE-08, OQ-ROUTE-03):** the area description is a single free-text
> `location_identifier VARCHAR(500)` column on the master — **no `route_geography` table, no region/district FK, no
> coordinates** in v1. The customer's existing `region`/`district` (Parties FR-PARTY-15) are **never** read as, or
> written from, route membership (BR-ROUTE-08, BR-ROUTE-06 of parties). When structured geography is later wanted
> (deferred §2, NFR-ROUTE-04), it is a clean **additive** migration (add a `route_geography` child or nullable
> `region_id`/`district_id` FKs alongside the text, backfill, tighten) — reserving the path costs nothing now.
> 500 chars matches the `products.description` width shipped in V3 (the established "free-text label" length).

> **Route name uniqueness (OQ-ROUTE-02):** the **code** is unique per company (`uq_route_company_code`); the **name**
> is **not** unique-constrained, matching Products/Parties where the code is the unique key and two records may share
> a display name. If the owner later wants name-unique-per-company, it is an additive partial unique
> (`CREATE UNIQUE INDEX uq_route_company_name ON routes (company_id, lower(name))`). **Not built in v1.**

### D-3 — `route_customer` junction (N:M), singular, no uid, same-company service guard

The N:M route↔customer membership (FR-ROUTE-04/05/06) is a **singular junction** mirroring `customer_branch` exactly
(no uid, `assigned_at`/`assigned_by`, `uq` pair, two-direction indexes). All customers are routable — both
`CASH_WALK_IN` and `CREDIT_ACCOUNT` (FR-ROUTE-04); the junction does not discriminate on customer kind.

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT GENERATED BY DEFAULT AS IDENTITY PK | NO | internal key |
| `route_id` | BIGINT | NO | FK → `routes(id)` |
| `customer_id` | BIGINT | NO | FK → `customers(id)` (scalar; same-company checked at assign-time, service, D-9) |
| `assigned_at` | TIMESTAMPTZ | NO | DEFAULT `now()` (when the customer was grouped into the route) |
| `assigned_by` | BIGINT | NO | FK → `app_users(id)` (who assigned) |

**Constraints / indexes:**
- **No `uid`, no `status`/`version`** (DATA-MODEL junction convention, same as `customer_branch`) — an association
  exists or is removed (hard delete of the link row is the "remove customer from route" op, FR-ROUTE-05).
- `uq_route_customer_pair UNIQUE (route_id, customer_id)` — a customer is on a route at most once.
- `fk_route_customer_route FOREIGN KEY (route_id) REFERENCES routes (id)`;
  `fk_route_customer_customer FOREIGN KEY (customer_id) REFERENCES customers (id)`;
  `fk_route_customer_by FOREIGN KEY (assigned_by) REFERENCES app_users (id)`.
- `ix_route_customer_route (route_id)` — list a route's customers (FR-ROUTE-05); and
  `ix_route_customer_customer (customer_id)` — "which routes is this customer on" (the other direction, and the input
  the deferred route-coverage reporting will consume).

**Same-company guard (BR-ROUTE-03) — DB FK + service guard, the established DB-can't / service-must split (ADR-0006
D-4):** SQL cannot cheaply assert `customer.company_id == route.company_id` (a cross-row subquery a plain FK can't
express; triggers rejected by the owner principle). The DB enforces the two halves it can (both FKs real,
`uq_route_customer_pair` no duplicates); the new **`RouteAssignmentGuard`** (in `routes.service`, modelled on
`PartyBranchGuard`/`ProductBranchGuard`) asserts on every customer-assign that `customer.company_id == route.company_id`,
else throws (mapped to 422/403), and delegates to `ScopeGuard.assertCanActIn(principal, route.companyId)` so the
caller may only manage assignments within their active company.

### D-4 — `route_agent` junction (N:M, EXTERNAL agents only) with `is_primary` (at-most-one-per-agent, partial unique)

The N:M route↔agent assignment (FR-ROUTE-07/08/09) is a singular junction, **EXTERNAL agents only** (BR-ROUTE-02),
carrying an advisory `is_primary` flag that drives the invoice route default (D-6, FR-ROUTE-13).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT GENERATED BY DEFAULT AS IDENTITY PK | NO | internal key |
| `route_id` | BIGINT | NO | FK → `routes(id)` |
| `agent_id` | BIGINT | NO | FK → `agents(id)` (scalar; **must be EXTERNAL** + same-company checked at assign-time, service, D-9) |
| `is_primary` | BOOLEAN | NO | DEFAULT false; advisory — the agent's primary route, used to default the invoice route (FR-ROUTE-09/13). **At most one true per agent** (partial unique below) |
| `assigned_at` | TIMESTAMPTZ | NO | DEFAULT `now()` |
| `assigned_by` | BIGINT | NO | FK → `app_users(id)` |

**Constraints / indexes:**
- **No `uid`, no `status`/`version`** (junction convention).
- `uq_route_agent_pair UNIQUE (route_id, agent_id)` — an agent is assigned to a route at most once.
- **`uq_route_agent_primary UNIQUE (agent_id) WHERE is_primary`** — a partial unique index enforcing **at most one
  primary route per agent** (BR-ROUTE-04, the constraint OQ-ROUTE-01 settles as worth DB-enforcing). This is the
  **exact shipped partial-unique pattern** (`uq_product_barcode_primary ON product_barcodes (product_id) WHERE
  is_primary`, V3; `uq_branch_company_default`, `uq_user_branch_default`, V1). It makes "one primary route per agent"
  a DB fact, so the invoice-default lookup (D-6) finds at most one row and never has to disambiguate.
- `fk_route_agent_route FOREIGN KEY (route_id) REFERENCES routes (id)`;
  `fk_route_agent_agent FOREIGN KEY (agent_id) REFERENCES agents (id)`;
  `fk_route_agent_by FOREIGN KEY (assigned_by) REFERENCES app_users (id)`.
- `ix_route_agent_route (route_id)` — list a route's agents (FR-ROUTE-08);
  `ix_route_agent_agent (agent_id)` — "which routes does this agent cover" + **the invoice-default lookup path**
  (D-6 reads `route_agent WHERE agent_id = :agent AND is_primary` — a single index probe; the partial unique above
  also covers it).

**Two service-enforced rules beyond the same-company guard (D-9), because no single-row CHECK can see the agent row:**
1. **EXTERNAL-only (BR-ROUTE-02).** A single-row CHECK on `route_agent` cannot read `agents.agent_kind` (cross-row).
   The `RouteAssignmentGuard` reads the agent (via `AgentService` DTO — `AgentDto.agentKind`, confirmed exposed) and
   **rejects an INTERNAL agent** (422). An INTERNAL agent is never offered for route assignment and is rejected if
   forced. This is the same cross-row, service-must placement BR-PARTY-10 used for the internal-agent active-user
   rule (ADR-0006 D-5).
2. **Primary-must-be-assigned + advisory non-exclusivity (BR-ROUTE-04).** Setting `is_primary = true` on a
   `route_agent` row is only valid for an agent **already assigned** to that route (the row must exist). The partial
   unique above guarantees the per-agent at-most-one; the service additionally **clears any prior primary** for that
   agent when a new one is set (so the operator "moves" the primary rather than colliding with the unique index), and
   **clears the primary flag if the agent is unassigned** from its primary route (§7.3 edge — primary not among
   assigned agents → cleared). The flag is **advisory and non-exclusive** — other assigned EXTERNAL agents still cover
   the route; `is_primary` only feeds the invoice default, it does not gate coverage.

> **Why per-AGENT primary, not per-ROUTE primary (the brief's chosen constraint):** the primary flag exists to
> **default the invoice route from the selling agent** (FR-ROUTE-13). The sale knows the *agent*, not the route; so
> the lookup is "this agent's primary route", which must resolve to **one** route for an unambiguous default. The DB
> constraint that guarantees a clean default is therefore "at most one `is_primary` per `agent_id`" — exactly
> `uq_route_agent_primary`. A per-route "one primary agent" constraint (`... WHERE is_primary` keyed on `route_id`)
> would **not** prevent an agent being primary on two routes, leaving the invoice default ambiguous — which is the
> very thing OQ-ROUTE-01's blank-on-ambiguity fallback exists to paper over. Enforcing per-agent makes the ambiguity
> structurally impossible for the common case; the OQ-ROUTE-01 fallback (D-6) remains as the belt-and-braces for the
> active-branch filter edge.

### D-5 — `route_branch` junction (N:M), mirror `customer_branch` exactly; same-company service guard

The per-company, branch-filtered association (FR-ROUTE-11/12) mirrors `customer_branch`/`agent_branch` **exactly** —
a route is company-owned, can span branches, and is selectable only at its associated branches.

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT GENERATED BY DEFAULT AS IDENTITY PK | NO | internal key |
| `route_id` | BIGINT | NO | FK → `routes(id)` |
| `branch_id` | BIGINT | NO | FK → `branches(id)` |
| `assigned_at` | TIMESTAMPTZ | NO | DEFAULT `now()` |
| `assigned_by` | BIGINT | NO | FK → `app_users(id)` |

**Constraints / indexes:**
- **No `uid`, no `status`/`version`** (junction convention, identical to `customer_branch`).
- `uq_route_branch_pair UNIQUE (route_id, branch_id)` — a route is associated with a branch at most once.
- `fk_route_branch_route FOREIGN KEY (route_id) REFERENCES routes (id)`;
  `fk_route_branch_br FOREIGN KEY (branch_id) REFERENCES branches (id)`;
  `fk_route_branch_by FOREIGN KEY (assigned_by) REFERENCES app_users (id)`.
- `ix_route_branch_route (route_id)` — list a route's branches (FR-ROUTE-11);
  `ix_route_branch_branch (branch_id)` — **the hot path:** "routes usable at my active branch" (FR-ROUTE-12, the
  branch-filtered route pick-list at sale time, NFR-ROUTE-02).

**Same-company guard (BR-ROUTE-03):** the `RouteAssignmentGuard` asserts on every branch-assign that
`branch.company_id == route.company_id`, the identical split as D-3 (DB FK + service cross-row check). **FR-ROUTE-12
selectability** ("usable only at associated branches") is a **selection-time query rule**, not a NOT NULL — a route
with zero branch associations is valid (parked) but appears in no branch's route pick-list; the selection query joins
`route_branch` and filters `routes.status = 'ACTIVE'` (BR-ROUTE-07 excludes archived):
`route_branch.branch_id = :activeBranch AND routes.company_id = :activeCompany AND routes.status = 'ACTIVE'`.

### D-6 — Sales invoice gains route: additive nullable `route_id` on `sales_invoices`, defaulted from the agent's primary route, captured-not-validated

The cross-module touch (FR-ROUTE-13/14/15, BR-ROUTE-05/09, routes.md §9) — the **same kind of additive change**
ADR-0008 D-5 made to `products` with `vat_status`. Three parts: the column, the create-time default behaviour, and
the DTO enrichment.

**(a) `sales_invoices.route_id` — an additive nullable column on the shipped `sales_invoices` table (clean ALTER, does
NOT break V5):**

```sql
ALTER TABLE sales_invoices
    ADD COLUMN route_id BIGINT;
ALTER TABLE sales_invoices
    ADD CONSTRAINT fk_sales_invoice_route
    FOREIGN KEY (route_id) REFERENCES routes (id);
```

- **Nullable** (a sale never requires a route — BR-ROUTE-05; a blank route is valid and never blocks finalisation).
  No `NOT NULL`, no `DEFAULT` — existing/draft invoices keep `route_id = NULL`, which is correct.
- A **scalar `Long` FK** to `routes(id)` (no cross-module `@ManyToOne` from `sales` into `routes` entities — the same
  SQL-only-FK convention `sales_invoices.customer_id`/`agent_id` already use, ADR-0008 D-1). The FK guarantees a
  recorded route is a real route; it does **not** (and must not) assert the route contains the customer — that is the
  accepted captured-not-validated risk.
- **No snapshot of route code/name onto the header** (unlike `product_code`/`product_name` on the line). The route is
  a soft-delete master (BR-ROUTE-07 — archived routes stay on historical invoices and are restorable, never hard
  deleted), so the FK + the route's own immutable `code` give an honest historical read without a snapshot column;
  the DTO enriches with the live route code/name (c). If the owner later wants a hard snapshot (e.g. a route could be
  *renamed*, and the printed invoice must show the name at sale time), it is an additive `route_code`/`route_name`
  column pair on the header — reserved, not built (the route is informational in v1, §10).

**(b) Default-from-agent-primary-route at invoice create (the service seam, FR-ROUTE-13/14):** on **draft create**,
after the selling `agent_id` is resolved (ADR-0008 D-6 — auto-defaulted to the operator's internal agent, or
operator-selected), the `SalesInvoiceServiceImpl` calls **`RouteService`** to find that agent's **primary route**
(`route_agent WHERE agent_id = :agent AND is_primary`) and, if exactly one qualifies **and it is associated with the
active branch** (FR-ROUTE-12) **and is ACTIVE** (BR-ROUTE-07), sets `route_id` to it. Per **OQ-ROUTE-01**, if **zero
or several** qualify (the agent is internal and has no route — BR-ROUTE-02; or has no primary; or the primary route is
not associated with the active branch), `route_id` defaults to **blank (NULL)** — valid, the operator selects. The
default is **operator-editable** (the operator may accept, change, or clear it) and the route is **OPTIONAL** — the
sale finalises regardless (BR-ROUTE-05, §7.2). The lookup is a **Routes service call returning a route DTO / scalar
id** (`RouteRepository` gains a projection such as `findActivePrimaryRouteIdForAgentAtBranch(companyId, agentId,
branchId)`), the same DTO/scalar-id cross-module read shape ADR-0008 D-6 used for the internal-agent-by-user default.

**(c) Captured-not-validated (BR-ROUTE-09, §10 ACCEPTED RISK):** v1 **captures** `route_id` on the invoice but does
**NOT validate** it against the customer's route memberships or the agent's assigned routes. The FK is the only
integrity the DB asserts (the route exists and is the same... — note: the service should assert the route is in the
**same company** as the invoice when an operator selects one, a cheap same-company guard via `RouteAssignmentGuard`;
but it does **not** check the customer is on that route). No code, report, or downstream consumer may assume a v1
invoice route is consistent with the customer's or agent's memberships. This is reversible/additive by design
(NFR-ROUTE-04) — agent-must-match validation and the route-coverage reporting that consumes the captured route are a
later round (§2).

**(d) DTO enrichment (ADR-0007 enriched-DTO precedent):** `SalesInvoiceDto` gains a **nullable** route reference,
enriched with the live route **uid + code + name** (e.g. `routeUid`, `routeCode`, `routeName`, all nullable) for
display, resolved by the service from the scalar `route_id` at read time (the same enrichment pattern ADR-0008 uses
for customer/agent display fields). `CreateSalesInvoiceRequest` / the line-or-header update request gains an optional
`routeUid` (String) the service resolves to the scalar id; omitting it accepts the (b) default, sending `null`
explicitly clears it. **No new endpoint** — the route rides on the existing invoice create/update payload, the same
way `agentUid` does.

> **Why this is an additive Sales touch, not a Sales rework (mirrors ADR-0008 D-5 on `products.vat_status`):** one
> nullable column + one FK on the header, one service seam at create, one DTO field + one request field. It does not
> change the invoice lifecycle, totals, numbering, or any existing constraint. It is recorded here and noted against
> ADR-0008 (the Sales doc reserved no slot for this, so this ADR is the authority for the `route_id` addition — the
> engineer ALTERs `sales_invoices` in **V9**, not by editing V5). Coordinate with whoever owns the Sales screen so
> the route pick-list (branch-filtered, FR-ROUTE-12) and the auto-default land together.

### D-7 — Per-company numbering: `ROUTE-####` via the shipped generic `code_sequence`, `entity_kind = 'ROUTE'`

Per-company route code (`ROUTE-0001`), unique per company, concurrency-safe (FR-ROUTE-16, BR-ROUTE-06). Reuse the
**shipped generic `code_sequence`** table (`(company_id, entity_kind)`, V3, ADR-0007 D-6) — **no new numbering
table**, only a new `entity_kind = 'ROUTE'` row allocated at runtime.

- **Allocation:** `RouteCodeGenerator.next(companyId, 'ROUTE')` does `SELECT … FOR UPDATE` on the `code_sequence` row
  for `(company_id, 'ROUTE')` (creating it with `next_value = 1` on first use), formats `ROUTE-%04d` (zero-padded,
  widening past 9999 naturally), increments, writes back — **inside the same transaction** as the route insert. The
  row lock serialises concurrent creates for the same company; different companies/kinds don't contend. This is the
  **identical mechanism** Products/Sales/Purchases ship (`code_sequence`, `entity_kind` ∈ {`PRODUCT`,
  `SALES_INVOICE`, `PURCHASE_ORDER`, `GOODS_RECEIPT`, …}); Routes adds **no new numbering table** — only the
  `'ROUTE'` kind. (Note: `code_sequence.entity_kind` is `VARCHAR(30)` with **no CHECK on the kind values** — unlike
  the parties-specific `party_code_sequence` — so `'ROUTE'` is added at runtime with no migration to the table.)
- **Backstop:** `uq_route_company_code` turns any generator bug into a constraint violation, not a silent duplicate
  (the same defence as every other master).
- **Code immutability:** `code` is assigned once and **not** user-editable; the service rejects updates to `code`
  (the document-reference key).

### D-8 — Enforcement split: DB enforces the unconditional/single-row, service enforces the cross-entity/cross-module

Consistent with ADR-0006 D-6 / ADR-0007 D-9 / ADR-0008 D-10 (the DB-can't / service-must split):

| rule | enforcement | mechanism |
| --- | --- | --- |
| BR-ROUTE-01 route belongs to one company; company never changes | **DB** | `company_id` NOT NULL + `fk_route_company`; service rejects re-home (never updates `company_id`) |
| FR-ROUTE-01/02 status ∈ {ACTIVE,INACTIVE,ARCHIVED}; soft-delete | **DB CHECK** | `chk_route_status`; archive sets `status='ARCHIVED'` (no row delete) |
| BR-ROUTE-06 code unique per company; `ROUTE-####` | **DB + service** | `uq_route_company_code` + `RouteCodeGenerator` on `code_sequence` (D-7) |
| FR-ROUTE-04 route↔customer at most once | **DB** | `uq_route_customer_pair` |
| FR-ROUTE-07 route↔agent at most once | **DB** | `uq_route_agent_pair` |
| FR-ROUTE-11 route↔branch at most once | **DB** | `uq_route_branch_pair` |
| BR-ROUTE-04 at most one primary route per agent | **DB** | `uq_route_agent_primary` partial unique `(agent_id) WHERE is_primary` |
| BR-ROUTE-02 route-assigned agent must be EXTERNAL | **service** | `RouteAssignmentGuard` reads `AgentDto.agentKind`; rejects INTERNAL (cross-row, no CHECK can see the agent) |
| BR-ROUTE-04 primary must be an assigned agent; advisory/non-exclusive; clear-on-unassign | **service** | `RouteAssignmentGuard`: row must exist; clear prior primary on set; clear flag on unassign |
| BR-ROUTE-03 assigned customer/agent/branch same company as route | **service** | `RouteAssignmentGuard` cross-row company check on every assign (+ `ScopeGuard.assertCanActIn`) |
| BR-ROUTE-07 archived route not selectable on new assignment/sale | **service / query** | assign + selection queries filter `routes.status = 'ACTIVE'`; archived stays on historical invoices |
| BR-ROUTE-08 geography free-text only | **by design** | one `location_identifier` column; no `route_geography`, no region/district FK |
| FR-ROUTE-13 invoice route defaults from agent's primary route | **service** | `SalesInvoiceServiceImpl` create-seam reads `RouteService` (D-6b) |
| BR-ROUTE-05 invoice route optional; never blocks a sale | **DB + service** | `sales_invoices.route_id` nullable (D-6a); finalise never requires it |
| BR-ROUTE-09 invoice route captured-not-validated | **by design** | FK only (route exists, same company on operator-select); **no** customer-membership check (accepted risk §10) |

**Tenant isolation (NFR-ROUTE-01):** every route and every route association is scoped by `company_id` (branch
associations by branch) and goes through the tenant-predicate repository base / `assertCanActIn` on **every read
path** (PROJECT-CONVENTIONS §3.2). Cross-company / cross-branch route leakage is a **release blocker**, as for
IAM/Parties/Products. The **`assertCanActIn`-on-every-read-path** discipline (the #1 anti-regression guard carried in
from earlier modules) applies to every Routes read — list, search, the branch-filtered pick-list, and the
invoice-default lookup.

### D-9 — `ScopeGuard.companyIdOf`: add the `route` target type

The 2-arg `@perm.scoped(#uid, 'route', 'ROUTE.MANAGE')` gate needs `ScopeGuard.companyIdOf` to resolve a route uid to
its company — exactly the extension ADR-0006 D-10 / ADR-0007 D-10 / ADR-0008 D-10 made for `customer`/`agent`/
`product`/`invoice`/… The engineer adds:

```java
case "route" -> routes.findCompanyIdByUid(uid);
```

backed by a single-column JPQL projection on `RouteRepository`
(`@Query("SELECT r.companyId FROM Route r WHERE r.uid = :uid")`), mirroring the existing cases. This adds a
`RouteRepository` constructor dependency to `ScopeGuard` — the same cross-cutting-spine pattern already accepted for
the product/party/unit/invoice repositories (`ScopeGuard` is the security spine, ArchUnit-allowed; ADR-0002 /
ADR-0006 D-10). **Not optional** — without it the target-uid gates fail closed. The three junctions are addressed
**under** their route uid in the API (`/routes/uid/{uid}/customers`, `/.../agents`, `/.../branches`), so they need no
own target type — the gate resolves on the parent route uid.

### D-10 — Permission catalogue additions (seeded in V9, module `routes`)

| code | module | description |
| --- | --- | --- |
| `ROUTE.VIEW` | routes | View, list, search and select routes (incl. the branch-filtered route pick-list on a sale) |
| `ROUTE.MANAGE` | routes | Create, update, archive and restore routes; manage a route's branch associations |
| `ROUTE.ASSIGN` | routes | Assign/unassign customers and external agents to/from a route; set/clear the primary agent |

- **`ROUTE.VIEW` / `ROUTE.MANAGE` / `ROUTE.ASSIGN`** mirror `PRODUCT.VIEW`/`PRODUCT.MANAGE` and `PARTY.BRANCH.ASSIGN`
  (FR-ROUTE-17). `ROUTE.MANAGE` bundles the master lifecycle **and** branch associations (branch association is a
  master-admin act, the same reasoning ADR-0007 folded branch-assign behaviour under the master's manage where
  appropriate); `ROUTE.ASSIGN` is the **separate** customer/agent assignment + primary-flag permission (the route
  administrator with `ROUTE.ASSIGN`, routes.md §4). If the owner later wants branch-assign split out as its own
  permission (parallel to `PARTY.BRANCH.ASSIGN`), it is an additive seed — **not built in v1** (flagged below).
- **Seeding (V9, idempotent):** `INSERT INTO permissions (code, module, description) VALUES (...) ON CONFLICT (code)
  DO NOTHING`, then the additive `INSERT INTO role_permission SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
  WHERE r.code = 'ORG_ADMIN' AND p.module = 'routes' ON CONFLICT DO NOTHING` — the **exact** V2/V3/V5/V8 pattern.
- **Gate shapes (ADR-0002, mirroring ADR-0007 D-11):**
  - `POST /routes` (create) → `@PreAuthorize("@perm.scoped(#request.companyUid, 'company', 'ROUTE.MANAGE')")`
    (active company is the target — `companyUid` in the body, D-11).
  - `PUT /routes/uid/{uid}` (edit), `POST /routes/uid/{uid}/archive` / `/restore`, and the branch-association
    sub-resources → `@PreAuthorize("@perm.scoped(#uid, 'route', 'ROUTE.MANAGE')")`.
  - `POST /routes/uid/{uid}/customers` / `DELETE …`, `POST /routes/uid/{uid}/agents` / `DELETE …`,
    `POST /routes/uid/{uid}/agents/{agentUid}/primary` (set/clear) →
    `@PreAuthorize("@perm.scoped(#uid, 'route', 'ROUTE.ASSIGN')")`, plus `RouteAssignmentGuard` (same-company +
    EXTERNAL-only + primary-per-agent).
  - `GET /routes` (list/search) and `GET /routes/selectable` (the branch-filtered pick-list, FR-ROUTE-12) →
    `@PreAuthorize("hasAuthority('ROUTE.VIEW')")`, results scoped by the tenant predicate + active branch (D-5).
  - The **Sales side** of the invoice route (D-6) is gated by the **existing** `SALES.INVOICE.CREATE`/`UPDATE`
    (selecting/clearing the route is part of editing the draft) plus `ROUTE.VIEW` to render the branch-filtered route
    pick-list — Routes adds no new Sales permission.

### D-11 — API / uid / companyUid discipline (mirror ADR-0007 D-12 / ADR-0008 D-12)

- **uids in URLs; ids (as JSON strings) in bodies for joins.** The route is addressed by uid (`/routes/uid/{uid}`);
  the three associations are addressed under it (`/routes/uid/{uid}/customers/{customerUid}`,
  `/.../agents/{agentUid}`, `/.../branches/{branchUid}`) — the junction row's own id is **not** exposed (junctions
  carry no uid, D-3/D-4/D-5; they are addressed by route-uid + target-uid). Assignment request bodies carry the
  target **uid** (`customerUid`, `agentUid`, `branchUid`) which the service resolves to the scalar `Long` id it
  persists.
- **`companyUid` (String) in the create body** (ADR-0007 D-12 / ADR-0008 D-12, the convention-consistent choice):
  `CreateRouteRequest` carries `companyUid`; the service resolves it and runs `ScopeGuard.assertCanActIn`. (This
  follows Products/Sales, **not** the Parties `companyId`-in-body wart, ADR-0007 D-12.)
- **`ApiResponse<T>` envelope** everywhere; list/search and the selectable pick-list paged via `PageMeta`
  (`page,size,totalElements,totalPages,hasNext`) — route lookup at point of sale is a paged read (NFR-ROUTE-02).
- **Enriched DTOs** (ADR-0007 precedent): `RouteCustomerDto` / `RouteAgentDto` / `RouteBranchDto` enrich the
  association with the target's **uid + code + display name** (customer code/name, agent code/name + `agentKind`,
  branch code/name) so the route-detail screens render without N round-trips; `RouteAgentDto` carries `isPrimary`.
  `RouteDto` may include cheap counts (customers / agents / branches) for the list screen, computed in the service.
- **Enums on the wire:** the string name (`ACTIVE`, `INACTIVE`, `ARCHIVED`); the agent kind surfaces via the enriched
  `RouteAgentDto.agentKind` (`EXTERNAL`).

### D-12 — Audit (ADR-0004): emit points and `target_type` strings (plural table names)

Routes' mutating service emits via the existing `AuditService.record(...)` (MANDATORY, same-TX, append-only —
NFR-ROUTE-03). `target_type` strings are the **plural / shipped table names** (the V2/V3/V5/V8 convention; the
`audit_logs` read filter reads naturally on the table name):

| action | target_type | when | detail (fact-only per ADR-0004 D-6) |
| --- | --- | --- | --- |
| `ROUTE.CREATE` | `routes` | on create | `code`, `name` |
| `ROUTE.UPDATE` | `routes` | on edit (name / location) | minimal/fact-only |
| `ROUTE.ARCHIVE` / `ROUTE.RESTORE` | `routes` | status transition | before/after `status` |
| `ROUTE.CUSTOMER.ADD` / `ROUTE.CUSTOMER.REMOVE` | `route_customer` | membership change | `customerUid` added/removed |
| `ROUTE.AGENT.ADD` / `ROUTE.AGENT.REMOVE` | `route_agent` | assignment change | `agentUid` added/removed |
| `ROUTE.AGENT.SETPRIMARY` / `ROUTE.AGENT.CLEARPRIMARY` | `route_agent` | primary flag set/cleared | `agentUid`, `isPrimary` |
| `ROUTE.BRANCH.ADD` / `ROUTE.BRANCH.REMOVE` | `routes` | branch association change | `branchUid` added/removed |

- **Every assignment mutation is audited** (NFR-ROUTE-03 names them explicitly: add/remove customer, add/remove
  external agent, set/clear primary, add/remove branch). The `target_type` is the **junction** table for
  customer/agent assignments (`route_customer`, `route_agent`) and the **master** for branch association
  (`routes` — matching how ADR-0006 D-12 audited `CUSTOMER.BRANCH.ADD` against `customers`, the master, since the
  branch link has no independent identity worth a separate target); this is a deliberate, minor consistency choice
  and may be normalised to `route_branch` if the owner prefers per-junction targets.
- **Profile-field edits are fact-only** (no old→new capture) per ADR-0004 D-6.
- **The invoice route (D-6) is audited on the Sales side, not here** — selecting/changing the invoice route is part
  of `SALES.INVOICE.CREATE`/`UPDATE` and is captured by Sales' existing `SALES.INVOICE.UPDATE` emit (ADR-0008 D-13,
  "customer/agent/discount/notes" — extend its fact-only detail to note a route change); Routes does not own that
  emit.
- **No outbox event in v1.** Routes has no cross-module async effect (no consumer reacts to "route assigned" /
  "route archived"). The invoice route default is a **synchronous** Routes read at invoice-create, not an event. If a
  later module needs "route archived → react", that is an additive `domain_event` (the outbox is shipped, V6/ADR-0009)
  under its own decision — not built now.

### D-13 — Migration: additive `V9__routes.sql`, never a V1–V8 edit

IAM=V1, Parties=V2, Products=V3, Units=V4, Sales=V5, Outbox=V6, Stock=V7, Purchases=V8 — all frozen. Routes is a
**new** module → purely **additive `V9__routes.sql`**. It **must not** edit V1–V8. Ordering within V9 (mirrors
ADR-0007 D-14 / ADR-0008 D-14):

1. **`routes`** (master) with `fk_route_company`, `uq_route_uid`, `uq_route_company_code`, `chk_route_status`.
2. **`route_customer`** (junction) — FKs to `routes`, `customers`, `app_users`; `uq_route_customer_pair`.
3. **`route_agent`** (junction) — FKs to `routes`, `agents`, `app_users`; `uq_route_agent_pair`.
4. **`route_branch`** (junction) — FKs to `routes`, `branches`, `app_users`; `uq_route_branch_pair`.
5. **`ALTER TABLE sales_invoices ADD COLUMN route_id BIGINT`** + `fk_sales_invoice_route FOREIGN KEY (route_id)
   REFERENCES routes (id)` (the additive cross-module touch, D-6a — placed **after** `routes` exists so the FK target
   is present; the only edit to an existing table, a pure additive nullable column with a FK, not a V5 rewrite).
6. **Indexes:** `ix_route_customer_route/customer`, `ix_route_agent_route/agent`, `ix_route_branch_route/branch`, the
   partial unique `uq_route_agent_primary ON route_agent (agent_id) WHERE is_primary`, and `ix_routes_company
   (company_id)` + `ix_routes_company_name ON routes (company_id, lower(name))` (search by name, FR-ROUTE-16;
   `uq_route_company_code` already indexes code lookup). Optional `ix_sales_invoices_route ON sales_invoices
   (route_id) WHERE route_id IS NOT NULL` (partial) for the future sales-by-route reporting (NFR-ROUTE-04) — cheap,
   reserves the read path.
7. **Permission seed + additive ORG_ADMIN grant** (D-10).

All FK targets (`companies`, `branches`, `app_users`, `customers`, `agents`, `sales_invoices`, `code_sequence`,
`roles`, `permissions`, `role_permission`) **already exist** in frozen V1–V8 — no dependency on un-frozen schema.
**No new numbering table** (`code_sequence` exists; Routes adds the `entity_kind = 'ROUTE'` row at runtime, D-7).
**No outbox table** (V6 owns it; Routes emits no event). **No trigger. No data seed** (routes are created by users;
no per-company default route to seed).

## Consequences

**Easier / safer:**
- **Tenant-safe and pattern-consistent from day one:** `routes` is `company_id`-scoped under the §3.2 predicate, all
  three associations are company-consistent by `RouteAssignmentGuard`, and the whole module is a faithful copy of the
  Parties master+junction recipe the codebase already proves — minimal new surface area, maximal reviewer familiarity.
- **The primary-route default is structurally unambiguous for the common case:** `uq_route_agent_primary (agent_id)
  WHERE is_primary` makes "one primary route per agent" a DB fact, so the invoice-default lookup (D-6) finds at most
  one route per agent; the OQ-ROUTE-01 blank-on-ambiguity fallback only covers the active-branch-filter edge.
- **EXTERNAL-only is enforced where it can see the agent** (service, reading `AgentDto.agentKind`) — an INTERNAL agent
  can never be route-assigned, and an internal selling agent simply yields a blank invoice route (valid, §7.3).
- **The Sales touch is genuinely additive** (one nullable FK column + one create-seam + one DTO/request field) — the
  invoice lifecycle, totals, numbering, and every existing constraint are untouched; the same low-risk shape the
  `products.vat_status` ALTER proved (V5).
- **Captured-not-validated is honoured deliberately:** the route is recorded for the deferred route-coverage
  reporting (§2) without pretending it is validated — the FK is the only integrity, and the partial index on
  `sales_invoices.route_id` reserves the reporting read path.
- **Numbering reuses the shipped `code_sequence`** (one new `entity_kind`, no new table) — concurrency-safe per-company
  `ROUTE-####`, the same mechanism four modules already share.
- **Routes stays decoupled** — it consumes `parties.domain.dto` (Customer, Agent) and IAM `Branch` via service/DTO,
  persists scalar-id FKs, adds one `ScopeGuard` case, and introduces **no** dependency cycle (Sales→Routes is a
  one-way DTO/scalar-id read).

**Harder / to watch:**
- **Three service-enforced cross-entity rules** (`RouteAssignmentGuard`: same-company on three junctions, EXTERNAL-only
  on the agent junction, primary-per-agent set/clear semantics) are the highest-discipline surfaces — no CHECK catches
  them. **Must** have unit/IT coverage: a customer/agent/branch of company B cannot be assigned to a route of company
  A (422/403); an INTERNAL agent cannot be route-assigned; setting a new primary clears the prior; unassigning a
  primary clears the flag. Flag to the test plan.
- **The invoice-default seam is the cross-module hot edge** (D-6b) — it must respect the active-branch filter
  (FR-ROUTE-12) and the ACTIVE-only rule (BR-ROUTE-07), and **fail open to blank** on any ambiguity (never throw,
  never block the sale — BR-ROUTE-05). An IT should assert: agent with one branch-associated primary route → defaulted;
  internal agent / no primary / primary-not-at-branch → blank; operator can override/clear.
- **Captured-not-validated must not be silently "fixed":** a future reviewer may be tempted to validate the invoice
  route against the customer's memberships. That is the **deferred** agent-must-match feature (§2) and is an explicit
  accepted risk in v1 (§10) — do not add the check without an ADR; the captured route is the *input* that round
  builds on.
- **`ScopeGuard` gains one more repository dependency** (`Route`) and one `companyIdOf` case — not optional; the
  target-uid gates fail closed without it (D-9).
- **The `route_id` FK does not assert same-company at the DB** (it cannot — cross-row); the service must same-company-
  guard an operator-selected route. A selected route of another company is a defect no FK catches — IT should assert
  it is rejected (the same set-once / cross-row discipline as every other association).

**Migration / delivery cost:**
- 1 additive Flyway file (`V9__routes.sql`): 1 master (`routes`) + 3 junctions (`route_customer`, `route_agent`,
  `route_branch`) = **4 new tables**, their FKs/uniques/CHECK, the partial unique `uq_route_agent_primary`, ~8
  indexes; **1 ALTER on `sales_invoices`** (+1 FK) for `route_id`; 3 permission rows + 1 additive ORG_ADMIN grant.
  **No** new numbering table, **no** outbox table, **no** trigger, **no** data seed.
- Backend: the `routes` entity set (1 master + 3 link entities + DTOs + 4 repositories + `RouteService`/Impl +
  `RouteCodeGenerator` on `code_sequence` + `RouteAssignmentGuard`); the `ScopeGuard` `route` case (D-9); the
  additive Sales change (`sales_invoices.route_id` entity field, `SalesInvoiceServiceImpl` create-default seam,
  `SalesInvoiceDto` route enrichment, `CreateSalesInvoiceRequest`/update request optional `routeUid`,
  `RouteRepository.findActivePrimaryRouteIdForAgentAtBranch` projection + `findCompanyIdByUid`).
- Web: a route master-admin screen (list/search, create/edit/archive/restore) with sub-screens for customer
  assignment, external-agent assignment (+ set-primary), and branch association (reusing the Parties/Products
  admin-screen patterns), WCAG 2.1 AA (NFR-ROUTE-05); plus a small additive change to the **Sales invoice screen** —
  a branch-filtered route pick-list (FR-ROUTE-12) that auto-defaults from the agent's primary route and is editable/
  clearable.
- Deployment risk: low — additive migration; the nullable `route_id` ALTER is safe on a populated `sales_invoices`
  (no default, no NOT NULL, existing rows stay NULL); no outbox infra to operate; no seed to run.

## Alternatives considered

- **Fold Routes into the `parties` module** (a fifth concern beside Customer/Supplier/Agent/Other). Fewer modules,
  reuses the parties spine directly. **Rejected (D-1):** a route is **not a party** (routes.md §1) — it is a named
  area parties belong to; putting the route↔customer and route↔agent junctions inside the module that owns *both*
  sides creates a self-referential tangle, and the deferred route-coverage reporting would reach into `parties` for an
  analytics concern. A dedicated `routes` module consuming `parties.domain.dto` is the boundary-clean choice (the same
  reasoning that kept masters out of Sales/Purchases, ADR-0006 D-1).

- **Per-ROUTE "one primary agent" constraint** (`uq_route_agent_primary ON route_agent (route_id) WHERE is_primary`)
  instead of per-AGENT. Reads naturally as "a route has one primary coverer". **Rejected (D-4):** the primary flag
  exists to **default the invoice route from the selling agent** — the lookup is "this agent's primary route", which
  must resolve to one route. A per-route constraint does **not** stop an agent being primary on two routes, leaving
  the invoice default ambiguous. The per-AGENT partial unique (`(agent_id) WHERE is_primary`) makes the default
  unambiguous by construction, which is what FR-ROUTE-13 needs. (The brief settles this; recorded for the six-months-
  out reader who wonders why the index keys on `agent_id`.)

- **A DB CHECK / trigger for EXTERNAL-only on `route_agent`.** Most "DB-true". **Rejected (D-4/D-8):** a single-row
  CHECK on `route_agent` cannot read `agents.agent_kind` (cross-row), and a trigger violates the owner principle
  ("operations live in the application," ADR-0004 D-5). The service guard reading `AgentDto.agentKind` is the
  established cross-row, service-must placement (BR-PARTY-10 / ADR-0006 D-5). The partial unique handles the
  single-row primary rule; the cross-row EXTERNAL-only rule stays in the service.

- **Snapshot the route code/name onto `sales_invoices`** (like `product_code`/`product_name` on the line). Honest
  historical print even if a route is renamed. **Rejected for v1 (D-6a):** routes are soft-delete masters (never hard
  deleted, BR-ROUTE-07), so the FK + the route's immutable `code` already give an honest read; the route is
  **informational** in v1 (§10), not a printed legal figure like a line price. The snapshot is a clean **additive**
  upgrade (a `route_code`/`route_name` column pair) if route renaming + at-sale-time route naming on the printout is
  later required. Reserved, not built — avoiding speculative columns.

- **Validate the invoice route against the customer's route memberships at create/finalise.** Catches a sale stamped
  with a route the customer is not on. **Rejected — explicitly deferred (D-6c, §10 accepted risk, BR-ROUTE-09):** v1
  is captured-not-validated by owner ruling; the route is the *input* the deferred agent-must-match + route-coverage
  reporting will build on. Adding the check now would pre-empt a deferred feature and break the accepted-risk
  contract. Reversible/additive by design (NFR-ROUTE-04).

- **A `route_geography` table / region-district binding now.** Structured territory. **Rejected — deferred
  (D-2, BR-ROUTE-08, OQ-ROUTE-03):** v1 geography is free-text only by owner ruling; a structured binding is a future
  additive option the v1 model does not preclude (NFR-ROUTE-04). A free-text `location_identifier` column is the
  boring, zero-ceremony choice the spec asks for.

## Open / flagged items (do NOT block the build; recommended defaults stand, all modelled to)

These are the three non-blocking detail OQ-ROUTE from routes.md §12 plus minor policy choices on top of this schema.
Each has a recommended default the architect has modelled to; none requires a schema change to confirm.

1. **OQ-ROUTE-01 — primary-route default when ambiguous.** Modelled to the recommended default: the **per-agent**
   partial unique (`uq_route_agent_primary`, D-4) makes one-primary-per-agent a DB fact; the invoice default (D-6b)
   additionally requires the primary route be **associated with the active branch** and **ACTIVE**, and **defaults to
   blank** if zero or several qualify (valid — the operator selects). **Blocks build:** NO — the model is built to the
   blank-on-ambiguity default.
2. **OQ-ROUTE-02 — route name uniqueness within a company.** Modelled to the recommended default: **name not unique**
   (the `code` disambiguates, matching Products/Parties); only `uq_route_company_code` constrains uniqueness. If the
   owner later wants name-unique-per-company, it is an additive partial unique (D-2). **Blocks build:** NO.
3. **OQ-ROUTE-03 — geo-hierarchy / region-district binding (`route_geography`).** Confirmed **deferred** (free-text
   only, D-2, BR-ROUTE-08); the v1 model does not preclude a future structured-geography round (NFR-ROUTE-04).
   **Blocks build:** NO.
4. **Minor — `ROUTE.MANAGE` granularity** (D-10): branch association is folded under `ROUTE.MANAGE`; customer/agent
   assignment + primary is `ROUTE.ASSIGN`. If the owner wants branch-assign split out (parallel to
   `PARTY.BRANCH.ASSIGN`), it is an additive seed. **Blocks build:** NO.
5. **Minor — invoice route snapshot** (D-6a): v1 records `route_id` only (no `route_code`/`route_name` snapshot on the
   header). If at-sale-time route naming on the printout is later required, the snapshot columns are additive.
   **Blocks build:** NO.
6. **Minor — audit `target_type` for branch association** (D-12): `ROUTE.BRANCH.ADD/REMOVE` is recorded against
   `routes` (the master, matching ADR-0006's `CUSTOMER.BRANCH.*`); may be normalised to `route_branch` if the owner
   prefers per-junction targets. **Blocks build:** NO.

No FR/BR is ambiguous enough to halt implementation; the items above are policy refinements and minor naming, all
defaulted here and overridable without a schema change.

## Summary

This ADR specifies the Routes data model as a per-company **`routes`** master (`UidEntity` shape, `ROUTE-####` via the
shipped generic `code_sequence`, free-text `location_identifier` geography, `MasterStatus` soft-delete) with **three
N:M singular junctions** — `route_customer` (all customer kinds), `route_agent` (**EXTERNAL agents only**, with an
advisory `is_primary` flag constrained **at most one per agent** by the shipped partial-unique pattern
`uq_route_agent_primary (agent_id) WHERE is_primary`), and `route_branch` (mirroring `customer_branch` exactly) — each
same-company-guarded in `RouteAssignmentGuard` (the DB-can't / service-must split). The cross-module touch is an
**additive nullable `route_id` on `sales_invoices`** (a FK, the only DB integrity) **defaulted at invoice-create from
the selling agent's primary route** (branch-filtered, ACTIVE, blank-on-ambiguity per OQ-ROUTE-01), **operator-editable,
optional, and captured-not-validated** (BR-ROUTE-09 / §10 accepted risk — never blocks a sale, never checked against
the customer) — the same additive shape `products.vat_status` proved in V5, with the route surfaced on
`SalesInvoiceDto` (enriched uid/code/name) and an optional `routeUid` on the create/update request. Enforcement is
placed deliberately (DB CHECK/unique for unconditional/single-row — status, code, the three pair uniques, the per-agent
primary; service guard for cross-row/cross-module — same-company, EXTERNAL-only, primary set/clear, the invoice
default). `ScopeGuard` gains one `route` case; permissions `ROUTE.VIEW`/`ROUTE.MANAGE`/`ROUTE.ASSIGN` are seeded with
an additive ORG_ADMIN grant; every assignment is audited; `assertCanActIn` gates every read path. The migration is a
single **additive `V9__routes.sql`** (4 new tables + 1 safe nullable ALTER on `sales_invoices` + indexes + permission
seed) that never edits V1–V8. **The model is ready for project-manager sequencing and backend build:** no
ADR-blocking question remains (the §12 OQ-ROUTE are policy values with defaults the model is built to), every FK
target already exists in frozen schema, the numbering / scope / partial-unique / junction primitives are reused not
reinvented, the one cross-module touch (the `sales_invoices.route_id` ALTER) is additive and named, and the
Sales→Routes dependency is a one-directional DTO/scalar-id read that introduces no cycle.

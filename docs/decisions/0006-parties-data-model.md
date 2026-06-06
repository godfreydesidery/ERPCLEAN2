# 0006 — Parties data model: separate Customer / Supplier / Agent masters, per-company, multi-branch

- **Status:** Accepted
- **Date:** 2026-06-06
- **Deciders:** solutions-architect (owner-ratified requirements + owner rulings on every open party decision)
- **Context source:** [docs/requirements/parties.md](../requirements/parties.md) (RATIFIED — FR-PARTY-01..20,
  BR-PARTY-01..13, §3 catalogue, §9 accepted-risk); ADR-0001 (D-A tenancy, D-G uid/ULID + internal-table rule),
  ADR-0002 (RBAC permission+scope, `ScopeGuard`), ADR-0004 (audit trail — emit points, `target_type`, `detail`),
  ADR-0005 (money is always `(amount, currency)`); [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) §2
  (module layout), §3.2 (multi-tenant predicate), §3.3 (uid/id); [DATA-MODEL.md](../../DATA-MODEL.md) (table style);
  V1 baseline + IAM entity patterns (`Company`, `Branch`, `UserBranch`, `UidEntity`).

This ADR is the **technical data model** for the Parties module. It translates the ratified business spec into
tables, columns, types, keys, indexes, constraints, and enforcement placement, concrete enough that the backend
engineer writes `V2__parties.sql` and the entities **without guessing a business rule**. It does **not** write
production code, entities, or the migration — that is the engineer's next step. The owner's confirmed decisions
(separate records, per-company scope, multi-branch association, internal/external agent, TZ identifiers,
individual/business typing, per-kind numbering, the v1 catalogue) are taken as given and designed to exactly.

## Context

The operational modules (Sales / Purchases / Stock) all transact with external actors. The party master must
exist before them. The owner has **ruled** on the previously-open modelling questions; the forces this ADR
resolves are now purely technical:

- **Separate records vs unified party-with-roles (D1, BR-PARTY-03).** Owner chose **separate, self-contained**
  Customer / Supplier / Agent masters — *not* one party table with role rows. This is load-bearing: it dictates
  three entity tables, three numbering sequences, three branch-association link tables, and three permission
  groups. The accepted cost (same legal entity diverging across records, no golden view) is recorded once in
  §9 of the spec and in Alternatives here; **not re-litigated**.
- **Per-company scope (D2, FR-PARTY-08/09, BR-PARTY-02).** Each party belongs to exactly one company and never
  re-homes. This mirrors IAM company-bound master data and means each party table carries `company_id` and
  participates in the §3.2 tenant predicate.
- **Multi-branch association (FR-PARTY-10/11/12, BR-PARTY-01/12).** A party associates with *many* branches of
  its company; a branch sees only its associated parties. The hard constraint: an associated branch **must**
  belong to the party's company. SQL FKs alone cannot assert "branch.company == party.company" cheaply, so the
  enforcement split between DB and service must be stated explicitly.
- **Internal vs external agent (D4, FR-PARTY-13, BR-PARTY-10/11).** An agent is `internal` (references an
  **active** IAM `app_user`) or `external` (standalone, **no** user link). The discriminator + the optional,
  conditionally-present user reference must be modelled, with the "must be active" rule placed correctly.
- **TZ identifiers + typing (FR-PARTY-14..18, BR-PARTY-04..07, BR-PARTY-13).** TIN, VRN (VAT-registered only),
  mobile-money, a registrar-agnostic business-registration number, plus contact. `party_type` (individual /
  business) and a `vat_registered` flag drive which identifiers are mandatory — and most of those rules are
  **conditional**, which Postgres can express only with `CHECK` constraints or service validation. The DB/service
  split must be deliberate, not accidental.
- **Per-kind numbering (D1, FR-PARTY-19, BR-PARTY-08).** Each kind numbers independently per company
  (`CUST-####`, `SUPP-####`, `AGENT-####`), unique per company per kind, safe under concurrency.
- **Schema freeze.** IAM V1 is frozen after Slice 6 (per V1 header + ADR-0004 D consequences). Parties is a
  **new** module and therefore lands as an **additive** `V2__parties.sql` — never a V1 edit.

## Decision

### D-1 — Module placement: one `com.erp.modules.parties` module (not split sales/purchasing)

The three masters live in a **single** module `com.erp.modules.parties`, with the standard internal layout:

```
com.erp.modules.parties
├── domain.entity      Customer, Supplier, Agent, OtherParty,
│                      CustomerBranch, SupplierBranch, AgentBranch, OtherPartyBranch (link entities)
├── domain.dto         CustomerDto, SupplierDto, AgentDto, OtherPartyDto, *BranchAssocDto, *CreateRequest …
├── domain.enums       PartyType, CustomerKind, SupplierKind, AgentKind, OtherPartyKind
├── repository         CustomerRepository, SupplierRepository, AgentRepository, OtherPartyRepository, link repos
└── service            CustomerService(+Impl), SupplierService(+Impl), AgentService(+Impl), OtherPartyService(+Impl),
                       PartyCodeGenerator (shared, D-7)
```

**Why one module, not `sales` + `purchasing`:** the four masters share an identical shape (identity / tax /
contact / typing / branch-association / numbering / audit) and an identical enforcement spine (tenant predicate,
`ScopeGuard`-style company-consistency, per-kind code generation). Splitting customers→sales and
suppliers→purchasing now would (a) duplicate that spine across two modules, (b) force a shared
`PartyCodeGenerator` and the company-consistency guard into `platform` prematurely, and (c) put the `OtherParty`
master nowhere clean. Sales and Purchases are **transaction** modules that will *consume* these masters via DTOs
(per the boundary rule); they are not the natural home of the master data itself. Keeping master data in one
`parties` module and letting Sales/Purchases depend on `parties.domain.dto` is the boring, boundary-clean choice
(PROJECT-CONVENTIONS §2). Controllers stay flat in `com.erp.api` (e.g. `CustomerController`, `SupplierController`,
`AgentController`, `OtherPartyController`) and touch only services.

> Boundary note for `ModuleBoundaryTest`: `parties` may read `iam.repository` **only** for the agent→user link
> validation, and that read should go through a **service-layer** call into IAM's existing user lookup, not a
> direct cross-module repository import. The internal-agent user check is the single cross-module dependency in
> this module (D-5); it must not become a precedent for `parties` reaching into IAM internals broadly. If a
> direct `AppUserRepository` read is unavoidable, it is added to the boundary allowlist as a named exception
> (the same mechanism ADR-0002 reserved for `platform.security` → `iam.repository`), with a one-line
> justification — not a silent relaxation.

### D-2 — Four entity tables (plural), `UidEntity`-style, per-company

Four master tables, all extending the `UidEntity` shape (id + uid + version + audit columns + `status`), all
plural per the entity-table convention: **`customers`**, **`suppliers`**, **`agents`**, **`other_parties`**.

Every row carries `company_id BIGINT NOT NULL` (FR-PARTY-08, BR-PARTY-02) and participates in the §3.2 tenant
predicate. There is **no `branch_id` column on the master row** — a party is company-scoped and *associated with
many branches* via a link table (D-4); a single `branch_id` would contradict the many-to-many. This is the
documented per-table multi-tenancy stance: **company-scoped at the row, branch-scoped via association.**

**Shared column block (every party master carries these):**

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal FK target |
| `uid` | VARCHAR(26) | NO | ULID; `uq_<table>_uid`; URLs address by uid |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant scope (BR-PARTY-02, never updated) |
| `code` | VARCHAR(20) | NO | per-kind sequence value, e.g. `CUST-0001` (D-7); `uq_<table>_company_code` |
| `party_type` | VARCHAR(20) | NO | enum `INDIVIDUAL` \| `BUSINESS` (FR-PARTY-17) |
| `display_name` | VARCHAR(200) | NO | the name shown on selection lists / documents (the always-present name, FR-PARTY-18) |
| `legal_name` | VARCHAR(200) | YES | registered/legal name for a business (FR-PARTY-18); null for individuals |
| `tin` | VARCHAR(20) | YES | Taxpayer Identification Number (FR-PARTY-14); mandatory-for-business is **service-enforced** (D-6) |
| `vat_registered` | BOOLEAN | NO | default false; gates whether `vrn` may be set (BR-PARTY-06) |
| `vrn` | VARCHAR(20) | YES | VAT Registration Number; null unless `vat_registered` (BR-PARTY-06); `uq` per company when present (BR-PARTY-13, D-8) |
| `business_reg_no` | VARCHAR(40) | YES | registrar-agnostic registration number (FR-PARTY-14, BR-PARTY-04); never BRELA-hard-coded; recommended-not-mandatory |
| `mobile_money_no` | VARCHAR(30) | YES | M-Pesa / Tigo Pesa / Airtel Money payment number (FR-PARTY-14) |
| `phone` | VARCHAR(40) | YES | contact (FR-PARTY-15) |
| `email` | VARCHAR(160) | YES | contact (FR-PARTY-15) |
| `physical_address` | VARCHAR(255) | YES | contact (FR-PARTY-15) |
| `postal_address` | VARCHAR(255) | YES | contact (FR-PARTY-15) |
| `region` | VARCHAR(80) | YES | contact (FR-PARTY-15) |
| `district` | VARCHAR(80) | YES | contact (FR-PARTY-15) |
| `status` | VARCHAR(32) | NO | `MasterStatus` ACTIVE \| INACTIVE \| ARCHIVED; archive = soft-delete (FR-PARTY-05, BR-PARTY-09) |
| `version` | BIGINT | NO | optimistic lock, default 0 |
| `created_at` / `created_by` / `updated_at` / `updated_by` | TIMESTAMPTZ / BIGINT | mixed | standard audit columns (`*_by` → `app_user.id`) |

**Per-table additions:**

- **`customers`** adds:
  - `customer_kind` VARCHAR(20) NOT NULL — enum `CASH_WALK_IN` \| `CREDIT_ACCOUNT` (FR-PARTY-06).
  - `credit_limit_amount` NUMERIC(19,4) NULL + `credit_limit_currency` CHAR(3) NULL — a **`Money` embeddable
    pair** per ADR-0005 D-1/D-2; **both null together or both set together** (CHECK), enforced structurally by
    the `Money` embeddable. Only meaningful for `CREDIT_ACCOUNT`; for `CASH_WALK_IN` it stays null. The
    currency defaults to the company base currency at write time (ADR-0005 D-4) — never a hard-coded `TZS`
    literal. **Credit-limit *enforcement* is deferred to Sales/Finance** (parties.md §10); this column only
    *records* the limit.
  - `payment_terms_days` SMALLINT NULL — optional payment-terms flag/value for credit customers (parties.md D8
    note "possibly payment terms (flag)"); recording-only in v1, no dunning behaviour. Null for walk-in.
- **`suppliers`** adds:
  - `supplier_kind` VARCHAR(20) NOT NULL — enum `GOODS` \| `SERVICE` (FR-PARTY-07).
- **`agents`** adds:
  - `agent_kind` VARCHAR(20) NOT NULL — enum `INTERNAL` \| `EXTERNAL` (FR-PARTY-13, D-5).
  - `app_user_id` BIGINT NULL — FK → `app_users(id)`; **set iff `agent_kind = INTERNAL`**, null iff `EXTERNAL`
    (CHECK + service guard, D-5). SQL-only scalar FK; no JPA association into IAM internals from a `@ManyToOne`
    that would drag IAM entities across the module boundary — model it as a `Long appUserId` scalar (the same
    convention `UserBranch.userId` / `audit_log.actor_user_id` already use). `uq_agent_app_user` per company is
    **not** imposed (the same staff member could legitimately be one agent record; but two internal agent
    records pointing at the same user in the same company is a smell — flagged as a service-level warning, not a
    hard unique, pending OQ-PARTY-03 commission-tier clarity).
- **`other_parties`** adds:
  - `other_kind` VARCHAR(40) NULL — a free-text/loosely-typed label (e.g. `LANDLORD`, `TRANSPORTER`) for the
    informal tracking of deferred party types (parties.md §3.1 Other/Misc); no enum constraint, because the
    whole point of Other is to not block on a fixed type list. Nullable.

> **Why four tables and not a shared `parties` table with a `kind` column:** the owner ruled separate,
> self-contained records (D1). A shared table re-introduces the unified-party model through the back door (shared
> code sequence, shared uniqueness, role rows) — exactly what was rejected. Four tables keep the masters
> decoupled, let each carry only its own kind/sub-kind and its own numbering, and keep the modules that consume
> them (Sales reads `customers`/`agents`, Purchases reads `suppliers`) cleanly separable later. The cost is real
> column duplication across four tables; it is the accepted price of D1 (Alternatives).

### D-3 — Enums stored as `VARCHAR`, not ordinals (consistent with `MasterStatus`)

`party_type`, `customer_kind`, `supplier_kind`, `agent_kind` are stored as `VARCHAR` of the enum **name**
(`@Enumerated(EnumType.STRING)`), matching the existing `MasterStatus` / `status` convention (stable across enum
reordering, legible in raw rows). Each gets a `CHECK (col IN (...))` constraint in the migration so the DB rejects
an out-of-range value even if a future code path bypasses the enum. Lengths: `party_type` VARCHAR(20),
`*_kind` VARCHAR(20), `other_kind` VARCHAR(40).

### D-4 — Branch association: one link table **per kind** (singular), company-consistency in the service

Per the separate-records model and the singular link-table convention, there are **four** association tables:

- **`customer_branch`** (`customer_id`, `branch_id`)
- **`supplier_branch`** (`supplier_id`, `branch_id`)
- **`agent_branch`** (`agent_id`, `branch_id`)
- **`other_party_branch`** (`other_party_id`, `branch_id`)

Each is a junction realising FR-PARTY-10/11 (many-to-many between a party and the branches of its company). Shape
(identical across the four; example for `customer_branch`):

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal key |
| `customer_id` | BIGINT | NO | FK → `customers(id)` |
| `branch_id` | BIGINT | NO | FK → `branches(id)` |
| `assigned_at` | TIMESTAMPTZ | NO | default `now()` (when the party became usable at this branch) |
| `assigned_by` | BIGINT | NO | FK → `app_users(id)` (who associated it) |

- **No `uid`** on link tables (DATA-MODEL convention: junctions/internal tables may omit uid; association rows
  are addressed via their party + branch uids in the API, not their own). **No `status`/`version`** — an
  association either exists or is removed (a hard delete of the link row is the "remove branch" operation,
  FR-PARTY-11), and the party master's own archive is the soft-delete that matters.
- **`uq_customer_branch_pair UNIQUE (customer_id, branch_id)`** — a party is associated with a branch at most
  once. Likewise `uq_supplier_branch_pair`, `uq_agent_branch_pair`, `uq_other_party_branch_pair`.
- **Indexes:** `ix_customer_branch_customer (customer_id)` (list a party's branches — FR-PARTY-11) and
  `ix_customer_branch_branch (branch_id)` (the hot path: "parties associated with my active branch" —
  FR-PARTY-12, point-of-sale selection). Mirror for the other three.

**Company-consistency (BR-PARTY-01) — DB FK + service guard, the split stated explicitly:**

SQL cannot cheaply assert "the associated branch's `company_id` equals the party's `company_id`" — that needs a
cross-row subquery a plain FK can't express (and a trigger is rejected by the owner principle "operations live in
the application," ADR-0004 D-5). Therefore:

- **DB enforces** the two halves it *can*: `branch_id` must reference a real branch (FK), and `customer_id` must
  reference a real customer (FK). The `uq_*_pair` prevents duplicate associations.
- **Service enforces** the cross-entity invariant: a new `PartyBranchGuard` (in `parties.service`, modelled on
  ADR-0002 `ScopeGuard`) asserts, on every association add, that `branch.company_id == party.company_id`, else
  throws a `ForbiddenException`/`ValidationException` (mapped to 422/403). This is the **same DB-can't /
  service-must** split ADR-0001 D-A and ADR-0002 D-4 already established for company-scope checks; it is *not* a
  new pattern. The guard also asserts the active-company scope (the caller may only manage associations within
  their active company) by delegating to the existing `ScopeGuard.assertActiveCompany(party.companyId)`.
- **BR-PARTY-12** ("usable only when associated with ≥1 branch") is a **selection-time** rule, not a DB NOT NULL:
  a party with zero associations is valid (parked) but appears in no branch's selection list. Enforced by the
  query in D-9 (selection joins the link table), not a constraint.

### D-5 — Agent kind: discriminator + conditional user link; "active user" is a service rule

- `agent_kind` (D-3) is the discriminator, set at creation, with a CHECK.
- `app_user_id` is **null for EXTERNAL, NOT NULL-equivalent for INTERNAL**, enforced by a DB CHECK:
  `CHECK ((agent_kind = 'INTERNAL' AND app_user_id IS NOT NULL) OR (agent_kind = 'EXTERNAL' AND app_user_id IS NULL))`.
  This makes BR-PARTY-11 (external must **not** reference a user) and the internal-must-reference half
  structurally true at the DB.
- **BR-PARTY-10 ("internal agent must reference an *active* user")** is **service-enforced**, not a DB
  constraint: the DB cannot watch `app_users.status` change after the agent is created. On agent create/update
  the `AgentServiceImpl` validates the referenced user exists, is in the same organisation, and is `ACTIVE`. On
  **selection for a new sale** (consumed later by Sales), the agent is excluded if its referenced user is no
  longer ACTIVE — "the agent record is not deleted" (BR-PARTY-10). The agent→user lookup goes through an IAM
  **service** call (D-1 boundary note), not a cross-module repository reach.

### D-6 — Mandatory-identifier rules: DB enforces the unconditional, service enforces the conditional

The FR/BR identifier rules are mostly **conditional on `party_type` / sub-kind / `vat_registered`**. Postgres
can express some as CHECK constraints, but conditional-mandatory ("business must have TIN") is cleaner and more
message-friendly in the service. The split:

| rule | enforcement | mechanism |
| --- | --- | --- |
| BR-PARTY-04 — **business must have TIN** | **service** | `*ServiceImpl.validate`: if `party_type=BUSINESS` and `tin` blank → 422. **Not** a DB NOT NULL (individuals are exempt — BR-PARTY-05). |
| BR-PARTY-04 — business **should** have `business_reg_no` | **service (soft / warn)** | recommended, not blocked (OQ-PARTY-05); a non-fatal warning, never a constraint. |
| BR-PARTY-05 — **individual may have none** | **DB allows** | `tin`, `business_reg_no` nullable; no constraint forces them. |
| BR-PARTY-06 — **VRN only if VAT-registered** | **DB CHECK + service** | `CHECK (vrn IS NULL OR vat_registered = true)` rejects a VRN without VAT-registration at the DB; service gives the friendly message. |
| BR-PARTY-07 — **credit customer minimum identity** | **service** | credit `customer_kind=CREDIT_ACCOUNT` requires `display_name` and (if business) `tin`; walk-in requires only `display_name`. Service-validated because it spans kind + type. |
| FR-PARTY-18 — business may carry `legal_name`; individual carries person name | **DB allows + service** | `display_name` always NOT NULL; `legal_name` nullable, populated for business. |
| `display_name` present | **DB NOT NULL** | the one identifier every party must have (BR-PARTY-05/07 floor). |

**Principle (consistent with ADR-0005 / ADR-0004 owner ruling):** the DB enforces invariants that are
**unconditional or single-row-expressible** (NOT NULL on `display_name`, `company_id`, `code`, kinds; the VRN
CHECK; the agent-kind/user CHECK; the credit-limit-pair CHECK); the **service** enforces invariants that are
**conditional on business type/sub-kind** or **cross-entity** (business→TIN, branch→company consistency,
internal-agent→active-user). This keeps the schema honest without baking branching business policy into CHECK
spaghetti that is hard to evolve when OQ-PARTY rules firm up.

### D-7 — Per-kind, per-company numbering: application-assigned from a guarded per-(company,kind) counter

Each kind numbers independently per company (`CUST-####`, `SUPP-####`, `AGENT-####`, and e.g. `OTHR-####`),
unique per company per kind (FR-PARTY-19, BR-PARTY-08). **Recommendation: an application-assigned counter backed
by a small `party_code_sequence` table, allocated under a row lock**, not a Postgres `SEQUENCE` per kind and not
naive `MAX(code)+1`.

- New table **`party_code_sequence`** (`company_id`, `party_kind`, `next_value`):

  | column | type | null? | notes |
  | --- | --- | --- | --- |
  | `id` | BIGINT IDENTITY PK | NO | |
  | `company_id` | BIGINT | NO | FK → `companies(id)` |
  | `party_kind` | VARCHAR(20) | NO | `CUSTOMER` \| `SUPPLIER` \| `AGENT` \| `OTHER` |
  | `next_value` | BIGINT | NO | next suffix to assign; default 1 |
  | `version` | BIGINT | NO | optimistic lock |
  - `uq_party_code_sequence UNIQUE (company_id, party_kind)`.

- **Allocation:** `PartyCodeGenerator.next(companyId, kind)` does `SELECT ... FOR UPDATE` on the
  `(company_id, party_kind)` row (creating it with `next_value=1` on first use), reads `next_value`, formats
  `PREFIX-%04d` (zero-padded, widening past 9999 naturally), increments and writes back — **inside the same
  transaction** as the party insert. The `FOR UPDATE` row lock serialises concurrent creates for the same
  company+kind; different companies/kinds don't contend (separate rows). This is concurrency-safe and
  per-company without a global sequence.
- **Why not a DB `SEQUENCE` per (company,kind):** sequences are global, not per-company, and creating one per
  company at runtime is DDL-at-runtime (ugly, and gaps on rollback). Why not `MAX(code)+1`: a race produces
  duplicate codes under concurrency. The locked-counter table is the boring, safe, per-company choice and keeps
  the "operations live in the application" principle (the format/allocation logic is Java, the table is just
  state).
- **Backstop:** `uq_<table>_company_code UNIQUE (company_id, code)` on each master (BR-PARTY-08) turns any
  generator bug into a constraint violation, not a silent duplicate. Prefix is owned by the kind, so `CUST-0001`
  and `SUPP-0001` coexist (BR-PARTY-08 explicitly permits the shared numeric suffix across kinds).
- **Code immutability:** `code` is assigned once and **not** user-editable (it is the document-reference key);
  the service rejects updates to `code`.

### D-8 — Uniqueness & VRN: uid global-unique, code per-(company), VRN per-company partial unique

- `uq_<table>_uid UNIQUE (uid)` — every master (ULID, ADR-0001 D-G).
- `uq_<table>_company_code UNIQUE (company_id, code)` — BR-PARTY-08 (per company per kind; the kind is implicit
  in the table). Two companies may both have `CUST-0001`.
- **VRN per-company uniqueness (BR-PARTY-13):** a **partial unique index** (the established Postgres pattern from
  ADR-0001 D-C):
  `CREATE UNIQUE INDEX uq_customer_company_vrn ON customers (company_id, vrn) WHERE vrn IS NOT NULL;`
  (and the same on `suppliers`, `agents`, `other_parties`). This makes "duplicate VRN within a company" a DB
  fact for *each kind's own table*. **Note an honest limitation:** because records are separate (D1), this does
  **not** prevent the same VRN appearing on a customer *and* a supplier in the same company — that cross-table
  uniqueness would require a shared table or a trigger, both rejected. The spec's BR-PARTY-13 is "unique within
  its company per party master" and explicitly accepts the separate-records divergence (§9); the per-table
  partial unique satisfies the rule as written. Cross-table VRN/TIN duplication is a **soft warning** only
  (BR-PARTY-13: TIN duplication warned not blocked).
- **TIN:** **not** unique-constrained anywhere — BR-PARTY-13 says TIN duplication is *warned, not blocked*
  (separate-records model deliberately permits the same entity as customer and supplier). The service may emit a
  warning on a duplicate TIN within the company; the DB does not block it.

### D-9 — Search & selection indexes (FR-PARTY-19, NFR fast lookup)

Point-of-sale party selection must stay fast as the master grows. Per master table:

- `ix_<table>_company (company_id)` — the tenant-predicate index; every scoped query filters on it first.
- `uq_<table>_company_code` (D-8) doubles as the **code** lookup index (search by code, FR-PARTY-19).
- `ix_<table>_company_name` — a composite on `(company_id, lower(display_name))` (expression index) for
  case-insensitive name search; or, if prefix/substring search is needed, a `pg_trgm` GIN index on
  `display_name` (recommend the expression-index first; add trigram only if substring search proves necessary —
  don't reach for the exotic feature until a boring one is shown insufficient).
- `ix_<table>_company_tin` — `(company_id, tin) WHERE tin IS NOT NULL` (partial) for TIN search.
- `ix_<table>_company_phone` — `(company_id, phone) WHERE phone IS NOT NULL` (partial) for phone search.
- **Branch-filtered selection (FR-PARTY-12)** is served by the link table's `ix_<table>_branch (branch_id)`
  joined to the master with `status = 'ACTIVE'` (BR-PARTY-09 excludes archived) — the active-branch selection
  query is `link.branch_id = :activeBranch AND master.company_id = :activeCompany AND master.status='ACTIVE'`.

Native SQL is permitted for the heavier search/report paths if JPQL can't express the expression/partial index
usage, kept behind a clearly-named repository method (PROJECT-CONVENTIONS — native allowed for reports/bulk).

### D-10 — Permission catalogue additions (seeded in V2, module `parties`)

New permission codes, one VIEW + one MANAGE per master, plus association gating. Seeded in `V2__parties.sql`
(idempotent `INSERT ... ON CONFLICT (code) DO NOTHING`, same style as the IAM seed):

| code | module | description |
| --- | --- | --- |
| `CUSTOMER.VIEW` | parties | View and select customers |
| `CUSTOMER.MANAGE` | parties | Create, update and archive customers |
| `SUPPLIER.VIEW` | parties | View and select suppliers |
| `SUPPLIER.MANAGE` | parties | Create, update and archive suppliers |
| `AGENT.VIEW` | parties | View and select sales agents |
| `AGENT.MANAGE` | parties | Create, update and archive sales agents |
| `OTHERPARTY.VIEW` | parties | View and select other/misc parties |
| `OTHERPARTY.MANAGE` | parties | Create, update and archive other/misc parties |
| `PARTY.BRANCH.ASSIGN` | parties | Associate/dissociate any party with branches of its company |

- **Why a single `PARTY.BRANCH.ASSIGN`** rather than `CUSTOMER.BRANCH.ASSIGN` × 4: branch association is the same
  administrative act regardless of kind, performed by the master-data administrator (parties.md §4); one
  permission keeps the catalogue lean. If the owner later wants per-kind association control, it splits then —
  cheap, additive. (Flagged as a minor open choice below.)
- **Seeding into ORG_ADMIN:** the V1 `ORG_ADMIN` seed grants every `module='iam'` permission via a
  `CROSS JOIN ... WHERE p.module='iam'`. V2 should **additively** grant the new `parties` permissions to
  `ORG_ADMIN` too (the org admin manages master data) — an additive `INSERT ... SELECT ... WHERE p.module='parties'
  ON CONFLICT DO NOTHING`. This is a **data** seed in V2, not a V1 edit (freeze respected).
- **Gate shapes (ADR-0002):** target-by-uid ops use the 2-arg evaluator form. This needs the D-2 resolution
  table (`ScopeGuard.companyIdOf`) extended with the new `targetType`s — `customer`/`supplier`/`agent`/
  `otherparty` → their `company_id`. That extension is an ADR-0002 follow-on the engineer applies when wiring;
  recorded here so it isn't missed. Examples:
  - `POST /customers` → `@PreAuthorize("hasPermission('CUSTOMER.MANAGE')")` (active company is target).
  - `PUT /customers/uid/{uid}` → `@PreAuthorize("hasPermission(#uid,'customer','CUSTOMER.MANAGE')")`.
  - `GET /customers` (list/search) → `@PreAuthorize("hasPermission('CUSTOMER.VIEW')")`, results scoped by the
    tenant predicate + active branch (D-9).
  - `POST /customers/uid/{uid}/branches` (associate) → `@PreAuthorize("hasPermission(#uid,'customer','PARTY.BRANCH.ASSIGN')")`,
    plus `PartyBranchGuard` company-consistency (D-4).

### D-11 — API / uid discipline (PROJECT-CONVENTIONS §3.1/§3.3, ADR-0005 D-7)

- **uids in URLs, ids (as JSON strings) in bodies.** Master endpoints address by uid: `/customers/uid/{uid}`.
  Branch associations are expressed by the party uid + branch uid; the link row's own id/uid is not exposed.
- **`ApiResponse<T>` envelope** for every response; list/search returns paged via the `PageMeta` convention
  established in ADR-0004 D-7 (`page,size,totalElements,totalPages,hasNext` in `ApiResponse.meta`). Party search
  at point-of-sale is a paged read.
- **DTOs are `Dto`-suffixed** and live in `parties.domain.dto`; Sales/Purchases consume **these DTOs**, never
  the entities (boundary rule).
- **Money on the wire (credit limit):** `creditLimit` serialises as `{ "amount": "...", "currency": "..." }`
  with `amount` a **string** (ADR-0005 D-7); null when unset. The Angular `Money` interface from ADR-0005
  applies unchanged.
- **Enums on the wire:** the string name (`INDIVIDUAL`, `CREDIT_ACCOUNT`, `INTERNAL`, …), matching DB storage.

### D-12 — Audit (ADR-0004): which actions emit, and the `target_type` strings

Party masters are auditable via the existing `AuditService.record(...)` (MANDATORY propagation, same-TX,
append-only — ADR-0004 D-2). The new module's mutating services emit:

| action | target_type | when | detail (D-6 policy: context + status transitions, fact-only for field edits) |
| --- | --- | --- | --- |
| `CUSTOMER.CREATE` | `customers` | on create | `code`, `party_type`, `customer_kind` |
| `CUSTOMER.UPDATE` | `customers` | on profile edit | minimal/fact-only (no old→new field values) |
| `CUSTOMER.ARCHIVE` / `CUSTOMER.RESTORE` | `customers` | status transition | before/after `status` |
| `CUSTOMER.BRANCH.ADD` / `CUSTOMER.BRANCH.REMOVE` | `customers` | association change | `branchUid` added/removed |
| `SUPPLIER.*` | `suppliers` | same set | as above |
| `AGENT.*` | `agents` | same set + create records `agent_kind` and (internal) the linked user uid in detail | as above |
| `OTHERPARTY.*` | `other_parties` | same set | as above |

- **`target_type` strings are the plural table names** (`customers`, `suppliers`, `agents`, `other_parties`) —
  matching the ADR-0004 convention (the IAM examples use `app_user`/`user_branch`; the audit table's
  `target_type` is "entity name" and our entity tables are plural per the pluralisation convention, so plural is
  correct here and the audit read filter (`targetType=customers`) reads naturally).
- **Branch-association changes ARE audited** (administrative, security-relevant — who made a party usable where).
- **Profile-field edits are fact-only** (`*.UPDATE` with minimal detail) per ADR-0004 D-6 — no PII old→new
  capture; this includes contact/identifier edits.
- No outbox event is required for v1 (no cross-module async effect yet); Sales/Purchases will read party DTOs
  synchronously. If a later module needs "party archived → react", that is an additive outbox event under its
  own decision, not built now.

### D-13 — Migration: additive `V2__parties.sql`, not a V1 edit (freeze respected)

IAM V1 is frozen after Slice 6. Parties is a **new** module, so it lands as **`V2__parties.sql`** — purely
**additive** (new tables, new indexes, new permission seed, additive ORG_ADMIN grant). It **must not** edit
`V1__baseline.sql`. Ordering within V2: (1) master tables (`customers`, `suppliers`, `agents`, `other_parties`)
with FKs to existing `companies`/`app_users`; (2) `party_code_sequence`; (3) link tables
(`customer_branch`, …) with FKs to masters + `branches` + `app_users`; (4) indexes incl. partial/expression; (5)
permission seed + additive ORG_ADMIN grant. All FK targets (`companies`, `branches`, `app_users`) already exist
in V1 — no dependency on un-frozen schema.

## Consequences

**Easier / safer:**
- The party master is **currency-safe and tenant-safe from day one**: `credit_limit` is a `Money` pair
  (ADR-0005), every master is `company_id`-scoped under the §3.2 predicate, associations are
  company-consistent by the `PartyBranchGuard`.
- **Per-kind numbering is concurrency-safe** (locked counter, D-7) and per-company, with a DB unique backstop —
  no duplicate codes under load, no global sequence sprawl.
- **DB enforces the unconditional invariants** (uid/code uniqueness, VRN-needs-VAT, agent-kind/user pairing,
  credit-limit pairing, VRN-per-company) so a code bug becomes a constraint violation; **conditional business
  rules live in the service** where they are legible and evolvable as OQ-PARTY questions firm up.
- **Sales/Purchases stay decoupled**: they consume `parties.domain.dto`, read `customers`/`agents` and
  `suppliers` respectively, and never import party entities or repositories.
- **Audit is consistent** with IAM (same `AuditService`, same `target_type`-is-table-name convention, same
  fact-only profile-edit policy).

**Harder / to watch:**
- **Four-way column duplication** (identity/contact/tax block repeated across four masters) is the accepted cost
  of D1. A future consolidation (if a unified-360 view is ever required) is a migration, not a tweak — recorded
  honestly, not mitigated in v1 (parties.md §9).
- **BR-PARTY-01 / BR-PARTY-10 are service-enforced, not DB-enforced** — the `PartyBranchGuard` company-consistency
  check and the internal-agent active-user check are the highest-discipline surfaces. They must have unit/IT
  coverage (a customer of company A cannot be associated with a branch of company B → 422/403; an internal agent
  whose user is disabled is excluded from selection). Flag to test plan.
- **Cross-table VRN/TIN uniqueness is NOT enforced** (D-8) — the same entity may carry the same VRN on a customer
  and a supplier record. This follows directly from D1 and is accepted (§9); the soft warning is the only
  mitigation.
- **ADR-0002 follow-on:** `ScopeGuard.companyIdOf` must learn the four new `targetType`s before the 2-arg
  `@PreAuthorize` gates work. Not optional — the engineer wires it with the controllers.
- **`parties` → IAM dependency** (agent→user) must go through an IAM service call and be the *only* cross-module
  edge; the boundary test allowlist is touched at most once, with justification (D-1).

**Migration / delivery cost:**
- 1 additive Flyway file (`V2__parties.sql`): 4 master tables + 4 link tables + 1 sequence table = 9 tables,
  their FKs/uniques/CHECKs, ~5 indexes per master + 2 per link table, 9 permission rows + 1 additive grant.
- Backend: 4 entity sets (entity + DTO(s) + repository + service interface/Impl + controller) sharing a
  `PartyCodeGenerator` and `PartyBranchGuard`; ADR-0002 evaluator extension.
- Web: 4 master-admin screens (list/search/create/edit/archive + branch-association sub-screen), WCAG 2.1 AA
  (NFR), reusing the IAM admin-screen patterns and the ADR-0005 `Money` input for credit limit.
- No outbox, no new infra, no DB triggers.

## Alternatives considered

- **Unified `parties` table with a `kind` discriminator + role rows (party-with-roles).** One table, one code
  sequence, a single golden view, no column duplication, cross-kind VRN/TIN uniqueness for free. **Not chosen —
  the owner ruled separate records (D1, BR-PARTY-03), knowingly (§9).** Recorded honestly: a unified model would
  have made a future party-360 / net-off-balances view trivial and removed the four-way duplication; the cost it
  carried (role-row complexity, coupling Sales and Purchases to one shared master, harder per-kind numbering and
  per-kind permissions, "every party is every kind" nullable-column sprawl on one table) is what the owner
  weighed against. The accepted trade is simpler, decoupled modules now; the future consolidation cost is real
  and is **not** re-litigated here.

- **One shared `party_branch` link table with a `party_type` + `party_id` polymorphic pair** (instead of four
  link tables). Fewer tables. **Rejected:** a polymorphic FK (`party_id` pointing at one of four tables by a
  type column) cannot have a real FK constraint — it sacrifices referential integrity, the thing the DB is best
  at, to save three small tables. It also reads worse and indexes worse for the per-kind branch-selection hot
  path. Four typed link tables with real FKs are the boring, safe choice and stay consistent with the
  separate-records model.

- **Postgres `SEQUENCE` per (company, kind) for numbering.** Native, fast, no row lock. **Rejected:** sequences
  are global objects, not naturally per-company; minting one per company at runtime is DDL-at-runtime and they
  leak gaps on rollback (codes would be non-contiguous — undesirable for human-readable `CUST-####`). The locked
  per-(company,kind) counter table (D-7) is per-company by construction, gap-free, and keeps allocation logic in
  the application.

- **DB CHECK / trigger for the full mandatory-identifier matrix** (business→TIN, credit-customer-identity).
  Most "DB-true". **Rejected for the conditional rules:** business-type-conditional mandatory fields and
  cross-entity company-consistency are awkward, message-poor, and brittle in CHECK/trigger form, and a trigger
  violates the owner principle (ADR-0004 D-5). The unconditional, single-row invariants *are* DB CHECKs (D-6);
  the conditional ones live in the service where they're legible and evolve with the OQ-PARTY questions.

## Open / ambiguous items flagged to owner (do not block modeling; noted for closure)

These do **not** block the engineer building the tables above; they are policy refinements that touch behaviour
layered on this schema:

1. **OQ-PARTY-02 (credit approval workflow)** — out of v1 scope (parties.md §10: credit-limit *enforcement*
   deferred to Finance/Sales). The schema **records** `credit_limit` + `payment_terms_days`; no approval state
   machine is modelled. **Not blocking.** If an approval status is later wanted on the customer, it is an
   additive column under a future ADR.
2. **OQ-PARTY-03 (commission tiers / calculation)** — out of v1 scope (parties.md §10: commission is Sales'
   concern). The agent master holds identity + kind + (internal) user link only; **no commission rate/tier
   columns in v1.** **Not blocking** the party master; it does mean the "two internal agents → same user"
   uniqueness call (D-2) is left as a soft warning rather than a hard unique until commission semantics exist.
3. **OQ-PARTY-06 (default walk-in customer)** — parties.md §3.1 mentions a reusable default walk-in customer for
   anonymous counter sales. **Question for owner:** should V2 *seed* a per-company default walk-in customer
   (e.g. `CUST-0000`, `CASH_WALK_IN`, `party_type=INDIVIDUAL`), and if so, is it auto-created on company
   creation (an IAM→parties hook) or seeded only for the bootstrap company? **Mildly blocking for Sales, not for
   the parties schema** — the schema supports it without change; the decision is *whether/how to seed* it.
   Recommend: do **not** auto-seed in V2; let Sales discovery decide the default-customer mechanism, since it is
   a point-of-sale convenience, not a master-data invariant. Flagged for the owner to confirm or override.
4. **Minor: `PARTY.BRANCH.ASSIGN` granularity** (D-10) — one shared association permission vs per-kind. Defaulted
   to one shared; trivially split later if the owner wants per-kind association control. **Not blocking.**
5. **`other_kind` typing** — modelled as free-text (no enum) so Other never blocks an operator (parties.md §3.1).
   If the owner later wants a constrained pick-list for Other, it becomes an enum + CHECK additively. **Not
   blocking.**

No FR/BR is ambiguous enough to halt implementation; the above are policy refinements on top of a schema that is
fully specified by this ADR.

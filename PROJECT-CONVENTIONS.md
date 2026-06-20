# ERP — Project Conventions

> Shared context for the agent team. This is the single source of truth for stack, layout,
> and invariants. Agents read the section relevant to their work. When the architect or
> analyst ratifies a change here, every agent inherits it. Keep it current.

This is a **clean-build, greenfield ERP**. The application is not yet scaffolded — the
engineering agents create it. Until the system-analyst completes requirements gathering,
treat business scope as **open**; do not invent modules or rules. What is fixed below is the
**stack and engineering discipline**, drawn from the team's prior ERP experience.

---

## 1. Stack (fixed)

| Layer | Choice |
|---|---|
| Backend | Spring Boot 3.3 · Java 21 · Maven · Hibernate 6 · Flyway |
| Database | **PostgreSQL 15+** (single engine — not DB-agnostic) |
| Web | Angular 17+ · standalone components (no NgModules) · TypeScript strict |
| Auth | In-house JWT (RS256), refresh-token rotation, RBAC by permission |
| Build | `mvn` (backend), `npm` / `ng` (web) |

There is **no mobile app** in scope. There is no Flutter, no POS/WMS runtime. If mobile is
added later it gets its own agent and an architecture decision first.

**PostgreSQL is the only database.** Unlike some prior projects, this build is NOT DB-agnostic.
Agents may use Postgres-native features deliberately and well: `JSONB` for audit payloads and
flexible attributes, `BIGINT GENERATED ... AS IDENTITY` or sequences, partial/expression
indexes, `gen_random_uuid()`. Prefer the boring, well-understood Postgres feature over a clever
one. JPQL / `CriteriaBuilder` for normal queries; native SQL is fine for reports and bulk paths
where it earns its keep, kept in clearly-named repository methods.

---

## 2. Backend layout — modular monolith

A modular monolith, not microservices. Boundaries are enforced by package and by an ArchUnit
test (`ModuleBoundaryTest`). Splitting out later is easy; recombining microservices is not.

Base package: `com.erp` (rename once a product name is chosen — one decision, one ADR).

```
com.erp
├── platform/          # cross-cutting infrastructure
│   ├── security/      # JWT, RBAC, RequestContext, @PreAuthorize plumbing
│   ├── iam/           # AppUser, Role, Permission, UserRole, UserBranch
│   ├── company/       # Organisation, Company, Branch
│   ├── audit/         # audit aspect + audit_log
│   ├── sequence/      # document numbering
│   ├── events/        # domain-event bus + transactional outbox
│   └── common/        # ApiResponse envelope, base entities, validation
└── modules/
    └── <name>/        # one package per business area (added during delivery)
        ├── domain/
        │   ├── entity/
        │   ├── dto/    # *Dto suffix; records for immutable DTOs
        │   ├── enums/
        │   └── event/  # outbox event payloads
        ├── service/    # interface Xxx + class XxxImpl; @Transactional at public methods
        ├── repository/ # Spring Data; one aggregate per repository
        └── (controllers live in com.erp.api..)
```

**Layering (ArchUnit-enforced):** controller → service → repository → domain.
- Controllers may **not** touch repositories.
- Modules talk only via `..domain.dto..` / `..domain.enums..` and the outbox — never by
  importing another module's entity or service.
- If a needed dependency breaks the rule, the **design** is wrong — fix it, don't relax the rule.
  A rule change needs an ADR.

REST controllers live flat under `com.erp.api..`, one per resource.

---

## 3. Invariants every agent respects

1. **API envelope.** Every REST response is wrapped in `ApiResponse<T>` (`data`, `errors[]`,
   meta). A response interceptor wraps automatically — controllers return the raw `T`. On the
   web side, an HTTP interceptor unwraps it before services see it. Never leak internal
   exception text into `errors[]` — user-safe strings only.

2. **Multi-company / multi-branch.** Every transactional table carries `company_id` +
   `branch_id`. A `RequestContext` filter sets current user / company / branch from the JWT plus
   a branch-override header (branch can be switched without re-login). Repository base
   interfaces inject the company/branch predicate automatically. A finder that bypasses the base
   interface is a tenant-isolation bug.

3. **Identity discipline.** Every externally exposed entity has:
   - numeric `id` (`BIGINT`, for body-level joins), and
   - a stable external `uid` (string, used in URLs and cross-system references).
   URLs address entities by `uid`: `/api/v1/<resource>/uid/{uid}`. Long-id fields serialise as
   JSON strings on the wire (configured globally) so 64-bit ids survive JavaScript. The web side
   types every id field as `string`.

4. **RBAC by permission, not role.** The atomic unit is a **permission** (entity `Permission`,
   table `permission`, dot-separated codes like `SALES_INVOICE.POST`). Roles are bundles of
   permissions. `@PreAuthorize` checks reference **permission codes**, never role names.
   Permissions are seeded via Flyway. A new permission-gated endpoint must have its permission
   in a seed migration.

5. **Cross-module side effects = transactional outbox.** Write a `domain_event` row in the same
   DB transaction as the business write; a scheduled poller dispatches it. Never call directly
   into another module's service for a side effect, and don't use Spring's in-memory
   `ApplicationEventPublisher` for cross-module events (events lost on crash).

6. **Persistence discipline.**
   - Flyway for all schema; `ddl-auto=validate` (never `update`).
   - Optimistic locking (`@Version`) on transactional aggregates.
   - Append-only posting tables (e.g. `stock_move`, `audit_log`) — corrections are new
     postings, never updates.
   - Soft-deletable masters use a `status` enum (`ACTIVE` / `INACTIVE` / `ARCHIVED`).
   - **Schema is FROZEN (since 2026-06-20) — additive-only.** The database is now durable in
     **every** environment (local, QA, prod); it is never wiped or recreated. **Never edit, rename,
     or delete an applied migration** — its checksum would drift and the app refuses to boot
     (`validate-on-migrate`) on a populated DB. Any schema/seed change is a **new `V<n>` migration**
     (the next free version), or — for convergent reference data (permissions/grants) — an edit to
     the repeatable `R__seed_permissions.sql`. CI enforces this (`scripts/check-migrations.sh`, job
     `migration-hygiene`, rules 1+2). Author changes against populated tables (expand→backfill→
     constrain; `CREATE INDEX CONCURRENTLY` in its own migration). Full discipline:
     [docs/ops/migrations-and-seeding.md](docs/ops/migrations-and-seeding.md).

7. **Coding standards.** Java: Google Java Style, `final` where reasonable, records for DTOs.
   **Lombok** is used to remove getter/setter boilerplate on JPA entities and elsewhere: annotate
   entities with `@Getter @Setter` (NOT `@Data`/`@EqualsAndHashCode`/`@ToString` on entities — they
   break JPA identity/lazy loading). Hand-write constructors and any behaviour methods (invariants,
   state transitions); Lombok generates only the plain accessors. TypeScript: strict, no `any`
   without a justification comment, ESLint + Prettier. Conventional Commits, one logical change per
   PR, mandatory review.

8. **Accessibility.** Web ships WCAG 2.1 AA. axe-core runs in CI against representative routes;
   new serious/critical violations fail the build.

---

## 4. IAM — identity & access (the first module)

The first thing the team builds. Scaffolds the security spine everything else depends on.

Core tables (carried from prior ERP work, adjust during requirements):

- **`organisation`** — top of the tree; one per deployment (single-tenant deployment model).
- **`company`** — a legal entity within the organisation. Scoping parent of company-bound
  master data.
- **`branch`** — a physical location (shop / depot / warehouse). Smallest unit at which stock
  and a business day exist. Columns include `company_id`, `code` (unique within company),
  `name`, `is_default` (at most one default branch per company).
- **`app_user`** — a login identity. `username` (unique), `password_hash` (bcrypt cost ≥ 12),
  `display_name`, `email`, `default_company_id`, `default_branch_id`, lockout fields, `status`.
- **`role`** — named permission bundle (`code`, `name`, `is_system`, `status`).
- **`permission`** — atomic unit (`code` dot-separated, `module`, `description`).
- **`role_permission`** — junction (role ⇄ permission).
- **`user_role`** — a user holds a role scoped to a company, optionally to one branch
  (`branch_id` NULL = all branches in the company).
- **`user_branch`** — **a user is assigned to many branches; one is the default.** Junction of
  `user_id` + `branch_id`, with `is_default` (at most one default per user). This realises the
  brief: *"a user can be assigned many branches, a user has a default branch."* The user's
  `default_branch_id` must reference a branch the user is actually assigned to.
- **`refresh_token`** — hashed, rotated on use.

**Branch assignment rule (the headline requirement):**
> A user may be assigned to **many** branches via `user_branch`. Exactly **one** assignment is
> the **default** (`is_default = true`). On login the user lands in their default branch; they
> can switch to any other assigned branch via the branch-override header without re-login.
> Authorization for a branch requires both (a) an active `user_branch` assignment and (b) a
> `user_role` whose scope covers that branch.

---

## 5. Working agreement for agents

- **Read before you write.** Open the relevant section here and any existing code before
  proposing structure. This document and memory snapshots can lag the code — verify.
- **Stay in your lane.** Each agent's boundaries are in its own file. Architecture decisions →
  solutions-architect (+ ADR). Scope/sequencing → project-manager. Requirements → system-analyst.
- **Nothing is built before it's understood.** Per the owner's instruction: requirements
  gathering comes first. Do not rush into implementation. The system-analyst drives discovery;
  engineering waits for ratified specs.
- **Decisions are written down.** Non-trivial architecture choices become ADRs in
  `docs/decisions/`. Requirements live in `docs/requirements/`. Plans in `docs/`.

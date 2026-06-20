# Architecture

Technical design for the ERP. Owned by solutions-architect. Builds on the fixed substrate in
[PROJECT-CONVENTIONS.md](PROJECT-CONVENTIONS.md); decisions are recorded as ADRs in
[docs/decisions/](docs/decisions/). This document grows per module; the **platform spine + IAM**
are specified first because everything else depends on them.

## 1. High-level shape

- **Modular monolith** (`com.erp`), PostgreSQL 15+, single deployable Spring Boot API + an Angular
  web client. No microservices, no mobile (this phase).
- Module boundaries enforced by package + ArchUnit (`ModuleBoundaryTest`): controller → service →
  repository → domain; controllers never touch repositories; modules talk only via `..domain.dto..`
  / `..domain.enums..` and the transactional outbox.
- Every REST response wrapped in `ApiResponse<T>`; every externally exposed entity carries `id`
  (numeric, internal) + `uid` (ULID, external); URLs address by `uid`.

```
Angular web ── HTTPS ──► Spring Boot API ──► PostgreSQL 15
   (JWT + X-Branch-Uid header)        │
                                      └─ Flyway migrations on start (validate)
```

## 2. Package layout (IAM lives here)

```
com.erp
├── api/                         # REST controllers (flat, one per resource)
│   └── iam/                     #   AuthController, UserController, RoleController,
│                                #   BranchController, CompanyController, UserBranchController
├── platform/
│   ├── common/                  # ApiResponse<T>, ResponseBodyAdvice wrapper,
│   │                            #   UidEntity base, ULID generator, error model, validation
│   ├── security/                # JWT issue/verify, RequestContext filter, PermissionResolver,
│   │                            #   @PreAuthorize hasPermission, branch-override validation
│   ├── audit/                   # audit aspect → audit_log (append-only)
│   ├── events/                  # domain-event outbox (domain_event) + dispatcher (later modules)
│   └── sequence/                # document numbering (later modules)
└── modules/
    └── iam/
        ├── domain/
        │   ├── entity/          # Organisation, Company, Branch, AppUser, Role, Permission,
        │   │                    #   RolePermission, UserRole, UserBranch, RefreshToken
        │   ├── dto/             # *Dto records (request/response/nested)
        │   └── enums/           # MasterStatus, AuditAction
        ├── service/             # interface + Impl: Auth, User, Role, Branch, Company,
        │                        #   UserBranch, PermissionResolver, Bootstrap
        └── repository/          # Spring Data repos, findByUid + scoped finders
```

> Note: IAM's domain (`organisation`/`company`/`branch`/`app_user` …) is foundational, so the
> `security`, `audit`, `company`, and `iam` concerns sit under `platform`/`modules.iam`. Later
> business modules depend on IAM only through DTOs/enums and the `RequestContext`/permission API,
> never on its entities.

## 3. Layering inside a module
1. **Controller** (`api/`) — HTTP, validation of request DTOs, `@PreAuthorize`, returns raw `T`
   (the advice wraps it). No business logic, no repository access.
2. **Service** (`service/`) — `interface Xxx` + `class XxxImpl`, `@Transactional` at public
   methods, owns invariants (e.g. "set default branch → clear old, in one TX").
3. **Repository** (`repository/`) — Spring Data; `Optional<X> findByUid(String)` + scoped finders.
4. **Domain** (`domain/`) — entities (extend `UidEntity`), DTOs (`*Dto`), enums.

## 4. AuthN / AuthZ (IAM)

- **Login** (`POST /api/v1/auth/login`): verify username + bcrypt password; on success issue
  **access JWT (15 min, RS256)** carrying `sub=userId`, `username`, active `companyId`,
  `branchId` (the user's default), `isRoot`; plus a **refresh token (7 d)**, stored as a SHA-256
  hash, single-use. On failure increment `failed_login_count`; at 5 lock `locked_until = now+15m`
  (FR-IAM-09). Disabled/locked users refused.
- **Default context at login** = the user's default branch (`user_branch.is_default`) and its
  company (ADR-0001 D-B). No-branch users authenticate into a read-only "no branch" state
  (FR-IAM-19).
- **Refresh** (`POST /api/v1/auth/refresh`): look up by hash; if `rotated_at`/`revoked_at` set →
  reuse → revoke chain. Else mint new pair, set `rotated_at`/`replaced_by_id` (US-IAM-002).
- **Logout** (`POST /api/v1/auth/logout`): revoke refresh token; access JTI added to a short-lived
  Redis/in-memory denylist until natural expiry (FR-IAM-07).
- **Authorisation**: `@PreAuthorize("hasPermission('USER.MANAGE')")` on service methods. The
  `PermissionResolver` computes effective permissions for the *active* (user, company, branch) from
  `user_role` + `role_permission`, cached briefly per scope, busted on grant/revoke (ADR-0001 D-E).
  `isRoot` short-circuits to allowed, always audited (FR-IAM-21).
- **JWT signing**: dev may use an ephemeral in-memory RSA key (rotates on restart → logs everyone
  out; acceptable in dev). **Production loads a stable RS256 key from a secret store** — gating item
  for security-engineer before any prod deploy.

## 5. Multi-company / multi-branch & the branch-override header

- `RequestContext` (request-scoped) is built by a servlet filter from the JWT plus an optional
  **`X-Branch-Uid`** header. If the header is present the filter **verifies the branch is in the
  caller's active `user_branch` assignments** (else 403, FR-IAM-18); the active company becomes that
  branch's company. Switching is context-only — no DB write, no re-login.
- Transactional **business** tables (later modules) carry `company_id` + `branch_id` and go through a
  repository base interface that injects the tenant predicate from `RequestContext`. **IAM admin
  tables are exempt** from the blanket predicate (administration is inherently cross-branch);
  isolation there is by permission + explicit scope checks in the service (ADR-0001 D-A).

## 6. Audit
- An **audit aspect** writes `audit_log` rows for IAM-significant actions (user/role/branch changes,
  default-branch change, password reset, lockout/unlock, login success/failure) — written by the
  aspect, not the calling code, so it can't be forgotten (FR-IAM-23). Append-only; the deploy grants
  the app DB role no UPDATE/DELETE on `audit_log` (US-IAM-010 AC2).

## 7. API conventions
- Base path `/api/v1`. Entity routes by uid: `/api/v1/<resource>/uid/{uid}`.
- Request DTOs validated (`@Valid`); numeric FK fields are `Long` (Jackson accepts `42` and `"42"`).
- Response DTOs include `id` + `uid`; errors are user-safe strings in `ApiResponse.errors[]` — no
  internal exception text leaked.
- IAM endpoints (indicative): `auth/login|refresh|logout`; `users` (CRUD, set-password, unlock);
  `users/{uid}/branches` (assign/remove/set-default); `users/{uid}/roles` (grant/revoke);
  `roles`, `permissions` (read), `companies`, `branches` (CRUD, set-default).

## 8. Persistence & migrations
- Flyway under `db/migration/`. `ddl-auto=validate`. Schema is **frozen (since 2026-06-20):
  additive-only** — never edit an applied migration; add a new `V<n>`. The DB is durable in every
  environment (never wiped); author changes against populated tables (conventions §3.6,
  [docs/ops/migrations-and-seeding.md](docs/ops/migrations-and-seeding.md)). Optimistic locking
  (`@Version`) on mutable aggregates. PostgreSQL-native where it pays
  (partial unique indexes for the default-branch invariants; `JSONB` for `audit_log.detail`).

## 9. Cross-module communication (forward-looking)
- IAM emits no cross-module side effects yet. When later modules need them, they use the
  transactional outbox (`domain_event` written in the same TX, dispatched by a poller) — never a
  direct call into another module. IAM exposes `RequestContext` + the permission API as its only
  cross-module surface.

## 10. Testing strategy (architectural stance; qa-engineer owns specifics)
- Backend: JUnit 5 + Spring Boot Test; **integration tests against real PostgreSQL via
  Testcontainers** (no mocked DB across a Flyway/query boundary). ArchUnit `ModuleBoundaryTest`
  green. The default-branch invariants and branch-override validation get dedicated integration
  tests.
- Web: unit + Playwright e2e + axe (WCAG 2.1 AA gate).

## 11. Open architectural items
- `PermissionResolver` cache invalidation strategy (event-driven vs short TTL) — start with a short
  TTL + explicit bust on writes; revisit if profiling demands.
- Access-token denylist store (in-memory vs Redis) — in-memory for single-node dev; Redis when we
  scale out (ADR when that happens).

# Security

ERPCLEAN2 secures every request through an in-house JWT resource server, enforces
authorization by permission code, and isolates tenants down to the branch. This document
describes how authentication, RBAC, multi-tenant isolation, the audit trail, and secret
handling actually work in the shipped system.

The decisions behind this design are recorded in
[ADR-0001](../decisions/0001-iam-architecture.md) (IAM architecture),
[ADR-0002](../decisions/0002-rbac-enforcement.md) (permission AND scope enforcement),
[ADR-0003](../decisions/0003-branch-switch-override.md) (runtime branch switch), and
[ADR-0004](../decisions/0004-iam-audit-trail.md) (audit trail).

## 1. Authentication

### 1.1 In-house JWT (RS256)

Authentication is a stateless OAuth2 resource-server model with tokens minted by the
application itself — there is no external identity provider. Access tokens are JWTs signed
with **RS256** (RSA, 2048-bit). The private key signs at login; the public key verifies on
every request. Signature and expiry are checked; the decoder is built without a
`JwtIssuerValidator`, which is acceptable for a single in-house signer (see
[ADR-0038](../decisions/0038-production-hardening.md) Risk 8 — flag to security before
accepting tokens from any external issuer).

A JWT carries the claims needed to build the request context without re-querying the
database on every hop:

- `userId` — numeric id of the authenticated user
- `username`
- `companyId` — the active company (the default branch's company at login)
- `branchId` — the active branch (the user's default branch at login)
- `isRoot` — the super-admin flag

The token does **not** embed the permission set. Permissions change with the active branch
and would bloat and stale the token; they are resolved per request instead
(ADR-0001 D-E, see §2.1).

### 1.2 Login flow

1. `POST /api/v1/auth/login` with username + password. The endpoint is public (see §5).
2. The password is verified against a **BCrypt** hash (cost 12). The unknown-user path runs
   a constant-time dummy comparison so a missing username is indistinguishable from a wrong
   password (no user enumeration).
3. On success the server mints an **access token** (short-lived, default 15 minutes) and a
   **refresh token**. The session lands the user in their **default branch** (the
   `user_branch` assignment with `is_default = true`); the active company is that branch's
   company.
4. A branch is only usable for a session if both the branch **and** its company are
   `ACTIVE` (the `Branch.isUsableForSession()` predicate, ADR-0004 D-8 F8). A user with no
   branch assignments lands in a read-only, no-scope state (FR-IAM-19).
5. Account lockout: 5 failed attempts lock the account for 15 minutes. Lockout bookkeeping
   runs in its own `REQUIRES_NEW` transaction so it survives the authentication-failure
   rollback (ADR-0004 D-3).

### 1.3 Refresh-token rotation

- `POST /api/v1/auth/refresh` exchanges a valid refresh token for a new access token and a
  new refresh token. Refresh tokens are **single-use and rotated** on every refresh.
- Tokens are stored **hashed** (SHA-256), never in plaintext — the `refresh_token` table
  keys on `token_hash`.
- **Reuse detection:** presenting an already-rotated (consumed) refresh token is treated as
  a compromise signal and rejected.
- `POST /api/v1/auth/logout` invalidates the refresh token.

### 1.4 Tokens and headers on each request

The client sends two headers (the Angular `authHeaderInterceptor` adds both automatically):

| Header | Purpose |
|---|---|
| `Authorization: Bearer <access-token>` | Authenticates the request; the resource server validates the RS256 signature and expiry. |
| `X-Branch-Uid: <branch-uid>` | Optional. Overrides the **active branch** for this request only (see §3.2). The value is a branch **uid** (ULID), never a numeric id. |
| `X-Request-Id` | Optional correlation id; echoed back in the response (ADR-0038 D-2). If absent, the server generates one. |

`JwtRequestContextFilter` runs **after** `BearerTokenAuthenticationFilter` so the JWT is
already validated and the principal is in the security context. The filter builds a
request-scoped `RequestContext.Principal` (`userId`, `username`, `root`, `companyId`,
`branchId`, `ip`) and clears it in a `finally` block to prevent cross-request leakage. It
also re-checks per request that the user is still `ACTIVE` — a disabled user is rejected on
their next request (401) rather than after the access-token TTL expires (ADR-0004 D-8 F9).

## 2. Authorization (RBAC)

Authorization is **by permission code, never by role name** (PROJECT-CONVENTIONS §3.4).
Roles exist only as bundles of permissions; the gates check the permissions.

### 2.1 Permission resolution

A `PermissionResolver` computes the **effective permission set** for the principal's
*active* company + branch on each request, reading `user_role` joined to `role_permission`,
cached briefly per `(user, company, branch)`. Because resolution is per active scope,
switching branches changes the effective permissions without re-issuing a token
(ADR-0001 D-E). The cache is busted on `user_role` / `role_permission` writes.

### 2.2 The gate: `@perm.has` and `@perm.scoped`

Method security is enabled (`@EnableMethodSecurity`). Every controller handler under
`com.erp.api` is gated by a `@PreAuthorize` referencing the `@perm` bean
(`PermissionChecks`, `com.erp.platform.security`). Spring Security has no 1-arg
`hasPermission` SpEL form, so the system uses a bean reference instead of a custom
expression handler (ADR-0002, Bug-1 fix). Two shapes:

- **`@perm.has('CODE')`** — create / list operations, where the implicit target is the
  active scope. True if the principal holds `CODE` in their active company + branch.

  ```java
  @PreAuthorize("@perm.has('AGENT.VIEW')")
  ```

- **`@perm.scoped(#uid, 'targetType', 'CODE')`** — operations addressing an existing target
  by path uid. True if the principal holds `CODE` **and** may act on the target (root, or
  the target lives in the principal's active company). This closes the cross-company hole: a
  user with a permission in company A cannot operate on a same-typed entity in company B
  (ADR-0002).

  ```java
  @PreAuthorize("@perm.scoped(#uid,'activity','CRM.ACTIVITY.MANAGE')")
  ```

A handler with **no** `@PreAuthorize` fails the build — `EndpointAuthorizationTest`
(ArchUnit) scans every `@RestController` under `com.erp.api` and fails `mvn verify` if any
handler lacks a gate. The allowlist is exactly 4 public endpoints (§5). Authorization is
**fail-closed**: missing a gate is a build failure, not a silent open door.

### 2.3 Seeded roles

The following roles ship seeded via Flyway. They are permission bundles; what each can do is
defined by the permissions granted to it, not by its name.

| Role | Intended scope |
|---|---|
| `ORG_ADMIN` | Organisation-wide administration (users, roles, companies, branches). |
| `SALES_MANAGER` | Full sales / order-to-cash management. |
| `SALES_REP` | Day-to-day sales operations (quotations, orders). |
| `ACCOUNTANT` | GL, AR, AP, cash & bank, tax. |
| `STOREKEEPER` | Inventory / stock operations. |
| `PURCHASE_OFFICER` | Procurement (requisitions, RFQ, PO, GRN, bills). |

Permission codes are dot-separated and module-prefixed (e.g. `SALES_INVOICE.POST`,
`CRM.ACTIVITY.MANAGE`). Every permission-gated endpoint must have its code present in a seed
migration (PROJECT-CONVENTIONS §3.4). A new permission-gated endpoint without its seeded
permission is a defect — see the POS prefix-mismatch defect in
[the test-case suite](../testing/test-cases/07-pos.md) (controllers checked `POS.*` but the
migration seeded `SALES.POS.*`).

### 2.4 Custom roles

Custom roles are created through the IAM admin UI / API. A custom role is a named bundle:
pick permissions, save, then grant the role to users scoped to a company (and optionally one
branch). Custom roles behave identically to seeded roles at the gate — the resolver does not
distinguish them. Seeded/system roles carry an `is_system` flag and are protected from
deletion; custom roles are freely editable.

### 2.5 Root (super-admin) bypass

The `rootadmin` super-user (`isRoot = true`) **bypasses RBAC**: every `@perm.has` /
`@perm.scoped` check short-circuits to allowed, and every scope check short-circuits in
`ScopeGuard`. Root is the seed / recovery actor and usually has no branch assignments, so it
is exempt from the branch-assignment check on switching (§3.2, ADR-0003 D-4). Root is never
unaudited — every root **action** produces its normal audit row (actor = root), and a
distinct `ROOT.BYPASS` row is written when root acts **out of** its active company
(ADR-0004 D-9). In the dev profile the backend bootstraps `rootadmin` / `RootPass12345`
(dev only — never a prod credential).

## 3. Multi-tenant and branch isolation

The tenancy tree is **organisation → company → branch** (PROJECT-CONVENTIONS §4). Every
transactional table carries `company_id` + `branch_id`. Isolation is enforced on two fronts:
the repository tenant predicate for business data, and `ScopeGuard` for cross-tenant
admin operations.

### 3.1 Tenant predicate on business tables

Transactional business tables are scoped by a company/branch predicate injected by the
repository base interface (PROJECT-CONVENTIONS §3.2). A finder that bypasses the base
interface is a tenant-isolation bug. The active company/branch come from `RequestContext`,
so the predicate tracks the current (possibly overridden) scope automatically.

IAM administration tables are the deliberate exception (ADR-0001 D-A): `organisation`,
`permission`, `role`, and `app_user` are **global** (no tenant columns), because
administering IAM is inherently cross-branch — an admin manages many branches' users, and
root must reach every company. IAM isolation is enforced by **permission + scope checks in
the service layer**, not a blanket row predicate.

### 3.2 Branch switch via `X-Branch-Uid`

A user assigned to many branches can switch the **active branch per request** without
re-login (ADR-0001 D-F, ADR-0003):

- The JWT scope minted at login is the **default**. The `X-Branch-Uid` header changes the
  **request** scope only — no token re-mint, no DB write.
- Validation runs in `JwtRequestContextFilter`: resolve the branch uid → `Branch`, read its
  company, and (for non-root) verify an **ACTIVE** `user_branch` assignment for that user +
  branch. The branch and its company must both be ACTIVE.
- **Fail closed** on any defect: unknown branch uid → 403; branch archived mid-session →
  403; no matching active assignment → 403. No header → keep the JWT default scope.
- These are uncached point-reads, so a revoked assignment or archived branch takes effect on
  the very next request (ADR-0003 D-5).
- A rejected override is rendered as a 403 `ApiResponse` envelope by `SecurityErrorResponder`
  directly inside the filter — the filter runs downstream of `ExceptionTranslationFilter`, so
  it cannot rely on the chain's access-denied handler (ADR-0003 D-2 erratum).
- Root may override to any existing ACTIVE branch, unchecked against assignments, but a
  bad/archived uid is still 403 — root must not operate in a phantom scope (ADR-0003 D-4).

The shell's branch selector reads the caller's own assignments from the self-scoped
`GET /api/v1/auth/my-branches` (gated `isAuthenticated()`), so switching one's own branch
does not require an admin permission (ADR-0003 D-6).

### 3.3 `ScopeGuard.assertCanActIn`

`ScopeGuard` (`com.erp.platform.security`) is the single home for the root-bypass +
same-company rule (ADR-0002 D-4):

- `assertCanActIn(RequestContext.get(), companyId)` — root short-circuits to allow; else the
  active company must equal `companyId`, otherwise a 403 (`ForbiddenException`). A null active
  company (no-branch state) is a 403.
- `canActOn(principal, targetType, uid)` — resolves a target uid to its owning company and
  compares it to the active company; this is what `@perm.scoped` calls.

Target-op scope checks live in the gate (`@perm.scoped`). The two **body-scoped** cases —
`UserRole.grant/revoke` and `Branch.create`, where the scoping company is in the request
body, not a path uid — call `ScopeGuard.assertCanActIn` directly in the service. Both paths
converge on `ScopeGuard`, so root-bypass and the same-company predicate exist exactly once.

## 4. Audit trail

Every access-significant action leaves an **append-only** record in `audit_log`
(`com.erp.platform.audit`, ADR-0004). It is the platform's cross-cutting audit table, used by
IAM and every later module.

- **Same-transaction guarantee.** `AuditService.record(...)` is
  `@Transactional(propagation = MANDATORY)` — it joins the caller's transaction, so the audit
  row commits or rolls back atomically with the business change. A rolled-back business
  transaction writes no audit row, structurally (ADR-0004 D-2).
- **Explicit emit, not an aspect.** Each mutating service calls `record(...)` explicitly so
  the trail captures the resolved target id, scope, and status transition that generic AOP
  advice could not see.
- **What is recorded:** actor user, action, target (type + id), company/branch scope (read
  from `RequestContext` post branch-override — the scope the action actually ran in),
  timestamp, and IP for login events. Login / lockout events (`LOGIN.SUCCESS`, `LOGIN.FAIL`,
  `ACCOUNT.LOCKED`) are emitted inside the `REQUIRES_NEW` lockout transaction;
  `LOGIN.FAIL` for an unknown username carries a NULL actor with `usernameAttempted` in detail
  (ADR-0004 D-3).
- **`detail` (JSONB):** identifying / context fields and before/after **status** for
  lifecycle transitions (enable/disable). Profile-field edits record only the **fact** of the
  change, not old→new values, to minimise PII. **Never** stored: password hashes, raw
  passwords, token values/hashes, or JWT contents (ADR-0004 D-6).
- **Append-only enforcement** is in the application + CI, not a DB trigger (owner ruling,
  ADR-0004 D-5): `AuditService` exposes only `record(...)` and read queries (no update/delete);
  an ArchUnit rule fails the build if any class outside `com.erp.platform.audit` depends on
  `AuditRepository` or if `AuditService` grows a mutation method; and the app's DB role is
  granted `INSERT, SELECT` on `audit_log` only — not `UPDATE`/`DELETE`.
- **Read API:** `GET /api/v1/audit`, gated `@perm.has('AUDIT.VIEW')`, org-wide read, with
  AND-combined filters (`actorUid`, `action`, `targetType`, `targetUid`, `from`/`to`) and
  Spring `Pageable` (default sort `at,desc`; size 50; cap 200), returning `PageMeta` in the
  `ApiResponse.meta` slot (ADR-0004 D-7).

## 5. Public endpoints

Only four endpoints are public; every other `/api/**` route requires a valid bearer token
**and** passes its method-security gate. This allowlist is asserted by
`EndpointAuthorizationTest`.

| Endpoint | Why public |
|---|---|
| `POST /api/v1/auth/login` | Mint the first token. |
| `POST /api/v1/auth/refresh` | Rotate tokens without a session. |
| `POST /api/v1/auth/logout` | Invalidate a refresh token. |
| `GET /api/v1/health` | Liveness probe. |

`GET` requests **outside** `/api` are served publicly so the Angular SPA shell, built assets,
and client-side deep-links load (the API above is matched first and stays gated). `/actuator/**`
and the springdoc paths (`/v3/api-docs/**`, `/swagger-ui/**`) are permitted at the filter
level; in production the springdoc surface is disabled by config and the Prometheus endpoint is
moved to a separate internal management port (see [05-deployment-ops.md](05-deployment-ops.md)).
CSRF is disabled — correct for a stateless, token-authenticated API with no session cookie.

## 6. Secret handling

- **12-factor config.** No secret is hardcoded anywhere in `backend/src/main`; every secret
  is supplied as an environment variable (`${ENV_VAR:default}` in `application.yml`).
- **JWT signing key** (see [docs/ops/jwt-keys.md](../ops/jwt-keys.md)). Two modes:
  - `dev-in-memory` (the dev default) generates a fresh RSA keypair at JVM start — every
    restart invalidates all tokens, which is fine for local dev.
  - `file` (the **production default**, hard-wired in the prod compose) reads stable PEM key
    files from disk so tokens survive restarts and multiple API replicas can share one key.
    Keys are generated by `infra/prod/generate-jwt-keys.sh` (PKCS8 private / X.509 public),
    are gitignored (`*.pem`), bind-mounted read-only into the container, and `chmod 600` on
    the host. `private.pem` can forge any user's token — treat it as a top-tier secret kept in
    a secret store, separate from DB backups.
- **Bootstrap admin password** has **no default**: `BootstrapRunner` is fail-closed — it
  refuses to start if `ERP_BOOTSTRAP_ADMIN_PASSWORD` is blank or a known placeholder, and
  enforces a minimum length. `ERP_BOOTSTRAP_ENABLED` defaults to `false`; it is set `true`
  only for the first deploy on a fresh DB, then immediately unset (it is idempotent, so a
  later restart with it `false` is safe). Leaving it `true` would re-attempt bootstrap on
  every restart and expose the password to `docker inspect`.
- **Passwords** are BCrypt-hashed at cost 12; **refresh tokens** are SHA-256-hashed at rest.
- **401/403** responses are enveloped without leaking which check failed or whether a username
  exists (no enumeration). Unexpected 500s log the full stack trace server-side but return only
  `"An unexpected error occurred."` to the client (ADR-0038 D-1).
- **Dependency hygiene:** Dependabot opens weekly PRs; `npm audit --audit-level=high` gates
  the web CI; the OWASP dependency-check runs on demand (see
  [docs/ops/security-sweep.md](../ops/security-sweep.md)).

The overall security posture — what is hardened and what is deferred — is catalogued in
[ADR-0038](../decisions/0038-production-hardening.md) (HTTP security headers / CSP and a JWT
issuer validator are explicitly deferred there, with their conditions for revisiting).

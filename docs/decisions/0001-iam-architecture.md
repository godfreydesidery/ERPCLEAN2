# 0001 — IAM module architecture & data-model decisions

- **Status:** Accepted
- **Date:** 2026-06-05
- **Deciders:** solutions-architect (with owner-ratified requirements)
- **Context source:** [docs/requirements/iam.md](../requirements/iam.md) (RATIFIED), [USER-STORIES.md](../../USER-STORIES.md) US-IAM-001..010

## Context

IAM is the first module and the security/multi-tenancy spine for everything after it. The business
requirements are ratified; this ADR resolves the **technical** calls the requirements left open, so
the data model and code have one authoritative reference. The fixed substrate
([PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md)): Spring Boot 3 / Java 21, **PostgreSQL 15+**,
modular monolith (`com.erp`), `ApiResponse<T>` envelope, uid/id duality, multi-tenancy by
`company_id` + `branch_id`, RBAC by permission, transactional outbox for cross-module effects.

Seven decisions follow. Each is small but load-bearing.

## Decision

### D-A — Tenancy columns on IAM's own tables
IAM tables are partly **global** (organisation, app_user, role, permission) and partly
**scoped** (branch, user_branch, user_role). We do **not** force `company_id`/`branch_id` onto the
global tables. Instead:
- `organisation`, `permission`, `role`, `app_user` — **global** (no tenant columns). `app_user` is
  org-wide because usernames are org-unique and a user may span companies (D5 in iam.md).
- `company` carries `organisation_id`; `branch` carries `company_id`.
- `user_role` carries `company_id` (required) + `branch_id` (nullable = company-wide).
- `user_branch` carries `branch_id` (its company is reachable via the branch).
- `refresh_token`, `audit_log` carry the context they were created in (`company_id`/`branch_id`
  nullable, because login/bootstrap events can precede a branch context).

The repository tenant-predicate base interface (conventions §3.2) applies to **transactional
business tables in later modules**, not to these IAM administration tables, which are managed by
permission-gated admin endpoints and (for cross-tenant ops) the super-admin. Tenant isolation for
IAM is enforced by **permission + scope checks in the service layer**, not a blanket row predicate —
because administering IAM is inherently cross-branch.

### D-B — Default branch & default company: single source of truth
A user's default is stored **once**, as a flag on the assignment, not duplicated as FK columns on
`app_user`:
- `user_branch.is_default BOOLEAN` — exactly one true per user (enforced by a **partial unique
  index** `WHERE is_default`).
- The user's **default company** is *derived*: it is the company of the default branch. We do **not**
  store `default_company_id` separately (avoids a second invariant that can drift from the branch).
- Rationale: BR-1 (one default branch/user) and "default must be assigned" (FR-IAM-17) become a
  single DB-enforceable fact. `app_user` keeps **no** `default_branch_id` column — the truth lives on
  `user_branch`. (Trade-off noted in Alternatives.)

### D-C — `is_default` uniqueness enforced in DB, not just code
PostgreSQL partial unique indexes give us:
- `CREATE UNIQUE INDEX uq_user_branch_default ON user_branch (user_id) WHERE is_default;` →
  at most one default branch per user (BR-1).
- `CREATE UNIQUE INDEX uq_branch_company_default ON branch (company_id) WHERE is_default;` →
  at most one default branch per company (BR-2).
The service layer still orchestrates "set new default → clear old" in one transaction; the index is
the backstop that makes a bug a constraint violation rather than silent corruption.

### D-D — Auto-fallback (earliest-assigned) is deterministic via `assigned_at`
`user_branch.assigned_at TIMESTAMPTZ NOT NULL`. "Earliest-assigned remaining branch" (FR-IAM-19) =
`ORDER BY assigned_at ASC, id ASC LIMIT 1` over the user's remaining assignments. The `id` tiebreak
makes it total even if two assignments share a timestamp. Fallback runs in the same transaction that
removes the old default.

### D-E — RBAC enforcement: permission set resolved per (user, company, branch)
A JWT carries `userId`, `username`, active `companyId`, active `branchId`, and `isRoot`. It does
**not** embed the full permission set (it changes with branch and would bloat/stale the token).
Instead a `PermissionResolver` computes the **effective permissions** for the *active* company+branch
on each request from `user_role` (+ `role_permission`), cached briefly per (user, company, branch).
`@PreAuthorize("hasPermission('USER.MANAGE')")` checks against that resolved set. Super-admin
(`isRoot`) short-circuits to "allowed" and is always audited.
- Why not embed perms in the JWT: branch switching changes the effective set without re-login
  (FR-IAM-18); a fresh resolve per active scope is correct and cache-cheap.

### D-F — Branch switch via header, validated every request
`RequestContext` is built by a servlet filter from the JWT + an optional `X-Branch-Uid` override
header. The filter **must** verify the override branch is in the caller's active `user_branch`
assignments before accepting it; otherwise 403 (FR-IAM-18). The active company is the override
branch's company. No DB write on switch — it's request-scoped context only.

### D-G — uid scheme: ULID (Crockford), stored as `VARCHAR(26)`
Every externally exposed IAM entity gets a `uid` (ULID): sortable, collision-resistant, URL-safe,
no central coordination. Stored `VARCHAR(26)`, unique. Numeric `id BIGINT` (identity/sequence) for
internal FKs. Long ids serialise as JSON strings globally (conventions §3.3). `refresh_token` uses a
`token_hash` (not a uid) as its lookup key; `audit_log` is internal (numeric id only, no uid needed).

## Consequences

- **Easier:** one place to enforce "one default branch" (DB index); branch switching is a pure
  context operation; permission changes/branch switches take effect without re-issuing tokens;
  no `default_company_id`/`default_branch_id` drift on `app_user`.
- **Harder / to watch:** the `PermissionResolver` cache must invalidate on role/permission changes
  (cache key includes user+company+branch; bust on `user_role` / `role_permission` writes).
  Reading a user's "default branch" requires joining `user_branch WHERE is_default` rather than a
  column on `app_user` (a deliberate normalisation trade-off — indexed, cheap).
- **Migration/delivery cost:** ~11 tables + seed migration (permissions, system roles, bootstrap).
  One Flyway baseline while schema is pre-stable (conventions §3.6 — edit-and-recreate, don't stack).
- **Security:** the branch-override validation (D-F) and the resolve-per-scope model (D-E) are the
  highest-risk surfaces — flagged to security-engineer for review before sign-off.

## Alternatives considered

- **Store `default_branch_id` on `app_user`** (instead of D-B's flag-on-assignment). Simpler read,
  but creates a second invariant ("the column must point at an existing assignment") enforced only in
  code, which can drift. Rejected: the partial-unique-index approach makes the invariant a DB fact.
- **Embed full permission set in the JWT** (instead of D-E resolve-per-request). Fewer DB hits per
  request, but the token goes stale on branch switch and on any role change, and bloats. Rejected:
  branch switching without re-login (FR-IAM-18) is a hard requirement; staleness is worse than a
  cached resolve.
- **Blanket tenant row-predicate on all IAM tables** (instead of D-A's service-layer scope checks).
  Uniform with later modules, but IAM administration is inherently cross-branch (an admin manages
  many branches' users), so a row predicate would fight the use case. Rejected for IAM; the predicate
  still applies to transactional modules later.
- **UUID v4 for uids** (instead of ULID). Ubiquitous, but not time-sortable and 36 chars. Rejected:
  ULID is sortable and shorter; useful for cursor pagination later.

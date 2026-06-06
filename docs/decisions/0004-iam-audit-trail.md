# 0004 — IAM audit trail: append-only, same-transaction, explicit emit

- **Status:** Accepted
- **Date:** 2026-06-06
- **Deciders:** solutions-architect (owner rulings confirmed; security-engineer review on sign-off)
- **Context source:** requirements/iam.md FR-IAM-23, US-IAM-010; DATA-MODEL.md §1.11; ADR-0001
  (D-A tenancy, D-G internal-table/uid), ADR-0002 (scope guard / root bypass), ADR-0003 (branch
  override); docs/security/findings.md F5, F8, F9.

## Context
Slice 6 closes IAM: every access-significant action must leave an append-only record (actor, action,
target, scope, time, IP for login), readable through a filterable, paged, `AUDIT.VIEW`-gated endpoint.
The table is specified in DATA-MODEL §1.11. This ADR fixes the technical calls: placement, the emit
mechanism + same-transaction guarantee (reconciled with the login path's `REQUIRES_NEW` bookkeeping),
the event catalogue and `detail` policy, how append-only is enforced, the first paged read contract,
and folding in F8 (archived-company branch) and F9 (mid-session disable). F5's interim root-bypass
`log.info` is replaced by this trail.

## Decision

### D-1 — `audit_log` lives in `com.erp.platform.audit` (cross-cutting)
Shared by every future module (DATA-MODEL §1.11 "IAM and later, global"); placing it in `modules/iam`
would force later modules to import `iam.service` to audit — a `ModuleBoundaryTest` violation. As
platform it is the established cross-cutting-spine exception (cf. `PermissionResolver` reading
`iam.repository`, ADR-0002). `actor_user_id`/`target_id` are plain `BIGINT` columns, **not** JPA
associations, so `platform.audit` depends on no module's entities; the FK is SQL-only.

### D-2 — Explicit `AuditService.record(...)`, joined to the caller's transaction (`MANDATORY`)
Reject an AOP aspect (brittle method→action/target mapping; can't see the target id resolved
mid-method or before/after) and the outbox (that is for async cross-module dispatch + crash
durability, not an intra-TX record read from its own table). `AuditServiceImpl.record(...)` is
`@Transactional(propagation = MANDATORY)`: it joins the active TX, so the audit INSERT commits/rolls
back atomically with the business change. A rolled-back business TX writes no audit row —
structurally guaranteed, not by discipline.

### D-3 — Login/lockout audited inside `LoginAttemptService`'s `REQUIRES_NEW` transaction
`LOGIN.SUCCESS`/`LOGIN.FAIL`/`ACCOUNT.LOCKED` are emitted from `LoginAttemptService`
(recordSuccess/recordFailure), which run `REQUIRES_NEW` so they survive the `AuthenticationException`
rollback. Because `record` is `MANDATORY` it joins that inner TX and commits with the lockout
bookkeeping. `LOGIN.FAIL` has no authenticated principal: actor = resolved user id or NULL (unknown
username, with `usernameAttempted` in detail); ip is threaded in from `AuthController` via
`AuthService.login(.., ip)`. A `record(event, actorId, ip)` overload serves this unauthenticated path;
the no-arg overload reads `RequestContext` for authenticated emits.

### D-4 — Actor/scope/IP from `RequestContext`; add `ip` to `RequestContext.Principal`
`actor_user_id`/`company_id`/`branch_id` are read from `RequestContext.get()` at emit time (post
branch-override — the scope the action ran in); `at = now()`. `RequestContext.Principal` gains an
`ip` field, populated by `JwtRequestContextFilter` from the request, so authenticated emits get IP
for free. System/bootstrap events carry NULL actor.

### D-5 — Append-only enforced in the APPLICATION + CI (no DB trigger) — OWNER RULING
Per the owner principle "the database stores data; operations live in the application," append-only is
NOT enforced by a DB trigger. It is enforced by:
- **Application:** `AuditService` exposes only `record(...)` + read queries — no update/delete methods;
  the `AuditLog` entity has no post-persist setters.
- **CI (ArchUnit):** a rule that no class outside `com.erp.platform.audit` depends on
  `AuditRepository`, and `AuditService` declares no `delete`/`update`/`remove` method — so a mutation
  path can't be introduced without failing the build.
- **Deployment (config, not DB logic):** the app's DB role is granted `INSERT, SELECT` on `audit_log`
  and explicitly **not** `UPDATE`/`DELETE` — documented in ARCHITECTURE's deploy section. This is a
  permissions/infra concern, not behaviour in the database.

### D-6 — `detail` JSONB: context + status transitions; fact-only for profile edits — OWNER RULING
`detail` carries identifying/context fields and before/after **status** for lifecycle transitions
(enable/disable). For profile-field edits (email/phone/display name) it records only the FACT of the
change (`USER.UPDATE` with empty/minimal detail), not old→new values — minimising PII in the trail.
**Never** password hashes, raw passwords, token values/hashes, or JWT contents.

### D-7 — Read API: `Pageable` + `PageMeta` in `ApiResponse.meta`; `AUDIT.VIEW` gate
`GET /api/v1/audit`, `@perm.has('AUDIT.VIEW')`, org-wide read. Optional AND-combined filters:
`actorUid`, `action`, `targetType`, `targetUid`, `from`/`to`; Spring `Pageable` (default sort
`at,desc`; size 50; cap 200). First paged endpoint: establish a reusable `PageMeta` record in
`platform.common.api` `{page,size,totalElements,totalPages,hasNext}`, returned via the existing
`ApiResponse.meta` slot. Page over cursor for v1 (boring first-class Spring); ULID cursor paging is a
future option for hot tables.

### D-8 — Fold in F8 (FIX) and F9 (FIX) — OWNER RULING
- **F8:** a branch under a non-ACTIVE company must not scope a session. Add a company-ACTIVE check to
  both `JwtRequestContextFilter.resolvePrincipal` (override) and `AuthServiceImpl.issueSession`
  (default branch), via a shared `Branch.isUsableForSession()` predicate (branch ACTIVE && company
  ACTIVE).
- **F9 (FIX):** re-check the user is ACTIVE per request in `JwtRequestContextFilter` (one indexed PK
  lookup — same order as ADR-0002's accepted per-request read); a disabled user is rejected (401) on
  their next request rather than after the access-token TTL. Application-layer check, consistent with
  D-5's principle.

### D-9 — Root bypass audit (replaces F5): every root ACTION + cross-company bypass — OWNER RULING
The F5 interim `log.info` fires on every gate check (several per request) — too noisy for a row each.
Root-initiated mutations are audited by their normal action rows (actor = root). A distinct
`ROOT.BYPASS` audit row is written only from `ScopeGuard` when root acts OUT of its active company
(the rare, security-interesting bypass). The per-check `log.info` may remain for observability. This
audits every root *action* (satisfying FR-IAM-21), not every root permission *check*.

## Consequences
- Same-TX guarantee is structural; one emit mechanism for both normal and login paths; append-only
  defended in app + CI (+ deploy grant); paging convention set once; F8 and F9 closed; F5 replaced.
- Every mutating service gains one explicit audit line per event (verbose but precise and
  reviewable — the deliberate trade vs an aspect). `RequestContext.Principal` grows an `ip` field.
  F9 adds one PK read per authenticated request.
- Migration: append `audit_log` to V1 — the LAST baseline edit before the post-Slice-6 freeze; then
  additive V2+. No outbox, no new infra, no DB triggers.

## Alternatives considered
- AOP `@Aspect` emit — rejected: brittle method→action/target/detail mapping; the data the trail needs
  is exactly what generic advice can't see; fails the reviewer-visible/fail-closed bar (ADR-0002).
- Transactional outbox for audit — rejected: outbox is for async cross-module dispatch + crash
  durability; audit is a synchronous intra-TX record read from its own table.
- DB `BEFORE UPDATE OR DELETE` trigger for append-only — rejected per the owner principle (logic stays
  in the application, not the database); enforced in app + CI + deploy grant instead.
- Cursor/keyset paging from day one — deferred: offset `Pageable` is the first-class option and the
  table isn't hot yet; ULID enables cursor paging later without a contract break.

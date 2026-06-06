# Security Review — Slice 3 (RBAC: roles, permissions, enforcement)

> Reviewer: security-engineer · Date: 2026-06-06 · Scope: permission resolution per active scope,
> `@PreAuthorize` enforcement across IAM endpoints, role/grant admin API, `/auth/me`. Verdict:
> **PASS for dev/QA** — the cross-company BLOCKER and three runtime enforcement bugs found in review
> are all fixed and covered by tests; production-gating items from Slice 2 still stand.

## Threat model (summary)
- **Assets:** the IAM admin surface (company/branch/user/role CRUD), the permission catalogue, the
  super-admin (`is_root`).
- **Trust boundary:** the controller layer, gated by `@PreAuthorize` against a permission set
  resolved per active scope (ADR-0001 D-E, ADR-0002).
- **Primary threats:** acting in a company/branch the caller wasn't granted (horizontal escalation),
  exercising a permission the caller doesn't hold (vertical escalation), a forgotten gate failing
  open, stale permissions after grant/revoke, unaudited root bypass.

## Controls verified (good)
- **[OK] Resolve-per-scope (D-E).** `PermissionResolver` computes effective codes from active
  `user_role` (+ role permissions) for the active `(user, company, branch)`; permissions are **not**
  embedded in the JWT, so branch switching / role changes take effect without re-login. Verified by
  `PermissionResolverIT` (grant flips empty→permitted; company-scoped; branch-scoped vs company-wide).
- **[OK] Cross-company isolation.** `@perm.scoped(#uid,'type','CODE')` asserts the target's owning
  company equals the caller's active company (root bypasses). Verified over HTTP
  (`RbacEnforcementHttpIT.nonRoot…CompanyB → 403`) and at the unit level (`ScopeGuardIT`).
- **[OK] Deny-by-default.** `EndpointAuthorizationTest` fails the build if any `com.erp.api` handler
  lacks `@PreAuthorize` (except an explicit public allowlist) — a forgotten gate can't ship open.
- **[OK] Cache invalidation (R3).** `PermissionResolver.invalidate()` is called on every RBAC write
  (role-permission edit, grant, revoke); verified a revoke flips the resolved set immediately, not
  after the 30s TTL backstop (`PermissionResolverIT` cache-bust tests).
- **[OK] System-role guard (BR-7).** `ORG_ADMIN` (is_system) cannot be archived; seeded with all IAM
  permissions. `RoleServiceImplIT`.
- **[OK] Both-required / BR-5 / duplicate-grant.** Grant enforces branch∈company, scope check, and
  the `existsActiveGrant` duplicate guard. `UserRoleServiceImplIT`.
- **[OK] No-leak 401/403.** `SecurityErrorResponder` (filter-level) and `GlobalExceptionHandler`
  (method-security) both return the `ApiResponse` envelope with a generic message — the missing
  permission code is never disclosed.

## Findings (all resolved this slice)

### [BLOCKER → FIXED] Cross-company escalation: a permission check is not a scope check
`hasPermission('COMPANY.MANAGE')` alone confirmed *what* the caller could do, not *where* — a user
with `COMPANY.MANAGE` in their active company could edit/archive a **different** company by uid.
**Fix:** ADR-0002 — target ops use `@perm.scoped(#uid,'type','CODE')`, which adds the same-company
assertion via `ScopeGuard`; root bypasses. Confirmed denied over HTTP.

### [HIGH → FIXED] Invalid 1-arg `hasPermission` SpEL → 400
Spring's SpEL `hasPermission` has no 1-arg form, so `@PreAuthorize("hasPermission('CODE')")` threw at
evaluation (HTTP 400) for create/list gates. **Fix:** replaced the `PermissionEvaluator` with a
bean-expression gate `@perm.has('CODE')` / `@perm.scoped(...)`. (Found by HTTP-level testing; the
service-level IT had missed it.)

### [HIGH → FIXED] `RequestContext` null over HTTP (filter ordering)
`JwtRequestContextFilter` ran **before** `BearerTokenAuthenticationFilter`, so `RequestContext` was
empty when `@perm`/`me()` read it — `/auth/me` 401'd and every gate saw a null principal. **Fix:**
register the context filter **after** the bearer filter. Regression-guarded by `RbacEnforcementHttpIT`.

### [MEDIUM → FIXED] Method-security denial returned 500 instead of 403
`GlobalExceptionHandler`'s catch-all mapped `AuthorizationDeniedException` to 500 before
`SecurityErrorResponder` could route it. **Fix:** explicit `AccessDeniedException → 403` envelope
handler. The gate still denied; only the status/message were wrong. Verified non-root denial → 403.

### [HIGH → MITIGATED] Root bypass was unaudited
`is_root` short-circuits every gate; the full audit aspect is Slice 6. **Mitigation:** an interim
`log.info` line is emitted on every root short-circuit (actor, method, scope) so root actions leave a
trail now. Residual: root-flag revocation only takes effect within the access-token TTL (claim is
minted at login) — accepted; keep the access TTL short.

## Process note (why the bugs slipped to runtime)
The first IT round set `RequestContext` manually and called services directly, so it passed while the
app was broken over HTTP. Added `RbacEnforcementHttpIT` (MockMvc through the full security filter
chain + SpEL) — this is now the standing guard for enforcement. **Rule going forward:** any
permission-gated endpoint needs an HTTP-level test, not only a service test.

## Carried-forward (production-gating, from Slice 2 — still open)
- **G1** Stable RS256 key from a secret store (dev key is in-memory).
- **G2** Access-token denylist on logout.
- **R2** (build plan) production JWT key. **Audit aspect** (Slice 6) replaces the interim root-bypass log.

## Verdict
**PASS for dev/QA.** RBAC enforcement is correct and isolated per company; the dev-open window (R1) is
closed. Do not deploy to production before the carried-forward G1/G2 items.

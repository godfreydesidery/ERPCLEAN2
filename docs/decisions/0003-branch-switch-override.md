# 0003 — Runtime branch-switch override: validation site & 403 rendering

- **Status:** Accepted
- **Date:** 2026-06-06
- **Deciders:** solutions-architect (root-scope ruling confirmed by owner; security-engineer review on sign-off)
- **Context source:** ADR-0001 D-E/D-F, [requirements/iam.md](../requirements/iam.md) FR-IAM-18/19/20/21, [iam-build-plan.md](../iam-build-plan.md) Slice 5

## Context
ADR-0001 D-F fixed *that* the active branch is overridden per-request by an `X-Branch-Uid` header
validated against the caller's assignments. Slice 5 fixes *how*: where the validation runs, how a
failure becomes a 403 `ApiResponse` envelope from inside a servlet filter (upstream of
`@RestControllerAdvice`), and how root — which has no/looser `user_branch` rows — behaves. The
override is the highest-risk IAM surface; the mechanism must be one obvious path.

## Decision

### D-1 — Validation lives in `JwtRequestContextFilter`
The header is resolved and validated in the same filter that builds `RequestContext` from the JWT.
The JWT scope (minted at login) is the DEFAULT; the header changes the REQUEST scope only — no token
re-mint, no DB write. The header carries a branch **uid** (ULID); the filter resolves uid → Branch
via `BranchRepository.findByUid`, reads `branch.company.id`, and (for non-root) verifies an ACTIVE
`user_branch` assignment via `UserBranchRepository.findByUserIdAndBranchId`.

### D-2 — A failed override renders the 403 envelope directly via `SecurityErrorResponder`
**(Corrected during implementation — see erratum.)** The filter catches the
`AccessDeniedException` from its override-validation step and renders the 403 `ApiResponse` envelope
by calling `SecurityErrorResponder.handle(...)` directly, then returns without continuing the chain.
It does NOT continue the chain on a rejected override.

> **Erratum (caught by `Slice5HttpIT`):** the original design assumed `ExceptionTranslationFilter`
> sat *upstream* and would catch an exception thrown by this filter. It does not. The chain order is
> `… → ExceptionTranslationFilter → BearerTokenAuthenticationFilter → JwtRequestContextFilter → …`,
> so `ExceptionTranslationFilter` runs **before** our filter; an exception thrown here propagates
> *out* of the chain uncaught (a container 500), never reaching the `accessDeniedHandler`. The HTTP
> integration tests surfaced this — exactly why gated behaviour needs HTTP-level tests, not only
> service tests. Fix: catch `AccessDeniedException` inside `doFilterInternal` and invoke
> `SecurityErrorResponder.handle(...)` ourselves (the same component the chain's accessDeniedHandler
> uses), producing the identical envelope. `RequestContext` is still cleared in `finally`.

### D-3 — Fail closed on every override defect
Header present but: branch uid unknown → 403; branch not ACTIVE (archived mid-session) → 403; no
matching ACTIVE assignment → 403. No header → keep JWT default scope (unchanged). Zero-assignment
user → null company/branch → read-only (FR-IAM-19), unchanged.

### D-4 — Root may override to any existing ACTIVE branch, unchecked against assignments
**(Owner-confirmed.)** Root (`is_root`) bypasses scoping (D-E, FR-IAM-21). With an override header,
root's request context is set to the resolved branch's company/branch IF the branch exists and is
ACTIVE (a bad/archived uid is still 403 — root must not operate in a phantom scope), but the
`user_branch` assignment check is **skipped**. Root authorization never depends on this context.
Rationale: root is the seed/recovery actor and usually has no assignments; checking them would lock
it out of switching.

### D-5 — Per-request resolve, no cache
The two override lookups are unique-index point-reads (`uq_branch_uid`, `uq_user_branch_user_branch`).
They are NOT cached: a revoked assignment or an archived branch must take effect on the next request,
which is the point of D-F. No new index is needed.

### D-6 — Self-scoped `GET /api/v1/auth/my-branches`, gated `isAuthenticated()`
The shell selector reads the caller's own ACTIVE branch assignments from a self-scoped endpoint
(`AuthController`), NOT from `GET /user-branches?userUid=` which requires `USER.VIEW`. A user reading
their own branches to switch is not an admin operation and must not require an admin permission.

## Consequences
- **Easier:** branch switch is a pure request-scoped context op; one rendering path for all security
  403s; the self-endpoint decouples "switch my branch" from `USER.VIEW`.
- **Harder / to watch:** `JwtRequestContextFilter` now reads two IAM repositories (same security-spine
  exception as `PermissionResolver`/`ScopeGuard`, ADR-0002 — not a module-boundary violation). Two
  extra point-reads per request when the header is present (accepted).
- **Security:** highest-risk surface; HTTP-level integration tests assert the full filter chain —
  switch-to-assigned, switch-to-unassigned → 403, branch-scoped-permission flip, archived/removed →
  403, root override, no-branch read-only — before sign-off.

## Alternatives considered
- **Throw `AccessDeniedException` and let the chain's `accessDeniedHandler` render it.** Originally
  chosen, then rejected (see D-2 erratum): this filter runs downstream of `ExceptionTranslationFilter`,
  so a thrown exception escapes the chain uncaught. We instead invoke the SAME `SecurityErrorResponder`
  the handler uses, directly — one rendering component, no divergence, and it actually works from this
  filter position.
- **Validate in a `@PreAuthorize`/HandlerInterceptor.** Too late — `RequestContext` and the
  PermissionResolver must already carry the overridden scope when method security runs.
- **Cache the assignment check.** Reintroduces the staleness D-F exists to prevent. Rejected.
- **Carry a numeric branch id in the header.** Leaks internal ids and breaks the uid-addresses-
  everything convention (PROJECT-CONVENTIONS §3.3). Rejected.

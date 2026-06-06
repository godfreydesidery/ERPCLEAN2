# Security findings log

> Running log of security findings across slices, with status. Per-slice review narratives live in
> `slice<N>-*-review.md`; this file is the at-a-glance tracker (the "no silent fix / audit trail"
> rule). Status: OPEN · FIXED · MITIGATED · ACCEPTED · DEFERRED.

| # | Slice | Severity | Finding | Status | Resolution |
|---|---|---|---|---|---|
| F1 | 3 | BLOCKER | Cross-company escalation — a permission check (`hasPermission('CODE')`) wasn't a scope check; a user could act on another company's resource by uid. | FIXED | ADR-0002 `@perm.scoped(#uid,'type','CODE')` asserts target company == active company. `RbacEnforcementHttpIT`. |
| F2 | 3 | HIGH | Invalid 1-arg `hasPermission` SpEL → 400 on create/list gates. | FIXED | Replaced evaluator with `@perm.has(...)` bean expression. |
| F3 | 3 | HIGH | `RequestContext` null over HTTP (filter ordering before bearer auth) → `/auth/me` 401, gates saw null principal. | FIXED | `JwtRequestContextFilter` registered after `BearerTokenAuthenticationFilter`. |
| F4 | 3 | MEDIUM | Method-security denial returned 500, not 403 (catch-all swallowed `AuthorizationDeniedException`). | FIXED | Explicit `AccessDeniedException → 403` envelope handler. |
| F5 | 3 | HIGH | Root bypass unaudited (full audit aspect is Slice 6). | MITIGATED | Interim `log.info` on every root short-circuit; full aspect in Slice 6. Residual: root-flag revoke takes effect within access-token TTL. |
| F6 | 4 | MEDIUM | **Archived branch could still scope a login session** — `AuthServiceImpl.issueSession` resolved the default branch without a status filter, and `BranchServiceImpl.archiveByUid` didn't touch `user_branch.is_default` rows pointing at the archived branch. No cross-tenant exposure (the company is still validly owned), but continued access scoped to a decommissioned branch. | FIXED | Login now skips a non-ACTIVE default branch (user lands read-only until reassigned); `archiveByUid` clears `user_branch.is_default` for the branch and promotes each affected user's earliest-remaining (D-D). Covered by Slice-4 IT. |
| F7 | 5 | (design erratum) | Branch-switch override 403 wasn't rendered: ADR-0003 assumed a thrown `AccessDeniedException` would reach the chain's `accessDeniedHandler`, but `JwtRequestContextFilter` runs *downstream* of `ExceptionTranslationFilter`, so it escaped uncaught (would be a container 500). Caught by `Slice5HttpIT` (HTTP-level). | FIXED | Filter catches `AccessDeniedException` and renders via `SecurityErrorResponder.handle` directly; returns without continuing the chain; `RequestContext` still cleared in `finally`. ADR-0003 D-2 erratum recorded. |
| F8 | 5 | LOW | **Override into an ACTIVE branch under an ARCHIVED company isn't blocked** — the override (and `issueSession`) check `branch.status == ACTIVE` but not `branch.company.status`. Company archival doesn't cascade to branches, so a user could switch into a live branch of an archived company. Latent tenant-lifecycle gap, not a Slice-5 regression; no archive-company-with-live-branches workflow is exercised today. | OPEN | Add a company-ACTIVE check to the override resolve + `issueSession`, or cascade-archive branches on company archive. Target Slice 6. |
| F9 | 5 | LOW | **A user disabled mid-session keeps scope until the access-token TTL expires** — `resolvePrincipal`/`me`/`myBranches` trust the still-valid access JWT without re-checking `user.isActive()`. Pre-existing access-token-lifetime property surfaced on the override path. | OPEN (accepted residual) | Bounded by the short access-token TTL. Either accept-as-documented or add an `isActive` gate in `JwtRequestContextFilter`. Owner: security. |

## Production-gating (carried, still OPEN)
- **G1** (Slice 2): stable RS256 signing key from a secret store — dev key is in-memory (everyone logged out on restart; not prod-safe).
- **G2** (Slice 2): access-token denylist on logout (access token currently valid until expiry after logout).

These block a production deploy; they do not block dev/QA. Track to the pre-prod hardening pass.

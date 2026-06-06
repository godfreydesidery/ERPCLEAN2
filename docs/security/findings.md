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

## Production-gating (carried, still OPEN)
- **G1** (Slice 2): stable RS256 signing key from a secret store — dev key is in-memory (everyone logged out on restart; not prod-safe).
- **G2** (Slice 2): access-token denylist on logout (access token currently valid until expiry after logout).

These block a production deploy; they do not block dev/QA. Track to the pre-prod hardening pass.

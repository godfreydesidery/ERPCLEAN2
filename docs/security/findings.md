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
| F5 | 3 | HIGH | Root bypass unaudited (full audit aspect is Slice 6). | FIXED | Slice 6 (ADR-0004 D-9): every root ACTION is audited by its own `audit_log` row (actor=root); a distinct `ROOT.BYPASS` row records root acting cross-company. Interim per-check `log.info` demoted to DEBUG. The root-flag-revoke residual is closed by F9. |
| F6 | 4 | MEDIUM | **Archived branch could still scope a login session** — `AuthServiceImpl.issueSession` resolved the default branch without a status filter, and `BranchServiceImpl.archiveByUid` didn't touch `user_branch.is_default` rows pointing at the archived branch. No cross-tenant exposure (the company is still validly owned), but continued access scoped to a decommissioned branch. | FIXED | Login now skips a non-ACTIVE default branch (user lands read-only until reassigned); `archiveByUid` clears `user_branch.is_default` for the branch and promotes each affected user's earliest-remaining (D-D). Covered by Slice-4 IT. |
| F7 | 5 | (design erratum) | Branch-switch override 403 wasn't rendered: ADR-0003 assumed a thrown `AccessDeniedException` would reach the chain's `accessDeniedHandler`, but `JwtRequestContextFilter` runs *downstream* of `ExceptionTranslationFilter`, so it escaped uncaught (would be a container 500). Caught by `Slice5HttpIT` (HTTP-level). | FIXED | Filter catches `AccessDeniedException` and renders via `SecurityErrorResponder.handle` directly; returns without continuing the chain; `RequestContext` still cleared in `finally`. ADR-0003 D-2 erratum recorded. |
| F8 | 5 | LOW | **Override into an ACTIVE branch under an ARCHIVED company isn't blocked** — the override (and `issueSession`) checked `branch.status` but not `branch.company.status`. | FIXED | Slice 6 (ADR-0004 D-8): `Branch.isUsableForSession()` (branch ACTIVE && company ACTIVE) applied in `JwtRequestContextFilter.resolvePrincipal` (via `findWithCompanyByUid`) + `AuthServiceImpl.issueSession`. Login lands read-only, override → 403. `AuditF8HttpIT`. |
| F9 | 5 | LOW | **A user disabled mid-session keeps scope until the access-token TTL expires** — the filter trusted the still-valid JWT without re-checking `user.isActive()`. | FIXED | Slice 6 (ADR-0004 D-8): `JwtRequestContextFilter` re-checks `existsByIdAndStatus(userId, ACTIVE)` per request; a disabled user is refused (401) on the next request. One indexed PK lookup. `AuditF9HttpIT`. Also closes the F5 root-flag-revoke residual. |
| F10 | 6 | MEDIUM | **Audit read was org-wide** — any `AUDIT.VIEW` holder saw all rows incl. other companies'. Became live once `AUDIT.VIEW` was granted to a non-root role. | FIXED | `AuditReadService.search` now adds a `company_id = activeCompany` predicate for non-root callers (root stays org-wide); fail-closed (no active company ⇒ matches nothing). `AuditHttpIT.getAudit_nonRootHolder_seesOnlyOwnCompanyRows`. Branch-level scoping deferred (company-level chosen). |
| F11 | 6 | MEDIUM | **Deploy-time `REVOKE UPDATE, DELETE ON audit_log` not documented/applied** — the third leg of append-only (ADR-0004 D-5). Today append-only rests on app code + the package-scoped ArchUnit rule only; the app DB role likely holds full DML. | OPEN (pre-prod) | Add `REVOKE UPDATE, DELETE ON audit_log FROM <app_role>` to the deploy/runbook before production; record it in the deploy docs as the ADR promises. |

### Slice 6 LOW (post-ship hardening, not blocking)
- ArchUnit append-only rule fences the **package**, not the verb — a future method inside `platform.audit` could call `delete*`. Add a no-mutator assertion or a read-only repo interface.
- `usernameAttempted` (unauthenticated `LOGIN.FAIL` path) is written to `audit_log.detail` without a length cap — cheap pre-auth storage amplification. Truncate at the auth boundary.
- `ROOT.BYPASS` detail records raw numeric company ids, not uids (inconsistent with the uid wire convention). Cosmetic.

## Production-gating (carried, still OPEN)
- **G1** (Slice 2): stable RS256 signing key from a secret store — dev key is in-memory (everyone logged out on restart; not prod-safe).
- **G2** (Slice 2): access-token denylist on logout (access token currently valid until expiry after logout).
- **F11** (Slice 6): the `audit_log` no-UPDATE/DELETE grant (above) — pre-prod gate alongside G1/G2.

These block a production deploy; they do not block dev/QA. Track to the pre-prod hardening pass.

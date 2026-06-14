# 01 — IAM & Access: Test Cases

Exhaustive UI-driven (Playwright) + manual test cases for the IAM & ACCESS domain: authentication,
password policy, user/role lifecycle, role-permission management, role and branch assignment, and
the organisation/company/branch hierarchy with default-branch and multi-tenant isolation. Every case
below cites endpoints, permission codes, enum values and routes verified by reading the source.

## Modules / submodules covered

| Submodule | Controller (`@RequestMapping` base) | Frontend route(s) |
|---|---|---|
| Authentication | `AuthController` — `/api/v1/auth` (`login`, `refresh`, `logout`, `me`, `my-branches`) | `/login`; shell `/auth/me` re-hydrate; branch switcher in `shell.component.ts` |
| Users | `UserController` — `/api/v1/users` | `/admin/users` (`user-list`), `/admin/users/uid/:uid` (`user-detail`) |
| Roles | `RoleController` — `/api/v1/roles` (+ `/roles/permissions`) | `/admin/roles` (`role-list`), `/admin/roles/uid/:uid` (`role-edit`) |
| Role assignment | `UserRoleController` — `/api/v1/user-roles` | `/admin/role-grants` (`grant-role`); also embedded in `user-detail` |
| Branch assignment | `UserBranchController` — `/api/v1/user-branches` | embedded in `/admin/users/uid/:uid` (`user-detail`) |
| Organisation | `OrganisationController` — `/api/v1/organisations` (`list`, `current`) | resolved automatically (no dedicated screen); used by `company-list`, `grant-role`, `user-detail` |
| Companies | `CompanyController` — `/api/v1/companies` | `/admin/companies` (`company-list`) |
| Branches | `BranchController` — `/api/v1/branches` (+ `/uid/{uid}/default`) | `/admin/companies/:companyUid/branches` (`branch-list`) |

Supporting code read: `PermissionChecks` (`@perm.has`, `@perm.scoped`), `ScopeGuard`
(`canActIn`/`canActOn`/`assertCanActIn`), `PasswordPolicy`, `LoginAttemptService`, `AuthServiceImpl`,
`UserServiceImpl`, `RoleServiceImpl`, `UserRoleServiceImpl`, `UserBranchServiceImpl`,
`permission.guard.ts` (`requirePermission`), `http.interceptors.ts` (X-Branch-Uid / envelope / 401 / 403),
`uid-picker.component.ts`, `shell.component.ts` nav, `SecurityProperties` + `application.yml`.

## Permission codes in scope (EXACT, from `@PreAuthorize`)

- `USER.VIEW` — `GET /users`, `GET /users/uid/{uid}`, `GET /user-branches?userUid=`
- `USER.MANAGE` — `POST /users`, `PUT /users/uid/{uid}`, `.../disable`, `.../enable`, `.../unlock`, `.../password`
- `ROLE.VIEW` — `GET /roles`, `GET /roles/uid/{uid}`, `GET /user-roles?userUid=`
- `ROLE.MANAGE` — `POST/PUT /roles...`, `PUT /roles/uid/{uid}/permissions`, `DELETE /roles/uid/{uid}`, `POST /user-roles`, `DELETE /user-roles/uid/{uid}`
- `PERMISSION.VIEW` — `GET /roles/permissions`
- `COMPANY.VIEW` — `GET /companies`, `GET /companies/uid/{uid}` (scoped), `GET /organisations`
- `COMPANY.MANAGE` — `POST /companies`, `PUT /companies/uid/{uid}` (scoped), `DELETE /companies/uid/{uid}` (scoped)
- `BRANCH.VIEW` — `GET /branches?companyUid=` (scoped), `GET /branches/uid/{uid}` (scoped)
- `BRANCH.MANAGE` — `POST /branches` (scoped), `PUT /branches/uid/{uid}` (scoped), `.../default` (scoped), `DELETE /branches/uid/{uid}` (scoped)
- `BRANCH.ASSIGN` — `POST /user-branches` (scoped on body `branchUid`), `PUT /user-branches/uid/{uid}/default`, `DELETE /user-branches/uid/{uid}`
- `isAuthenticated()` only — `GET /auth/me`, `GET /auth/my-branches`, `GET /organisations/current`
- Public (no auth) — `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`

Notes on scoping verified in source:
- `@perm.has(CODE)` = caller holds CODE in active scope (no target check). Used by all User/Role/UserRole endpoints (users & roles are org-wide — see `UserController` javadoc, ADR-0002).
- `@perm.scoped(uid, type, CODE)` = holds CODE **AND** (root, or target's company == caller's active company). Used by Company target ops, all Branch ops, and `POST /user-branches`.
- For `UserRoleController`/`UserBranchController` body-scoped ops (grant, revoke, set-default, remove) the company-scope check is enforced inside the service via `ScopeGuard.assertCanActIn` → throws 403 (`ForbiddenException.notPermitted()`).
- `rootadmin` (is_root) bypasses every permission and scope check (`PermissionResolver.hasPermission` short-circuit + `ScopeGuard` root short-circuit). Root cross-company action emits a `ROOT.BYPASS` audit row (constant `AuditActions.ROOT_BYPASS`).

## Enum / status values (verified)

- User status = `MasterStatus {ACTIVE, INACTIVE, ARCHIVED}` (`AppUser.status`). Disable sets `INACTIVE`; enable sets `ACTIVE`. The `UserDto.status` returns the MasterStatus name. (FE `user-list` optimistically labels the row `DISABLED` after a disable click, but the API value is `INACTIVE` — assert against API value on reload.)
- User `locked` (bool) = lockout window active; `isRoot` (bool); `lastLoginAt` (ISO or null).
- Role status = `MasterStatus`; `system` (bool, seeded system roles); `permissionCodes` (sorted).
- Company / Branch / Organisation status = `MasterStatus`. Branch `isDefault` (bool).
- Lockout config (`application.yml`): `max-failed-attempts: 5`, `lock-minutes: 15`.
- Password policy (`PasswordPolicy` + `min-length: 8`): min 8 chars, must contain a letter AND a digit, must not be in the common list (`password`, `password1`, `12345678`, `qwerty123`, `admin123`, `letmein123`, `changeme`, `welcome1`, `passw0rd`). (Bootstrap root password rule is separate: ≥12 chars — not API-settable.)

## Type / role variations exercised

| Dimension | Values varied across cases |
|---|---|
| User type | `rootadmin` (bypass/positive only); `ORG_ADMIN`; module roles `SALES_MANAGER`/`ACCOUNTANT`/`STOREKEEPER`/`PURCHASE_OFFICER` (used as permission-lacking negatives); a CUSTOM role (subset of perms); a NO-PERMISSION user (forbidden/empty-nav) |
| Permission held vs lacking | each endpoint run as a holder AND a non-holder → 403 / hidden nav / guard redirect |
| Branch model | default vs non-default branch; single-branch vs multi-branch company; user assigned to ONE / MANY branches; active-branch switch; acting in a branch the user is NOT assigned to (denied) |
| Company / tenant | single company; multi-company org; cross-tenant access (company B target while active in company A) → 403 |
| Status lifecycle | User ACTIVE↔INACTIVE, locked→unlock; Role ACTIVE→ARCHIVED (system-role archive blocked); Branch default flip + archive |
| Entity validation | required fields, length caps, email format, duplicate code/username, unknown permission codes |

---

# TEST CASES

## A. Authentication, token, password policy

### TC-IAM-001 — Successful login lands in admin shell with branch-scoped session
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Authentication (`/login` · `POST /api/v1/auth/login`)
- **Permission / Role:** public endpoint — runs as `rootadmin` (and as `ORG_ADMIN` with a default branch)
- **Variation:** user with a usable default branch (active company+branch resolved)
- **Preconditions / Seed:** seeded `rootadmin`; an `ORG_ADMIN` user that has at least one ACTIVE default branch.
- **Steps:**
  1. Navigate to `/login`.
  2. Fill the username field (by label/placeholder) and password field.
  3. Submit.
  4. Wait for redirect to `/admin`.
- **Test Data:** username `rootadmin`, its known password.
- **Expected Result:** redirect to `/admin`; topbar shows the user's initials/display name; the active branch is shown in the branch selector; `POST /auth/login` 200 with envelope `{data:{accessToken, accessTokenExpiresAt, refreshToken, user:{uid,username,displayName,isRoot,activeCompanyUid,activeBranchUid,hasBranch:true}}}`; a follow-up `GET /auth/me` 200 populates permissions.
- **Convention Assertions:** C2 envelope; C8 `accessTokenExpiresAt` epoch-seconds; C6 axe-clean login page; C1 no uid shown on screen (user referenced by display name).
- **Negative / Edge:** see TC-IAM-002..009.

### TC-IAM-002 — Login with wrong password shows a single generic error (no user enumeration)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Authentication (`/login` · `POST /api/v1/auth/login`)
- **Permission / Role:** public — runs unauthenticated
- **Preconditions / Seed:** a known existing username.
- **Steps:** 1. `/login`. 2. Enter valid username + wrong password. 3. Submit.
- **Test Data:** username `org.admin`, password `totally-wrong-1`.
- **Expected Result:** inline error (generic "Sign-in failed…" / invalid-credentials); stays on `/login`; `POST /auth/login` returns 401 with envelope error; **no** red modal (login endpoint is exempt from the global error interceptor).
- **Convention Assertions:** C2 envelope error array; C4 form error state distinct from loading.
- **Negative / Edge:** the error message MUST be identical to the unknown-username case (TC-IAM-003) — no enumeration.

### TC-IAM-003 — Login with unknown username returns the same generic error
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Authentication (`POST /api/v1/auth/login`)
- **Permission / Role:** public
- **Steps:** 1. `/login`. 2. Enter a non-existent username + any password. 3. Submit.
- **Test Data:** username `does.not.exist`, password `Whatever123`.
- **Expected Result:** identical generic error to TC-IAM-002; 401; no timing/text difference observable (constant-time decoy hash path); audit records an unknown-username `LOGIN.FAIL` with no actor (manual/backend assertion).
- **Convention Assertions:** C2 envelope.
- **Negative / Edge:** response text MUST equal TC-IAM-002's text exactly.

### TC-IAM-004 — Account lockout after 5 consecutive failures, then locked message
- **Type:** Both (Automated drive + manual audit check)
- **Priority:** P1
- **Module / Submodule:** Authentication (`POST /api/v1/auth/login`)
- **Permission / Role:** public
- **Variation:** lockout threshold = 5, lock window = 15 min (`application.yml`)
- **Preconditions / Seed:** a dedicated throwaway user (do NOT use rootadmin); know its real password.
- **Steps:**
  1. `/login`. 2. Submit wrong password 5 times for the same username.
  3. On the 6th attempt, submit the **correct** password.
- **Test Data:** username `lockme.user`, wrong `bad-pass-1`, correct = real password.
- **Expected Result:** attempts 1–4 → generic invalid-credentials; the attempt that reaches the threshold and subsequent attempts → distinct "Account is locked. Try again later or contact an administrator."; the 6th attempt with the correct password is still refused while locked; audit has `LOGIN.FAIL` ×5 + one `ACCOUNT.LOCKED` with `failedCount`/`lockedUntil` detail (manual).
- **Convention Assertions:** C2 envelope; C4 the "locked" message is a distinct state from generic error.
- **Negative / Edge:** counter resets to 0 on a successful login (verify via TC-IAM-006 unlock then login).

### TC-IAM-005 — Disabled (INACTIVE) user cannot log in (generic error, reason not disclosed)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Authentication (`POST /api/v1/auth/login`)
- **Permission / Role:** public
- **Preconditions / Seed:** a user disabled via TC-IAM-021 (status INACTIVE), correct password known.
- **Steps:** 1. `/login`. 2. Enter the disabled user's correct credentials. 3. Submit.
- **Test Data:** username `disabled.user`, correct password.
- **Expected Result:** 401 generic invalid-credentials (reason intentionally not disclosed); stays on `/login`.
- **Convention Assertions:** C2 envelope; message identical to wrong-password case.
- **Negative / Edge:** re-enabling (TC-IAM-022) then logging in succeeds.

### TC-IAM-006 — Admin unlock clears the lockout and allows login
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Users (`/admin/users` · `PUT /users/uid/{uid}/unlock`)
- **Permission / Role:** `USER.MANAGE` — runs as `ORG_ADMIN`; also as a user lacking `USER.MANAGE` → Unlock action hidden / 403
- **Preconditions / Seed:** the locked user from TC-IAM-004.
- **Steps:**
  1. As `ORG_ADMIN` go to `/admin/users`.
  2. Locate the row whose status shows locked; click **Unlock**.
  3. Log out; log in as the previously-locked user with its correct password.
- **Expected Result:** Unlock → 204; row no longer shows locked; subsequent login succeeds; `USER.UNLOCK` audit row.
- **Convention Assertions:** C1 user chosen from the visible table (no uid typed; uid only in the per-row action URL); C3 RBAC; C4 list states.
- **Negative / Edge:** Unlock as a user without `USER.MANAGE` → 403 (and the button is not rendered).

### TC-IAM-007 — Token refresh rotates the refresh token (single-use)
- **Type:** Manual (API) — refresh is not directly user-triggered in the UI
- **Priority:** P2
- **Module / Submodule:** Authentication (`POST /api/v1/auth/refresh`)
- **Permission / Role:** public
- **Preconditions / Seed:** a valid login session (capture `refreshToken` R1).
- **Steps:** 1. `POST /auth/refresh` with R1 → get new access + refresh R2. 2. `POST /auth/refresh` again **with R1** (the consumed one).
- **Expected Result:** step 1 → 200 new tokens; step 2 → 401 "Refresh token already used. Please sign in again." and the whole token chain for that user is revoked (token-theft response). A subsequent refresh with R2 also fails (chain revoked).
- **Convention Assertions:** C2 envelope.
- **Negative / Edge:** expired refresh token → 401 "Refresh token expired…"; unknown/garbage token → 401 "Invalid refresh token."; refresh for a now-disabled user → 401 invalid-credentials.

### TC-IAM-008 — Logout revokes the refresh token and clears the local session
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Authentication (`POST /api/v1/auth/logout`)
- **Permission / Role:** public (body carries refresh token); UI requires an active session
- **Steps:** 1. Log in. 2. Open the user menu in the topbar; click **Logout**. 3. Try to navigate back to `/admin`.
- **Expected Result:** `POST /auth/logout` 204; redirect to `/login`; navigating to `/admin` again redirects to `/login` (no token). Reusing the revoked refresh token afterwards → 401.
- **Convention Assertions:** C4 unauthenticated state.
- **Negative / Edge:** logout still clears local state even if the server logout call errors (interceptor clears on error).

### TC-IAM-009 — Expired/invalid access token bounces to login (401 interceptor)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Authentication (any authenticated API call)
- **Permission / Role:** authenticated
- **Steps:** 1. Log in. 2. Corrupt/expire the stored access token (e.g. tamper localStorage). 3. Trigger any API-backed screen (e.g. `/admin/users`).
- **Expected Result:** the call returns 401; the `authErrorInterceptor` clears the session, shows a toast "Your session has expired. Please sign in again.", and routes to `/login`.
- **Convention Assertions:** C4 the 401 → login is distinct from 403 (forbidden) handling.
- **Negative / Edge:** 401 on `/auth/login` or `/auth/refresh` is NOT treated as a dead session (no redirect loop).

### TC-IAM-010 — `GET /auth/me` drives permission-aware nav (hidden items for missing perms)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Authentication (`GET /api/v1/auth/me`) + shell nav
- **Permission / Role:** `isAuthenticated()`; varies by the caller's effective permissions
- **Variation:** NO-PERMISSION user vs `ORG_ADMIN` vs CUSTOM (subset)
- **Preconditions / Seed:** a NO-PERMISSION user (no roles), the CUSTOM role granting only `COMPANY.VIEW`+`USER.VIEW`.
- **Steps:**
  1. Log in as the NO-PERMISSION user; open the sidebar.
  2. Log out; log in as the CUSTOM-role user; open the sidebar.
  3. Log out; log in as `ORG_ADMIN`.
- **Expected Result:** NO-PERMISSION → Administration group shows none of Companies/Users/Roles/Audit (all are permission-gated); CUSTOM → only "Companies" and "Users" appear; `ORG_ADMIN` → all Administration items appear; `GET /auth/me` returns the exact `permissions[]` list (root returns `isRoot:true` and an empty permission list, yet sees everything).
- **Convention Assertions:** C3 nav gating == permission codes; C2 envelope; C6 axe on the shell.
- **Negative / Edge:** direct navigation to `/admin/roles` for a user lacking `ROLE.VIEW` → `requirePermission` guard redirects to `/admin/home` (no 403 modal).

### TC-IAM-011 — `GET /auth/my-branches` lists only ACTIVE switchable branches
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Authentication (`GET /api/v1/auth/my-branches`) + branch switcher
- **Permission / Role:** `isAuthenticated()` (self-scoped; no `USER.VIEW` needed)
- **Variation:** user assigned to MANY branches, one of which sits under an ARCHIVED branch/company
- **Preconditions / Seed:** a user assigned to ≥2 ACTIVE branches plus 1 ARCHIVED branch.
- **Steps:** 1. Log in as that user. 2. Open the topbar branch selector.
- **Expected Result:** only the ACTIVE branches appear (the archived one is filtered out); each shown by branch name (not uid); the current active branch is marked.
- **Convention Assertions:** C1 branches shown by name; C7 only the user's own branches; C2 envelope.
- **Negative / Edge:** a user with no usable default branch logs in with no active branch (read-only session, `hasBranch:false`).

### TC-IAM-012 — Switch active branch re-resolves permissions and sends X-Branch-Uid
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Branch switching (`shell.component.ts` → `switchBranch` → `GET /auth/me`); header `X-Branch-Uid`
- **Permission / Role:** `isAuthenticated()`; the user's role grants differ per branch
- **Variation:** user granted `SALES_MANAGER` on Branch A (company-wide) but a narrower CUSTOM role limited to Branch B; multi-branch company
- **Preconditions / Seed:** user assigned to Branch A and Branch B; role grants that differ by branch.
- **Steps:** 1. Log in (lands on default Branch A). 2. Open branch selector; pick Branch B by name. 3. Observe nav.
- **Expected Result:** on switch, `setActiveBranchUid` stores B; the next `GET /auth/me` is sent with header `X-Branch-Uid: <B-uid>` and returns the permission set effective for Branch B; nav recomputes accordingly. If `me()` errors the branch reverts to A.
- **Convention Assertions:** C1 branch picked by name, uid only in the header under the hood; C3 permissions re-scope; C7 branch-scoped data.
- **Negative / Edge:** selecting the already-active branch is a no-op (menu closes, no refetch).

### TC-IAM-013 — Login required-field guard (client-side) before any API call
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Authentication (`/login`)
- **Permission / Role:** public
- **Steps:** 1. `/login`. 2. Leave username and/or password blank. 3. Submit.
- **Expected Result:** inline "Enter your username and password."; no `POST /auth/login` issued.
- **Convention Assertions:** C4 validation state; C6 axe; field has accessible label.
- **Negative / Edge:** whitespace-only username is treated as empty.

---

## B. Users (CRUD + activate/deactivate + unlock + password)

### TC-IAM-020 — Create a user (inline form) with valid data
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Users (`/admin/users` · `POST /api/v1/users`)
- **Permission / Role:** `USER.MANAGE` — runs as `ORG_ADMIN`; also as `USER.VIEW`-only user → create form/action 403
- **Preconditions / Seed:** logged in with `USER.MANAGE`.
- **Steps:**
  1. `/admin/users`.
  2. In the create form, enter username, display name, and a policy-valid temp password.
  3. Submit.
- **Test Data:** username `new.clerk`, display `New Clerk`, password `Welcome123` (8+, letter+digit, not in common list).
- **Expected Result:** 201 `UserDto`; the new row appears in the table (username lowercased to `new.clerk`); toast "User created"; `is_root` is false (never settable); `USER.CREATE` audit row.
- **Convention Assertions:** C1 no uid shown — the row links to `/admin/users/uid/:uid` only via "Manage branches"; C2 envelope; C9 masters not hard-deleted (created ACTIVE).
- **Negative / Edge:** see TC-IAM-024..027.

### TC-IAM-021 — Disable an active user (status → INACTIVE)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Users (`/admin/users` · `PUT /users/uid/{uid}/disable`)
- **Permission / Role:** `USER.MANAGE` — `ORG_ADMIN`; also as a viewer-only user → 403 / action hidden
- **Variation:** target is a non-root user
- **Preconditions / Seed:** an ACTIVE non-root user.
- **Steps:** 1. `/admin/users`. 2. On the target row, click **Disable**.
- **Expected Result:** 204; row reflects disabled; on reload `GET /users` shows the user `status: "INACTIVE"`; `USER.DISABLE` audit row with previous/new status.
- **Convention Assertions:** C1 target chosen from the table (uid only in the action URL); C9 soft-deactivate not delete.
- **Negative / Edge:** the Disable action is NOT rendered for a root user (UI hides it); calling disable on a root user via API → 409 "A root administrator cannot be disabled via the API."

### TC-IAM-022 — Enable a disabled user (status → ACTIVE)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Users (`PUT /users/uid/{uid}/enable`)
- **Permission / Role:** `USER.MANAGE`
- **Preconditions / Seed:** an INACTIVE user (from TC-IAM-021).
- **Steps:** 1. `/admin/users`. 2. On the disabled row, click **Enable**.
- **Expected Result:** 204; status returns to ACTIVE on reload; `USER.ENABLE` audit row; the user can log in again.
- **Convention Assertions:** C1; C3 RBAC.
- **Negative / Edge:** enabling an already-active user is idempotent (still ACTIVE).

### TC-IAM-023 — Admin set/reset a user's password (inline per-row form)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Users (`PUT /users/uid/{uid}/password`)
- **Permission / Role:** `USER.MANAGE`
- **Preconditions / Seed:** any non-root user.
- **Steps:** 1. `/admin/users`. 2. Expand the row's set-password form. 3. Enter a policy-valid password; save. 4. Log out; log in as that user with the new password.
- **Test Data:** new password `Reset2026` (8+, letter+digit).
- **Expected Result:** 204; toast "Password updated"; password/hash NEVER logged (`USER.PASSWORD_SET` audit has empty detail — manual); login with new password succeeds.
- **Convention Assertions:** C1; the password field is masked; C6 axe on the expanded form.
- **Negative / Edge:** a weak password is rejected (see TC-IAM-026/027); empty password → no API call.

### TC-IAM-024 — Create user: duplicate username rejected (409)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Users (`POST /api/v1/users`)
- **Permission / Role:** `USER.MANAGE`
- **Preconditions / Seed:** an existing username, e.g. `org.admin`.
- **Steps:** 1. `/admin/users`. 2. Create form with username `ORG.ADMIN` (different case). 3. Submit.
- **Expected Result:** 409 "Username already exists: org.admin" (compared lowercased); inline form error shown; no row added.
- **Convention Assertions:** C2 envelope error surfaced inline (`formError`); C4 error state.
- **Negative / Edge:** username is matched case-insensitively (stored lowercased) — `ORG.ADMIN` collides with `org.admin`.

### TC-IAM-025 — Create user: required-field validation (client + server)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Users (`POST /api/v1/users`)
- **Permission / Role:** `USER.MANAGE`
- **Steps:** 1. `/admin/users`. 2. Submit with username, displayName, or password blank.
- **Expected Result:** client error "Username, display name, and password are required." (no API call). If bypassed, server `@NotBlank` returns 400 envelope errors.
- **Convention Assertions:** C4 validation state; C6 axe.
- **Negative / Edge:** field length caps — username ≤80, displayName ≤160, email ≤160, phone ≤40 (server `@Size`); over-long → 400.

### TC-IAM-026 — Create/set password: too short (< 8) rejected
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Users (`POST /users` and `PUT /users/uid/{uid}/password`)
- **Permission / Role:** `USER.MANAGE`
- **Steps:** 1. `/admin/users`. 2. Create with password `Ab1` (3 chars).
- **Expected Result:** 400/409 "Password must be at least 8 characters."; inline error; user not created.
- **Convention Assertions:** C2 envelope error.
- **Negative / Edge:** boundary — exactly 8 chars passes; 7 fails.

### TC-IAM-027 — Password complexity + common-password rejection
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Users (password paths)
- **Permission / Role:** `USER.MANAGE`
- **Steps:** Attempt each: (a) `abcdefgh` (no digit), (b) `12345678` (no letter / common), (c) `password1` (common-list), (d) `Welcome123` (valid).
- **Expected Result:** (a) "Password must contain letters and at least one number."; (b) rejected (no letter and common); (c) "Password is too common; choose a stronger one."; (d) succeeds.
- **Convention Assertions:** C2 envelope error messages.
- **Negative / Edge:** every common-list entry (`password`,`password1`,`12345678`,`qwerty123`,`admin123`,`letmein123`,`changeme`,`welcome1`,`passw0rd`) is rejected case-insensitively.

### TC-IAM-028 — Update user display/contact fields (thin update)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Users (`PUT /users/uid/{uid}`)
- **Permission / Role:** `USER.MANAGE`
- **Preconditions / Seed:** an existing user.
- **Steps:** From `/admin/users/uid/:uid` (or applicable form) update displayName/email/phone.
- **Test Data:** email `clerk@otapp.net`, phone `+255700000000`.
- **Expected Result:** 200 `UserDto`; only display/contact change; username/status/isRoot unchanged; `USER.UPDATE` audit (fact-only, no field values).
- **Convention Assertions:** C2 envelope; C8 N/A.
- **Negative / Edge:** invalid email format → 400 (`@Email`); blank displayName → 400 (`@NotBlank`).

### TC-IAM-029 — Users list: four states (loading / empty / error / populated)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Users (`/admin/users` · `GET /api/v1/users`)
- **Permission / Role:** `USER.VIEW`
- **Steps:** Drive each: throttle to observe loading; (empty unlikely with seed — assert via mocked empty if available); force 500 → error; normal → populated.
- **Expected Result:** distinct loading spinner, empty message, error panel, and the table — never blended.
- **Convention Assertions:** C4 four states; C6 axe; table has caption/`scope` headers.
- **Negative / Edge:** `GET /users` as a user lacking `USER.VIEW` → 403; the `/admin/users` route guard redirects them to `/admin/home` first.

### TC-IAM-030 — Forbidden: viewer-only user cannot manage users
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Users (`POST/PUT /users...`)
- **Permission / Role:** holds `USER.VIEW` but NOT `USER.MANAGE` (CUSTOM role)
- **Steps:** 1. Log in as the view-only user. 2. `/admin/users` (list loads). 3. Attempt create / disable / set-password.
- **Expected Result:** list renders; management actions are hidden or, if forced, return 403; no 403 modal (calm state).
- **Convention Assertions:** C3 RBAC at action level; C4 forbidden distinct from error.
- **Negative / Edge:** the NO-PERMISSION user can't even reach `/admin/users` (guard redirect).

### TC-IAM-031 — User detail: uid only in URL, never displayed; references via picker
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Users (`/admin/users/uid/:uid` · `GET /users/uid/{uid}`)
- **Permission / Role:** `USER.VIEW`
- **Steps:** 1. From `/admin/users`, click "Manage branches" on a row → `/admin/users/uid/:uid`. 2. Inspect the header and panels.
- **Expected Result:** header shows username/display name/status; the uid appears only in the URL path; the branch-assign and role-grant panels choose company/branch/role by NAME via selects (`uid-picker`/native selects), storing uid under the hood; no raw uid is typed or shown in any label/cell.
- **Convention Assertions:** C1 (primary focus) uid-not-shown + picker-used; C4 each panel has its own four states.
- **Negative / Edge:** navigating to `/admin/users/uid/<nonexistent>` → detail error state (404 from `GET /users/uid/{uid}`).

---

## C. Roles (CRUD + permissions) & role assignment

### TC-IAM-040 — Create a role with org-unique code
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Roles (`/admin/roles` · `POST /api/v1/roles`)
- **Permission / Role:** `ROLE.MANAGE` — `ORG_ADMIN`; also as `ROLE.VIEW`-only → create blocked
- **Steps:** 1. `/admin/roles`. 2. Create form: code, name, optional description. 3. Submit.
- **Test Data:** code `BRANCH_SUP`, name `Branch Supervisor`, description `Reviews branch ops`.
- **Expected Result:** 201 `RoleDto`; row appears; new role is non-system (`system:false`), status ACTIVE, empty `permissionCodes`.
- **Convention Assertions:** C1 row links to `/admin/roles/uid/:uid` (uid in URL only); C2 envelope.
- **Negative / Edge:** duplicate code → 409 "Role code already exists: BRANCH_SUP"; code length cap 40, name cap 120 (server `@Size`); blank code/name → client + 400.

### TC-IAM-041 — Edit role name/description (code immutable)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Roles (`/admin/roles/uid/:uid` · `PUT /roles/uid/{uid}`)
- **Permission / Role:** `ROLE.MANAGE`
- **Preconditions / Seed:** an existing non-system role.
- **Steps:** 1. `/admin/roles/uid/:uid`. 2. Change name/description; Save details.
- **Expected Result:** 200; the `code` field is read-only on screen; only name/description persist; success notice.
- **Convention Assertions:** C1; C4 saved/error states.
- **Negative / Edge:** blank name → "Name is required." (client) and 400 (server `@NotBlank`).

### TC-IAM-042 — Set role permissions (grouped checkbox UI, replace semantics)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Roles (`PUT /roles/uid/{uid}/permissions`; catalogue `GET /roles/permissions`)
- **Permission / Role:** `ROLE.MANAGE` (page) + `PERMISSION.VIEW` (to load the catalogue)
- **Preconditions / Seed:** a role; the seeded permission catalogue.
- **Steps:** 1. `/admin/roles/uid/:uid`. 2. Permissions render grouped by module (from `PermissionDto.module`). 3. Check `USER.VIEW`+`USER.MANAGE`, uncheck others; Save permissions.
- **Expected Result:** 200 `RoleDto` with the new (sorted) `permissionCodes`; checkbox state reflects the saved set; the permission cache is invalidated server-side so grantees' effective permissions update.
- **Convention Assertions:** C2 envelope; C6 axe (checkbox groups labelled); C3 needs `ROLE.MANAGE`.
- **Negative / Edge:** saving an unknown code → 409 "Unknown permission codes: [...]"; saving an EMPTY list is valid (clears all permissions); a user lacking `PERMISSION.VIEW` cannot load the catalogue (page error/forbidden).

### TC-IAM-043 — Archive a (non-system) role; system role archive blocked
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Roles (`DELETE /roles/uid/{uid}`)
- **Permission / Role:** `ROLE.MANAGE`
- **Variation:** non-system role vs seeded system role (e.g. `ORG_ADMIN`)
- **Steps:** 1. Archive a custom role. 2. Attempt to archive a seeded system role.
- **Expected Result:** custom → 204, status ARCHIVED (soft-delete, not removed), cache invalidated; system role → 409 "System role cannot be archived: <code>".
- **Convention Assertions:** C9 soft-delete (ARCHIVED, append-only); C2 envelope error for the blocked case.
- **Negative / Edge:** archived role no longer appears as grantable (verify it is excluded/inactive in grant flows as applicable).

### TC-IAM-044 — Roles list: four states + RBAC
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Roles (`/admin/roles` · `GET /api/v1/roles`)
- **Permission / Role:** `ROLE.VIEW`
- **Steps:** Drive loading/error/populated; confirm `system` roles render with a marker; non-holder is redirected by the guard.
- **Expected Result:** four states distinct; each row shows code/name/system flag (no uid).
- **Convention Assertions:** C4; C6 axe; C1 no uid in cells.
- **Negative / Edge:** `/admin/roles/uid/:uid` requires `ROLE.MANAGE` (edit guard) — a `ROLE.VIEW`-only user is redirected from the edit route.

### TC-IAM-045 — Grant a role to a user (company-wide) via pickers
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Role assignment (`/admin/role-grants` · `POST /api/v1/user-roles`)
- **Permission / Role:** `ROLE.MANAGE` — `ORG_ADMIN`; also as `ROLE.VIEW`-only → grant blocked/403
- **Variation:** company-wide grant (branchUid null)
- **Preconditions / Seed:** a target user; a role; the active company.
- **Steps:** 1. `/admin/role-grants`. 2. Choose user by display name (picker), role by name, company by name; leave branch blank. 3. Grant.
- **Test Data:** user `New Clerk`, role `Branch Supervisor`, company by name.
- **Expected Result:** 201 `UserRoleDto` with `branchUid:null`; toast "Role granted"; the grant appears when the user's grants are looked up; `ROLE.GRANT` audit row.
- **Convention Assertions:** C1 every reference chosen by NAME via picker/select, no uid typed/shown; C2 envelope; C7 grant scoped to caller's active company (ScopeGuard).
- **Negative / Edge:** missing user/role/company → client "User UID, role, and company UID are required." (no API call).

### TC-IAM-046 — Grant a role scoped to a specific branch (BR-5 company/branch match)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Role assignment (`POST /api/v1/user-roles`)
- **Permission / Role:** `ROLE.MANAGE`
- **Variation:** branch-scoped grant; multi-branch company
- **Steps:** 1. `/admin/role-grants` (or user-detail role panel). 2. Pick user, role, company; on company change the branch picker reloads that company's branches. 3. Pick a branch belonging to that company; Grant.
- **Expected Result:** 201 with the chosen `branchUid`; grant effective only in that branch context.
- **Convention Assertions:** C1 branch chosen by name after company selection; C7 branch belongs to the selected company.
- **Negative / Edge:** if a branch from a DIFFERENT company is submitted (API-level) → 409 "Branch ... does not belong to company ..." (BR-5).

### TC-IAM-047 — Duplicate active grant rejected
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Role assignment (`POST /api/v1/user-roles`)
- **Permission / Role:** `ROLE.MANAGE`
- **Preconditions / Seed:** an existing active grant (user+role+company+branch).
- **Steps:** Re-grant the exact same combination.
- **Expected Result:** 409 "Active grant already exists for this user/role/company/branch combination."; inline form error.
- **Convention Assertions:** C2 envelope error.
- **Negative / Edge:** the same role for the same user in a DIFFERENT branch is allowed (distinct combination).

### TC-IAM-048 — Revoke a role grant (by grant uid in action URL)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Role assignment (`DELETE /user-roles/uid/{uid}`; list `GET /user-roles?userUid=`)
- **Permission / Role:** `ROLE.MANAGE` (revoke + view via `ROLE.VIEW`)
- **Steps:** 1. `/admin/role-grants`; look up grants by selecting the user. 2. On a grant row, click Revoke.
- **Expected Result:** 204; row removed from the list on reload; `ROLE.REVOKE` audit; grantee's effective permissions drop the role (cache invalidated).
- **Convention Assertions:** C1 grant referenced from the visible list (uid only in the action URL); C4 list states.
- **Negative / Edge:** revoking an already-revoked grant → 409 "Assignment already revoked: <uid>".

### TC-IAM-049 — Role-grants list: four states + look-up by user
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Role assignment (`GET /user-roles?userUid=`)
- **Permission / Role:** `ROLE.VIEW`
- **Steps:** Select a user → loading → populated/empty; force error.
- **Expected Result:** distinct idle (before search), loading, empty ("no grants"), error states; grant rows show roleCode + company/branch names.
- **Convention Assertions:** C4; C1 references by name.
- **Negative / Edge:** the grant list endpoint requires `ROLE.VIEW`; a user lacking it sees forbidden/empty.

### TC-IAM-050 — Cross-tenant grant denied (ScopeGuard)
- **Type:** Manual (API) — UI normally scopes the company picker to the active company
- **Priority:** P1
- **Module / Submodule:** Role assignment (`POST /user-roles`, `DELETE /user-roles/uid/{uid}`)
- **Permission / Role:** `ROLE.MANAGE` held, but acting on Company B while active in Company A
- **Variation:** multi-company org; non-root admin
- **Preconditions / Seed:** Company A (caller's active) and Company B; a grant under Company B.
- **Steps:** As a non-root `ROLE.MANAGE` admin active in Company A: 1. `POST /user-roles` with `companyUid` = Company B. 2. `DELETE` a Company-B grant.
- **Expected Result:** both → 403 (`ScopeGuard.assertCanActIn` → `ForbiddenException.notPermitted()`), even though the user holds `ROLE.MANAGE`.
- **Convention Assertions:** C7 tenant isolation enforced at the service; C3 permission alone is insufficient without scope.
- **Negative / Edge:** rootadmin performing the same cross-company grant succeeds and emits a `ROOT.BYPASS` audit row.

---

## D. Branch assignment to users

### TC-IAM-060 — Assign a user to a branch (non-default)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Branch assignment (`/admin/users/uid/:uid` · `POST /api/v1/user-branches`)
- **Permission / Role:** `BRANCH.ASSIGN` (scoped on body `branchUid`) — `ORG_ADMIN`; also as a user lacking it → 403
- **Variation:** user assigned to ONE branch first
- **Preconditions / Seed:** a user with no branch yet; a company with branches.
- **Steps:** 1. `/admin/users/uid/:uid`. 2. Branch panel: pick company by name → pick branch by name; leave make-default unchecked; Assign.
- **Expected Result:** 201 `UserBranchDto`; the branch appears in the user's assignments (by code/name); `BRANCH_ASSIGN` audit.
- **Convention Assertions:** C1 company+branch chosen by name; uid only under the hood; C2 envelope; C7 branch belongs to caller's active company (scoped check).
- **Negative / Edge:** assigning the same branch twice → 409 "User is already assigned to that branch."; no branch selected → client "Select a branch to assign."

### TC-IAM-061 — Assign a branch with make-default = true (clears prior default)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Branch assignment (`POST /user-branches` with `makeDefault:true`)
- **Permission / Role:** `BRANCH.ASSIGN`
- **Variation:** user already has a default branch
- **Preconditions / Seed:** a user whose Branch X is default; a second Branch Y to assign.
- **Steps:** 1. `/admin/users/uid/:uid`. 2. Assign Branch Y with make-default checked.
- **Expected Result:** 201; Branch Y becomes the single default; Branch X's default is cleared (one-default invariant BR-1); only one row shows "Default".
- **Convention Assertions:** C1; C7; the one-default invariant holds (DB partial unique index backstop).
- **Negative / Edge:** never two defaults simultaneously.

### TC-IAM-062 — Set an existing assignment as default
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Branch assignment (`PUT /user-branches/uid/{uid}/default`)
- **Permission / Role:** `BRANCH.ASSIGN`
- **Variation:** user assigned to MANY branches
- **Steps:** 1. `/admin/users/uid/:uid`. 2. On a non-default assignment row, click Set default.
- **Expected Result:** 200; that row becomes Default; the previous default is cleared; `BRANCH_SET_DEFAULT` audit.
- **Convention Assertions:** C1 row referenced by branch name (uid in the action URL only).
- **Negative / Edge:** clicking Set default on the already-default row is a no-op (UI guards `isDefault`).

### TC-IAM-063 — Remove a branch assignment; default auto-falls back to earliest remaining
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Branch assignment (`DELETE /user-branches/uid/{uid}`)
- **Permission / Role:** `BRANCH.ASSIGN`
- **Variation:** removing the DEFAULT while ≥1 other branch remains
- **Preconditions / Seed:** user with Branch X (default, assigned first) and Branch Y, Z.
- **Steps:** 1. `/admin/users/uid/:uid`. 2. Remove the current default (X).
- **Expected Result:** 204; X removed; the earliest-assigned remaining branch becomes the new default (FR-IAM-19 / D-D fallback); `BRANCH_UNASSIGN` audit includes `fallbackBranchUid`.
- **Convention Assertions:** C1; C9 (assignment is a junction row removal, not a master delete — distinct from master soft-delete).
- **Negative / Edge:** removing the LAST branch leaves the user with no default → no active branch on next login (read-only session, `hasBranch:false`). `wasDefault` recorded in audit.

### TC-IAM-064 — Branch-assignment list (`GET /user-branches?userUid=`) requires USER.VIEW
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Branch assignment (`GET /api/v1/user-branches?userUid=`)
- **Permission / Role:** `USER.VIEW` (note: list uses `USER.VIEW`, while assign/set-default/remove use `BRANCH.ASSIGN`)
- **Steps:** 1. As a `USER.VIEW` holder open `/admin/users/uid/:uid` → the branch list loads. 2. As a user lacking `USER.VIEW`, the page is guarded away.
- **Expected Result:** list loads for `USER.VIEW`; four states handled; the manage actions still require `BRANCH.ASSIGN` separately.
- **Convention Assertions:** C3 split permissions (view vs assign); C4 states.
- **Negative / Edge:** a `USER.VIEW`-only user can SEE branches but Assign/Set-default/Remove → 403 (and actions hidden).

### TC-IAM-065 — Cross-tenant branch assign denied; act-in-unassigned-branch denied
- **Type:** Manual (API + UI scope)
- **Priority:** P1
- **Module / Submodule:** Branch assignment (`POST /user-branches`, set-default, remove)
- **Permission / Role:** `BRANCH.ASSIGN` held; non-root admin active in Company A
- **Variation:** target branch in Company B; and a user trying to ACT in a branch not assigned to them
- **Steps:**
  1. As a non-root `BRANCH.ASSIGN` admin active in Company A, `POST /user-branches` with a Company-B `branchUid` (scoped on the body).
  2. set-default / remove an assignment whose branch is in Company B.
  3. Separately: log in as a user, send `X-Branch-Uid` of a branch NOT in their `my-branches`, and call a branch-scoped endpoint.
- **Expected Result:** (1) 403 via `@perm.scoped(#request.branchUid(),'branch','BRANCH.ASSIGN')`; (2) 403 via `ScopeGuard.assertCanActIn`; (3) acting in an unassigned/foreign branch is denied (session resolves no rights there; the switcher only exposes the user's own ACTIVE branches).
- **Convention Assertions:** C7 multi-tenant + branch isolation; C3 permission insufficient without scope.
- **Negative / Edge:** rootadmin can assign across companies (`ROOT.BYPASS` audited).

---

## E. Organisation / Company / Branch hierarchy

### TC-IAM-080 — Resolve current organisation (no uid typing)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Organisation (`GET /api/v1/organisations/current`)
- **Permission / Role:** `isAuthenticated()` only
- **Steps:** 1. Log in. 2. Open `/admin/companies` (which calls `organisations/current` under the hood).
- **Expected Result:** the deployment's organisation resolves by name; the companies list loads scoped to that org; the admin never pastes an org uid.
- **Convention Assertions:** C1 org referenced by name/uid-under-the-hood; C2 envelope.
- **Negative / Edge:** `GET /organisations` (the list, not `/current`) requires `COMPANY.VIEW` — a user lacking it gets 403 on that endpoint.

### TC-IAM-081 — Create a company under the organisation
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Companies (`/admin/companies` · `POST /api/v1/companies`)
- **Permission / Role:** `COMPANY.MANAGE` — `ORG_ADMIN`; also as `COMPANY.VIEW`-only → create blocked
- **Steps:** 1. `/admin/companies`. 2. Create form: code, name (org resolved automatically). 3. Submit.
- **Test Data:** code `C2`, name `Second Co Ltd`.
- **Expected Result:** 201 `CompanyDto` (status ACTIVE); row appears; `organisationUid` taken from the resolved org (not typed).
- **Convention Assertions:** C1 org chosen automatically by name; C2 envelope.
- **Negative / Edge:** duplicate code within the org → 409 (service-validated); blank code/name → client "Code and name are required." + 400; code cap 20, name cap 160 (`@Size`).

### TC-IAM-082 — Multi-company creation + isolation (tenant A cannot see tenant B)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Companies (`GET /companies?organisationUid=`, `GET /companies/uid/{uid}` scoped)
- **Permission / Role:** `COMPANY.VIEW` / `COMPANY.MANAGE`; non-root scoped to one company
- **Variation:** multi-company org; non-root admin whose active company is A
- **Preconditions / Seed:** Company A and Company B under the same org.
- **Steps:**
  1. As a non-root admin active in Company A, open `/admin/companies` (list).
  2. Attempt `GET /companies/uid/{B-uid}` (scoped get) / `PUT` / `DELETE` on Company B.
- **Expected Result:** list (org-scoped) may show companies, but the **target** ops on Company B → 403 (`@perm.scoped(#uid,'company','COMPANY.VIEW'/'MANAGE')`); the user cannot read/edit/archive Company B.
- **Convention Assertions:** C7 tenant isolation; C3 scope on target uid.
- **Negative / Edge:** rootadmin sees and edits both companies (cross-tenant bypass; `ROOT.BYPASS` audited on write).

### TC-IAM-083 — Edit company mutable fields
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Companies (`PUT /companies/uid/{uid}`)
- **Permission / Role:** `COMPANY.MANAGE` (scoped)
- **Steps:** Update name/legalName/taxId/timeZone for the active company.
- **Expected Result:** 200 `CompanyDto`; code & organisation unchanged (identity); changes persist.
- **Convention Assertions:** C1 (uid in URL only); C2 envelope.
- **Negative / Edge:** blank name → 400; over-long taxId(>60)/legalName(>200)/timeZone(>64) → 400.

### TC-IAM-084 — Archive a company (soft-delete)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Companies (`DELETE /companies/uid/{uid}`)
- **Permission / Role:** `COMPANY.MANAGE` (scoped)
- **Steps:** Archive a non-primary company.
- **Expected Result:** 204; company status → ARCHIVED (not removed); branches under an archived company become non-usable for sessions (`Branch.isUsableForSession()` excludes them).
- **Convention Assertions:** C9 soft-delete; C7 scope.
- **Negative / Edge:** a user whose default branch sits under the now-archived company logs in with no active branch (read-only).

### TC-IAM-085 — Create a branch under a company (default flag)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Branches (`/admin/companies/:companyUid/branches` · `POST /api/v1/branches`)
- **Permission / Role:** `BRANCH.MANAGE` (scoped on `request.companyUid`) — `ORG_ADMIN`; also `BRANCH.VIEW`-only → create blocked
- **Variation:** single-branch company gaining a second branch
- **Steps:** 1. From `/admin/companies` click a company → `/admin/companies/:companyUid/branches`. 2. Create form: code, name, optional timeZone, make-default checkbox. 3. Submit.
- **Test Data:** code `BR2`, name `Mwanza Branch`, makeDefault unchecked.
- **Expected Result:** 201 `BranchDto` (status ACTIVE, isDefault per checkbox); list reloads; if makeDefault was set, the prior default is cleared.
- **Convention Assertions:** C1 companyUid comes from the route (not typed); C2 envelope.
- **Negative / Edge:** blank code/name → client + 400; code cap 20 / name cap 160 / timeZone cap 64; duplicate branch code within company (service-validated) → 409.

### TC-IAM-086 — Set a branch as the company default (clears previous)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Branches (`PUT /branches/uid/{uid}/default`)
- **Permission / Role:** `BRANCH.MANAGE` (scoped)
- **Variation:** multi-branch company
- **Steps:** 1. `/admin/companies/:companyUid/branches`. 2. On a non-default branch, click Set default.
- **Expected Result:** 200; that branch becomes default; the previous default is cleared (one-default-per-company invariant); list reflects exactly one Default.
- **Convention Assertions:** C1 branch referenced from the visible list (uid in action URL); C4 states.
- **Negative / Edge:** Set default on the already-default branch is a no-op (UI guards `isDefault`).

### TC-IAM-087 — Edit / archive a branch
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Branches (`PUT /branches/uid/{uid}`, `DELETE /branches/uid/{uid}`)
- **Permission / Role:** `BRANCH.MANAGE` (scoped)
- **Steps:** 1. Update a branch name/timeZone. 2. Archive a non-default branch.
- **Expected Result:** edit → 200 (name/timeZone change; default flag is changed only via the dedicated endpoint); archive → 204, status ARCHIVED (soft-delete); archived branch drops out of `my-branches` switchable list.
- **Convention Assertions:** C9 soft-delete; C1; C7 scope.
- **Negative / Edge:** archiving the company's default branch (behaviour per backend) — verify the resulting default/active-branch state; a user whose default was the archived branch gets no active branch next login.

### TC-IAM-088 — Branch list/get is company-scoped (cross-tenant denied)
- **Type:** Manual (API) + UI
- **Priority:** P1
- **Module / Submodule:** Branches (`GET /branches?companyUid=` scoped, `GET /branches/uid/{uid}` scoped)
- **Permission / Role:** `BRANCH.VIEW` held; non-root active in Company A
- **Steps:** 1. `GET /branches?companyUid={B-uid}` (Company B). 2. `GET /branches/uid/{B-branch-uid}`.
- **Expected Result:** both → 403 (`@perm.scoped(...,'company'/'branch','BRANCH.VIEW')`) for a non-root admin not active in Company B.
- **Convention Assertions:** C7 isolation; C3 scope on companyUid/branchUid.
- **Negative / Edge:** rootadmin reads any company's branches.

### TC-IAM-089 — Companies list: four states incl. explicit forbidden panel
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Companies (`/admin/companies` · `GET /companies`)
- **Permission / Role:** `COMPANY.VIEW`
- **Steps:** Drive loading; force a 403 on `GET /companies` (e.g. revoke the perm mid-session) to observe the calm forbidden panel; force 500 → error; normal → populated.
- **Expected Result:** the component renders a distinct **forbidden** state (`state === 'forbidden'`) on 403 (NOT the red modal — the global interceptor suppresses the modal for 403), separate from generic error/loading/empty.
- **Convention Assertions:** C4 four states with forbidden explicitly distinct; C6 axe; C3 RBAC.
- **Negative / Edge:** the route guard normally keeps a permissionless user off the page; this in-page panel is the backstop if the perm is lost after entry.

---

## F. Cross-cutting convention checks (apply across IAM)

### TC-IAM-100 — No raw uid is ever displayed or hand-typed (C1) across IAM screens
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All IAM screens (companies, branches, users, user-detail, roles, role-edit, role-grants)
- **Permission / Role:** `ORG_ADMIN` (broad view)
- **Steps:** Visit each IAM list/detail/form; scan visible text and inputs.
- **Expected Result:** uids appear ONLY in URL paths (`/admin/.../uid/:uid`, `/admin/companies/:companyUid/branches`); every cross-resource reference (user/role/company/branch) is selected from a NAME-bearing select/`uid-picker`; no `<input>` accepts a raw uid; tables/labels show codes/names, never uids.
- **Convention Assertions:** C1 (the headline check); C6 each picker/select has an accessible label.
- **Negative / Edge:** the grant/branch panels show the picker placeholder ("— select —") and filter by name when the list is large (>12 options → filter box appears).

### TC-IAM-101 — Envelope unwrap + paginated meta passthrough (C2/C5)
- **Type:** Manual (API) + Automated where lists paginate
- **Priority:** P2
- **Module / Submodule:** All IAM endpoints
- **Permission / Role:** any holder
- **Steps:** Inspect responses for `{data, errors, meta}`; confirm the FE receives unwrapped `data`.
- **Expected Result:** all responses are `ApiResponse<T>`; the interceptor unwraps to `T`; IAM list endpoints (`/users`, `/roles`, `/companies`, `/branches`, `/user-roles`, `/user-branches`) currently return plain arrays (no server-side pagination params observed) — assert lists render fully; if a shared `<app-paginator>` is present it self-hides at 1 page (C5).
- **Convention Assertions:** C2 envelope; C5 paginator self-hide where applicable.
- **Negative / Edge:** error responses carry `errors[]`; the FE surfaces `errors[0]` inline (form) or as a toast/alert per interceptor rules.

### TC-IAM-102 — 403 vs 401 handling distinction (C3/C4)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All IAM endpoints (interceptor behaviour)
- **Permission / Role:** mixed
- **Steps:** 1. Trigger a 403 (lacking permission). 2. Trigger a 401 (dead token).
- **Expected Result:** 403 → no red modal; the screen renders its own calm forbidden/empty state and the rejected promise reaches the component handler; 401 → session cleared + toast + redirect to `/login`.
- **Convention Assertions:** C3; C4 (forbidden distinct from error and from session-expired).
- **Negative / Edge:** a 5xx/network error → centered acknowledge alert (distinct from both).

### TC-IAM-103 — Accessibility sweep of IAM screens (C6)
- **Type:** Automated (Playwright + axe)
- **Priority:** P2
- **Module / Submodule:** `/login`, `/admin/companies`, `/admin/companies/:companyUid/branches`, `/admin/users`, `/admin/users/uid/:uid`, `/admin/roles`, `/admin/roles/uid/:uid`, `/admin/role-grants`
- **Permission / Role:** `ORG_ADMIN`
- **Steps:** Load each route; run an axe scan; tab through forms.
- **Expected Result:** axe-clean (WCAG 2.1 AA); all inputs/selects have labels/aria-labels; tables have captions and `scope` headers; the branch/user menus are keyboard operable (Esc closes).
- **Convention Assertions:** C6 (headline); keyboard operability.
- **Negative / Edge:** the `uid-picker` filter input and select expose `aria-label` derived from the placeholder.

### TC-IAM-104 — rootadmin bypass is positive-only; never used for negative-auth tests
- **Type:** Manual (test-design guard) + spot Automated
- **Priority:** P2
- **Module / Submodule:** All IAM
- **Permission / Role:** `rootadmin`
- **Steps:** Confirm rootadmin can perform every IAM action across all companies/branches; confirm negative/forbidden cases (TC-IAM-030/050/065/082/088) are run with NON-root scoped users, never root.
- **Expected Result:** root succeeds everywhere (permission + scope bypass); cross-company writes by root emit `ROOT.BYPASS` audit rows; negative cases never rely on root.
- **Convention Assertions:** C3/C7 — root is the bypass baseline, scoped users are the enforcement subjects.
- **Negative / Edge:** root cannot be disabled via the API (`USER.MANAGE` disable on root → 409) — a deployment-lockout safeguard.

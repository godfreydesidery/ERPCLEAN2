# Authentication & Permissions

This is the first section of the POS-integration guide. It is written for a developer
building an **external POS client** (desktop or mobile) against this ERP's REST API.

It covers the full authentication lifecycle, how to carry the token, branch scoping, the
`/auth/me` / `/auth/my-branches` discovery endpoints, every `POS.*` permission, and how an
administrator provisions a POS-operator user (user + role + grant + branch assignment).

> Every endpoint, field, type and permission below is grounded in the actual backend code
> under `src/main/java`. Source files are cited inline.

---

## 0. The shared contract (read first)

These rules apply to **every** call in this guide. The full statement lives in the POS
integration overview; the essentials you need here:

- **Base path / versioning.** All endpoints are versioned under the fixed prefix `/api/v1`
  (path-based, via Spring `@RequestMapping` class prefixes — no header/media-type
  versioning). `AuthController` is `@RequestMapping("/api/v1/auth")`
  (`com/erp/api/AuthController.java`).
- **Response envelope.** Every `com.erp.api` controller return value is auto-wrapped by
  `ApiResponseAdvice` into `com.erp.platform.common.api.ApiResponse<T>`:

  ```java
  public record ApiResponse<T>(T data, List<String> errors, Object meta) { ... }
  ```

  On **success**: `{ "data": <T>, "errors": [], "meta": null }` (or a `PageMeta` in `meta`
  for paged endpoints). On **error**: `{ "data": null, "errors": ["...user-safe..."],
  "meta": null }`. Error strings are user-facing only — never internal exception text.
  Controllers that already return `ApiResponse<?>` (e.g. `PosSessionController.list`) pass
  through unchanged.
- **Content type.** POST/PUT bodies **must** be `application/json`, otherwise `415`
  (`Content-Type not supported. Use application/json.`).
- **Correlation id.** Send `X-Request-Id` to correlate logs; it is echoed back on the
  response (generated if absent). It is **purely** a logging/correlation id — it is **not**
  used for request deduplication.

### Common HTTP error statuses

These are produced by the global exception handling and the security filters and are
rendered into the same `ApiResponse` envelope. The full matrix:

| Status | When |
| --- | --- |
| `400 Bad Request` | Bean-validation failures on request DTOs (`field: message` per error); missing/uncoercible query param or path-var; malformed JSON / bad enum value; `IllegalArgumentException`; `WeakPasswordException`; DB check/not-null/truncation/overflow violations. |
| `401 Unauthorized` | Bad credentials / auth failure (generic, no enumeration); missing/invalid/expired bearer token; a user no longer `ACTIVE` (re-checked every request → `User account is no longer active.`). |
| `403 Forbidden` | `ForbiddenException` (lacks permission or acting outside active scope); `@PreAuthorize` denial → generic `You do not have permission to perform this action.` (never names the missing permission); rejected `X-Branch-Uid` override. |
| `404 Not Found` | Entity addressed by uid/id does not exist (`NotFoundException`). |
| `409 Conflict` | `ConflictException` (domain-rule violation), `IllegalStateException` (business-state conflict), optimistic-lock collisions (`This record was modified by another transaction. Please reload and try again.`), unique-key (`23505`) / FK (`23503`) violations. |
| `415 Unsupported Media Type` | Wrong `Content-Type` on POST/PUT. |
| `422 Unprocessable Entity` | Currency not enabled for the company/branch scope (`CurrencyNotEnabledException`, ADR-0039). |
| `500 Internal Server Error` | Any uncaught exception → generic `An unexpected error occurred.` (text never echoed; full stack logged with MDC). |

---

## 1. Auth model in one paragraph

The API is a **stateless JWT resource server** (`SecurityConfig`:
`SessionCreationPolicy.STATELESS`, CSRF disabled, `oauth2ResourceServer.jwt()`). The only
**public** API paths are `/api/v1/auth/login`, `/api/v1/auth/refresh`,
`/api/v1/auth/logout`, and `/api/v1/health` (plus the Swagger/OpenAPI surface and all `GET
/**` for the co-located Angular SPA). **Everything else under `/api/**` is
`authenticated()` AND must pass the controller's `@PreAuthorize` permission gate.**

A POS client therefore: (1) logs in to obtain an access token + refresh token; (2) sends
`Authorization: Bearer <accessToken>` on every protected call; (3) refreshes the access
token before it expires; (4) logs out (revokes the refresh token) when the operator signs
off.

Source: `com/erp/platform/security/config/SecurityConfig.java`.

---

## 2. Login — `POST /api/v1/auth/login`

| | |
| --- | --- |
| Method + path | `POST /api/v1/auth/login` |
| Auth required | **No** (public) |
| Permission | None |
| Content-Type | `application/json` |

### Request body — `LoginRequest`

Source: `com/erp/modules/iam/domain/dto/LoginRequest.java`.

| Field | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `username` | `String` | `@NotBlank` | Matched **case-insensitively** (stored lowercased). |
| `password` | `String` | `@NotBlank` | Raw password. |

```json
{ "username": "pos.cashier1", "password": "S3cretCashierPass" }
```

### Success response — `TokenResponse` (HTTP 200)

The controller returns `TokenResponse`; the advice wraps it. Source:
`com/erp/modules/iam/domain/dto/TokenResponse.java`, `AuthServiceImpl.issueSession`.

| Field | Type | Notes |
| --- | --- | --- |
| `accessToken` | `String` | The JWT to send as `Authorization: Bearer ...`. |
| `accessTokenExpiresAt` | `long` | **Epoch SECONDS** (`access.expiresAt().getEpochSecond()`) — not millis. |
| `refreshToken` | `String` | Single-use; rotated on each refresh. Store securely. |
| `user` | `AuthUser` | Thin profile (see below). |

`AuthUser` (`TokenResponse.AuthUser`):

| Field | Type | Notes |
| --- | --- | --- |
| `uid` | `String` | The user's uid. |
| `username` | `String` | Lowercased username. |
| `displayName` | `String` | Human name. |
| `isRoot` | `boolean` | Root bypasses all permission scoping. A POS operator is **not** root. |
| `activeCompanyUid` | `String` | Company of the session's default branch; `null` if no usable branch. |
| `activeBranchUid` | `String` | The user's default branch uid; `null` if none usable. |
| `hasBranch` | `boolean` | `false` → user landed with no active branch (read-only); they cannot transact until an admin assigns a usable default branch. |

> **Session scope.** The session is scoped to the user's **default** branch
> (`findByUserIdAndIsDefaultTrue`, filtered by `isUsableForSession` — excludes archived
> branches and branches under an archived company). If none is usable, `companyId`/
> `branchId` in the token are null and `hasBranch=false`. A POS client should treat
> `hasBranch=false` as "not provisioned for transacting" and surface that to the operator.

```json
{
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI...",
    "accessTokenExpiresAt": 1750329600,
    "refreshToken": "9f0c5b1e4a...e2",
    "user": {
      "uid": "01J8Z3K9R7AbCdEfGhJkLmNpQr",
      "username": "pos.cashier1",
      "displayName": "Cashier One",
      "isRoot": false,
      "activeCompanyUid": "01J8Z2COMPANYUID0000000001",
      "activeBranchUid": "01J8Z2BRANCHUID00000000001",
      "hasBranch": true
    }
  },
  "errors": [],
  "meta": null
}
```

### Notable errors

- `401 Unauthorized` — unknown user / wrong password / disabled / locked account, returned
  as a **generic** message (no account enumeration). Source: `AuthServiceImpl.login` →
  `AuthenticationException.invalidCredentials()`.
- **Lockout:** after `max-failed-attempts: 5` failed attempts the account is locked for
  `lock-minutes: 15` (`application.yml` → `erp.security.lockout`). While locked, login
  returns `401` with `Account is locked. Try again later or contact an administrator.`
- `400 Bad Request` — blank `username`/`password` (bean validation).

### curl

```bash
curl -sS -X POST https://erp.example.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: pos-login-001' \
  -d '{"username":"pos.cashier1","password":"S3cretCashierPass"}'
```

---

## 3. Using the bearer token

Send the access token on **every** protected call:

```
Authorization: Bearer <accessToken>
```

After the standard `BearerTokenAuthenticationFilter` validates the JWT,
`JwtRequestContextFilter` reads the validated `Jwt` and builds the request's
`RequestContext.Principal` from the claims `sub` (userId), `username`, `isRoot`,
`companyId`, `branchId`. **It re-checks on every request that the user is still `ACTIVE`**
(`existsByIdAndStatus(..ACTIVE)`); if not, the request fails `401` with `User account is no
longer active.` (so disabling a user takes effect immediately, even with a still-valid
token). Source: `SecurityConfig`, `JwtRequestContextFilter`.

### Token lifetimes (`application.yml` → `erp.jwt`)

| | Value | Meaning |
| --- | --- | --- |
| Access token TTL | `access-token-ttl-minutes: 15` | Short-lived; refresh before it expires. |
| Refresh token TTL | `refresh-token-ttl-days: 7` | After this, the operator must log in again. |
| Signing mode | `dev-in-memory` (default) | **Ephemeral RSA key, rotated each restart → all tokens are invalidated on backend restart.** Production uses `signing-mode=file` with stable RS256 keys (issuer `erp-api`). |

> Practical consequence for a POS client: in a dev/test environment a backend restart
> invalidates all tokens — expect a `401` and re-login. In production with `file` signing,
> tokens survive restarts.

---

## 4. Refresh — `POST /api/v1/auth/refresh`

| | |
| --- | --- |
| Method + path | `POST /api/v1/auth/refresh` |
| Auth required | **No** (public; the refresh token *is* the credential) |
| Permission | None |

### Request body — `RefreshRequest`

Source: `com/erp/modules/iam/domain/dto/RefreshRequest.java`.

| Field | Type | Constraints |
| --- | --- | --- |
| `refreshToken` | `String` | `@NotBlank` |

```json
{ "refreshToken": "9f0c5b1e4a...e2" }
```

### Success response

A **new** `TokenResponse` (same shape as login) with a fresh access token **and a fresh
refresh token** — the old refresh token is consumed. HTTP 200.

### Critical refresh semantics (single-use rotation)

Source: `AuthServiceImpl.refresh`.

- Refresh tokens are **single-use / rotated**: each refresh mints a successor and marks the
  presented token as rotated. **Always replace your stored refresh token with the new one.**
- **Reuse = theft.** Presenting an already-consumed refresh token **revokes the user's
  entire token chain** (fail closed) and returns `401` `Refresh token already used. Please
  sign in again.` — the operator must log in again. Never refresh from two clients with the
  same token.
- Expired/invalid token → `401` (`Refresh token expired. Please sign in again.` /
  `Invalid refresh token.`).
- If the user has since been disabled → `401` generic invalid-credentials.

### curl

```bash
curl -sS -X POST https://erp.example.com/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"9f0c5b1e4a...e2"}'
```

---

## 5. Logout — `POST /api/v1/auth/logout`

| | |
| --- | --- |
| Method + path | `POST /api/v1/auth/logout` |
| Auth required | **No** (public) |
| Permission | None |
| Success status | **`204 No Content`** (`@ResponseStatus(HttpStatus.NO_CONTENT)`) |

Revokes the presented refresh token. The access token is **not** server-side revoked (it is
stateless) — it simply expires within its 15-minute TTL; discard it client-side. Source:
`AuthController.logout`, `AuthServiceImpl.logout`.

### Request body — `RefreshRequest`

```json
{ "refreshToken": "9f0c5b1e4a...e2" }
```

### curl

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X POST \
  https://erp.example.com/api/v1/auth/logout \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"9f0c5b1e4a...e2"}'   # -> 204
```

---

## 6. Who am I + what can I do — `GET /api/v1/auth/me`

| | |
| --- | --- |
| Method + path | `GET /api/v1/auth/me` |
| Auth required | **Yes** (`@PreAuthorize("isAuthenticated()")`) |
| Permission | None beyond authentication |

This is the endpoint a POS client uses **right after login** to discover whether it holds
the POS permissions it needs (and to drive UI gating). Source: `AuthController.me`,
`AuthServiceImpl.me`.

### Success response — `MeResponse` (HTTP 200)

Source: `com/erp/modules/iam/domain/dto/MeResponse.java`.

| Field | Type | Notes |
| --- | --- | --- |
| `uid` | `String` | User uid. |
| `username` | `String` | |
| `displayName` | `String` | |
| `isRoot` | `boolean` | |
| `activeCompanyUid` | `String` | Resolved from the active scope (`null` if none). |
| `activeBranchUid` | `String` | Resolved from the active scope (`null` if none). |
| `permissions` | `List<String>` | **Effective permission codes for the active scope.** For **root, this is empty** (the client keys off `isRoot`); otherwise it is the user's effective codes for the active company/branch. |

> A POS client should check that `permissions` contains the `POS.*` codes it needs (e.g.
> `POS.SALE.CREATE`, `POS.SESSION.OPEN`) — or that `isRoot` is true — before exposing those
> actions. The `403` gate is the real enforcement; `me` is convenience for the UI.

```json
{
  "data": {
    "uid": "01J8Z3K9R7AbCdEfGhJkLmNpQr",
    "username": "pos.cashier1",
    "displayName": "Cashier One",
    "isRoot": false,
    "activeCompanyUid": "01J8Z2COMPANYUID0000000001",
    "activeBranchUid": "01J8Z2BRANCHUID00000000001",
    "permissions": [
      "POS.SALE.CREATE",
      "POS.SESSION.OPEN",
      "POS.SESSION.CLOSE",
      "POS.SESSION.VIEW",
      "POS.TILL.VIEW"
    ]
  },
  "errors": [],
  "meta": null
}
```

### curl

```bash
curl -sS https://erp.example.com/api/v1/auth/me \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

---

## 7. My switchable branches — `GET /api/v1/auth/my-branches`

| | |
| --- | --- |
| Method + path | `GET /api/v1/auth/my-branches` |
| Auth required | **Yes** (`@PreAuthorize("isAuthenticated()")`) |
| Permission | **None** — self-scoped, no `USER.VIEW` required |

Returns the caller's own **ACTIVE** branch assignments (the branches they may switch into).
Source: `AuthController.myBranches`, `AuthServiceImpl.myBranches`.

### Success response — `List<UserBranchDto>` (HTTP 200)

Source: `com/erp/modules/iam/domain/dto/UserBranchDto.java`.

| Field | Type | Notes |
| --- | --- | --- |
| `id` | `Long` | (Serialised as a JSON string globally.) |
| `uid` | `String` | The **assignment** uid (used for set-default / remove). |
| `userUid` | `String` | The caller's uid. |
| `branchUid` | `String` | Use this value in the `X-Branch-Uid` header to switch scope. |
| `branchCode` | `String` | |
| `branchName` | `String` | |
| `companyUid` | `String` | |
| `isDefault` | `boolean` | Whether this is the session default. |
| `assignedAt` | `String` | ISO-8601, nullable. |

```json
{
  "data": [
    {
      "id": "501",
      "uid": "01J8Z2UB000000000000000001",
      "userUid": "01J8Z3K9R7AbCdEfGhJkLmNpQr",
      "branchUid": "01J8Z2BRANCHUID00000000001",
      "branchCode": "BR-01",
      "branchName": "Head Office",
      "companyUid": "01J8Z2COMPANYUID0000000001",
      "isDefault": true,
      "assignedAt": "2026-06-01T08:00:00Z"
    }
  ],
  "errors": [],
  "meta": null
}
```

---

## 8. Branch scoping — the `X-Branch-Uid` header

A POS operator assigned to multiple branches can switch the **request scope** for a single
call **without re-login and without a DB write** (ADR-0003) by sending the optional header:

```
X-Branch-Uid: <branchUid>
```

- The JWT's minted `companyId`/`branchId` is the **default** scope; the header overrides it
  for that one request.
- The branch is resolved by uid. For a **non-root** user it **must** be one of their
  **ACTIVE** `user_branch` assignments, else `403` (`You are not assigned to that branch.`
  / `Branch not available.`). Root may switch into any existing ACTIVE branch.
- Use `GET /api/v1/auth/my-branches` to obtain valid `branchUid` values.

> **POS-sale note.** `POST /api/v1/pos/sales` takes **no** branch in the body — scope comes
> from the JWT (or `X-Branch-Uid`) plus the resolved session's company.
> `ScopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId())` enforces that
> the caller can act in the session's company.

### curl (act in a non-default branch)

# `X-Branch-Uid` sets the active branch *scope* for this request; the `companyId` /
# `branchId` query params are the list filter for `/pos/tills`. Quote the URL so the
# shell does not treat `&` as a background operator.
```bash
curl -sS "https://erp.example.com/api/v1/pos/tills?companyId=12&branchId=34" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Branch-Uid: 01J8Z2BRANCHUID00000000002"
```

---

## 9. The POS permissions

There are **seven** `POS.*` permission codes. They are seeded in
`src/main/resources/db/migration/R__seed_permissions.sql` (module `sales`) and enforced via
`@PreAuthorize` on the POS controllers. `@perm.has('X')` checks the caller holds code `X` in
the active scope; `@perm.scoped(#uid, '<entity>', 'X')` additionally checks the caller can
act in the company that owns the entity addressed by `uid`.

| Permission code | Gates (method + path) | Enforcement | Seed description |
| --- | --- | --- | --- |
| `POS.SALE.CREATE` | `POST /api/v1/pos/sales` | `@perm.has('POS.SALE.CREATE')` | Ring a POS sale on an open session |
| `POS.SESSION.OPEN` | `POST /api/v1/pos/sessions` (open); `POST /api/v1/pos/sessions/uid/{uid}/payouts` | `@perm.has` (open); `@perm.scoped(#uid,'possession',...)` (payout) | Open a POS cashier session on a till |
| `POS.SESSION.CLOSE` | `POST /api/v1/pos/sessions/uid/{uid}/close` | `@perm.scoped(#uid,'possession','POS.SESSION.CLOSE')` | Close an open POS cashier session |
| `POS.SESSION.VIEW` | `GET /api/v1/pos/sessions/uid/{uid}`; `GET /api/v1/pos/sessions` (list); `GET /api/v1/pos/sessions/uid/{uid}/x-read` | `@perm.scoped` (single + x-read); `@perm.has` (list) | View POS cashier sessions and X/Z reads |
| `POS.SESSION.RECONCILE` | `POST /api/v1/pos/sessions/uid/{uid}/reconcile` (Z-read, posts variance to GL) | `@perm.scoped(#uid,'possession','POS.SESSION.RECONCILE')` | Reconcile a closed POS session (posts variance to GL) |
| `POS.TILL.MANAGE` | `POST /api/v1/pos/tills` (create); `DELETE /api/v1/pos/tills/uid/{uid}` (deactivate) | `@perm.has` (create); `@perm.scoped(#uid,'postill',...)` (deactivate) | Create and manage POS tills (registers) |
| `POS.TILL.VIEW` | `GET /api/v1/pos/tills/uid/{uid}`; `GET /api/v1/pos/tills` (list by branch) | `@perm.scoped` (single); `@perm.has` (list) | View POS tills (registers) |

Sources: `com/erp/api/PosSaleController.java`, `PosSessionController.java`,
`PosTillController.java`, `R__seed_permissions.sql`.

### Recommended permission set for a POS operator (cashier)

A typical cashier needs to view/open/close their own sessions and ring sales:

- `POS.SALE.CREATE`
- `POS.SESSION.OPEN`
- `POS.SESSION.CLOSE`
- `POS.SESSION.VIEW`
- `POS.TILL.VIEW`

`POS.TILL.MANAGE` (create/deactivate tills) and `POS.SESSION.RECONCILE` (Z-read variance
posting to GL) are **back-office / supervisor** permissions and are usually withheld from a
plain cashier role.

> A `403` is intentionally **generic** (`You do not have permission to perform this
> action.`) and never names the missing permission. If a POS action returns `403`, compare
> the operator's `GET /auth/me` `permissions` against the table above.

---

## 10. Provisioning a POS-operator user

A POS operator is just a non-root application user who (a) holds the POS permissions via a
role grant scoped to a company, and (b) has a usable **default branch** so their session
lands in a transactable scope. The whole flow is administrator-driven through the IAM
controllers — there is **no** self-registration.

> **Bootstrap.** On a fresh DB, the backend can bootstrap an org + company + default branch
> + a **root** admin from env (`erp.bootstrap.*` in `application.yml`, e.g.
> `ERP_BOOTSTRAP_ADMIN_USERNAME` default `rootadmin`). The root admin (or any user holding
> `USER.MANAGE` / `ROLE.MANAGE` / `BRANCH.ASSIGN`) performs the steps below.

The admin steps and the permission each requires:

| # | Step | Endpoint | Admin permission |
| --- | --- | --- | --- |
| 1 | Create the operator user | `POST /api/v1/users` | `USER.MANAGE` |
| 2 | Create a POS role (if not seeded) | `POST /api/v1/roles` | `ROLE.MANAGE` |
| 3 | Assign POS permissions to the role | `PUT /api/v1/roles/uid/{uid}/permissions` | `ROLE.MANAGE` |
| 4 | Grant the role to the user (scoped to a company) | `POST /api/v1/user-roles` | `ROLE.MANAGE` |
| 5 | Assign the user to a branch + make it default | `POST /api/v1/user-branches` | `BRANCH.ASSIGN` (scoped to the branch's company) |

All five controllers return raw DTOs wrapped by the envelope; create endpoints return
`201 Created`.

### 10.1 Create the user — `POST /api/v1/users`

Permission: `@perm.has('USER.MANAGE')`. Source: `UserController.create`,
`com/erp/modules/iam/domain/dto/CreateUserRequest.java`.

`CreateUserRequest` fields:

| Field | Type | Constraints |
| --- | --- | --- |
| `username` | `String` | `@NotBlank`, `@Size(max=80)`, `@Pattern ^[A-Za-z0-9._-]+$` (letters/digits/dots/underscores/hyphens). |
| `displayName` | `String` | `@NotBlank`, `@Size(max=160)`. |
| `password` | `String` | `@NotBlank`, `@Size(max=100)`. Validated against the password policy (`min-length: 8`); too-weak → `400`/`WeakPasswordException`. |
| `email` | `String` | `@Size(max=160)`, `@Email`. Optional. |
| `phone` | `String` | `@Size(max=40)`. Optional. |

> `is_root` is **intentionally absent** — it can never be set via the API.

Returns `UserDto` (`id, uid, username, displayName, email, phone, isRoot, status, locked,
lastLoginAt, mustChangePassword, passwordExpiresAt, lastLoginIp, employeeId`). Capture
`uid` for the next steps.

```bash
curl -sS -X POST https://erp.example.com/api/v1/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "username": "pos.cashier1",
        "displayName": "Cashier One",
        "password": "S3cretCashierPass",
        "email": "cashier1@example.com",
        "phone": "+255700000001"
      }'
```

```json
{
  "data": {
    "id": "1001",
    "uid": "01J8Z3K9R7AbCdEfGhJkLmNpQr",
    "username": "pos.cashier1",
    "displayName": "Cashier One",
    "email": "cashier1@example.com",
    "phone": "+255700000001",
    "isRoot": false,
    "status": "ACTIVE",
    "locked": false,
    "lastLoginAt": null,
    "mustChangePassword": false,
    "passwordExpiresAt": null,
    "lastLoginIp": null,
    "employeeId": null
  },
  "errors": [],
  "meta": null
}
```

Notable errors: `409` if the username already exists (unique constraint);
`400`/`WeakPasswordException` on a weak password; `403` without `USER.MANAGE`.

### 10.2 Create a POS role — `POST /api/v1/roles`

Permission: `@perm.has('ROLE.MANAGE')`. Source: `RoleController.create`,
`com/erp/modules/iam/domain/dto/CreateRoleRequest.java`.

`CreateRoleRequest` fields:

| Field | Type | Constraints |
| --- | --- | --- |
| `code` | `String` | `@NotBlank`, `@Size(max=40)`. Org-unique (service-validated). |
| `name` | `String` | `@NotBlank`, `@Size(max=120)`. |
| `description` | `String` | `@Size(max=255)`. Optional. |

Returns `RoleDto` (`id, uid, code, name, description, system, status, permissionCodes`).
Capture `uid`.

```bash
curl -sS -X POST https://erp.example.com/api/v1/roles \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"code":"POS_CASHIER","name":"POS Cashier","description":"Rings POS sales and runs sessions"}'
```

> **Discover available codes.** `GET /api/v1/roles/permissions` (permission
> `PERMISSION.VIEW`) lists all seeded `PermissionDto`s — including the seven `POS.*` codes —
> to populate the next step.

### 10.3 Assign POS permissions to the role — `PUT /api/v1/roles/uid/{uid}/permissions`

Permission: `@perm.has('ROLE.MANAGE')`. This **replaces** the role's full permission set.
Source: `RoleController.setPermissions`,
`com/erp/modules/iam/domain/dto/SetRolePermissionsRequest.java`.

`SetRolePermissionsRequest`:

| Field | Type | Constraints |
| --- | --- | --- |
| `permissionCodes` | `List<String>` | `@NotNull`. Empty list clears all. **Unknown codes → `409`** (validated against the seeded catalogue). |

```bash
curl -sS -X PUT \
  https://erp.example.com/api/v1/roles/uid/01J8Z2ROLEUID0000000000POS/permissions \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "permissionCodes": [
          "POS.SALE.CREATE",
          "POS.SESSION.OPEN",
          "POS.SESSION.CLOSE",
          "POS.SESSION.VIEW",
          "POS.TILL.VIEW"
        ]
      }'
```

Returns the updated `RoleDto` with `permissionCodes` populated (sorted).

### 10.4 Grant the role to the user — `POST /api/v1/user-roles`

Permission: `@perm.has('ROLE.MANAGE')` (the company-scope check — the grant's company must
match the active company — is enforced in the service via `ScopeGuard.assertCanActIn`).
Source: `UserRoleController.grant`,
`com/erp/modules/iam/domain/dto/GrantRoleRequest.java`.

`GrantRoleRequest` fields:

| Field | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `userUid` | `String` | `@NotBlank` | The operator's uid (from 10.1). |
| `roleUid` | `String` | `@NotBlank` | The POS role uid (from 10.2). |
| `companyUid` | `String` | `@NotBlank` | Company the grant is scoped to. |
| `branchUid` | `String` | optional | `null` = company-wide grant (FR-IAM-13). If supplied, its company must match `companyUid` (BR-5, service-validated). |

```bash
curl -sS -X POST https://erp.example.com/api/v1/user-roles \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "userUid": "01J8Z3K9R7AbCdEfGhJkLmNpQr",
        "roleUid": "01J8Z2ROLEUID0000000000POS",
        "companyUid": "01J8Z2COMPANYUID0000000001",
        "branchUid": null
      }'
```

Returns `UserRoleDto` (`id, uid, userUid, roleCode, companyUid, branchUid, grantedAt`).
HTTP 201.

### 10.5 Assign + default a branch — `POST /api/v1/user-branches`

Permission: `@perm.scoped(#request.branchUid(), 'branch', 'BRANCH.ASSIGN')` — gated on the
target branch's company. Source: `UserBranchController.assign`,
`com/erp/modules/iam/domain/dto/AssignBranchRequest.java`.

`AssignBranchRequest` fields:

| Field | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `userUid` | `String` | `@NotBlank`, `@Size(max=26)` | The operator's uid. |
| `branchUid` | `String` | `@NotBlank`, `@Size(max=26)` | The branch to assign. |
| `makeDefault` | `boolean` | — | `true` promotes this assignment to the user's single default (clears the previous default). **Set this `true` for a POS operator** so their login session lands in a transactable branch (`hasBranch=true`). |

```bash
curl -sS -X POST https://erp.example.com/api/v1/user-branches \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "userUid": "01J8Z3K9R7AbCdEfGhJkLmNpQr",
        "branchUid": "01J8Z2BRANCHUID00000000001",
        "makeDefault": true
      }'
```

Returns `UserBranchDto` (same shape as §7). HTTP 201.

> Without a **usable default branch**, the operator logs in with `hasBranch=false`,
> `activeCompanyUid`/`activeBranchUid` null, and cannot transact. Step 10.5 with
> `makeDefault: true` is what makes the operator session-ready.

### 10.6 Verify provisioning

Have the operator log in (§2) and call `GET /auth/me` (§6). Confirm:

- `hasBranch` is `true` / `activeBranchUid` is non-null in the login `AuthUser`;
- `permissions` in `me` contains the expected `POS.*` codes.

If a POS call later returns `403`, re-check `me.permissions` against §9 — the grant scope
(company/branch in step 10.4) must match the scope the operator is acting in.

---

## 11. Quick reference

| Operation | Method + path | Public? | Permission |
| --- | --- | --- | --- |
| Login | `POST /api/v1/auth/login` | yes | — |
| Refresh | `POST /api/v1/auth/refresh` | yes | — |
| Logout | `POST /api/v1/auth/logout` | yes | — |
| Who am I + permissions | `GET /api/v1/auth/me` | no | authenticated |
| My switchable branches | `GET /api/v1/auth/my-branches` | no | authenticated |
| Create user | `POST /api/v1/users` | no | `USER.MANAGE` |
| Create role | `POST /api/v1/roles` | no | `ROLE.MANAGE` |
| List permission catalogue | `GET /api/v1/roles/permissions` | no | `PERMISSION.VIEW` |
| Set role permissions | `PUT /api/v1/roles/uid/{uid}/permissions` | no | `ROLE.MANAGE` |
| Grant role to user | `POST /api/v1/user-roles` | no | `ROLE.MANAGE` |
| Assign user to branch | `POST /api/v1/user-branches` | no | `BRANCH.ASSIGN` |
| Ring a sale | `POST /api/v1/pos/sales` | no | `POS.SALE.CREATE` |
| Open session | `POST /api/v1/pos/sessions` | no | `POS.SESSION.OPEN` |
| Close session | `POST /api/v1/pos/sessions/uid/{uid}/close` | no | `POS.SESSION.CLOSE` |
| Reconcile (Z-read) | `POST /api/v1/pos/sessions/uid/{uid}/reconcile` | no | `POS.SESSION.RECONCILE` |
| View session / list / x-read | `GET /api/v1/pos/sessions...` | no | `POS.SESSION.VIEW` |
| Create / deactivate till | `POST` / `DELETE /api/v1/pos/tills...` | no | `POS.TILL.MANAGE` |
| View till / list | `GET /api/v1/pos/tills...` | no | `POS.TILL.VIEW` |

The POS sale, session-lifecycle and till endpoints themselves are documented in the
following sections of this guide.

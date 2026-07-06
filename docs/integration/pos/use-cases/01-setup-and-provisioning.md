# POS Use Cases — Setup & Provisioning

End-to-end scenarios an integrator/admin must complete **before any selling can happen**: provisioning a POS operator, resolving the operating context, and creating/listing/retiring the till the operator will ring sales on. Every endpoint, field, permission, and limitation below is grounded in the API reference sections cited inline — see [§01 Authentication & Permissions](../01-authentication-and-permissions.md), [§02 Company & Branch Context](../02-company-branch-context.md), [§07 Tills](../07-tills.md), [§08 Sessions](../08-sessions.md), and [§12 Known Limitations](../12-known-limitations.md).

> **Audience & scope.** You already have the API reference; this page strings those endpoints into the realistic *setup* journeys. All these use cases are performed by an **integrator/admin** (or the bootstrap **root** user) — they are back-office actions, not cashier actions. The actual selling loop (open session → ring sale → close → reconcile) is covered in later use-case groups.

> **Identifier conventions used throughout.** Path variables and most request bodies take the opaque string **`uid`**; the two POS *list* query params (`companyId`, `branchId`) take the **numeric `id`** (serialised on the wire as a JSON string). `CreatePosTillRequest` is the odd one out — `companyUid` (string) **plus** numeric `branchId`. Full rules in [§02 §5–§6](../02-company-branch-context.md).

---

## Use cases in this group

| ID | Title | Actor |
|---|---|---|
| [UC-A1](#uc-a1-provision-a-pos-operator) | Provision a POS operator (user + company membership + role + grant + branch + sales agent) | Integrator/admin |
| [UC-A2](#uc-a2-resolve-the-operating-context) | Resolve the operating context (org → company → branch) | Integrator/admin |
| [UC-A3](#uc-a3-create-a-till-register) | Create a till (register) | Integrator/admin |
| [UC-A4](#uc-a4-listselect-an-existing-till) | List / select an existing till | Integrator/admin |
| [UC-A5](#uc-a5-retire--delete-a-till) | Retire / delete a till | Integrator/admin |

---

### UC-A1: Provision a POS operator

- **Actor:** Integrator/admin (the bootstrap **root** user, or any admin holding `USER.MANAGE` + `ROLE.MANAGE` + `BRANCH.ASSIGN`).
- **Goal:** Create a cashier user who can log in with the right `POS.*` permissions and land in a transactable branch scope.
- **Preconditions:**
  - Admin is authenticated and holds `Authorization: Bearer <ADMIN_TOKEN>` ([§01 §3](../01-authentication-and-permissions.md)).
  - Admin holds `USER.MANAGE`, `USER.COMPANY.MANAGE`, `ROLE.MANAGE`, `BRANCH.ASSIGN`, and `AGENT.MANAGE` (scoped to the target branch's company). Root bypasses all of these.
  - The target **company** and a usable **branch** already exist (on a fresh DB the bootstrap creates an org + company + default branch + the root admin — see [§01 §10](../01-authentication-and-permissions.md)).
  - There is **no self-registration** — provisioning is entirely admin-driven.
  - **Two easily-missed prerequisites** for a *selling* cashier (steps 2 and 7 below): the user must be a **member of the company** before any role/branch can be granted, and must have an **internal sales Agent** linked to them before any sale will post. Following only the user→role→grant→branch path leaves the cashier unable to sell.

- **Main flow:**
  1. **Create the operator user** — `POST /api/v1/users` ([§01 §10.1](../01-authentication-and-permissions.md)), permission `USER.MANAGE`. Body `CreateUserRequest{ username, displayName, password, email?, phone? }`. `username` must match `^[A-Za-z0-9._-]+$` (≤80); `password` must satisfy the policy (min length 8). Returns `201` with `UserDto` — **capture `uid`** (and the numeric `id` for step 7). (`is_root` cannot be set via the API.)
  2. **Assign the user to the company (membership)** — `POST /api/v1/user-companies`, permission `USER.COMPANY.MANAGE`. Body `{ userUid, companyUid }`. **This is a hard prerequisite** (ADR-0045/0046): granting a role (step 5) or assigning a branch (step 6) to a user who is *not* a member of the company is rejected **`409`** (`Assign this user to the company before granting/assigning…`). Following the table verbatim without this step leaves you stuck at step 5.
  3. **Create a POS role** (if not already seeded) — `POST /api/v1/roles` ([§01 §10.2](../01-authentication-and-permissions.md)), permission `ROLE.MANAGE`. Body `CreateRoleRequest{ code, name, description? }` (e.g. `code: "POS_CASHIER"`). Returns `RoleDto` — **capture `uid`**. Optionally discover the available permission codes via `GET /api/v1/roles/permissions` (permission `PERMISSION.VIEW`). *(The product ships a seeded `CASHIER` bundle — you can grant that instead of hand-building a role.)*
  4. **Assign the `POS.*` permissions to the role** — `PUT /api/v1/roles/uid/{uid}/permissions` ([§01 §10.3](../01-authentication-and-permissions.md)), permission `ROLE.MANAGE`. Body `SetRolePermissionsRequest{ permissionCodes }` — this **replaces** the role's whole set. The recommended cashier set ([§01 §9](../01-authentication-and-permissions.md)):
     ```json
     { "permissionCodes": ["POS.SALE.CREATE","POS.SESSION.OPEN","POS.SESSION.CLOSE","POS.SESSION.VIEW","POS.TILL.VIEW"] }
     ```
     Withhold `POS.TILL.MANAGE` (create/retire tills) and `POS.SESSION.RECONCILE` (Z-read variance to GL) from a plain cashier — those are supervisor/back-office permissions (a supervisor or manager reconciles). The seeded `CASHIER` bundle deliberately excludes `POS.SESSION.RECONCILE` for the same segregation-of-duties reason.
  5. **Grant the role to the user (scoped to a company)** — `POST /api/v1/user-roles` ([§01 §10.4](../01-authentication-and-permissions.md)), permission `ROLE.MANAGE`. Body `GrantRoleRequest{ userUid, roleUid, companyUid, branchUid? }`. Use `branchUid: null` for a company-wide grant; if you supply `branchUid`, its company must match `companyUid`. *(Requires the step-2 membership.)*
  6. **Assign the user to a branch and make it the default** — `POST /api/v1/user-branches` ([§01 §10.5](../01-authentication-and-permissions.md)), permission `BRANCH.ASSIGN` (scoped to the branch's company). Body `AssignBranchRequest{ userUid, branchUid, makeDefault }`. **Set `makeDefault: true`** so the operator's login session lands in a transactable scope (`hasBranch=true`). *(Requires the step-2 membership.)*
  7. **Link an internal sales Agent to the user** — `POST /api/v1/agents`, permission `AGENT.MANAGE`. Body `CreateAgentRequest{ companyId, partyType: "INDIVIDUAL", displayName, agentKind: "INTERNAL", appUserId }`, where `appUserId` is the operator's **numeric** user id (from step 1). **Required for selling:** every POS sale is attributed to the cashier's internal agent, resolved automatically from their login. Without this record the *first* sale fails **`400`** (`No sales agent could be determined for this sale. Select a sales agent, or ask an administrator to link an internal sales agent to your user account…`) — and the fix lives in this different module, so it is easy to miss. A user may have **at most one ACTIVE internal agent**; a duplicate is rejected **`409`** (`This user is already linked to an active internal sales agent.`).
  8. **Verify** — have the operator log in (`POST /api/v1/auth/login`, [§01 §2](../01-authentication-and-permissions.md)) and call `GET /api/v1/auth/me` ([§01 §6](../01-authentication-and-permissions.md)). Confirm `hasBranch=true` / `activeBranchUid` non-null in the login `AuthUser`, and that `me.permissions` contains the expected `POS.*` codes. Optionally confirm the agent link via `GET /api/v1/agents/my?companyUid=<uid>` (returns the caller's internal agent) — a non-empty result means selling will resolve an agent.

- **Alternate / exception flows:**
  - Username already taken → **`409`** (unique constraint) at step 1.
  - Weak password → **`400`** / `WeakPasswordException` at step 1.
  - **Skip step 2 (company membership)** → the role grant (step 5) and branch assignment (step 6) both return **`409`** (`Assign this user to the company before granting/assigning…`). This is the most common "I followed the steps and it 409s" trap.
  - Unknown permission code in `permissionCodes` → **`409`** at step 4 (validated against the seeded catalogue).
  - `branchUid` whose company ≠ `companyUid` on the grant → rejected by the service (BR-5) at step 5.
  - **Skip step 7 (internal agent)** → the operator logs in and opens a session fine, but the **first sale fails `400`** (`No sales agent could be determined for this sale…`). Create the agent, then retry — no other change needed.
  - **A second internal agent for the same user** → **`409`** at step 7 (`This user is already linked to an active internal sales agent.`). A user backs exactly one ACTIVE internal agent; archive the old one first if you must re-link.
  - Admin missing the required permission for any step → **`403`** (generic `You do not have permission to perform this action.` — never names the missing code).
  - `companyUid`/`branchUid`/`userUid`/`roleUid`/`appUserId` does not resolve → **`404`** (or **`400`** if the user is inactive/root/not a company member for the agent link).
  - Skip step 6 (or leave `makeDefault: false` with no other default) → the operator logs in with `hasBranch=false`, `activeCompanyUid`/`activeBranchUid` null, and **cannot transact** (read-only landing).

- **Outcome:** A non-root `ACTIVE` user exists who, on login, receives a JWT scoped to a usable default branch and holds the `POS.*` permissions for that company scope. They are now session-ready (proceed to UC-A2/UC-A3 and then the selling loop). No async stock/GL/AR effects — this is pure IAM setup.

- **Notes & limitations:**
  - A `403` on a later POS call almost always means a **scope mismatch**: the role grant's company/branch (step 4) must match the scope the operator is acting in. Re-check `me.permissions` against [§01 §9](../01-authentication-and-permissions.md).
  - Disabling a user takes effect **immediately** — every request re-checks `ACTIVE` status, so a still-valid token starts failing `401` (`User account is no longer active.`).
  - In a **dev** deployment (`dev-in-memory` JWT signing) a backend restart rotates the key and invalidates all tokens — expect `401` and re-login. Production (`file` signing) survives restarts. ([§01 §3](../01-authentication-and-permissions.md))

---

### UC-A2: Resolve the operating context

- **Actor:** Integrator/admin (any authenticated user with the read permissions below; a cashier resolving their own branches needs none).
- **Goal:** Determine the organisation → company → branch the till belongs to, and capture the **numeric `companyId`/`branchId`** the POS till/session *list* endpoints require.
- **Preconditions:**
  - Authenticated with a valid bearer token.
  - For the administrative read path: `COMPANY.VIEW` (organisations/companies) and `BRANCH.VIEW` scoped to the company (branches). Root bypasses these. ([§02 §8](../02-company-branch-context.md))
  - **Fast path first:** the login `AuthUser` already carries `activeCompanyUid`/`activeBranchUid`. If the till lives in that default scope you only need the *numeric* ids (steps 2–3), not a picker.

- **Main flow:**
  1. **Resolve the organisation** — `GET /api/v1/organisations/current` ([§02 §2.1](../02-company-branch-context.md)), permission `isAuthenticated()` (no `COMPANY.VIEW` needed). Returns `OrganisationDto` — keep `uid`. (Returns the lowest-id/first-bootstrapped org; single-org deployments are the norm.)
  2. **List companies in that org** — `GET /api/v1/companies?organisationUid=<uid>` ([§02 §3](../02-company-branch-context.md)), permission `COMPANY.VIEW`. The `organisationUid` query param is **required**. Pick a `CompanyDto`; keep both its **`id`** (numeric) and `uid`, plus `baseCurrency` (relevant when a later sale omits/overrides currency).
  3. **List branches in that company** — `GET /api/v1/branches?companyUid=<company uid>` ([§02 §4](../02-company-branch-context.md)), permission `BRANCH.VIEW` scoped to the company. `companyUid` is **required**. Pick a `BranchDto` (for a store, typically `branchType=RETAIL`); keep both its **`id`** and `uid`. `BranchDto` conveniently carries both `companyId` and `companyUid` too.
  4. Carry forward: company `uid` + numeric `id`, and branch `uid` + numeric `id`. UC-A3 (create till) uses `companyUid` + numeric `branchId`; UC-A4 (list tills) uses numeric `companyId` + numeric `branchId`.

- **Alternate / exception flows:**
  - **No org bootstrapped yet** → `GET /organisations/current` returns **`404`** (`No organisation exists yet. Bootstrap the deployment first.`).
  - `organisationUid` omitted on the companies list, or `companyUid` omitted on the branches list → **`400`** (missing required query param).
  - Non-numeric value where a numeric id is expected later → **`400`** (`MethodArgumentTypeMismatchException`).
  - Caller lacks `COMPANY.VIEW` / `BRANCH.VIEW`, or cannot act in the company's scope → **`403`**.
  - **Operator without `COMPANY.VIEW`/`BRANCH.VIEW`** (e.g. a plain cashier): use the self-scoped `GET /api/v1/auth/my-branches` ([§01 §7](../01-authentication-and-permissions.md)) instead — it returns the caller's ACTIVE switchable branches (`branchUid`, `companyUid`, `isDefault`) with **no** `USER.VIEW`/`COMPANY.VIEW` requirement. Note it does **not** return the numeric `companyId`/`branchId` needed for the POS *list* endpoints, so the numeric-id path still needs `COMPANY.VIEW`/`BRANCH.VIEW`.

- **Outcome:** You hold the organisation uid, the company (`id` + `uid` + `baseCurrency`), and the branch (`id` + `uid`) the till belongs to. No state is changed (all reads). The next steps (create/list a till) can now bind the correct identifiers.

- **Notes & limitations:**
  - Every numeric id is serialised as a **JSON string** on the wire (`"id": "10"`) and accepts either `10` or `"10"` inbound — type your client id fields as strings to avoid 64-bit precision loss. ([§02 §6](../02-company-branch-context.md))
  - To act in a **non-default** branch on any single call, add `X-Branch-Uid: <branchUid>` ([§01 §8](../01-authentication-and-permissions.md)); for a non-root user it must be one of their ACTIVE assignments, else `403`.
  - `GET /api/v1/organisations` (the unfiltered list, distinct from `/current`) requires `COMPANY.VIEW` and is rarely needed — prefer `/current`.

---

### UC-A3: Create a till (register)

- **Actor:** Integrator/admin / store manager (holder of `POS.TILL.MANAGE`).
- **Goal:** Register a new till (cash register) on a branch so cashiers can open sessions on it.
- **Preconditions:**
  - Authenticated; caller holds `POS.TILL.MANAGE` and can act in the target company's scope (`ScopeGuard`). ([§07 §1](../07-tills.md))
  - Context resolved (UC-A2): you have the owning company `uid` and the numeric `branchId`.
  - The company has a usable **cash/bank account** (a till's drawer account is a non-null FK). If you won't pass one explicitly, the company must have a default-or-first active cash account.

- **Main flow:**
  1. **Create the till** — `POST /api/v1/pos/tills` ([§07 §1](../07-tills.md)), permission `POS.TILL.MANAGE`, success **`201 Created`**. Body `CreatePosTillRequest`:

     | Field | Type | Required | Notes |
     |---|---|---|---|
     | `companyUid` | string | yes | Owning company uid (resolves to `companyId`). |
     | `branchId` | number | yes | **Numeric** branch id — *not* a uid (the odd-one-out body). |
     | `name` | string | yes | Human label, ≤60 chars (e.g. `"Front Counter 1"`). |
     | `cashBankAccountUid` | string | no | Drawer account uid; omit/blank → defaults (see below). |

     ```json
     { "companyUid": "co_4Bn1Zx", "branchId": 100, "name": "Front Counter 1" }
     ```
  2. **Default drawer-account resolution** (when `cashBankAccountUid` omitted/blank): the service picks (a) the company's default active cash account, else (b) the first active cash account, else (c) **`409`**. ([§07 §1 "Default cash-account resolution"](../07-tills.md))
  3. **Capture the response** — `PosTillDto{ id, uid, companyId, branchId, code, name, cashBankAccountId, status }`. New tills are `status: "ACTIVE"`; `code` is `null` (no create-time field for it). **Keep `uid`** — sessions are opened with the till's `uid` (`OpenSessionRequest.tillUid`, [§08](../08-sessions.md)).

- **Alternate / exception flows:**
  - Blank `companyUid`/`name`, null `branchId`, or `name` > 60 chars → **`400`**.
  - `companyUid` does not resolve, or an explicit `cashBankAccountUid` does not resolve for the company → **`404`**.
  - No usable cash/bank account for the company and none supplied → **`409`** (`No cash/bank account found for company <id>. Create a cash account before creating a POS till.`).
  - Caller lacks `POS.TILL.MANAGE` or cannot act in the company's scope → **`403`**.
  - `Content-Type` not `application/json` → **`415`**.

- **Outcome:** A new `ACTIVE` till exists on the `(companyId, branchId)` pair, bound to a drawer cash/bank account, and is immediately listable (UC-A4) and openable as a session. No async stock/GL/AR effect — till creation is master-data only. The `(companyId, branchId)` pairing is **immutable** thereafter.

- **Notes & limitations:**
  - **No update endpoint.** The till surface is create / get / list / deactivate only — there is no PUT/PATCH. `name`, `code`, drawer account, and branch cannot be changed after creation. To "rename" or "move", deactivate (UC-A5) and recreate. ([§07 "Integration notes"](../07-tills.md))
  - `code`, `defaultPriceListId`, and `deviceTerminalId` are **not** settable via this API (`code` comes back `null`; the other two aren't exposed in the DTO at all) — don't build your client to require them.
  - "At most one OPEN session per till" is enforced on the **session** layer (a `409` at session-open time), not at till creation. ([§07 "What a till is"](../07-tills.md))

---

### UC-A4: List / select an existing till

- **Actor:** Integrator/admin / cashier (holder of `POS.TILL.VIEW`).
- **Goal:** Find and select the till a session will be opened on (the start-of-shift bootstrap step).
- **Preconditions:**
  - Authenticated; caller holds `POS.TILL.VIEW` and can act in the company's scope.
  - Context resolved (UC-A2): you have the **numeric** `companyId` and `branchId`.

- **Main flow:**
  1. **List tills for the branch** — `GET /api/v1/pos/tills?companyId={companyId}&branchId={branchId}` ([§07 §3](../07-tills.md)), permission `POS.TILL.VIEW`. Both query params are **required numeric ids**. Returns a plain `List<PosTillDto>` (not paged; `meta` is `null`).
  2. **Filter client-side to live tills.** The endpoint returns **every** status (`ACTIVE`, `INACTIVE`, `ARCHIVED`) with no server-side status filter — present only `status == "ACTIVE"` tills for session open.
  3. **Select a till** and keep its **`uid`** for opening a session ([§08](../08-sessions.md)).
  4. *(Optional)* fetch one till by uid — `GET /api/v1/pos/tills/uid/{uid}` ([§07 §2](../07-tills.md)), permission `POS.TILL.VIEW` (scoped to that till) — e.g. to refresh a single till's status.

- **Alternate / exception flows:**
  - Missing/non-numeric `companyId` or `branchId` → **`400`**.
  - **Empty state:** no tills on that `(company, branch)` → `data: []`. Branch onboarding action: create one via UC-A3 (requires `POS.TILL.MANAGE`, which a plain cashier won't hold — route the user to a manager).
  - All returned tills are `INACTIVE`/`ARCHIVED` (none `ACTIVE` after the client filter) → treat as "no usable till"; create or reactivate-by-recreate is needed.
  - `GET /pos/tills/uid/{uid}` with an unknown uid → **`404`** (`NotFoundException.of("PosTill", uid)`).
  - Caller lacks `POS.TILL.VIEW` or cannot act in the scope → **`403`**.

- **Outcome:** The operator has selected an `ACTIVE` till `uid` ready to open a session on. No state change (reads only).

- **Notes & limitations:**
  - **Status filtering is on you** — the list never hides deactivated tills, so always filter to `ACTIVE` before offering tills for session open. ([§07 "Integration notes"](../07-tills.md))
  - The list takes the **numeric** `companyId`/`branchId` (not uids); resolve them in UC-A2. `uid` is what you carry forward into the session/sale flows.

---

### UC-A5: Retire / delete a till

- **Actor:** Integrator/admin / store manager (holder of `POS.TILL.MANAGE`).
- **Goal:** Take a till out of service (e.g. retired register, wrong branch, needs "renaming").
- **Preconditions:**
  - Authenticated; caller holds `POS.TILL.MANAGE` (scoped to the till) and can act in its company's scope.
  - You have the till's `uid` (from UC-A4).

- **Main flow:**
  1. **Deactivate (soft-delete) the till** — `DELETE /api/v1/pos/tills/uid/{uid}` ([§07 §4](../07-tills.md)), permission `POS.TILL.MANAGE` (scoped to the till). Success is **`200 OK`** with an **empty** body: `{ "data": null, "errors": [], "meta": null }`.
  2. The service loads the till (404 if absent), runs the scope guard, sets `status = INACTIVE`, stamps audit fields, and records a `POS_TILL_DEACTIVATE` audit event.

- **Alternate / exception flows:**
  - Unknown `uid` → **`404`** (`NotFoundException.of("PosTill", uid)`).
  - Caller lacks `POS.TILL.MANAGE` for the till's scope, or cannot act in its company → **`403`**.
  - Missing/invalid/expired bearer, or user no longer ACTIVE → **`401`**.

- **Outcome:** The till's `status` becomes `INACTIVE`. **The row is not removed** — it still appears in the UC-A4 list endpoint (filter it out client-side) but should not be offered for new sessions.

- **Notes & limitations:**
  - **There is no hard delete** — `DELETE` is a soft status flip to `INACTIVE`; the record and its history are retained. ([§07 §4](../07-tills.md))
  - **No reactivate endpoint.** There is no PUT/PATCH and no "set ACTIVE" route, so once `INACTIVE` a till cannot be brought back via this API — to resume operations, create a new till (UC-A3). The "rename / move branch" workaround is the same: deactivate + recreate.
  - **No open-session guard at the till layer.** Deactivation does **not** check whether a session is currently OPEN on the till — that constraint lives on the session layer. Close/reconcile any open session first ([§08](../08-sessions.md)) so you don't strand an open shift on a retired register.

---

## Not supported today (setup-related gaps)

These are setup actions an integrator might expect but the API does **not** offer; documented here so you design around them. See [§12 Known Limitations](../12-known-limitations.md) and [§07 Tills](../07-tills.md).

| Desired action | Status | Reason / reference | Closest workaround |
|---|---|---|---|
| **Update / rename a till** (change `name`, `code`, drawer account) | **Not supported today** | The till controller exposes only create / get / list / deactivate — no PUT/PATCH ([§07 "Integration notes"](../07-tills.md)). | Deactivate (UC-A5) and recreate (UC-A3) with the new values. |
| **Move a till to another branch** | **Not supported today** | `companyId`/`branchId` are stamped at create and are `updatable = false` (immutable) ([§07 "Relationship to a branch and company"](../07-tills.md)). | Deactivate the old till and create a new one on the target branch. |
| **Reactivate a deactivated till** | **Not supported today** | No route flips `status` back to `ACTIVE`; `DELETE` only goes `ACTIVE → INACTIVE` ([§07 §4](../07-tills.md)). | Create a fresh till (UC-A3). |
| **Set a till `code` / `defaultPriceListId` / `deviceTerminalId` at create** | **Not supported today** | `CreatePosTillRequest` has no such fields; `code` is "generated externally or left unset" and the other two aren't exposed ([§07 "What a till is"](../07-tills.md)). | Leave `code` null; manage device/price-list mapping outside this API. |
| **Self-service operator sign-up** | **Not supported today** | No self-registration — provisioning is admin-driven via the IAM controllers ([§01 §10](../01-authentication-and-permissions.md)). | Admin runs UC-A1. |
| **Set `is_root` on a provisioned user** | **Not supported (by design)** | `is_root` is intentionally absent from `CreateUserRequest` and can never be set via the API ([§01 §10.1](../01-authentication-and-permissions.md)). | Use roles + permission grants (UC-A1) for least-privilege access. |

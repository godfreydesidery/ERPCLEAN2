# POS Tills (Registers)

This section documents the `/api/v1/pos/tills` CRUD surface for an **external POS client**
(desktop or mobile) integrating against the ERP REST API.

It assumes you have already read the shared contract sections (base URL/versioning, the
`ApiResponse<T>` envelope, the error table, the JWT auth flow, pagination, and POS
permissions). Those are **not** repeated here — only the till-specific deltas are called out.

Ground-truth source files (open these if you need to verify anything below):

- `src/main/java/com/erp/api/PosTillController.java` — the REST controller
- `src/main/java/com/erp/modules/sales/domain/dto/PosTillDto.java` — the response DTO
- `src/main/java/com/erp/modules/sales/domain/dto/CreatePosTillRequest.java` — the create request DTO
- `src/main/java/com/erp/modules/sales/service/PosTillServiceImpl.java` — the service logic
- `src/main/java/com/erp/modules/sales/domain/entity/PosTill.java` — the JPA entity

---

## What a till is

A **till** (a.k.a. register) is a physical or virtual cash register registered to a single
**branch**. From `PosTill.java`:

> "A POS till (physical or virtual cash register) registered to a branch (ADR-0029 D-5).
> At most one session may be OPEN on a till at a time (partial unique index in DB)."

A till is the anchor for the cashier-session lifecycle: you **open a POS session on a till**,
ring sales on that session, then close and reconcile it (see the sessions section). The
"at most one OPEN session per till" rule is enforced at the DB level by a partial unique
index — so attempting to open a second session on a till that already has an OPEN session is
a session-layer conflict (`409`), not a till-layer concern.

> **A till is only released by an explicit cash-up.** Nothing else frees it: there is no idle
> timeout, no nightly sweeper, and no logout hook that closes a session — deliberately, because
> closing a shift records a **counted cash amount** and the system must never invent one. So an
> occupied till stays occupied until someone closes that session (see the sessions section), and a
> till picker must therefore tell the cashier *who* holds it rather than just greying it out.

### Relationship to a branch and company

Every till is owned by exactly one `(companyId, branchId)` pair. Both are stamped at create
time and are **immutable** afterwards (the entity columns are `updatable = false`):

```java
@Column(name = "company_id", nullable = false, updatable = false) private Long companyId;
@Column(name = "branch_id",  nullable = false, updatable = false) private Long branchId;
```

There is no "move a till to another branch" operation. If a till belongs to the wrong
branch, deactivate it and create a new one.

### Relationship to a cash/bank account

Each till has a **drawer account** — a non-null FK to `cash_bank_accounts(id)`
(`cashBankAccountId`). This is the account that POS cash takes against. You may pass an
explicit `cashBankAccountUid` on create; if you omit it, the service resolves the company's
**default active cash account** automatically (details under *Create* below).

### `PosTillDto` fields

The response payload for every till endpoint is `PosTillDto` (a Java `record`). Exact fields
and types, verbatim from `PosTillDto.java`:

| Field                  | JSON type        | Notes |
|------------------------|------------------|-------|
| `id`                   | string (`Long`)  | Internal numeric id, serialised as a **JSON string** (global Long-as-string config). Stable for body joins; **not** used in till URLs. |
| `uid`                  | string           | 26-char ULID. This is what you put in `/uid/{uid}` paths and cross-system references. |
| `companyId`            | string (`Long`)  | Owning company id. Immutable. |
| `branchId`             | string (`Long`)  | Owning branch id. Immutable. |
| `code`                 | string \| null   | Short per-company till code, **server-generated at create** (see note below). |
| `name`                 | string           | Short human label, e.g. `"Till 1"`. Max length 60. |
| `cashBankAccountId`    | string (`Long`)  | The drawer account id (FK → `cash_bank_accounts`). Always present (NOT NULL). |
| `status`               | string (enum)    | `MasterStatus` — one of `ACTIVE`, `INACTIVE`, `ARCHIVED`. New tills are `ACTIVE`; `DELETE` flips it to `INACTIVE`. |
| `hasOpenSession`       | boolean          | `true` while a `PosSession` in status `OPEN` exists for **this till**. See *Occupancy* below — this flag is till-keyed and says nothing about **whose** shift it is. |
| `openSessionUid`       | string \| null   | The occupying session's uid — pass it straight to the session endpoints (x-read / close). `null` when the till is free. |
| `openSessionCashierId` | string (`Long`) \| null | The occupying cashier's user id — compare this to decide "mine". `null` when the till is free. |
| `openSessionCashierName` | string \| null | The occupying cashier's display name, for the label. `null` when the till is free; a neutral phrase (`"Another cashier"`) when the occupant can no longer be named — **print it as-is**, it is never an id. |
| `openSessionOpenedAt`  | string (ISO-8601) \| null | When that session was opened (`Instant`). `null` when the till is free. |

> **Note on `code`:** `CreatePosTillRequest` still has **no** `code` field — you cannot choose
> it — but the service now generates one per company at create time
> (`numberGen.nextPosTill(companyId)`, format `TILL-0001`), so `code` comes back populated on new
> tills. Rows created before that change may still carry `null`; treat the field as nullable and
> never key on it.

> **Fields not in the DTO:** the entity also carries `defaultPriceListId` and
> `deviceTerminalId` (P3 columns) plus audit timestamps, but **none of these are exposed**
> in `PosTillDto`. Do not expect them in responses.

### Occupancy — reading `hasOpenSession` and the `openSession*` fields

`hasOpenSession` answers *"is this till busy?"*, **not** *"is this till mine?"*. The service looks
up `findByPosTillIdAndStatus(tillId, OPEN)` and reports whatever it finds, with **no comparison
against the caller**. A cashier whose app was force-closed still holds their own open shift, so
their till comes back `hasOpenSession: true` exactly like a colleague's live one — and treating
that as "occupied, hands off" is what locks a cashier out of their own shift.

The `openSession*` fields exist to break that tie. **The client decides "mine" vs "someone else's"
by comparing `openSessionCashierId` to the authenticated user's numeric id** — the name field is for
the label, never for the decision:

- **`openSessionCashierId` == the signed-in user** → this is *your* abandoned/parked shift. Offer
  **resume** (carry on selling against `openSessionUid`) or **cash up** (`POST
  /api/v1/pos/sessions/uid/{openSessionUid}/close` with a real counted amount).
- **`openSessionCashierId` != the signed-in user** → someone else holds the till. Say so by name
  (`openSessionCashierName`), and point the cashier at another till or at a supervisor who can
  close it. Do **not** offer resume.
- **`hasOpenSession: false`** → free; open a fresh session on it.

> **Where the caller's numeric id comes from.** `GET /api/v1/auth/me` returns the caller's `uid`,
> not their numeric id, so it cannot be compared to `openSessionCashierId` directly. The access
> token's `sub` claim **is** the numeric user id (as a string) — that is what the shipped till app
> compares against. Read `sub` from your own access token; do not try to derive it from `/auth/me`.

The session fields all come from the occupancy lookup that was already being made for
`hasOpenSession`, and the list endpoint resolves every occupant's name in a **single batched** IAM
lookup for the whole page — so reading them costs you no extra request, and costs the server no
per-till query beyond the occupancy check it already performed.

> **Use the id to decide, the name to display.** `openSessionCashierName` is display copy: it may be
> the neutral `"Another cashier"` when the occupant's account no longer resolves, so it is not a
> stable key and must never be matched against. Branch on `openSessionCashierId`.

---

## Common conventions for this surface

- **Base path:** `/api/v1/pos/tills` (class-level `@RequestMapping("/api/v1/pos/tills")`).
- **Auth:** every endpoint requires a valid `Authorization: Bearer <accessToken>` and a
  `@PreAuthorize` permission check (see per-endpoint tables).
- **Scope:** the service calls `scopeGuard.assertCanActIn(RequestContext.get(), companyId)`
  on every operation. If your active scope (from the JWT, optionally overridden by
  `X-Branch-Uid`) cannot act in the till's company, you get **403 Forbidden**.
- **Content-Type:** `POST` bodies MUST be `application/json` (else **415**).
- **Envelope:** all four endpoints return the raw DTO / list; `ApiResponseAdvice` wraps it
  into `{data, errors, meta}`. **None of the till endpoints are paged**, so `meta` is always
  `null` (the list endpoint returns a plain `List<PosTillDto>`, not a `Page`).

---

## 1. Create a till

Register a new till on a branch.

| | |
|---|---|
| **Method + path** | `POST /api/v1/pos/tills` |
| **Permission** | `POS.TILL.MANAGE` — `@PreAuthorize("@perm.has('POS.TILL.MANAGE')")` (seed desc: "Create and manage POS tills (registers)") |
| **Success status** | `201 Created` (`@ResponseStatus(HttpStatus.CREATED)`) |
| **Path/query params** | none |
| **Content-Type** | `application/json` (required) |

### Request body — `CreatePosTillRequest`

Exact fields and validation, verbatim from `CreatePosTillRequest.java`:

| Field               | JSON type | Required | Validation | Meaning |
|---------------------|-----------|----------|------------|---------|
| `companyUid`        | string    | yes      | `@NotBlank` | UID of the owning company. Resolved to the company id; the till's `companyId` is derived from this, **not** from a numeric id in the body. |
| `branchId`          | number    | yes      | `@NotNull`  | Numeric branch id the till belongs to. (Note: this is the numeric `branchId`, not a uid — matches the field type in the DTO.) |
| `name`              | string    | yes      | `@NotBlank`, `@Size(max = 60)` | Human label, e.g. `"Till 1"`. |
| `cashBankAccountUid`| string    | no       | nullable    | UID of the drawer cash/bank account. When omitted/blank, the service defaults to the company's default active cash account (see below). |

> There is no `code`, `defaultPriceListId`, or `deviceTerminalId` field on this request — do
> not send them; they will be ignored as unknown JSON properties at best.

### Default cash-account resolution

If `cashBankAccountUid` is null or blank, `PosTillServiceImpl.resolveCashAccount` resolves
the drawer account as follows:

1. `cashAccounts.findByCompanyIdAndIsDefaultTrue(companyId)` — the company's default account; else
2. the first account from `cashAccounts.findByCompanyIdAndActive(companyId, true)`; else
3. **409 Conflict** — `"No cash/bank account is configured for this company. Please create a cash
   account before setting up a POS till."`

If `cashBankAccountUid` **is** provided but does not resolve for the company, you get
**404 Not Found** (`NotFoundException.of("CashBankAccount", uid)`).

### Success response (201)

The controller returns the created `PosTillDto`, wrapped by the envelope:

```json
{
  "data": {
    "id": "41",
    "uid": "01HZX9Q7M3K2J8VN4C6B1TFD5R",
    "companyId": "7",
    "branchId": "3",
    "code": "TILL-0003",
    "name": "Till 1",
    "cashBankAccountId": "12",
    "status": "ACTIVE",
    "hasOpenSession": false,
    "openSessionUid": null,
    "openSessionCashierId": null,
    "openSessionCashierName": null,
    "openSessionOpenedAt": null
  },
  "errors": [],
  "meta": null
}
```

### Notable errors

| Status | Cause |
|--------|-------|
| `400` | Bean-validation failure — blank `companyUid`/`name`, null `branchId`, or `name` longer than 60 chars (message format `"field: message"`). |
| `401` | Missing/invalid/expired bearer token, or the user is no longer ACTIVE. |
| `403` | Caller lacks `POS.TILL.MANAGE`, or cannot act in the resolved company's scope (`ScopeGuard`). |
| `404` | `companyUid` does not resolve (`NotFoundException.of("Company", uid)`), or an explicit `cashBankAccountUid` does not resolve for the company. |
| `409` | No usable cash/bank account exists for the company (and none was supplied). |
| `415` | `Content-Type` was not `application/json`. |

### curl

```bash
curl -i -X POST "$BASE/api/v1/pos/tills" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: $(uuidgen)" \
  -d '{
        "companyUid": "01HZX0COMPANYUID00000000AA",
        "branchId": 3,
        "name": "Till 1"
      }'
```

With an explicit drawer account:

```bash
curl -i -X POST "$BASE/api/v1/pos/tills" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "companyUid": "01HZX0COMPANYUID00000000AA",
        "branchId": 3,
        "name": "Express Lane",
        "cashBankAccountUid": "01HZX0CASHACCTUID0000000BB"
      }'
```

---

## 2. Get a till by uid

| | |
|---|---|
| **Method + path** | `GET /api/v1/pos/tills/uid/{uid}` |
| **Permission** | `POS.TILL.VIEW` — `@PreAuthorize("@perm.scoped(#uid,'postill','POS.TILL.VIEW')")` (scoped: the permission is checked against the till identified by `{uid}`) |
| **Success status** | `200 OK` |
| **Path params** | `uid` — the till's 26-char ULID |
| **Query params** | none |

### Success response (200)

```json
{
  "data": {
    "id": "41",
    "uid": "01HZX9Q7M3K2J8VN4C6B1TFD5R",
    "companyId": "7",
    "branchId": "3",
    "code": "TILL-0003",
    "name": "Till 1",
    "cashBankAccountId": "12",
    "status": "ACTIVE",
    "hasOpenSession": true,
    "openSessionUid": "01HZXB4K9P0Q2R7S5T3U8V6W1X",
    "openSessionCashierId": "12",
    "openSessionCashierName": "Amina Juma",
    "openSessionOpenedAt": "2026-08-01T05:12:33.412Z"
  },
  "errors": [],
  "meta": null
}
```

### Notable errors

| Status | Cause |
|--------|-------|
| `401` | Missing/invalid/expired bearer token, or user no longer ACTIVE. |
| `403` | Caller lacks `POS.TILL.VIEW` for the till's scope, or cannot act in the till's company (`ScopeGuard`). |
| `404` | No till with that uid (`NotFoundException.of("PosTill", uid)`). |

### curl

```bash
curl -s "$BASE/api/v1/pos/tills/uid/01HZX9Q7M3K2J8VN4C6B1TFD5R" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

---

## 3. List tills by branch

| | |
|---|---|
| **Method + path** | `GET /api/v1/pos/tills?companyId={companyId}&branchId={branchId}` |
| **Permission** | `POS.TILL.VIEW` — `@PreAuthorize("@perm.has('POS.TILL.VIEW')")` |
| **Success status** | `200 OK` |
| **Path params** | none |
| **Query params** | `companyId` (`Long`, **required**) and `branchId` (`Long`, **required**) — both bound via `@RequestParam` with no default, so both must be present |

This endpoint returns **all** tills for the given `(companyId, branchId)` regardless of
status — `ACTIVE`, `INACTIVE`, and `ARCHIVED` are all included (the repository query
`findByCompanyIdAndBranchId` applies **no** status filter). If you only want live tills,
filter client-side on `status == "ACTIVE"`.

> **Not paged.** The handler returns a plain `List<PosTillDto>`, so the response is a JSON
> array in `data` and `meta` is `null`. There are no `page`/`size`/`sort` params here.

### Success response (200)

```json
{
  "data": [
    {
      "id": "41",
      "uid": "01HZX9Q7M3K2J8VN4C6B1TFD5R",
      "companyId": "7",
      "branchId": "3",
      "code": "TILL-0003",
      "name": "Till 1",
      "cashBankAccountId": "12",
      "status": "ACTIVE",
      "hasOpenSession": true,
      "openSessionUid": "01HZXB4K9P0Q2R7S5T3U8V6W1X",
      "openSessionCashierId": "12",
      "openSessionCashierName": "Amina Juma",
      "openSessionOpenedAt": "2026-08-01T05:12:33.412Z"
    },
    {
      "id": "42",
      "uid": "01HZXA0F8N5R7P2Q9D3E6W8YH1",
      "companyId": "7",
      "branchId": "3",
      "code": "TILL-0004",
      "name": "Express Lane",
      "cashBankAccountId": "12",
      "status": "INACTIVE",
      "hasOpenSession": false,
      "openSessionUid": null,
      "openSessionCashierId": null,
      "openSessionCashierName": null,
      "openSessionOpenedAt": null
    }
  ],
  "errors": [],
  "meta": null
}
```

In the first row above, a client signed in as user `12` should offer **resume / cash up**; any
other cashier should be told the till is in use.

When no tills match, `data` is `[]` (empty array), `errors` is `[]`, `meta` is `null`.

### Notable errors

| Status | Cause |
|--------|-------|
| `400` | Missing `companyId` or `branchId` query param, or a non-numeric value (`MissingServletRequestParameterException` / `MethodArgumentTypeMismatchException`). |
| `401` | Missing/invalid/expired bearer token, or user no longer ACTIVE. |
| `403` | Caller lacks `POS.TILL.VIEW`, or cannot act in `companyId`'s scope (`ScopeGuard`). |

### curl

```bash
curl -s "$BASE/api/v1/pos/tills?companyId=7&branchId=3" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

---

## 4. Deactivate (soft-delete) a till

There is **no hard delete**. `DELETE` flips the till's `status` to `INACTIVE` — the row
remains and still appears in the list endpoint.

| | |
|---|---|
| **Method + path** | `DELETE /api/v1/pos/tills/uid/{uid}` |
| **Permission** | `POS.TILL.MANAGE` — `@PreAuthorize("@perm.scoped(#uid,'postill','POS.TILL.MANAGE')")` (scoped to the till) |
| **Success status** | `200 OK` with an **empty** body — the handler returns `void`, so the envelope is `{"data": null, "errors": [], "meta": null}` |
| **Path params** | `uid` — the till's 26-char ULID |
| **Query params** | none |

What the service does (`deactivateTill`): loads the till (404 if absent), runs the scope
guard, sets `status = INACTIVE`, stamps `updatedAt`/`updatedBy`, and records a
`POS_TILL_DEACTIVATE` audit event. There is **no** check that the till has no open
session — that constraint lives on the session layer.

### Success response (200)

```json
{
  "data": null,
  "errors": [],
  "meta": null
}
```

### Notable errors

| Status | Cause |
|--------|-------|
| `401` | Missing/invalid/expired bearer token, or user no longer ACTIVE. |
| `403` | Caller lacks `POS.TILL.MANAGE` for the till's scope, or cannot act in the till's company (`ScopeGuard`). |
| `404` | No till with that uid (`NotFoundException.of("PosTill", uid)`). |

### curl

```bash
curl -i -X DELETE "$BASE/api/v1/pos/tills/uid/01HZX9Q7M3K2J8VN4C6B1TFD5R" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

---

## Integration notes for the POS client

- **Provisioning is back-office, not cashier.** Creating/deactivating tills needs
  `POS.TILL.MANAGE`; cashiers typically only hold `POS.TILL.VIEW` (and the session/sale
  permissions). Plan your client UI so the "register management" screen is gated behind
  `POS.TILL.MANAGE` (check `/api/v1/auth/me` `permissions`).
- **Bootstrapping a shift:** list tills for the branch (`GET /pos/tills?companyId=&branchId=`),
  let the cashier pick an `ACTIVE` one, then open a session on its `uid` (sessions section).
- **Status filtering is on you.** The list returns every status; filter to `status == "ACTIVE"`
  before presenting tills for session open.
- **Never grey out a busy till without saying why.** For each `hasOpenSession: true` row, compare
  `openSessionCashierId` with the signed-in user (the token's `sub`): offer **resume / cash up** on
  a match, and an explanation on a mismatch. A disabled tile with no reason is how a cashier ends
  up permanently locked out of a shift only they can close.
- **Re-list after a cash-up.** The occupancy fields are computed per request, so refreshing the
  till list is enough to see a freed till — the cashier should never have to restart the app.
- **No update endpoint.** The controller exposes only create / get / list / deactivate.
  There is no PUT/PATCH — `name`, `code`, drawer account, etc. cannot be changed after
  creation via this API. To "rename", deactivate and recreate.
- **`uid` vs `id`.** Use `uid` (ULID) in all URLs and for cross-system references; `id`
  (numeric) is exposed for convenience but is not addressable by any till endpoint.

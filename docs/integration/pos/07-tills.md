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

| Field               | JSON type        | Notes |
|---------------------|------------------|-------|
| `id`                | number (`Long`)  | Internal numeric id. Stable for body joins; **not** used in till URLs. |
| `uid`               | string           | 26-char ULID. This is what you put in `/uid/{uid}` paths and cross-system references. |
| `companyId`         | number (`Long`)  | Owning company id. Immutable. |
| `branchId`          | number (`Long`)  | Owning branch id. Immutable. |
| `code`              | string \| null   | Optional short code (e.g. `"T-001"`). Nullable — there is **no** create-time field for it (see note below), so it is `null` on freshly created tills. |
| `name`              | string           | Short human label, e.g. `"Till 1"`. Max length 60. |
| `cashBankAccountId` | number (`Long`)  | The drawer account id (FK → `cash_bank_accounts`). Always present (NOT NULL). |
| `status`            | string (enum)    | `MasterStatus` — one of `ACTIVE`, `INACTIVE`, `ARCHIVED`. New tills are `ACTIVE`; `DELETE` flips it to `INACTIVE`. |

> **Note on `code`:** the entity has a `code` column and the DTO exposes it, but
> `CreatePosTillRequest` has **no** `code` field — the controller offers no way to set it on
> create or update. Per the entity Javadoc it is "generated externally or left unset", so
> expect `code = null` from this API. Do not build your client to require it.

> **Fields not in the DTO:** the entity also carries `defaultPriceListId` and
> `deviceTerminalId` (P3 columns) plus audit timestamps, but **none of these are exposed**
> in `PosTillDto`. Do not expect them in responses.

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
3. **409 Conflict** — `"No cash/bank account found for company <id>. Create a cash account before creating a POS till."`

If `cashBankAccountUid` **is** provided but does not resolve for the company, you get
**404 Not Found** (`NotFoundException.of("CashBankAccount", uid)`).

### Success response (201)

The controller returns the created `PosTillDto`, wrapped by the envelope:

```json
{
  "data": {
    "id": 41,
    "uid": "01HZX9Q7M3K2J8VN4C6B1TFD5R",
    "companyId": 7,
    "branchId": 3,
    "code": null,
    "name": "Till 1",
    "cashBankAccountId": 12,
    "status": "ACTIVE"
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
    "id": 41,
    "uid": "01HZX9Q7M3K2J8VN4C6B1TFD5R",
    "companyId": 7,
    "branchId": 3,
    "code": null,
    "name": "Till 1",
    "cashBankAccountId": 12,
    "status": "ACTIVE"
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
      "id": 41,
      "uid": "01HZX9Q7M3K2J8VN4C6B1TFD5R",
      "companyId": 7,
      "branchId": 3,
      "code": null,
      "name": "Till 1",
      "cashBankAccountId": 12,
      "status": "ACTIVE"
    },
    {
      "id": 42,
      "uid": "01HZXA0F8N5R7P2Q9D3E6W8YH1",
      "companyId": 7,
      "branchId": 3,
      "code": null,
      "name": "Express Lane",
      "cashBankAccountId": 12,
      "status": "INACTIVE"
    }
  ],
  "errors": [],
  "meta": null
}
```

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
- **No update endpoint.** The controller exposes only create / get / list / deactivate.
  There is no PUT/PATCH — `name`, `code`, drawer account, etc. cannot be changed after
  creation via this API. To "rename", deactivate and recreate.
- **`uid` vs `id`.** Use `uid` (ULID) in all URLs and for cross-system references; `id`
  (numeric) is exposed for convenience but is not addressable by any till endpoint.

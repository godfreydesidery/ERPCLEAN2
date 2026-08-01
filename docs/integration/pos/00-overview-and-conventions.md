# Overview & Conventions

> **Audience.** Developers building an **external POS client** (desktop or mobile) that talks to this
> ERP over its REST API. This document is the entry point for the POS integration set: it defines the
> contract conventions every later page assumes (base URL, the response envelope, content types,
> pagination, the id conventions, value formats, errors, and where to find the machine-readable spec).
>
> Everything here is grounded in the actual backend source under
> `backend/src/main/java/com/erp/...`. Class and field names are quoted verbatim from that code.

---

## 1. What this API is

The ERP exposes a **stateless, JWT-secured JSON REST API**. The POS surface is three controllers in
package `com.erp.api`:

| Controller | Class-level `@RequestMapping` | Purpose |
|---|---|---|
| `PosSaleController` | `/api/v1/pos/sales` | Ring a quick sale on an open session |
| `PosSessionController` | `/api/v1/pos/sessions` | Cashier-session lifecycle: open / payout / close / X-read / reconcile (Z-read) |
| `PosTillController` | `/api/v1/pos/tills` | Till (register) CRUD |

A POS client also depends on the **auth** controller (`com.erp.api.AuthController`,
`@RequestMapping("/api/v1/auth")`) to obtain and refresh tokens. See §6.

There is **no SOAP, no GraphQL, no WebSocket** surface for POS. Every interaction is HTTP request →
JSON response.

---

## 2. Base URL & versioning

All endpoints are versioned under the fixed prefix **`/api/v1`**. Versioning is **path-based** via
Spring `@RequestMapping` class-level prefixes — there is **no** header/media-type versioning and **no**
version-negotiation logic. (`AuthController` is `@RequestMapping("/api/v1/auth")`; the POS controllers
are `/api/v1/pos/sales`, `/api/v1/pos/sessions`, `/api/v1/pos/tills` as shown above.)

`SecurityConfig` gates everything under `/api/**` as `authenticated()` **except** the explicit public
list (login / refresh / logout / health / OpenAPI). So the POS base paths a client uses are:

- `POST /api/v1/pos/sales`
- the `/api/v1/pos/sessions/*` lifecycle
- the `/api/v1/pos/tills/*` CRUD

Construct full URLs as `{host}{/api/v1/...}`, e.g. `https://erp.example.com/api/v1/pos/sales`. Pick the
host per environment; the path is identical everywhere.

---

## 3. The `ApiResponse<T>` envelope

**Every** `com.erp.api` controller return value is auto-wrapped by `ApiResponseAdvice` — a
`ResponseBodyAdvice` annotated `@RestControllerAdvice(basePackages = "com.erp.api")` — into the single
envelope `com.erp.platform.common.api.ApiResponse<T>`:

```java
public record ApiResponse<T>(T data, List<String> errors, Object meta) {
    public static <T> ApiResponse<T> ok(T data) { ... }
    public static <T> ApiResponse<T> ok(T data, Object meta) { ... }
    public static <T> ApiResponse<T> error(List<String> errors) { ... }
    public static <T> ApiResponse<T> error(String error) { ... }
}
```

Field meanings (from the record Javadoc):

- **`data`** — the payload, or `null` on error.
- **`errors`** — user-facing error message strings; **empty list on success**. These are
  user-safe only — never internal exception text (PROJECT-CONVENTIONS §3.1).
- **`meta`** — optional metadata (paging, etc.); `null` when unused.

**On success:** `{ "data": <T>, "errors": [], "meta": null | <PageMeta> }`
**On error:** `{ "data": null, "errors": ["..."], "meta": null }`

Controllers return the **raw `T`** (e.g. `PosSaleController.processSale` returns `SalesInvoiceDto`); the
advice wraps it. Three exceptions to wrapping:

1. If a controller **already** returns an `ApiResponse<?>` it passes through unchanged — e.g.
   `PosSessionController.list` returns `ApiResponse.ok(page.getContent(), PageMeta.from(page))`.
2. `String`, `byte[]`, and `Resource` bodies are **not** wrapped (binary/file downloads keep their own
   content type). No POS endpoint returns these.
3. Endpoints that return nothing (`void`) — e.g. record-payout, deactivate-till — produce an empty body.

So a POS sale success body is the finalised invoice wrapped in the envelope with **HTTP 201**:

```json
{ "data": { /* ...SalesInvoiceDto... */ }, "errors": [], "meta": null }
```

**Client rule:** always read `data` for the payload and treat a non-empty `errors` array as a failure,
in tandem with the HTTP status code.

---

## 4. Request / response content types

- **Requests** with a body (every `POST`/`PUT`/`DELETE`-with-body) **MUST** send
  `Content-Type: application/json`. A wrong content type yields **415 Unsupported Media Type** with the
  message `Content-Type not supported. Use application/json.`
- **Responses** are `application/json` (the envelope), except the unwrapped binary types in §3 which no
  POS endpoint uses.
- Send `Accept: application/json` (optional but recommended).
- Character encoding is UTF-8.

---

## 5. Identifier conventions: `uid` in the URL, numeric `id` in the body

This codebase deliberately splits two kinds of identifier, and POS endpoints follow it strictly:

- **Public `uid` (opaque string) in the URL path.** Path variables address resources by their `uid`,
  not by their numeric primary key. Examples from the POS controllers:
  - `GET  /api/v1/pos/sessions/uid/{uid}`
  - `POST /api/v1/pos/sessions/uid/{uid}/close`
  - `POST /api/v1/pos/sessions/uid/{uid}/payouts`
  - `POST /api/v1/pos/sessions/uid/{uid}/reconcile`
  - `GET  /api/v1/pos/sessions/uid/{uid}/x-read`
  - `GET  /api/v1/pos/tills/uid/{uid}`, `DELETE /api/v1/pos/tills/uid/{uid}`

  Request **bodies** also reference parent resources by uid where the field name ends in `Uid`, e.g.
  `PosSaleRequest.sessionUid`, `OpenSessionRequest.tillUid`, `CreatePosTillRequest.companyUid` /
  `cashBankAccountUid`.

- **Numeric `id` (`Long`) in the body for cross-entity references** that are looked up by primary key,
  e.g. `PosSaleRequest.customerId`, `agentId`, and per line `productId` / `unitId`;
  `CreatePosTillRequest.branchId`; the `companyId` query param on the list endpoints.

### `Long` ids serialise as JSON **strings**

Per `JacksonConfig` (PROJECT-CONVENTIONS §3.3), every `Long` is serialised to JSON as a **string** so
64-bit ids survive JavaScript's 53-bit number precision:

```java
module.addSerializer(Long.class, ToStringSerializer.instance);
module.addSerializer(Long.TYPE, ToStringSerializer.instance);
```

Consequences for a client:

- In **responses**, every `id` field (`SalesInvoiceDto.id`, `customerId`, `agentId`, `companyId`,
  `branchId`, `posTillId`, `cashierId`, `varianceJournalId`, …) is a **JSON string**, e.g.
  `"id": "100045"`. The `uid` fields are strings already.
- On the **way in**, Jackson still coerces numeric strings to `Long`, so you may send a numeric id as
  either `42` or `"42"` in the request body — both deserialise. Sending the string form everywhere is
  the safe default.
- `PageMeta` carries no ids, so its counters serialise as plain JSON **numbers** (see §7).

---

## 6. Authentication (summary — full detail in the auth integration page)

Stateless JWT (`SessionCreationPolicy.STATELESS`, CSRF disabled, OAuth2 resource server). Obtain a
token, then send it as a bearer on every protected call.

- **Login** — `POST /api/v1/auth/login` (public) with body `LoginRequest{ username, password }`
  (both `@NotBlank`; username matched case-insensitively). Returns `TokenResponse`:

  ```java
  public record TokenResponse(String accessToken, long accessTokenExpiresAt,
                              String refreshToken, AuthUser user) {
    public record AuthUser(String uid, String username, String displayName, boolean isRoot,
                           String activeCompanyUid, String activeBranchUid, boolean hasBranch) {}
  }
  ```

  `accessTokenExpiresAt` is **epoch-seconds**. Access token TTL **15 min**; refresh token TTL **7 days**
  (`erp.jwt`). On unknown user / wrong password / disabled / locked: generic **401** (no enumeration);
  5 failed attempts → 15-min lockout.

- **Bearer usage** — send `Authorization: Bearer <accessToken>` on every protected call.
  `JwtRequestContextFilter` re-checks the user is still `ACTIVE` on **every** request → **401**
  `User account is no longer active.` if not.

- **Refresh** — `POST /api/v1/auth/refresh` body `RefreshRequest{ refreshToken }` → a new
  `TokenResponse`. Refresh tokens are **single-use / rotated**: replaying a consumed token is treated as
  theft and **revokes the whole user's token chain** (fail closed).

- **Logout** — `POST /api/v1/auth/logout` body `RefreshRequest{ refreshToken }` → **204 No Content**.

- **`GET /api/v1/auth/me`** (`isAuthenticated()`) → `MeResponse(uid, username, displayName, isRoot,
  activeCompanyUid, activeBranchUid, List<String> permissions)`. For root, `permissions` is empty
  (client keys off `isRoot`); otherwise it lists the effective permission codes for the active scope —
  use it to verify the cashier holds the `POS.*` codes before showing POS UI.

- **`GET /api/v1/auth/my-branches`** (`isAuthenticated()`, self-scoped) → `List<UserBranchDto>` of the
  caller's ACTIVE switchable branches.

- **Branch scoping header `X-Branch-Uid`** (optional) switches the **request** scope without re-login
  or DB write (ADR-0003). The JWT's minted branch is the default; the header branch is resolved by uid
  and, for non-root, must be an ACTIVE assignment of the caller else **403**
  (`You are not assigned to that branch.` / `Branch not available.`). Root may switch into any existing
  ACTIVE branch.

- **`X-Request-Id`** (optional) — correlation id echoed back in the response header (generated if
  absent). **It is purely a logging/correlation id and is NOT used for request deduplication** (see the
  idempotency note in the sale page).

> **Dev signing caveat.** The default signing mode is `dev-in-memory` (ephemeral RSA key that rotates on
> each backend restart → all tokens invalidated on restart). Production uses `signing-mode=file` with
> stable RS256 keys.

---

## 7. Pagination (Spring Data `Pageable`)

Paged endpoints bind a Spring Data `Pageable` directly from query params. In the POS set, the **session
list** is paged:

```
GET /api/v1/pos/sessions?companyId={id}&status=&page=&size=&sort=
```

(`status` is an optional `PosSessionStatus` filter — `OPEN` / `CLOSED` / `RECONCILED`. Both session
finders already order **newest-first**, so page 0 is the most recent shifts. §10.7.)

| Param | Meaning | Default |
|---|---|---|
| `page` | zero-based page index | `0` |
| `size` | page size | `20` |
| `sort` | `field,(asc|desc)`, e.g. `sort=createdAt,desc` | unsorted |

The response uses the envelope with `meta` populated by `PageMeta.from(page)`:

```java
public record PageMeta(int page, int size, int totalElements, int totalPages, boolean hasNext) {}
```

- `page` — zero-based current page (matches `Pageable.getPageNumber()`)
- `size` — page size
- `totalElements` — total matching records (`int`, serialises as a JSON number)
- `totalPages` — `ceil(totalElements / size)`
- `hasNext` — `true` when more pages follow

`PosSessionController.list` returns `ApiResponse.ok(page.getContent(), PageMeta.from(page))`, i.e.:

```json
{ "data": [ /* PosSessionDto[] */ ], "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 3, "totalPages": 1, "hasNext": false } }
```

> **Not every list is paged.** The **till list** `GET /api/v1/pos/tills` is **not** paged — it returns a
> plain `List<PosTillDto>` filtered by `companyId` + `branchId`, so its `meta` is `null`. Do not pass
> `page`/`size`/`sort` to it.

---

## 8. Value formats: currency, decimals, dates

These conventions hold across all POS DTOs.

### Currency

- Currency is a 3-letter code carried as a JSON **string** (e.g. `"TZS"`, `"USD"`). On the request side
  it is `PosSaleRequest.currency` (`@NotBlank`); on the response side `SalesInvoiceDto.currency` (the
  service maps the entity's `CurrencyCode.value()` to a string).
- The currency used in a sale **must be enabled for the session's company/branch scope** (ADR-0039). A
  currency not enabled for that scope yields **422 Unprocessable Entity** (`CurrencyNotEnabledException`).

### Decimals / money & quantity

- All monetary and quantity amounts are `java.math.BigDecimal` and serialise as JSON **numbers** with
  full precision (e.g. `1500.00`, `2.5`). Send them as numbers (a numeric string also coerces).
- Validation seen on POS request DTOs:
  - `PosSaleRequest.LineItem.quantity` — `@NotNull @DecimalMin("0.0001")`
  - `PosSaleRequest.LineItem.unitPrice` — **optional** `@DecimalMin("0.00")` (the `@NotNull` was relaxed
    in ADR-0042 D-4). Accepted but **ignored by the sale path** — the server re-derives the price from the
    price list, so this field never reaches pricing; safe to omit or send for receipt display only. See
    [04](./04-pricing-tax-currency.md) and [12](./12-known-limitations.md).
  - `PosSaleRequest.LineItem.lineDiscountAmount` — nullable `BigDecimal`
  - `OpenSessionRequest.openingFloatAmount` — `@NotNull @DecimalMin("0.00")`
  - `CloseSessionRequest.countedCashAmount` — `@NotNull`
  - `PosPayoutRequest.amount` — `@NotNull @DecimalMin("0.01")`
- Do not assume the server rounds for you; submit amounts at the currency's natural scale.

### Dates / timestamps

- Timestamp fields are returned as **ISO-8601 UTC strings**. In the DTOs they are typed as `String` and
  produced from a `java.time.Instant` via `.toString()` (the underlying entity columns are `Instant`),
  e.g. `SalesInvoiceDto.finalisedAt`, `createdAt`, `updatedAt`, and the session DTO's `openedAt` /
  `closedAt` / `reconciledAt`. Expect values like `2026-06-19T10:15:30.123456Z`. They are `null` when
  not yet set (e.g. `closedAt` on an OPEN session).
- POS request DTOs contain **no** client-supplied date/time fields — timestamps are all server-stamped.

### Enums

Enums serialise as their **name string**. The ones a POS client will encounter:

- `SalesInvoiceDto.status` → `InvoiceStatus { DRAFT, FINALISED, VOID }` (a finalised POS sale comes back
  `FINALISED`).
- `SalesInvoiceDto.documentType` → `DocumentType { INVOICE }` (v1 admits only `INVOICE`).
- `PosSessionDto.status` → `PosSessionStatus { OPEN, CLOSED, RECONCILED }`.
- `PosTillDto.status` → `MasterStatus` (e.g. `ACTIVE` / `INACTIVE`).
- `PosPayoutRequest.payoutType` → `PosPayoutType { REFUND, PAID_OUT }` (both subtract from expected
  cash). A bad enum value yields **400** with the offending value scrubbed of internal class names.

---

## 9. POS permissions (what each endpoint requires)

Method security is enforced by `@PreAuthorize` on each controller method (in addition to the
`authenticated()` filter). The permission codes (seeded in `V43__pos.sql`):

| Permission | Gates |
|---|---|
| `POS.SALE.CREATE` | `POST /api/v1/pos/sales` (`@perm.has`) |
| `POS.SALE.VOID` | `POST /pos/sales/uid/{uid}/reverse` — whole-invoice POS reversal (`@perm.scoped` on the invoice uid; seeded in `V70`, ADR-0042 D-2) |
| `POS.SESSION.OPEN` | `POST /api/v1/pos/sessions` (open, `@perm.has`) **and** `POST /pos/sessions/uid/{uid}/payouts` (`@perm.scoped`) |
| `POS.SESSION.CLOSE` | `POST /pos/sessions/uid/{uid}/close` (`@perm.scoped`) |
| `POS.SESSION.VIEW` | `GET /pos/sessions/uid/{uid}`, `GET /pos/sessions` (list), `GET /pos/sessions/uid/{uid}/x-read` |
| `POS.SESSION.RECONCILE` | `POST /pos/sessions/uid/{uid}/reconcile` (Z-read; posts variance to GL) |
| `POS.TILL.MANAGE` | `POST /api/v1/pos/tills` (create) **and** `DELETE /pos/tills/uid/{uid}` (deactivate) |
| `POS.TILL.VIEW` | `GET /pos/tills/uid/{uid}` and `GET /pos/tills` (list by branch) |

A method-security denial returns a **403** with the generic message
`You do not have permission to perform this action.` — it never names the missing permission. Use
`GET /api/v1/auth/me` to discover which codes the cashier actually holds.

---

## 10. Endpoint reference (POS)

> Examples assume `$BASE=https://erp.example.com` and `$TOKEN=<accessToken from login>`. Ids in bodies
> are shown as numeric strings (the safe form). Response bodies show the **envelope**.

### 10.1 `POST /api/v1/pos/tills` — create a till

- **Permission:** `POS.TILL.MANAGE`
- **Body:** `CreatePosTillRequest`
  - `companyUid` (`@NotBlank` String)
  - `branchId` (`@NotNull` Long)
  - `name` (`@NotBlank @Size(max=60)` String)
  - `cashBankAccountUid` (nullable String — defaults to the company's default active cash/bank account)
- **Success:** **201**, `data` = `PosTillDto`.

```bash
curl -sS -X POST "$BASE/api/v1/pos/tills" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"companyUid":"cmp_7f3a","branchId":"3001","name":"Front Register 1"}'
```

```json
{ "data": { "id": "5001", "uid": "till_a91c", "companyId": "100", "branchId": "3001",
            "code": "TILL-0001", "name": "Front Register 1",
            "cashBankAccountId": "8800", "status": "ACTIVE",
            "hasOpenSession": false, "openSessionUid": null,
            "openSessionCashierId": null, "openSessionCashierName": null,
            "openSessionOpenedAt": null },
  "errors": [], "meta": null }
```

- **Notable errors:** 400 (validation, e.g. blank `name` → `name: must not be blank`); 401; 403
  (lacks `POS.TILL.MANAGE`); 404 (unknown `companyUid` / `cashBankAccountUid`); 415 (wrong content type).

### 10.2 `GET /api/v1/pos/tills?companyId=&branchId=` — list tills (NOT paged)

- **Permission:** `POS.TILL.VIEW`
- **Query params:** `companyId` (`@RequestParam Long`, required), `branchId` (`@RequestParam Long`,
  required).
- **Success:** **200**, `data` = `List<PosTillDto>`, `meta` = `null`.

```bash
curl -sS "$BASE/api/v1/pos/tills?companyId=100&branchId=3001" \
  -H "Authorization: Bearer $TOKEN"
```

> **Each row reports its occupancy.** `hasOpenSession` plus `openSessionUid` /
> `openSessionCashierId` / `openSessionCashierName` / `openSessionOpenedAt` say whether a till is
> busy **and by whom**. `hasOpenSession` is till-keyed with no comparison against the caller, so a
> client must compare `openSessionCashierId` with its own user id (the access token's `sub`) to tell
> its own abandoned shift from a colleague's live one — the name is display copy only. See
> [07 — Occupancy](./07-tills.md#occupancy--reading-hasopensession-and-the-opensession-fields).

- **Notable errors:** 400 (missing/uncoercible `companyId`/`branchId`); 401; 403.

### 10.3 `GET /api/v1/pos/tills/uid/{uid}` — get one till

- **Permission:** `POS.TILL.VIEW` (scoped to the till uid)
- **Success:** **200**, `data` = `PosTillDto`.
- **Notable errors:** 401; 403; 404 (unknown till uid).

### 10.4 `DELETE /api/v1/pos/tills/uid/{uid}` — deactivate a till

- **Permission:** `POS.TILL.MANAGE` (scoped)
- **Success:** **200** with an empty/`void` body (deactivates the till).
- **Notable errors:** 401; 403; 404.

### 10.5 `POST /api/v1/pos/sessions` — open a session

- **Permission:** `POS.SESSION.OPEN`
- **Body:** `OpenSessionRequest`
  - `tillUid` (`@NotBlank` String)
  - `openingFloatAmount` (`@NotNull @DecimalMin("0.00")` BigDecimal)
- **Success:** **201**, `data` = `PosSessionDto`.

```bash
curl -sS -X POST "$BASE/api/v1/pos/sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tillUid":"till_a91c","openingFloatAmount":200000.00}'
```

```json
{ "data": { "id": "9001", "uid": "sess_4d2e", "companyId": "100", "branchId": "3001",
            "posTillId": "5001", "cashierId": "7201", "sessionNumber": "POS-0001",
            "status": "OPEN", "openedAt": "2026-06-19T08:02:11.044Z",
            "closedAt": null, "reconciledAt": null,
            "openingFloatAmount": 200000.00, "countedCashAmount": null,
            "expectedCashAmount": null, "varianceAmount": null,
            "varianceJournalId": null, "notes": null },
  "errors": [], "meta": null }
```

- **Notable errors:** 400 (validation); 401; 403 (lacks `POS.SESSION.OPEN`); 404 (unknown `tillUid`);
  409 (domain rule, e.g. a session already open on the till); 415.

### 10.6 `GET /api/v1/pos/sessions/uid/{uid}` — get one session

- **Permission:** `POS.SESSION.VIEW` (scoped)
- **Success:** **200**, `data` = `PosSessionDto`.
- **Notable errors:** 401; 403; 404 (`NotFoundException.of("PosSession", uid)`).

### 10.7 `GET /api/v1/pos/sessions?companyId=&status=&page=&size=&sort=` — list sessions (PAGED)

- **Permission:** `POS.SESSION.VIEW`
- **Query params:** `companyId` (`@RequestParam Long`, required), `status`
  (`PosSessionStatus`, **optional** — `OPEN` / `CLOSED` / `RECONCILED`) + the `Pageable` params (§7).
- **Ordering:** both finders order **newest-first** (`openedAt DESC, id DESC`) in the query itself.
- **Success:** **200**, `data` = `List<PosSessionDto>`, `meta` = `PageMeta`.

```bash
# recovering a cashier's still-open shift — ALWAYS filter server-side
curl -sS "$BASE/api/v1/pos/sessions?companyId=100&status=OPEN&size=50" \
  -H "Authorization: Bearer $TOKEN"
```

> **Use `status=OPEN` to find an open shift.** Fetching page 0 unfiltered and filtering in client
> code silently stops working once the company has more lifetime sessions than fit on the page —
> the open shift is not on the page to be found, and the cashier is locked out of a till only they
> can close. See [08 §5.1](./08-sessions.md).

- **Notable errors:** 400 (missing/uncoercible `companyId`, or an invalid `status`); 401; 403.

### 10.8 `POST /api/v1/pos/sessions/uid/{uid}/payouts` — record a payout

- **Permission:** `POS.SESSION.OPEN` (scoped to the session uid)
- **Body:** `PosPayoutRequest`
  - `payoutType` (`@NotNull` `PosPayoutType` = `REFUND` | `PAID_OUT`)
  - `amount` (`@NotNull @DecimalMin("0.01")` BigDecimal)
  - `reason` (`@Size(max=255)` String, nullable)
- **Success:** **200** with an empty/`void` body.

```bash
curl -sS -X POST "$BASE/api/v1/pos/sessions/uid/sess_4d2e/payouts" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"payoutType":"PAID_OUT","amount":50000.00,"reason":"Drawer-to-safe drop"}'
```

- **Notable errors:** 400 (validation / bad enum); 401; 403; 404 (unknown session uid); 409 (session not
  OPEN → `Session <sessionNumber> is not OPEN.`); 415.

### 10.9 `POST /api/v1/pos/sessions/uid/{uid}/close` — close a session

- **Permission:** `POS.SESSION.CLOSE` (scoped)
- **Body:** `CloseSessionRequest`
  - `countedCashAmount` (`@NotNull` BigDecimal — cashier's declared count)
  - `notes` (String, nullable)
- **Success:** **200**, `data` = `PosSessionDto` (now `status: "CLOSED"`, `closedAt` /
  `countedCashAmount` / `expectedCashAmount` / `varianceAmount` populated).

```bash
curl -sS -X POST "$BASE/api/v1/pos/sessions/uid/sess_4d2e/close" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"countedCashAmount":1480000.00,"notes":"End of shift"}'
```

- **Notable errors:** 400; 401; 403; 404; 409 (session not OPEN); 415.

> **This call is the ONLY thing that ends a shift.** There is no idle timeout, no sweeper and no
> logout hook that closes a session — by design, since the close records a **counted** cash figure
> the system must never invent. So a killed app leaves its session `OPEN` and its till held; the
> client's job is to offer recovery (`GET /pos/sessions?companyId=…&status=OPEN`, §10.7), and never
> to pre-fill `countedCashAmount`.

### 10.10 `GET /api/v1/pos/sessions/uid/{uid}/x-read` — mid-session X-read

- **Permission:** `POS.SESSION.VIEW` (scoped)
- **Success:** **200**, `data` = `XReadDto`
  (`sessionUid, posTillId, cashierId, openedAt, openingFloatAmount, totalSalesAmount,
  cashTenderAmount, totalPayoutsNetAmount, expectedCashAmount, invoiceCount, tenderSubtotals`).
  Does **not** close the session. Note `totalSalesAmount` is gross turnover across every tender
  type; the drawer figure is `cashTenderAmount` ([08 §7](./08-sessions.md)).
- **Notable errors:** 401; 403; 404.

### 10.11 `POST /api/v1/pos/sessions/uid/{uid}/reconcile` — Z-read / reconcile

- **Permission:** `POS.SESSION.RECONCILE` (scoped)
- **Body:** `ReconcileSessionRequest` — `{ "notes": "..." }` (notes only; the variance is computed
  server-side, so the body may even be `{}`).
- **Success:** **200**, `data` = `ZReadDto`
  (`sessionUid, posTillId, cashierId, openedAt, closedAt, reconciledAt, openingFloatAmount,
  totalSalesAmount, totalPayoutsNetAmount, expectedCashAmount, countedCashAmount, varianceAmount,
  varianceJournalId, invoiceCount`). Posts the variance journal to the GL.
- **Notable errors:** 401; 403; 404; 409 (session not in a reconcilable state).

### 10.12 `POST /api/v1/pos/sales` — ring a sale

- **Permission:** `POS.SALE.CREATE`
- **Optional header:** `Idempotency-Key` (≤80 chars) — see the idempotency note below and
  [11](./11-errors-offline-idempotency.md).
- **Body:** `PosSaleRequest`
  - `sessionUid` (`@NotBlank` String) — must reference an **OPEN** session
  - `customerId` (`@NotNull` Long)
  - `agentId` (optional Long — the `@NotNull` was relaxed in ADR-0042 D-4; informational only, the sale is
    recorded against the logged-in cashier)
  - `currency` (`@NotBlank` String, 3-letter code)
  - `lines` (`@NotEmpty @Valid List<LineItem>`), each `LineItem`:
    - `productId` (`@NotNull` Long), `unitId` (`@NotNull` Long)
    - `quantity` (`@NotNull @DecimalMin("0.0001")` BigDecimal)
    - `unitPrice` (optional `@DecimalMin("0.00")` BigDecimal — `@NotNull` relaxed in ADR-0042 D-4; accepted
      but **ignored**, the server re-derives the price from the price list, see
      [12](./12-known-limitations.md))
    - `lineDiscountAmount` (nullable BigDecimal)
  - `tenders` (nullable `@Valid List<PosTender>` — optional **split / non-cash** payment list, ADR-0042
    D-3; each `PosTender` carries `tenderType` (`CASH` / `CARD` / `MOBILE_MONEY` / `CHEQUE`) + `amount` +
    instrument refs, and their sum must cover the gross total. **Omit** ⇒ a single exact CASH payment
    (legacy). See [09](./09-sales-payments-receipts.md).)
  - `tenderedAmount` (nullable BigDecimal — **receipt-only, not stored on the invoice**)
  - `notes` (`@Size(max=500)` String, nullable)
- **Success:** **201**, `data` = `SalesInvoiceDto` (the finalised invoice, ready for receipt printing).
  Replaying the same `Idempotency-Key` returns the **original** invoice (still **201**), no double post.

> **No branch in the body.** Scope comes from the JWT (or `X-Branch-Uid`) and the resolved session's
> company; `ScopeGuard.assertCanActIn(...)` enforces the caller can act in the session's company.

```bash
curl -sS -X POST "$BASE/api/v1/pos/sales" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "sessionUid": "sess_4d2e",
        "customerId": "400123",
        "agentId": "7201",
        "currency": "TZS",
        "lines": [
          { "productId": "5500", "unitId": "12", "quantity": 2,
            "unitPrice": 15000.00, "lineDiscountAmount": 0 }
        ],
        "tenderedAmount": 30000.00,
        "notes": "Walk-in"
      }'
```

```json
{ "data": { "id": "100045", "uid": "inv_8b1f", "companyId": "100", "branchId": "3001",
            "documentType": "INVOICE", "invoiceNumber": "INV-0042",
            "status": "FINALISED", "customerId": "400123", "customerName": "Walk-in Customer",
            "agentId": "7201", "agentName": "Jane Cashier", "currency": "TZS",
            "netTotalAmount": 30000.00, "vatTotalAmount": 5400.00, "grossTotalAmount": 35400.00,
            "finalisedAt": "2026-06-19T08:31:55.210Z", "finalisedBy": "7201",
            "version": "0", "createdAt": "2026-06-19T08:31:55.001Z" },
  "errors": [], "meta": null }
```

- **Notable errors:**
  - **400** — bean-validation (e.g. empty `lines` → `lines: must not be empty`; bad `quantity`); malformed
    JSON / bad enum.
  - **401** / **403** — auth / lacks `POS.SALE.CREATE` / acting outside active scope.
  - **404** — unknown `sessionUid` (`NotFoundException.of("PosSession", uid)`), `customerId`, `productId`,
    or `unitId`.
  - **409** — session **not OPEN** (`This POS session is not OPEN.`); **not enough stock** for a line
    (`Not enough stock of <product> …` — blocked by default, see [06](./06-stock-availability.md));
    finalising with no lines; cash/tenders not covering the gross; credit-limit exceeded without
    `SALES.CREDIT.OVERRIDE`; the original attempt under the **same `Idempotency-Key`** is still
    committing (`This sale is still being processed. Please try again in a moment.` — **retryable and
    NOT terminal**: keep the key and resend it); optimistic-lock conflict (`This record was modified by
    another transaction. Please reload and try again.` — retryable).
  - **415** — wrong content type.
  - **422** — `currency` not enabled for the session's company/branch scope.

> **Two critical behaviours for sale create** (each detailed on its own page):
>
> 1. **Idempotency is opt-in server-side, and DURABLE on the client (ADR-0042 D-1, commit `f08fb08`).**
>    Send an optional **`Idempotency-Key`** request header (≤80 chars, per-company scope) and a retry after
>    a timeout is safe: the key is reserved before processing, so replaying it returns the **original**
>    invoice (still **201**, matched by uid) with no double post; a request that arrives while the original
>    is still committing gets a **409** (`This sale is still being processed…`) which is **retryable and not
>    terminal** — keep the key and resend it. The client's half of the contract is not optional: **persist
>    the key (and body) to device storage before the POST, clear it only on a confirmed terminal outcome,
>    and reconcile an unresolved key on relaunch instead of re-ringing** — a key held only in memory is lost
>    by the app kill it exists to protect against. **Omitting** the header keeps the legacy non-idempotent
>    path, so a blind retry there still duplicates the invoice (stock + GL/AR). `X-Request-Id` is
>    correlation only and is **not** used for dedup. See [11](./11-errors-offline-idempotency.md) §4.1a.
> 2. **Posting is only partially synchronous.** The 201 returns a **FINALISED, fully-paid** invoice, but
>    the stock issue, GL journal, and AR posting are applied **asynchronously** by an outbox poller
>    (~1s). Do **not** assume the ledger/stock is posted at response time.

### 10.13 `POST /api/v1/pos/sales/uid/{uid}/reverse` — reverse (void/refund) a POS sale

- **Permission:** `POS.SALE.VOID` (scoped to the **invoice** uid; ADR-0042 D-2, commit `f08fb08`)
- **Body:** `{ "reason": "..." }`
- **Success:** **204 No Content**. Performs a **whole-invoice reversal** — reverses revenue + VAT + cash,
  reverses the stock issue and inventory valuation (DR Inventory / CR COGS), and the reversed sale drops out
  of the session's expected cash at X/Z-read.
- **Preconditions:** the invoice must be POS-origin, **FINALISED**, and its originating session still
  **OPEN**; otherwise **409** (use the back-office invoice void instead).
- **Notable errors:** 401; 403 (lacks `POS.SALE.VOID`); 404 (unknown invoice uid); 409 (precondition not met).
- **Scope:** whole-invoice only — **partial / line-level POS refunds are deferred** (ADR-0042). See
  [10](./10-returns-refunds.md).

```bash
curl -sS -X POST "$BASE/api/v1/pos/sales/uid/inv_8b1f/reverse" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Customer changed mind"}'
```

---

## 11. Error model (status → cause)

All errors come back in the envelope (`data: null`, populated `errors[]`). Summary of what produces each
status across the POS path:

| HTTP | Typical cause |
|---|---|
| **400 Bad Request** | Bean-validation failures (`field: message` per field); missing/uncoercible query param or path-var type mismatch; malformed JSON or bad enum value (FQCN scrubbed); `IllegalArgumentException`; DB check/not-null/truncation/overflow (SQLSTATE 23514/23502/22001/22003). |
| **401 Unauthorized** | Bad credentials (generic, no enumeration); missing/invalid/expired bearer token; user no longer ACTIVE (`User account is no longer active.`). |
| **403 Forbidden** | `ForbiddenException` (lacks permission / acting outside active scope); `@PreAuthorize` denial (`You do not have permission to perform this action.`); rejected `X-Branch-Uid`. |
| **404 Not Found** | Entity addressed by uid/id does not exist (`NotFoundException`) — e.g. unknown `sessionUid`, `customerId`, `productId`, `unitId`, till/invoice uid. |
| **409 Conflict** | Domain-rule violation (`ConflictException`) incl. session not OPEN; business-state conflict (`IllegalStateException`); optimistic-lock (retryable); DB unique (23505) / FK (23503). |
| **415 Unsupported Media Type** | Wrong `Content-Type` → `Content-Type not supported. Use application/json.` |
| **422 Unprocessable Entity** | Currency not enabled for the company/branch scope (`CurrencyNotEnabledException`). |
| **500 Internal Server Error** | Uncaught exception → generic `An unexpected error occurred.` (text never echoed; logged with MDC). |

**Client handling:** decide on the HTTP status; show the strings in `errors[]` to the user (already
user-safe); treat **409 optimistic-lock** as retryable (reload + retry); never parse error strings to
branch program logic — they are display text and may change.

---

## 12. Machine-readable spec (OpenAPI / Swagger)

springdoc/OpenAPI is exposed (ADR-0038 D-9), gated by `ERP_SWAGGER_ENABLED` (default `true`):

- **OpenAPI JSON:** `GET /v3/api-docs` (and `/v3/api-docs/**`)
- **Swagger UI:** `/swagger-ui/index.html` (and `/swagger-ui/**`, `/swagger-ui.html`)

`SecurityConfig` permits these **publicly**, so you can pull the spec without a token:

```bash
curl -sS "$BASE/v3/api-docs" -o erp-openapi.json
```

Use it to generate a typed client. **Note:** in production this surface is typically removed
(`ERP_SWAGGER_ENABLED=false`); pull the spec from a non-production environment for codegen, and remember
the envelope-wrapping (§3) and `Long`-as-string (§5) conventions are **applied by advice/Jackson at
runtime** — the OpenAPI schema may show the raw controller return type (`SalesInvoiceDto`) rather than
`ApiResponse<SalesInvoiceDto>`, and `Long` ids as integers rather than strings. Treat this document as
the source of truth for those two conventions.

---

## 13. Quick checklist for a POS client

1. `POST /api/v1/auth/login` → store `accessToken` + `refreshToken`; refresh before `accessTokenExpiresAt`.
2. Send `Authorization: Bearer <accessToken>` and `Content-Type: application/json` on every call.
3. (Optional) set `X-Branch-Uid` to scope to a specific branch; set `X-Request-Id` for traceability.
4. Confirm POS permissions via `GET /api/v1/auth/me`.
5. Ensure a till exists (`/pos/tills`), open a session (`/pos/sessions`), then ring sales (`/pos/sales`).
6. Read `data` for payloads; treat non-empty `errors[]` (with the HTTP status) as failure; retry on
   409 optimistic-lock or the `Idempotency-Key`-in-progress 409 (resend the same key).
7. Send an **`Idempotency-Key`** header on `POST /pos/sales` so retries are safe (without it a blind retry
   duplicates); **do not** assume GL/stock is posted when the 201 returns. To undo a sale on an open session,
   `POST /pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`).
8. X-read any time; close + reconcile at end of shift.

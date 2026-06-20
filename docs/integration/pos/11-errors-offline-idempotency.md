# Errors, Offline & Idempotency

Audience: a developer building an **external POS client** (desktop / mobile / kiosk) against this
ERP's REST API. This page is the authoritative reference for **how errors are shaped**, **which HTTP
status each failure produces**, and — critically — **what the server does and does NOT do about
duplicate / retried sale requests**, with concrete client-side guidance for safe retries and offline
operation.

Everything below is grounded in the actual backend:

| Concern | Source of truth |
| --- | --- |
| Response envelope | `com.erp.platform.common.api.ApiResponse` |
| Auto-wrapping | `com.erp.platform.common.api.ApiResponseAdvice` |
| Status mapping | `com.erp.platform.common.api.GlobalExceptionHandler` |
| Sale path | `com.erp.api.PosSaleController` → `com.erp.modules.sales.service.PosSaleServiceImpl` |
| Session path | `com.erp.api.PosSessionController` |
| Till path | `com.erp.api.PosTillController` |
| Sale DTO | `com.erp.modules.sales.domain.dto.PosSaleRequest` |
| Idempotency | **`Idempotency-Key` header** → table `pos_sale_idempotency` (Flyway `V70`), `com.erp.modules.sales.repository.PosSaleIdempotencyRepository` (see [§4](#4-idempotency-provided)). Shipped in commit `f08fb08` / ADR-0042. |

All endpoints are versioned under the fixed prefix `/api/v1` (path-based versioning only — no header
or media-type negotiation). The POS base paths are `/api/v1/pos/sales`, `/api/v1/pos/sessions`,
and `/api/v1/pos/tills`.

---

## 1. The error envelope

Every controller under `com.erp.api` returns its raw payload (`T`); `ApiResponseAdvice`
(`@RestControllerAdvice`) wraps it into the single envelope `ApiResponse<T>`:

```java
public record ApiResponse<T>(T data, List<String> errors, Object meta) { }
```

| Field | On success | On error |
| --- | --- | --- |
| `data` | the payload `<T>` | `null` |
| `errors` | `[]` (empty list) | `["user-facing message", ...]` |
| `meta` | `null`, or a `PageMeta` on paged endpoints | `null` |

**Success** (e.g. a POS sale → HTTP `201`):

```json
{ "data": { "...SalesInvoiceDto...": "..." }, "errors": [], "meta": null }
```

**Error** (any non-2xx):

```json
{ "data": null, "errors": ["POS session 9f3c-... is not OPEN."], "meta": null }
```

### Rules a client MUST rely on

1. **`errors[]` is user-safe only.** Per PROJECT-CONVENTIONS §3.1, error strings never contain
   internal exception text, stack traces, SQL, constraint names, or column names. You may display
   them to a cashier, but **do not parse them** to drive control flow — branch on the **HTTP status
   code**, not the message text. Messages may change wording between releases.
2. **`errors` may carry more than one string.** Bean-validation failures return one entry per
   invalid field (`"field: message"` — see [§3.1](#31-400-bad-request--validation)). Render them as
   a list.
3. **Validation/exception failures are NOT wrapped twice.** The `GlobalExceptionHandler` builds the
   envelope itself and sets the HTTP status, so an error response is always the shape above.
4. **Binary/string bodies are not wrapped.** `String`, `byte[]`, and `Resource` bodies bypass the
   advice. None of the POS JSON endpoints return these, so for POS you will always get the envelope.

> **Note on success envelopes for list endpoints.** `GET /api/v1/pos/sessions` already returns
> `ApiResponse.ok(content, PageMeta.from(page))` from the controller, so its `meta` is populated.
> `GET /api/v1/pos/tills` is **not paged** — it returns a plain `List<PosTillDto>` (the advice wraps
> it, `meta` is `null`).

---

## 2. The HTTP status table

These are the statuses an external POS client can actually receive, mapped from
`GlobalExceptionHandler` and the security layer. **Branch on these codes.**

| Status | Meaning for the POS client | Triggered by (real backend cause) |
| --- | --- | --- |
| `400 Bad Request` | The request is malformed or fails validation — **fix and resend**. | Bean-validation on a request DTO (`MethodArgumentNotValidException`); missing/uncoercible query param or path var (`MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`); malformed JSON or bad enum value (`HttpMessageNotReadableException` — FQCN scrubbed, e.g. `"Invalid value 'XXXX' for field 'payoutType' (PosPayoutType)."`); `IllegalArgumentException` from the sale service (e.g. product not sellable, no price list, agent rules); DB-level `CHECK`/`NOT NULL`/truncation/numeric-overflow (`23514`/`23502`/`22001`/`22003`). |
| `401 Unauthorized` | Not authenticated — **(re)login or refresh the token**, then resend. | Bad credentials at `/auth/login`; missing/invalid/expired bearer token; user no longer `ACTIVE` (re-checked on **every** request by `JwtRequestContextFilter` → `"User account is no longer active."`). |
| `403 Forbidden` | Authenticated but **not allowed** — do **not** retry as-is. | Missing POS permission via `@PreAuthorize` → generic `"You do not have permission to perform this action."` (never names the permission); `ForbiddenException` from `ScopeGuard.assertCanActIn` (acting outside the session's company); a rejected `X-Branch-Uid` override (`"You are not assigned to that branch."` / `"Branch not available."`). |
| `404 Not Found` | A referenced entity does not exist — **fix the id/uid**, do not blind-retry. | `NotFoundException`. In the sale path: unknown `sessionUid` (`NotFoundException.of("PosSession", uid)`), unknown `customerId`, company, `productId`, `unitId`, or invoice uid. |
| `409 Conflict` | A business-state or concurrency conflict. **Sub-cases differ — see [§2.1](#21-the-409-family-the-one-that-matters-for-pos).** | `ConflictException`, `IllegalStateException`, optimistic-lock failures, and DB unique/FK violations (`23505`/`23503`). |
| `415 Unsupported Media Type` | Wrong `Content-Type`. | `HttpMediaTypeNotSupportedException` → `"Content-Type not supported. Use application/json."` **POS clients MUST send `Content-Type: application/json`.** |
| `422 Unprocessable Entity` | Currency not enabled for the company/branch scope. | `CurrencyNotEnabledException` (ADR-0039). Relevant if a POS sale's `currency` is not enabled for the session's company. |
| `500 Internal Server Error` | Unexpected server fault — **safe to retry with the SAME `Idempotency-Key`** (see [§4](#4-idempotency-provided)). | Uncaught `NullPointerException` or any other `Exception` → generic `"An unexpected error occurred."` The exception text is never echoed; the full stack is logged server-side with MDC (`requestId`, user, company, branch). |

### 2.1 The 409 family (the one that matters for POS)

A `409` is **not** a single thing. The POS client must distinguish two fundamentally different
sub-cases, because one is **terminal** and the other is **retryable**:

| 409 sub-case | Example `errors[0]` | Retryable? | What the client should do |
| --- | --- | --- | --- |
| **Domain-rule violation** (`ConflictException`) | `"POS session <uid> is not OPEN."` | **No** | The session must be re-opened (or a new one opened). Do not auto-retry the sale; surface to the cashier. |
| **Business-state conflict** (`IllegalStateException`) | `"Cannot finalise an invoice with no lines."`, `"Tenders under-cover the gross total..."`, `"Credit limit exceeded for customer <uid>. ... Requires SALES.CREDIT.OVERRIDE permission."` | **No** | The request itself is wrong (empty cart, under-tender, over-limit). Fix the request / obtain override; do not blind-retry. |
| **Optimistic-lock conflict** (`OptimisticLockingFailureException` / `StaleObjectStateException` / jakarta `OptimisticLockException`) | `"This record was modified by another transaction. Please reload and try again."` | **Yes (transient)** | Reload the affected resource and retry. Expected under contention (e.g. concurrent stock-on-hand). |
| **Idempotency-key in progress** (sale path only) | `"A POS sale with this Idempotency-Key is still in progress; retry shortly."` | **Yes (transient)** | A concurrent in-flight duplicate caught the original sale after it reserved the key but before it stamped the invoice uid. Resend the **same** `Idempotency-Key` after a short delay; you will receive the winner's original invoice. See [§4](#4-idempotency-provided). |
| **DB unique violation** (`23505`) | `"A record with the same unique identifier already exists."` | No | Conflicting state already exists. |
| **DB FK violation** (`23503`) | `"The referenced record does not exist or has been removed."` | No | A referenced row is missing/removed. |

> **Why this distinction is load-bearing:** the credit-limit and empty-cart checks are
> `IllegalStateException`, which `GlobalExceptionHandler.handleIllegalState` maps to **409**, not
> 400. So a POS client that treats *all* 409s as "transient, retry" will hammer the server on a
> genuinely rejected sale. **Match on the message family above, or simpler: only auto-retry a 409
> whose message is exactly the optimistic-lock string or the idempotency-key "still in progress"
> string** — and, for the latter, only ever resend the *same* `Idempotency-Key`.

---

## 3. Per-endpoint error contract

Each section gives method + path, required permission, params, the real request JSON, a success
example in the envelope, and the notable errors for that endpoint. Send
`Authorization: Bearer <accessToken>` and `Content-Type: application/json` on every call.

### 3.1 `POST /api/v1/pos/sales` — ring a sale

* **Permission:** `POS.SALE.CREATE` (`@PreAuthorize("@perm.has('POS.SALE.CREATE')")`).
* **Success status:** `201 Created`. Body is a `SalesInvoiceDto` (finalised invoice, for receipt
  printing).
* **Request header (optional but strongly recommended):** `Idempotency-Key` — an opaque string,
  `<=80` chars, **per company**. Sending it makes a retry of the *same* logical sale return the
  **original** invoice instead of double-posting (ADR-0042 / commit `f08fb08`). Omitting it falls
  back to the legacy non-idempotent behaviour where a blind retry creates a duplicate sale. See
  [§4](#4-idempotency-provided) for the full contract.
* **Request body** (`PosSaleRequest`):

| Field | Type | Constraint |
| --- | --- | --- |
| `sessionUid` | `String` | `@NotBlank` |
| `customerId` | `Long` | `@NotNull` |
| `agentId` | `Long` | optional (`@NotNull` relaxed, ADR-0042 D-4) — informational; the sale is recorded against the logged-in cashier |
| `currency` | `String` | `@NotBlank` |
| `lines` | `List<LineItem>` | `@NotEmpty @Valid` |
| `tenders` | `List<PosTender>` | optional `@Valid` — split / non-cash tenders (ADR-0042 D-3); omit for single exact-CASH. See the [sales & payments page](09-sales-payments-receipts.md). |
| `tenderedAmount` | `BigDecimal` | optional — receipt-only, **not stored** on the invoice |
| `notes` | `String` | `@Size(max = 500)` |

`LineItem`: `productId` (`Long`, `@NotNull`), `unitId` (`Long`, `@NotNull`),
`quantity` (`BigDecimal`, `@NotNull @DecimalMin("0.0001")`),
`unitPrice` (`BigDecimal`, **optional and IGNORED** — `@NotNull` was relaxed (ADR-0042 D-4); pricing
is **server-authoritative**, re-derived from the product's company-scoped price list (`ProductPrice`)
and VAT from the company's `tax_rates`. The submitted value has **no effect** — a client cannot set
price `0`. You may still send it for your own receipt display, but treat the **server** totals in the
returned `SalesInvoiceDto` as authoritative), `lineDiscountAmount` (`BigDecimal`, optional — the
supported way to express a negotiated reduction).

`PosTender` (each entry, when `tenders` is supplied): `tenderType` (`TenderType` —
`CASH`/`CARD`/`MOBILE_MONEY`/`CHEQUE`, `@NotNull`), `amount` (`BigDecimal`,
`@NotNull @DecimalMin("0.0001")`), `reference` (`String`, optional), plus the instrument refs
`cashBankAccountId`, `chequeId`, `mobileMoneyRef`, `cardRef` (ADR-0041). The tender sum must
`>=` the gross total (validated).

> **No branch in the body.** Scope comes from the JWT (or `X-Branch-Uid`) and the resolved session's
> company; `ScopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId())` enforces it.

* **curl:**

```bash
curl -i -X POST https://erp.example.com/api/v1/pos/sales \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $LOCAL_SALE_ID" \
  -H "X-Request-Id: $(uuidgen)" \
  -d '{
        "sessionUid": "9f3c1d2e-1111-2222-3333-444455556666",
        "customerId": 1042,
        "agentId": 77,
        "currency": "TZS",
        "tenderedAmount": 50000.00,
        "notes": "walk-in",
        "lines": [
          { "productId": 501, "unitId": 9, "quantity": 2, "unitPrice": 12500.00, "lineDiscountAmount": 0 }
        ]
      }'
```

* **Success (201):**

```json
{
  "data": {
    "id": "900123",
    "uid": "a1b2c3d4-...",
    "companyId": "10",
    "branchId": "20",
    "documentType": "INVOICE",
    "invoiceNumber": "INV-0042",
    "status": "FINALISED",
    "customerId": "1042",
    "customerName": "Walk-in Customer",
    "agentId": "77",
    "agentName": "Cashier One",
    "currency": "TZS",
    "netTotalAmount": 21186.44,
    "vatTotalAmount": 3813.56,
    "grossTotalAmount": 25000.00,
    "finalisedAt": "2026-06-19T10:15:30Z",
    "version": 2
  },
  "errors": [],
  "meta": null
}
```

* **Notable errors:**

| Status | Cause |
| --- | --- |
| `400` | Validation (`"sessionUid: must not be blank"`, `"lines: must not be empty"`, `"lines[0].quantity: must be greater than or equal to 0.0001"`); product not sellable / archived / no price list (`IllegalArgumentException`). |
| `401` | Missing/expired token, or user no longer active. |
| `403` | Caller lacks `POS.SALE.CREATE`, or cannot act in the session's company. |
| `404` | Unknown `sessionUid`, `customerId`, `productId`, or `unitId`. |
| `409` | `"POS session <uid> is not OPEN."`; `"Credit limit exceeded ... Requires SALES.CREDIT.OVERRIDE permission."`; tender under-cover; optimistic-lock (retryable); `"A POS sale with this Idempotency-Key is still in progress; retry shortly."` (retryable — resend the SAME key after a short delay). |
| `415` | Body sent without `application/json`. |
| `422` | `currency` not enabled for the session's company. |

> **Read [§4](#4-idempotency-provided) before you implement retry on this endpoint** — sending the
> same `Idempotency-Key` is now the primary safe-retry mechanism.

### 3.2 `POST /api/v1/pos/sessions` — open a session

* **Permission:** `POS.SESSION.OPEN`. **Success:** `201`, body `PosSessionDto`.
* **Request** (`OpenSessionRequest`): `tillUid` (`String`, `@NotBlank`),
  `openingFloatAmount` (`BigDecimal`, `@NotNull @DecimalMin("0.00")`).

```bash
curl -i -X POST https://erp.example.com/api/v1/pos/sessions \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{ "tillUid": "till-7a8b...", "openingFloatAmount": 100000.00 }'
```

* **Notable errors:** `400` validation; `403` missing permission; `404` unknown `tillUid`;
  `409` (e.g. the till already has an open session — `ConflictException`).

### 3.3 `POST /api/v1/pos/sessions/uid/{uid}/payouts` — record cash-in/out

* **Permission:** `POS.SESSION.OPEN` (scoped: `@perm.scoped(#uid,'possession','POS.SESSION.OPEN')`).
  **Success:** `200` with an **empty body** (controller returns `void`; the advice wraps to
  `{"data":null,"errors":[],"meta":null}`).
* **Path var:** `uid` — session uid.
* **Request** (`PosPayoutRequest`): `payoutType` (`PosPayoutType`, `@NotNull`),
  `amount` (`BigDecimal`, `@NotNull @DecimalMin("0.01")`), `reason` (`String`, `@Size(max = 255)`).
* **Notable errors:** `400` (`"amount: must be greater than or equal to 0.01"`, or bad
  `payoutType` enum → `"Invalid value 'XXX' for field 'payoutType' (PosPayoutType)."`); `403`/`404`;
  `409` if the session is not OPEN.

### 3.4 `POST /api/v1/pos/sessions/uid/{uid}/close` — close

* **Permission:** `POS.SESSION.CLOSE` (scoped). **Success:** `200`, body `PosSessionDto`.
* **Request** (`CloseSessionRequest`): `countedCashAmount` (`BigDecimal`, `@NotNull`),
  `notes` (`String`, optional).
* **Notable errors:** `400` if `countedCashAmount` is null; `403`/`404`; `409` if not OPEN.

### 3.5 `GET /api/v1/pos/sessions/uid/{uid}` and `.../x-read`

* **Permission:** `POS.SESSION.VIEW` (scoped). **Success:** `200`; body `PosSessionDto`
  (get-by-uid) or `XReadDto` (x-read). `404` on unknown uid; `403` on missing permission.

### 3.6 `POST /api/v1/pos/sessions/uid/{uid}/reconcile` — Z-read

* **Permission:** `POS.SESSION.RECONCILE` (scoped; posts variance to GL). **Success:** `200`, body
  `ZReadDto`. Request `ReconcileSessionRequest` (note: `@RequestBody` here is **not** `@Valid`).
  `409` if the session is not in a reconcilable state; `403`/`404` as usual.

### 3.7 `GET /api/v1/pos/sessions` — list (paged)

* **Permission:** `POS.SESSION.VIEW`. **Query params:** `companyId` (`Long`, **required** — omitting
  it → `400 "Missing required request parameter: companyId"`), plus Spring `Pageable`:
  `page` (default 0), `size` (default 20), `sort` (e.g. `sort=createdAt,desc`).
* **Success:** `200` with `meta` populated:

```json
{ "data": [ { "...PosSessionDto..." } ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 3, "totalPages": 1, "hasNext": false } }
```

### 3.8 `POST /api/v1/pos/tills` — create a till

* **Permission:** `POS.TILL.MANAGE`. **Success:** `201`, body `PosTillDto`.
* **Request** (`CreatePosTillRequest`): `companyUid` (`String`, `@NotBlank`),
  `branchId` (`Long`, `@NotNull`), `name` (`String`, `@NotBlank @Size(max = 60)`),
  `cashBankAccountUid` (`String`, optional — defaults to the company's default cash/bank account).
  *(Note: there is no `code` field in the request; `PosTillDto` exposes `code` on the response.)*
* **Notable errors:** `400` validation; `403` missing permission; `404` unknown `companyUid` or
  `cashBankAccountUid`; `409` duplicate till (DB unique → `23505`).

### 3.9 `GET /api/v1/pos/tills` and `.../uid/{uid}`, `DELETE .../uid/{uid}`

* **List** (`GET /api/v1/pos/tills`): permission `POS.TILL.VIEW`; **required** query params
  `companyId` and `branchId` (both `Long`); **not paged** — returns `List<PosTillDto>`, `meta` null.
* **Get by uid:** `POS.TILL.VIEW` (scoped); `404` on unknown uid.
* **Deactivate** (`DELETE .../uid/{uid}`): `POS.TILL.MANAGE` (scoped); `200` empty body.

---

## 4. Idempotency: provided

**The server provides idempotent sale creation** via an optional `Idempotency-Key` request header
(ADR-0042 / commit `f08fb08`). When you send it and reuse the *same* value on a retry of the *same*
logical sale, the server returns the **original** invoice — no duplicate invoice, stock issue, GL/AR
posting, payment, or `SALE_FINALISED` event. This is grounded in the actual backend, not assumed:

* `PosSaleController.processSale` declares
  `@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey` and passes it
  to `PosSaleService.processSale(idempotencyKey, request)`.
* Table `pos_sale_idempotency(company_id, idem_key, invoice_uid)` with `UNIQUE(company_id, idem_key)`
  (Flyway `V70`), accessed via `PosSaleIdempotencyRepository`.
* **Reserve-before-process:** a native `INSERT ... ON CONFLICT (company_id, idem_key) DO NOTHING`
  runs **inside the sale's single transaction BEFORE the invoice is created**. The winner stamps
  `invoice_uid`; a later send with the same key short-circuits to the stored invoice.

> **Verified by `PosSaleIdempotencyRepositoryIT`.**

### 4.1 The header contract — exactly how it behaves

| Aspect | Behaviour |
| --- | --- |
| **Header** | `Idempotency-Key` — opaque string, **`<=80` chars**, optional. |
| **Scope** | **Per company** (namespaced by the authenticated session's company). The key need only be unique within a company; the same value in another company is independent. |
| **Replay** (same key, original already committed) | Returns the **original finalised `SalesInvoiceDto`** — no second invoice / stock issue / GL/AR / payment / `SALE_FINALISED` event. **A replay still returns HTTP `201`** (the controller hardcodes `CREATED`), so identify a replay by **matching the returned invoice `uid`** to one you already hold — *not* by a 200-vs-201 distinction. |
| **Concurrent in-flight duplicate** | The duplicate `INSERT` **blocks until the winner commits**, then returns the winner's original invoice. In the narrow window where the marker row exists but `invoice_uid` is not yet stamped, the duplicate gets **`409 "A POS sale with this Idempotency-Key is still in progress; retry shortly."`** — this specific `409` is **retryable**: resend the *same* key after a short delay. |
| **Omitting the header** | Legacy **non-idempotent** behaviour — a blind retry creates a **duplicate** sale. The client **MUST** send the header to get the guarantee. |
| **Failed / rolled-back sale** | **Frees the key** — the next send with the same key creates the sale normally. |

> **The body is unchanged.** Idempotency is carried entirely by the header; `PosSaleRequest` itself
> gained no key field.

### 4.2 What this means for retries — the new primary mechanism

`POST /api/v1/pos/sales` is **partially asynchronous**. The transaction synchronously produces a
**FINALISED, fully-paid invoice** tagged to the session, plus a queued `SALE_FINALISED`
transactional-outbox row. The **stock issue, GL journal, and AR posting are applied asynchronously**
by the outbox poller (within ~1s, retried on failure). Retry safety:

1. **The ledger is NOT posted when the 201 returns.** Do not assume stock has decremented or GL/AR
   are posted at response time. They follow eventually. (Idempotency covers the *sale row* and its
   downstream postings — a replay never re-queues them.)
2. **Sending the same `Idempotency-Key` on retry is the PRIMARY safe-retry mechanism.** On *any*
   ambiguous outcome of the *same* logical sale — timeout, dropped connection, `500`, or a `409
   "...still in progress..."` — **resend the SAME `Idempotency-Key`**. If the original committed you
   get its invoice back (match on `uid`); if it had not, the sale is created normally. This closes
   the "server committed but client never learned" window that previously could not be closed.
3. **Reconcile-before-resend is now the FALLBACK** — only needed when the header was *omitted* (so
   the guarantee does not apply) and you must establish whether a duplicate already exists. Before
   re-sending a *keyless* sale, query for the one you may have already created and only resend if it
   is absent:
   * Confirm the session is still OPEN: `GET /api/v1/pos/sessions/uid/{uid}` (perm
     `POS.SESSION.VIEW`).
   * List the invoices for the company and look for the one you may have just created: `GET
     /api/v1/sales-invoices?companyId={companyId}` (newest first), and match on amount, line
     snapshot, and timestamp window. (The session `x-read` returns only aggregate totals
     —`totalSalesAmount`/`invoiceCount`— so it can tell you *whether* the count moved but not
     *which* invoice; use the sales-invoices list to identify the specific one.) If a matching
     finalised invoice already exists, **treat the original as succeeded** — do not resend; reprint
     the receipt from the existing invoice.
4. **Generate and persist a durable local sale id before the first POST**, and keep it across app
   restarts. Use it as the `Idempotency-Key` on **every** attempt of the *same* logical sale, and
   (optionally) also send it as `X-Request-Id` for log correlation. Use a fresh id for genuinely new
   sales. Note `X-Request-Id` itself does **not** deduplicate — `JwtRequestContextFilter` only puts
   it in the SLF4J MDC and echoes it back; only `Idempotency-Key` dedupes.
5. **Branch retries on HTTP status, not message text** (see [§2.1](#21-the-409-family-the-one-that-matters-for-pos)):
   * `400` / `403` / `404` / `415` / `422` — **terminal**: fix the request, do not retry the same
     bytes.
   * `409` — **retry only** if `errors[0]` is exactly
     `"This record was modified by another transaction. Please reload and try again."` **or**
     `"A POS sale with this Idempotency-Key is still in progress; retry shortly."` (the latter only
     by resending the *same* key); otherwise terminal.
   * `401` — refresh the token (`POST /api/v1/auth/refresh`) or re-login, then retry once **with the
     same `Idempotency-Key`**.
   * `500` / network failure — **ambiguous**: resend the **same `Idempotency-Key`** (rule 2). Only if
     the key was omitted, fall back to reconcile (rule 3).
6. **Make the receipt print idempotent on your side.** The 201 body is the authoritative
   `SalesInvoiceDto` (`uid`, `invoiceNumber`, totals). Persist it locally keyed by your sale id so a
   reprint never triggers a second POST.

### 4.3 Offline operation

The API is a **stateless JWT resource server**; there is no server-side queue you can post to "for
later". Offline POS therefore has hard constraints:

* **You cannot ring a sale offline against the server.** `POST /pos/sales` requires a live
  authenticated request, an OPEN session resolved server-side, and synchronous invoice
  number allocation. There is no batch/offline ingest endpoint.
* **Tokens expire.** Access tokens have a 15-minute TTL; refresh tokens last 7 days and are
  **single-use/rotated** (presenting an already-consumed refresh token revokes the whole chain —
  fail-closed). A client offline longer than the refresh TTL must re-login. In the default
  `dev-in-memory` signing mode the server's key rotates on restart, invalidating all tokens — assume
  reconnection may require a fresh login.
* **Recommended offline pattern:** queue sales **locally** while offline, each stamped with a
  durable local sale id (rule 4 above). On reconnect, **replay them one at a time**, sending that id
  as the `Idempotency-Key` on **every** attempt — so a sale that actually reached the server during a
  flaky earlier attempt returns its original invoice instead of double-posting (match on `uid`). Do
  not fire the whole queue in parallel — serialize, and stop on the first terminal error for cashier
  review.
* **Keep your own session clock.** Because the session must be OPEN server-side, a long offline
  period may outlast the cashier's session (it could be closed/reconciled by then). On reconnect,
  re-check `GET /pos/sessions/uid/{uid}` before replaying; if it is no longer OPEN, the queued sales
  cannot be posted to it and must be handled as an exception (open a new session / escalate).

### 4.4 Capabilities summary

| Capability | Status today |
| --- | --- |
| Server idempotency key for sale create | **Provided** — `Idempotency-Key` header + `pos_sale_idempotency` (Flyway `V70`), ADR-0042 / `f08fb08` |
| Idempotency scope | **Per company** (namespaced by the session's company) |
| Replay returns the original invoice | **Yes** (still HTTP `201` — match by invoice `uid`, not status) |
| Omitting the header | **Legacy non-idempotent** — a blind retry duplicates the sale |
| `X-Request-Id` used for dedup | **No** (correlation/logging only — use `Idempotency-Key`) |
| Outbox dedup protects against double-POST | n/a for HTTP retries — covered by `Idempotency-Key`; the outbox still dedupes same-event re-delivery |
| Ledger posted synchronously with the 201 | **No** (stock/GL/AR are eventual, ~1s poll) |
| Offline sale ingest endpoint | **None** |
| Safe-retry responsibility | **Client sends the key**; the server then guarantees exactly-once |

With the `Idempotency-Key` header, a deployment **can** get exactly-once sale posting, provided the
client always sends a durable key per logical sale and resends the *same* key on retry. The only
residual responsibility on the client is to **generate and persist that key** before the first POST
and reuse it; if you omit the header you are back to the keyless reconcile-before-resend discipline.

---

## 5. Quick reference: status → client action

| Status | Auto-retry? | Action |
| --- | --- | --- |
| `201` / `200` | n/a | Persist result locally; reprint from it. On a sale, match the returned `uid` to detect an idempotent replay. |
| `400` | No | Fix the field(s) in `errors[]`; resend corrected. |
| `401` | After refresh/login | `POST /auth/refresh` then retry once (reuse the same `Idempotency-Key`). |
| `403` | No | Cashier lacks permission / wrong scope — escalate. |
| `404` | No | Bad id/uid — fix and resend. |
| `409` optimistic-lock | Yes | Reload affected resource, retry. |
| `409` idempotency "still in progress" | Yes | Resend the **same** `Idempotency-Key` after a short delay. |
| `409` other | No | Terminal business conflict — surface to cashier. |
| `415` | No | Set `Content-Type: application/json`. |
| `422` | No | Use a currency enabled for the company. |
| `500` / network | **Yes — resend the SAME `Idempotency-Key`** | The key makes it safe (returns the original invoice if it had committed). If the key was omitted, reconcile before any resend. |

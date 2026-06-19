# POS Sessions

This section documents the **POS session lifecycle** under the base path `/api/v1/pos/sessions`.
A *session* is one cashier's shift on one till (register): it is opened with a cash float, accrues
POS sales, may record cash payouts (refunds / drawer drops), is closed against a counted-cash
declaration, and is finally reconciled (a Z-read that posts any cash variance to the GL).

> **Read the shared contract first.** Base URL / versioning, the `ApiResponse<T>` envelope, the
> JWT auth flow (`Authorization: Bearer <accessToken>`), the `X-Branch-Uid` scoping header,
> pagination (`page`/`size`/`sort` + `PageMeta`), and the full HTTP error table are described once
> in the shared POS-integration contract and are **not** re-derived here. This page only adds the
> session-specific request/response shapes, permissions, and rules.

Source of truth for everything below:
`com.erp.api.PosSessionController`, `com.erp.modules.sales.domain.dto.{PosSessionDto, OpenSessionRequest,
CloseSessionRequest, ReconcileSessionRequest, PosPayoutRequest, XReadDto, ZReadDto}`,
`com.erp.modules.sales.domain.enums.{PosSessionStatus, PosPayoutType}`, and
`com.erp.modules.sales.service.PosSessionServiceImpl`.

---

## 1. Lifecycle overview

The session status enum is `PosSessionStatus` with exactly three values and a one-way flow:

```
OPEN  →  CLOSED  →  RECONCILED
```

(`com.erp.modules.sales.domain.enums.PosSessionStatus` — `OPEN`, `CLOSED`, `RECONCILED`.)

| Action | Endpoint | Allowed in status | Resulting status |
|--------|----------|-------------------|------------------|
| Open | `POST /api/v1/pos/sessions` | n/a (creates new) | `OPEN` |
| Ring a sale | `POST /api/v1/pos/sales` (see Sales section) | **must be `OPEN`** | unchanged |
| Record payout | `POST /api/v1/pos/sessions/uid/{uid}/payouts` | **`OPEN`** | unchanged |
| X-read (mid-shift report) | `GET /api/v1/pos/sessions/uid/{uid}/x-read` | **`OPEN`** | unchanged |
| Close | `POST /api/v1/pos/sessions/uid/{uid}/close` | **`OPEN`** | `CLOSED` |
| Reconcile (Z-read) | `POST /api/v1/pos/sessions/uid/{uid}/reconcile` | **`CLOSED`** | `RECONCILED` |
| Get one / list | `GET .../uid/{uid}` and `GET /api/v1/pos/sessions` | any | unchanged |

Notes that fall straight out of `PosSessionServiceImpl`:

- **A till may have at most one OPEN session.** `openSession` checks
  `findByPosTillIdAndStatus(tillId, OPEN)` and throws **409 Conflict**
  (`"Till <tillUid> already has an OPEN session."`) if one already exists. Close (or reconcile)
  the previous session before opening a new one on the same till.
- **`openedAt` is server-stamped** at open (`PosSession.openedAt = Instant.now()` default); the
  `cashierId` is the calling user (`actorId()` from `RequestContext`). Clients do **not** send
  either.
- **`OPEN` is required to ring sales.** `POST /api/v1/pos/sales` resolves the session by
  `sessionUid` and, if it is not `OPEN`, throws **409 Conflict**
  (`"POS session <uid> is not OPEN."` — see `PosSaleServiceImpl`). So once a session is `CLOSED`
  or `RECONCILED`, sales against it are rejected; open a fresh session.
- All session timestamps in DTOs (`openedAt`, `closedAt`, `reconciledAt`) are serialized as
  **strings** — `Instant.toString()`, i.e. ISO-8601 UTC like `2026-06-19T08:15:42.123Z` (or `null`
  when not yet set).

---

## 2. `PosSessionDto` (the canonical session resource)

Returned by **open**, **get-by-uid**, **list**, and **close**. Fields, exactly as declared in
`com.erp.modules.sales.domain.dto.PosSessionDto`:

| Field | JSON type | Meaning |
|-------|-----------|---------|
| `id` | number (Long) | Internal DB id. Prefer `uid` for addressing. |
| `uid` | string | Stable external identifier; use this in all `/uid/{uid}` paths. |
| `companyId` | number (Long) | Owning company. |
| `branchId` | number (Long) | Owning branch (inherited from the till). |
| `posTillId` | number (Long) | The till this session runs on. |
| `cashierId` | number (Long) | The user who opened the session. |
| `sessionNumber` | string | Human-readable reference, e.g. `POS-0001` (server-generated at open). |
| `status` | string enum | `OPEN` \| `CLOSED` \| `RECONCILED`. |
| `openedAt` | string (ISO-8601) \| null | When opened. |
| `closedAt` | string (ISO-8601) \| null | When closed; null until close. |
| `reconciledAt` | string (ISO-8601) \| null | When reconciled; null until reconcile. |
| `openingFloatAmount` | number (BigDecimal) | Cash float declared at open. |
| `countedCashAmount` | number (BigDecimal) \| null | Cashier-counted cash; null until close. |
| `expectedCashAmount` | number (BigDecimal) \| null | System-computed expected cash; null until close. |
| `varianceAmount` | number (BigDecimal) \| null | `counted − expected` (positive = over, negative = short); null until close. |
| `varianceJournalId` | number (Long) \| null | GL journal id raised at reconcile when variance ≠ 0; else null. |
| `notes` | string \| null | Free text set at close / reconcile. |

`expectedCashAmount` is computed at close as
`openingFloatAmount + cashSalesTotal − totalPayouts` (see §5).

---

## 3. Open a session

Creates a new `OPEN` session on a till with a cash float.

- **Method + path:** `POST /api/v1/pos/sessions`
- **Permission:** `POS.SESSION.OPEN` (`@perm.has('POS.SESSION.OPEN')`)
- **Success status:** `201 Created`
- **Path/query params:** none
- **Request body** (`OpenSessionRequest`):

| Field | JSON type | Constraints |
|-------|-----------|-------------|
| `tillUid` | string | `@NotBlank` — the uid of the till to open on (see Tills section). |
| `openingFloatAmount` | number (BigDecimal) | `@NotNull`, `@DecimalMin("0.00")` — opening cash float (may be `0.00`). |

```json
{
  "tillUid": "8f1c2a90-till-...",
  "openingFloatAmount": 200000.00
}
```

**Success response** (envelope wraps the `PosSessionDto`):

```json
{
  "data": {
    "id": 41,
    "uid": "b3d7e2c1-sess-...",
    "companyId": 1,
    "branchId": 3,
    "posTillId": 7,
    "cashierId": 12,
    "sessionNumber": "POS-0001",
    "status": "OPEN",
    "openedAt": "2026-06-19T08:15:42.123Z",
    "closedAt": null,
    "reconciledAt": null,
    "openingFloatAmount": 200000.00,
    "countedCashAmount": null,
    "expectedCashAmount": null,
    "varianceAmount": null,
    "varianceJournalId": null,
    "notes": null
  },
  "errors": [],
  "meta": null
}
```

**curl:**

```bash
curl -i -X POST https://erp.example.com/api/v1/pos/sessions \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tillUid":"8f1c2a90-till-...","openingFloatAmount":200000.00}'
```

**Notable errors:**

- `400` — `tillUid` blank or `openingFloatAmount` null / negative (bean validation).
- `403` — caller lacks `POS.SESSION.OPEN`, or cannot act in the till's company
  (`ScopeGuard.assertCanActIn`).
- `404` — `tillUid` does not resolve (`NotFoundException.of("PosTill", tillUid)`).
- `409` — the till already has an `OPEN` session (`"Till <tillUid> already has an OPEN session."`).
- `415` — wrong `Content-Type` (must be `application/json`).

---

## 4. Get a session by uid

- **Method + path:** `GET /api/v1/pos/sessions/uid/{uid}`
- **Permission:** `POS.SESSION.VIEW`, scoped to the session
  (`@perm.scoped(#uid,'possession','POS.SESSION.VIEW')` — resolves the session's company by uid).
- **Success status:** `200 OK`
- **Path params:** `uid` — the session uid.
- **Query/body:** none.

**Success response:** the same `PosSessionDto` envelope shown in §3 (current state — fields like
`closedAt`, `varianceAmount` populated once the session has progressed).

**curl:**

```bash
curl -s https://erp.example.com/api/v1/pos/sessions/uid/b3d7e2c1-sess-... \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Notable errors:**

- `403` — lacks `POS.SESSION.VIEW`, or cannot act in the session's company.
- `404` — unknown session uid (`NotFoundException.of("PosSession", uid)`).

---

## 5. List sessions

- **Method + path:** `GET /api/v1/pos/sessions`
- **Permission:** `POS.SESSION.VIEW` (`@perm.has('POS.SESSION.VIEW')`)
- **Success status:** `200 OK`
- **Query params:**
  - `companyId` (Long, **required** — bound as `@RequestParam Long companyId`)
  - `page` (zero-based, default `0`), `size` (default `20`), `sort` (e.g. `sort=openedAt,desc`)
    — standard Spring `Pageable` (see shared pagination contract).

This endpoint is **paged**: the controller returns
`ApiResponse.ok(page.getContent(), PageMeta.from(page))`, so `data` is the array and `meta` carries
the page metadata.

**Success response:**

```json
{
  "data": [
    {
      "id": 41,
      "uid": "b3d7e2c1-sess-...",
      "companyId": 1,
      "branchId": 3,
      "posTillId": 7,
      "cashierId": 12,
      "sessionNumber": "POS-0001",
      "status": "CLOSED",
      "openedAt": "2026-06-19T08:15:42.123Z",
      "closedAt": "2026-06-19T17:02:10.000Z",
      "reconciledAt": null,
      "openingFloatAmount": 200000.00,
      "countedCashAmount": 845000.00,
      "expectedCashAmount": 846000.00,
      "varianceAmount": -1000.00,
      "varianceJournalId": null,
      "notes": "short by 1000"
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

**curl:**

```bash
curl -s "https://erp.example.com/api/v1/pos/sessions?companyId=1&page=0&size=20&sort=openedAt,desc" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Notable errors:**

- `400` — `companyId` missing or non-numeric (`MissingServletRequestParameterException` /
  `MethodArgumentTypeMismatchException`).
- `403` — lacks `POS.SESSION.VIEW`, or cannot act in `companyId`
  (`ScopeGuard.assertCanActIn`).

---

## 6. Record a payout (cash out / drawer drop)

Records a cash outflow from the till during an **OPEN** session. Both payout types are *outflows*
and reduce expected cash.

- **Method + path:** `POST /api/v1/pos/sessions/uid/{uid}/payouts`
- **Permission:** `POS.SESSION.OPEN`, scoped to the session
  (`@perm.scoped(#uid,'possession','POS.SESSION.OPEN')` — i.e. the same permission that opens a
  session; there is no separate "payout" permission).
- **Success status:** `200 OK` — the controller method returns **`void`**, so the response body is
  the envelope with `data: null`.
- **Path params:** `uid` — the session uid.
- **Request body** (`PosPayoutRequest`):

| Field | JSON type | Constraints |
|-------|-----------|-------------|
| `payoutType` | string enum | `@NotNull` — `REFUND` or `PAID_OUT` (see below). |
| `amount` | number (BigDecimal) | `@NotNull`, `@DecimalMin("0.01")` — must be > 0. |
| `reason` | string \| null | `@Size(max = 255)` — optional note. |

`PosPayoutType` (`com.erp.modules.sales.domain.enums.PosPayoutType`) has exactly two values:

- `REFUND` — cash paid out on a POS refund (cash returns to the customer).
- `PAID_OUT` — misc cash payout (drawer-to-safe drop / petty payout).

Both types **subtract** from expected cash. (The enum has no `CASH_IN` value — all payouts are
outflows.)

```json
{
  "payoutType": "PAID_OUT",
  "amount": 50000.00,
  "reason": "drawer-to-safe drop"
}
```

**Success response** (void method → null data):

```json
{ "data": null, "errors": [], "meta": null }
```

**curl:**

```bash
curl -i -X POST https://erp.example.com/api/v1/pos/sessions/uid/b3d7e2c1-sess-.../payouts \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"payoutType":"PAID_OUT","amount":50000.00,"reason":"drawer-to-safe drop"}'
```

**Notable errors:**

- `400` — `payoutType` null / not a valid enum value, `amount` null or `< 0.01`, or `reason`
  over 255 chars.
- `403` — lacks `POS.SESSION.OPEN`, or cannot act in the session's company.
- `404` — unknown session uid.
- `409` — session is not `OPEN` (`requireOpen` → `"Session <sessionNumber> is not OPEN."`).
- `415` — wrong `Content-Type`.

---

## 7. X-read (mid-session sales report)

A non-mutating snapshot of the session's running totals — does **not** close the session.

- **Method + path:** `GET /api/v1/pos/sessions/uid/{uid}/x-read`
- **Permission:** `POS.SESSION.VIEW`, scoped (`@perm.scoped(#uid,'possession','POS.SESSION.VIEW')`).
- **Success status:** `200 OK`
- **Path params:** `uid` — the session uid.
- **Requires the session to be `OPEN`** (`requireOpen`): once closed, use the reconcile Z-read
  instead.

**Response body** (`XReadDto`, wrapped in the envelope):

| Field | JSON type | Meaning |
|-------|-----------|---------|
| `sessionUid` | string | The session uid. |
| `posTillId` | number (Long) | Till id. |
| `cashierId` | number (Long) | Cashier (opener) id. |
| `openedAt` | string (ISO-8601) | When opened. |
| `openingFloatAmount` | number (BigDecimal) | Float at open. |
| `totalSalesAmount` | number (BigDecimal) | Gross cash-sales total for the session (`sumGrossByPosSession`). |
| `totalPayoutsNetAmount` | number (BigDecimal) | Total payouts (sum of all `REFUND` + `PAID_OUT`). |
| `expectedCashAmount` | number (BigDecimal) | `openingFloat + totalSales − totalPayouts`. |
| `invoiceCount` | number (long) | Count of POS invoices on this session. |

```json
{
  "data": {
    "sessionUid": "b3d7e2c1-sess-...",
    "posTillId": 7,
    "cashierId": 12,
    "openedAt": "2026-06-19T08:15:42.123Z",
    "openingFloatAmount": 200000.00,
    "totalSalesAmount": 696000.00,
    "totalPayoutsNetAmount": 50000.00,
    "expectedCashAmount": 846000.00,
    "invoiceCount": 23
  },
  "errors": [],
  "meta": null
}
```

**curl:**

```bash
curl -s https://erp.example.com/api/v1/pos/sessions/uid/b3d7e2c1-sess-.../x-read \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Notable errors:**

- `403` — lacks `POS.SESSION.VIEW`, or cannot act in the session's company.
- `404` — unknown session uid.
- `409` — session is not `OPEN`.

> **Eventual-consistency note:** `totalSalesAmount` / `invoiceCount` reflect **finalised** POS
> invoices tagged to the session. POS sale create is synchronous for the invoice itself, so totals
> appear immediately on X-read — but the downstream stock/GL/AR effects of those sales post
> asynchronously (see the Sales section). An X-read is a cash-drawer report, not a ledger report.

---

## 8. Close a session

The cashier declares the counted cash; the server computes expected cash and the variance, and
flips the session to `CLOSED`. **No GL posting happens here** — that is deferred to reconcile (§9).

- **Method + path:** `POST /api/v1/pos/sessions/uid/{uid}/close`
- **Permission:** `POS.SESSION.CLOSE`, scoped (`@perm.scoped(#uid,'possession','POS.SESSION.CLOSE')`).
- **Success status:** `200 OK`
- **Path params:** `uid` — the session uid.
- **Requires the session to be `OPEN`** (`requireOpen`).
- **Request body** (`CloseSessionRequest`):

| Field | JSON type | Constraints |
|-------|-----------|-------------|
| `countedCashAmount` | number (BigDecimal) | `@NotNull` — physical cash counted in the drawer. |
| `notes` | string \| null | Optional free text (stored on the session). |

```json
{
  "countedCashAmount": 845000.00,
  "notes": "short by 1000"
}
```

On close, the server (per `PosSessionServiceImpl.closeSession`) sets:

- `expectedCashAmount = openingFloatAmount + cashSalesTotal − totalPayouts`
- `varianceAmount = countedCashAmount − expectedCashAmount` (positive = over, negative = short)
- `status = CLOSED`, `closedAt = now`, and stores `notes`.

**Success response:** the updated `PosSessionDto` envelope (now with `status: "CLOSED"`,
`closedAt`, `countedCashAmount`, `expectedCashAmount`, `varianceAmount` populated):

```json
{
  "data": {
    "id": 41,
    "uid": "b3d7e2c1-sess-...",
    "companyId": 1,
    "branchId": 3,
    "posTillId": 7,
    "cashierId": 12,
    "sessionNumber": "POS-0001",
    "status": "CLOSED",
    "openedAt": "2026-06-19T08:15:42.123Z",
    "closedAt": "2026-06-19T17:02:10.000Z",
    "reconciledAt": null,
    "openingFloatAmount": 200000.00,
    "countedCashAmount": 845000.00,
    "expectedCashAmount": 846000.00,
    "varianceAmount": -1000.00,
    "varianceJournalId": null,
    "notes": "short by 1000"
  },
  "errors": [],
  "meta": null
}
```

**curl:**

```bash
curl -i -X POST https://erp.example.com/api/v1/pos/sessions/uid/b3d7e2c1-sess-.../close \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"countedCashAmount":845000.00,"notes":"short by 1000"}'
```

**Notable errors:**

- `400` — `countedCashAmount` null (bean validation).
- `403` — lacks `POS.SESSION.CLOSE`, or cannot act in the session's company.
- `404` — unknown session uid.
- `409` — session is not `OPEN` (already closed/reconciled).
- `415` — wrong `Content-Type`.

---

## 9. Reconcile (Z-read — posts the variance to the GL)

Finalises a **CLOSED** session: posts the cash variance to the GL (when non-zero), flips the
session to `RECONCILED`, and returns the Z-read report.

- **Method + path:** `POST /api/v1/pos/sessions/uid/{uid}/reconcile`
- **Permission:** `POS.SESSION.RECONCILE`, scoped
  (`@perm.scoped(#uid,'possession','POS.SESSION.RECONCILE')`).
- **Success status:** `200 OK`
- **Path params:** `uid` — the session uid.
- **Requires the session to be `CLOSED`** — if not, **409 Conflict**
  (`"Session must be CLOSED before reconciliation."`).
- **Request body** (`ReconcileSessionRequest`): only an optional note. The variance is computed
  server-side; no amount input is accepted.

| Field | JSON type | Constraints |
|-------|-----------|-------------|
| `notes` | string \| null | Optional; appended to the session if non-null. |

```json
{ "notes": "verified by supervisor" }
```

> The controller binds the body as a plain `@RequestBody` (no `@Valid`); an empty body `{}` is
> valid. Still send `Content-Type: application/json` (a body is expected).

**Variance posting (synchronous, fail-fast):** if `varianceAmount ≠ 0`, the service posts a
balanced journal *inline* in the request transaction (`GLPostingSafeInvoker.postInNewTx`):

- **Over** (variance > 0): `DR Cash / CR POS_CASH_OVER`
- **Short** (variance < 0): `DR POS_CASH_SHORT / CR Cash`

Currency is the company base currency; posting date is the session's `closedAt` date. Because this
is a human-initiated command (not the async outbox), missing GL config **fails the reconcile**
(propagates rather than being swallowed). The resulting journal id is stored on
`varianceJournalId` and echoed in the Z-read. If variance is exactly `0`, no journal is posted and
`varianceJournalId` stays `null`.

**Response body** (`ZReadDto`, wrapped in the envelope):

| Field | JSON type | Meaning |
|-------|-----------|---------|
| `sessionUid` | string | The session uid. |
| `posTillId` | number (Long) | Till id. |
| `cashierId` | number (Long) | Cashier id. |
| `openedAt` | string (ISO-8601) | When opened. |
| `closedAt` | string (ISO-8601) \| null | When closed. |
| `reconciledAt` | string (ISO-8601) | When reconciled (just now). |
| `openingFloatAmount` | number (BigDecimal) | Float at open. |
| `totalSalesAmount` | number (BigDecimal) | Gross cash-sales total. |
| `totalPayoutsNetAmount` | number (BigDecimal) | Total payouts. |
| `expectedCashAmount` | number (BigDecimal) | Expected cash (from close). |
| `countedCashAmount` | number (BigDecimal) | Counted cash (from close). |
| `varianceAmount` | number (BigDecimal) | `counted − expected`. |
| `varianceJournalId` | number (Long) \| null | GL journal id, or null when variance was 0. |
| `invoiceCount` | number (long) | POS invoice count for the session. |

```json
{
  "data": {
    "sessionUid": "b3d7e2c1-sess-...",
    "posTillId": 7,
    "cashierId": 12,
    "openedAt": "2026-06-19T08:15:42.123Z",
    "closedAt": "2026-06-19T17:02:10.000Z",
    "reconciledAt": "2026-06-19T17:05:33.500Z",
    "openingFloatAmount": 200000.00,
    "totalSalesAmount": 696000.00,
    "totalPayoutsNetAmount": 50000.00,
    "expectedCashAmount": 846000.00,
    "countedCashAmount": 845000.00,
    "varianceAmount": -1000.00,
    "varianceJournalId": 90211,
    "invoiceCount": 23
  },
  "errors": [],
  "meta": null
}
```

**curl:**

```bash
curl -i -X POST https://erp.example.com/api/v1/pos/sessions/uid/b3d7e2c1-sess-.../reconcile \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"notes":"verified by supervisor"}'
```

**Notable errors:**

- `403` — lacks `POS.SESSION.RECONCILE`, or cannot act in the session's company.
- `404` — unknown session uid.
- `409` — session not `CLOSED` (`"Session must be CLOSED before reconciliation."`).
- `409` — a missing GL config for the variance posting surfaces here as a domain conflict (the
  reconcile fails fast rather than completing without the journal).
- `415` — wrong `Content-Type`.

---

## 10. Required permissions (recap)

All gated via Spring method security. `@perm.has(code)` checks the active-scope permission;
`@perm.scoped(#uid,'possession',code)` additionally resolves the session's company by uid and
checks scope on it.

| Endpoint | Permission | Style |
|----------|-----------|-------|
| `POST /api/v1/pos/sessions` | `POS.SESSION.OPEN` | `@perm.has` |
| `GET /api/v1/pos/sessions/uid/{uid}` | `POS.SESSION.VIEW` | `@perm.scoped` |
| `GET /api/v1/pos/sessions` | `POS.SESSION.VIEW` | `@perm.has` |
| `POST .../uid/{uid}/payouts` | `POS.SESSION.OPEN` | `@perm.scoped` |
| `GET .../uid/{uid}/x-read` | `POS.SESSION.VIEW` | `@perm.scoped` |
| `POST .../uid/{uid}/close` | `POS.SESSION.CLOSE` | `@perm.scoped` |
| `POST .../uid/{uid}/reconcile` | `POS.SESSION.RECONCILE` | `@perm.scoped` |

A POS client should read its effective codes from `GET /api/v1/auth/me` (`permissions`) — or rely
on `isRoot` — to decide which actions to surface. Permission-denial responses are always the
generic `403` ("You do not have permission to perform this action.") and never name the missing
code.

---

## 11. End-to-end flow (happy path)

1. `POST /api/v1/pos/sessions` `{tillUid, openingFloatAmount}` → `201`, session `OPEN`, keep `uid`.
2. Ring sales: `POST /api/v1/pos/sales` with `{"sessionUid": "<uid>", ...}` (session must be
   `OPEN`).
3. (optional) `POST .../uid/{uid}/payouts` for refunds / drawer drops.
4. (optional) `GET .../uid/{uid}/x-read` for a mid-shift cash report.
5. `POST .../uid/{uid}/close` `{countedCashAmount, notes}` → `200`, session `CLOSED`, variance
   computed.
6. `POST .../uid/{uid}/reconcile` `{notes}` → `200`, Z-read returned, variance journal posted (if
   any), session `RECONCILED`.

# Stock Availability

How an external POS (desktop/mobile) client reads on-hand inventory before and during a sale.

This section covers the **read-only** stock surface you call to show "in stock / out of stock", quantity badges, per-location availability, and (for batch- or serial-tracked products) the lot/serial detail you need to ring a line. It does **not** decrement stock — that happens asynchronously after `POST /api/v1/pos/sales` (see the sale-posting note in the shared contract: stock issue is applied by the outbox poller within ~1s, not inline).

The endpoints live in four controllers under `com.erp.api`:

| Controller | Base path | What it gives you |
|---|---|---|
| `StockController` | `/api/v1/stock` | On-hand levels at the active branch (paged), and per-location on-hand views |
| `StockLocationController` | `/api/v1/stock-locations` | The locations (warehouse / store / van) within a branch |
| `StockBatchController` | `/api/v1/stock-batches` | Lot/batch on-hand for batch-tracked products |
| `StockSerialController` | `/api/v1/stock-serials` | Individual serial numbers for serial-tracked products |

All responses are auto-wrapped in the standard `ApiResponse<T>` envelope (`{data, errors, meta}`) — see the shared **envelope** and **error-table** contract; this section does not re-derive them. Every call requires `Authorization: Bearer <accessToken>` (shared **auth-flow** contract).

---

## Branch and location scoping (read this first)

The ERP holds stock at two nested levels:

- **Branch** — the on-hand summary (`stock_on_hand`) is keyed by `(companyId, branchId, productId)`.
- **Location** — within a branch, a product's quantity is further split across stock locations (`WAREHOUSE`, `STORE`, `VAN`, `QUARANTINE`, `OTHER`).

Two scoping rules matter for a POS client:

1. **The active-branch endpoints take NO branch parameter.** `GET /api/v1/stock/on-hand` resolves the company **and** branch from your token's `RequestContext` (minted `companyId`/`branchId`, optionally overridden by the `X-Branch-Uid` header per the shared auth contract). The service literally does `onHands.findByCompanyIdAndBranchId(principal.companyId(), principal.branchId(), pageable)`. If your token has no active company/branch it returns **409 Conflict** (`IllegalStateException: "No active company/branch in request context."` — `IllegalStateException` is mapped to 409 by the global handler) — a POS terminal must be logged in to a session-usable branch (`hasBranch=true`).

2. **The location / batch / serial endpoints take `companyId` (and `branchId`/`locationId`) as explicit query params.** These are validated against your scope by `ScopeGuard.assertCanActIn(principal, companyId)` — passing a `companyId` you cannot act in yields **403**. So pass the same `companyId` your token is scoped to. (Get it from `GET /api/v1/auth/me` → `activeCompanyUid`, but note these stock endpoints want the numeric `companyId`/`branchId`/`locationId` **ids**, not uids — you obtain branch/location ids from `GET /api/v1/stock-locations` and on-hand rows.)

A typical POS startup sequence:

1. `GET /api/v1/stock-locations` → discover the branch's sellable locations (capture each `id`).
2. `GET /api/v1/stock/on-hand` → cache branch-level on-hand for fast "is this product in stock?" lookups.
3. Per product as needed: `GET /api/v1/stock/on-hand/by-product/uid/{productUid}` for the per-location split, then `GET /api/v1/stock-batches` or `GET /api/v1/stock-serials` if the product is lot/serial tracked.

> **Permission note (verify before shipping).** The batch and serial controllers gate on `@perm.has('STOCK.BATCH.VIEW')` / `@perm.has('STOCK.SERIAL.VIEW')` in `@PreAuthorize`, but the seeded permission codes in `R__seed_permissions.sql` are `INVENTORY.BATCH.VIEW` and `INVENTORY.SERIAL.VIEW` (the on-hand/location codes `STOCK.VIEW` and `STOCK.LOCATION.VIEW` **are** seeded). Treat the `@PreAuthorize` strings (`STOCK.BATCH.VIEW` / `STOCK.SERIAL.VIEW`) as the codes the runtime check actually evaluates; if your POS role only carries the `INVENTORY.*` codes you may get **403** on the batch/serial reads. Confirm with your ERP admin which codes are granted to the POS role.

---

## 1. Branch on-hand (paged) — `GET /api/v1/stock/on-hand`

The primary "what's in stock at this terminal's branch" feed.

- **Method + path:** `GET /api/v1/stock/on-hand`
- **Permission:** `STOCK.VIEW` (`@perm.has('STOCK.VIEW')`)
- **Path params:** none
- **Query params:** standard `Pageable` — `page` (0-based, default 0), `size` (default 20), `sort` (e.g. `sort=quantity,asc`). No branch/company param; scope comes from the token.
- **Request body:** none

**Response** — `ApiResponse<List<StockOnHandDto>>` with `PageMeta`. `StockOnHandDto` fields (`com.erp.modules.stock.domain.dto.StockOnHandDto`):

| Field | Type | Notes |
|---|---|---|
| `id` | Long (string-serialised) | on-hand row id |
| `uid` | String | stable uid |
| `companyId` | Long | tenant |
| `branchId` | Long | branch |
| `productId` | Long | product |
| `quantity` | BigDecimal (string) | current on-hand at branch |
| `reorderLevel` | BigDecimal (string) \| null | reorder threshold |
| `maxQty` | BigDecimal (string) \| null | optional max-stock indicator |
| `lastMovementAt` | String (ISO) \| null | last movement timestamp |
| `lastCountedAt` | String (ISO) \| null | last physical count |
| `negative` | boolean | **derived:** `quantity < 0` (overselling indicator) |
| `low` | boolean | **derived:** `reorderLevel != null && quantity <= reorderLevel` |
| `version` | Long | optimistic-lock version |
| `createdAt` / `createdBy` / `updatedAt` / `updatedBy` | String/Long | audit |

> Numeric ids and `BigDecimal` quantities serialise as **JSON strings** (global Jackson config — avoids JS precision loss). Parse `quantity` as a decimal string, not a `number`.

For a POS "in stock?" check: a product is sellable from this branch if a row exists with `quantity > 0`. `negative=true` means the branch is already oversold (overselling is a flagged, not forbidden, state).

**Success example:**

```json
{
  "data": [
    {
      "id": "5012",
      "uid": "soh_7f3a9c21",
      "companyId": "1",
      "branchId": "3",
      "productId": "880",
      "quantity": "42.000",
      "reorderLevel": "10.000",
      "maxQty": null,
      "lastMovementAt": "2026-06-19T08:14:55Z",
      "lastCountedAt": null,
      "negative": false,
      "low": false,
      "version": "7",
      "createdAt": "2026-01-04T06:00:00Z",
      "createdBy": "2",
      "updatedAt": "2026-06-19T08:14:55Z",
      "updatedBy": "2"
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

**curl:**

```bash
curl -s "https://erp.example.com/api/v1/stock/on-hand?page=0&size=50&sort=productId,asc" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Request-Id: $(uuidgen)"
```

**Notable errors:** `401` (missing/expired token, or user no longer ACTIVE); `403` (lacks `STOCK.VIEW`); `409 Conflict` if the token carries no active company/branch (`IllegalStateException: "No active company/branch in request context."`, mapped to 409 by the global handler; the user-safe message text is not the raw exception text).

---

## 2. Per-location on-hand for a branch — `GET /api/v1/stock/on-hand/by-location`

When you need the breakdown across locations (e.g. only the `STORE` location is sellable at the till, while `WAREHOUSE`/`QUARANTINE` is not).

- **Method + path:** `GET /api/v1/stock/on-hand/by-location`
- **Permission:** `STOCK.VIEW` (`@perm.has('STOCK.VIEW')`)
- **Query params (all required except paging):**
  - `companyId` — Long, **required**
  - `branchId` — Long, **required**
  - `Pageable` — `page`, `size`, `sort`
- **Request body:** none

**Response** — `ApiResponse<List<LocationOnHandRowDto>>` with `PageMeta`. `LocationOnHandRowDto` fields:

| Field | Type | Notes |
|---|---|---|
| `locationId` | Long | location id |
| `locationUid` | String | location uid |
| `locationCode` | String | location code |
| `locationName` | String | location name |
| `productId` | Long | product |
| `productUid` | String | **returned `null` on this endpoint** (not enriched at this layer) |
| `productCode` | String | **returned `null`** |
| `productName` | String | **returned `null`** |
| `quantity` | BigDecimal (string) | on-hand at that location |
| `onHandValue` | BigDecimal (string) | inventory value (0 if unset) |
| `avgCost` | BigDecimal (string) \| null | moving-average unit cost |
| `currency` | String | **hard-coded `"TZS"`** in the current query implementation |

> Be aware that `productUid/productCode/productName` come back `null` here (the `LocationOnHandQuery.queryForBranch` leaves them unenriched — join `productId` against your own product cache or the products API). Likewise `currency` is currently a hard-coded `"TZS"` literal, not derived per company.

**Success example:**

```json
{
  "data": [
    {
      "locationId": "11",
      "locationUid": "loc_a1b2",
      "locationCode": "STORE-01",
      "locationName": "Front Store",
      "productId": "880",
      "productUid": null,
      "productCode": null,
      "productName": null,
      "quantity": "30.000",
      "onHandValue": "150000.0000",
      "avgCost": "5000.0000",
      "currency": "TZS"
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

**curl:**

```bash
curl -s "https://erp.example.com/api/v1/stock/on-hand/by-location?companyId=1&branchId=3&page=0&size=20" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Notable errors:** `400` if `companyId` or `branchId` is missing/non-numeric; `401`; `403` if you pass a `companyId` outside your scope (`ScopeGuard.assertCanActIn`).

---

## 3. Per-location on-hand for one product — `GET /api/v1/stock/on-hand/by-product/uid/{productUid}`

The cleanest per-product call when ringing a line: "where (which locations) is this product held, and how much?"

- **Method + path:** `GET /api/v1/stock/on-hand/by-product/uid/{productUid}`
- **Permission:** `STOCK.VIEW` (`@perm.has('STOCK.VIEW')`)
- **Path param:** `productUid` — String, the product's uid
- **Query param:** `companyId` — Long, **required**
- **Request body:** none

**Response** — **NOT** paged and **not** the `meta` form: a plain `List<LocationOnHandRowDto>` (the controller returns the raw list, so it is wrapped as `{data: [...], errors: [], meta: null}`). Same `LocationOnHandRowDto` shape as §2; here too `productUid/productCode/productName` are `null` and `currency` is `"TZS"`.

**Success example:**

```json
{
  "data": [
    {
      "locationId": "11", "locationUid": null, "locationCode": null, "locationName": null,
      "productId": "880", "productUid": null, "productCode": null, "productName": null,
      "quantity": "30.000", "onHandValue": "150000.0000", "avgCost": "5000.0000", "currency": "TZS"
    },
    {
      "locationId": "12", "locationUid": null, "locationCode": null, "locationName": null,
      "productId": "880", "productUid": null, "productCode": null, "productName": null,
      "quantity": "12.000", "onHandValue": "60000.0000", "avgCost": "5000.0000", "currency": "TZS"
    }
  ],
  "errors": [],
  "meta": null
}
```

> Note: on this code path even the **location** descriptors (`locationUid/locationCode/locationName`) are returned `null` (`queryForProduct` only fills `locationId`). Map `locationId` against the result of `GET /api/v1/stock-locations` (§4) to label them.

**curl:**

```bash
curl -s "https://erp.example.com/api/v1/stock/on-hand/by-product/uid/prod_9f2c11?companyId=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Notable errors:** `404` `NotFoundException` (`"Product not found: <uid>"`) if the uid does not exist in that company; `400` if `companyId` is missing; `401`; `403` for out-of-scope `companyId`.

---

## 4. Stock locations in the branch — `GET /api/v1/stock-locations`

Discover the branch's locations so you can label per-location rows and decide which are sellable.

- **Method + path:** `GET /api/v1/stock-locations`
- **Permission:** `STOCK.LOCATION.VIEW` (`@perm.has('STOCK.LOCATION.VIEW')`)
- **Path params:** none
- **Query params:** `Pageable` (`page`, `size`, `sort`). No company/branch param — `locationService.listForBranch(pageable)` resolves them from the token scope.
- **Request body:** none

**Response** — `ApiResponse<List<StockLocationDto>>` with `PageMeta`. `StockLocationDto` fields:

| Field | Type | Notes |
|---|---|---|
| `id` | Long (string) | location id (use this as `locationId` in batch/serial calls) |
| `uid` | String | location uid |
| `companyId` | Long | tenant |
| `branchId` | Long | branch |
| `code` | String | short code (e.g. `STORE-01`) |
| `name` | String | display name |
| `locationType` | enum | one of `WAREHOUSE`, `STORE`, `VAN`, `QUARANTINE`, `OTHER` |
| `isDefault` | boolean | branch default location |
| `parentLocationId` | Long \| null | parent (nesting) |
| `allowNegative` | boolean | whether negative on-hand is permitted here |
| `pickable` | boolean | available for picking |
| `sellable` | boolean | **POS-relevant:** is this location sellable from |
| `glAccountId` | Long \| null | GL mapping |
| `status` | enum | `MasterStatus` (e.g. `ACTIVE`) |

For a POS, prefer locations where `sellable=true` (and `status=ACTIVE`). `QUARANTINE` stock should not be offered for sale.

**Success example:**

```json
{
  "data": [
    {
      "id": "11", "uid": "loc_a1b2", "companyId": "1", "branchId": "3",
      "code": "STORE-01", "name": "Front Store", "locationType": "STORE",
      "isDefault": true, "parentLocationId": null,
      "allowNegative": false, "pickable": true, "sellable": true,
      "glAccountId": "4400", "status": "ACTIVE"
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

**curl:**

```bash
curl -s "https://erp.example.com/api/v1/stock-locations?page=0&size=50" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Related reads (same controller, same `STOCK.LOCATION.VIEW` gate):**

- `GET /api/v1/stock-locations/uid/{uid}` — single location (`@perm.scoped(#uid,'stocklocation','STOCK.LOCATION.VIEW')`); **404** if the uid is unknown.
- `GET /api/v1/stock-locations/active?branchUid=...` — returns a plain `List<StockLocationDto>` (no paging, `meta: null`) of active locations for a branch.

**Notable errors:** `401`; `403` if lacking `STOCK.LOCATION.VIEW`.

---

## 5. Batch / lot reads — `GET /api/v1/stock-batches`

Only relevant for **batch/lot-tracked** products. Use to pick a lot (e.g. FEFO — first-expiry-first-out) and to avoid selling expired stock at the till.

- **Method + path:** `GET /api/v1/stock-batches`
- **Permission:** `STOCK.BATCH.VIEW` (`@perm.has('STOCK.BATCH.VIEW')` — see the permission-note caveat above re: `INVENTORY.BATCH.VIEW` seed)
- **Path params:** none
- **Query params (all required except paging):**
  - `companyId` — Long, **required**
  - `locationId` — Long, **required** (a `StockLocationDto.id` from §4)
  - `productId` — Long, **required**
  - `Pageable` — `page`, `size`, `sort`
- **Request body:** none

**Response** — `ApiResponse<List<StockBatchDto>>` with `PageMeta`. `StockBatchDto` fields:

| Field | Type | Notes |
|---|---|---|
| `id` | Long (string) | batch id |
| `uid` | String | batch uid |
| `companyId` | Long | tenant |
| `branchId` | Long | branch |
| `locationId` | Long | location holding the lot |
| `productId` | Long | product |
| `lotNumber` | String | lot/batch number |
| `manufactureDate` | LocalDate (`YYYY-MM-DD`) \| null | manufacture date |
| `expiryDate` | LocalDate (`YYYY-MM-DD`) \| null | expiry date |
| `qtyOnHand` | BigDecimal (string) | quantity remaining in this lot |
| `expired` | boolean | derived expiry flag |

**Success example:**

```json
{
  "data": [
    {
      "id": "9001", "uid": "batch_4c7e", "companyId": "1", "branchId": "3",
      "locationId": "11", "productId": "880", "lotNumber": "LOT-2026-031",
      "manufactureDate": "2026-01-10", "expiryDate": "2027-01-10",
      "qtyOnHand": "18.000", "expired": false
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

**curl:**

```bash
curl -s "https://erp.example.com/api/v1/stock-batches?companyId=1&locationId=11&productId=880&sort=expiryDate,asc" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Related reads (same controller):**

- `GET /api/v1/stock-batches/uid/{uid}` — single batch (`@perm.scoped(#uid,'stockbatch','STOCK.BATCH.VIEW')`); **404** if unknown.
- `GET /api/v1/stock-batches/expiring?companyId=...&horizon=YYYY-MM-DD` — near-expiry report (qty > 0, expiring on/before `horizon`). **Different permission:** `INVENTORY.EXPIRY.VIEW`. `horizon` is an ISO date query param.

**Notable errors:** `400` if `companyId`/`locationId`/`productId` missing or non-numeric, or `horizon` is not a valid `YYYY-MM-DD`; `401`; `403` for out-of-scope `companyId` or missing permission.

> **Batch writes are internal only.** Lot rows are created on goods receipt and consumed (FEFO) by internal services — there is no REST endpoint to create/consume a batch. The POS only reads.

---

## 6. Serial-number reads — `GET /api/v1/stock-serials`

Only relevant for **serial-tracked** products (one physical unit = one serial). Use to pick/scan the exact unit being sold and confirm it is `IN_STOCK`.

- **Method + path:** `GET /api/v1/stock-serials`
- **Permission:** `STOCK.SERIAL.VIEW` (`@perm.has('STOCK.SERIAL.VIEW')` — see the permission-note caveat re: `INVENTORY.SERIAL.VIEW` seed)
- **Path params:** none
- **Query params:**
  - `companyId` — Long, **required**
  - `locationId` — Long, **required**
  - `productId` — Long, **required**
  - `status` — enum `SerialStatus`, **optional** (`IN_STOCK`, `ISSUED`, `RETURNED`); filter to `IN_STOCK` to list only sellable units
  - `Pageable` — `page`, `size`, `sort`
- **Request body:** none

**Response** — `ApiResponse<List<StockSerialDto>>` with `PageMeta`. `StockSerialDto` fields:

| Field | Type | Notes |
|---|---|---|
| `id` | Long (string) | serial row id |
| `uid` | String | serial uid |
| `companyId` | Long | tenant |
| `branchId` | Long | branch |
| `locationId` | Long | location |
| `productId` | Long | product |
| `serialNumber` | String | the serial number |
| `serialStatus` | enum | `IN_STOCK` / `ISSUED` / `RETURNED` |
| `receivedDocumentUid` | String \| null | doc that received the unit |
| `issuedDocumentUid` | String \| null | doc that issued the unit |
| `createdAt` | Instant (ISO) | created timestamp |

**Success example:**

```json
{
  "data": [
    {
      "id": "7701", "uid": "ser_d9f0", "companyId": "1", "branchId": "3",
      "locationId": "11", "productId": "905", "serialNumber": "SN-AB-0099",
      "serialStatus": "IN_STOCK", "receivedDocumentUid": "grn_3321",
      "issuedDocumentUid": null, "createdAt": "2026-05-02T10:22:31Z"
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

**curl:**

```bash
curl -s "https://erp.example.com/api/v1/stock-serials?companyId=1&locationId=11&productId=905&status=IN_STOCK" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Related reads (same controller):**

- `GET /api/v1/stock-serials/uid/{uid}` — single serial (`@perm.scoped(#uid,'stockserial','STOCK.SERIAL.VIEW')`); **404** if unknown.
- `GET /api/v1/stock-serials/lookup?companyId=...&productId=...&serialNumber=...` — resolve a scanned serial to its current row/status (returns a single `StockSerialDto`, `meta: null`); ideal for barcode/serial scanning at the till.
- `GET /api/v1/stock-serials/product/uid/{productUid}?companyId=...` — full serial history for a product (paged).

**Notable errors:** `400` if a required `companyId`/`locationId`/`productId` is missing, or `status` is not a valid `SerialStatus` value (`"Invalid value 'XXX' for field 'status' (SerialStatus)."`); `401`; `403` for out-of-scope `companyId` or missing permission; `404` from `/lookup` and `/uid/{uid}` when the serial does not exist.

> **Serial writes are internal only.** Serial state transitions (`IN_STOCK → ISSUED → RETURNED`) happen inside internal services on sale/delivery/return. The POS only reads.

---

## How this feeds the sale

A POS line ultimately posts to `POST /api/v1/pos/sales` with `lines[].productId`, `unitId`, `quantity`, `unitPrice`, `lineDiscountAmount` (shared **idempotency** contract). The stock reads above are **advisory** — they let you show availability and pick a lot/serial — but the authoritative decrement is applied **asynchronously** by the outbox poller after the sale finalises (shared **sale-posting** contract). Consequences for the client:

- **Do not assume the ledger/stock reflects your sale immediately.** Re-query on-hand after a short delay (~1s+) if you display live counts.
- **On-hand reads are point-in-time and not reserved.** Two terminals can both see the last unit. Overselling is a *flagged* state (`StockOnHandDto.negative=true`), not a hard block, unless the location's `allowNegative=false` policy applies upstream. Build your own client-side guard if you need hard stock reservation.
- **Retrying a timed-out `POST /pos/sales` creates a SECOND sale** (no idempotency key) — never blindly retry; check for the resulting invoice first (shared **idempotency** contract).

---

## Quick reference

| Endpoint | Method | Permission (`@PreAuthorize`) | Paged? | Scope source |
|---|---|---|---|---|
| `/api/v1/stock/on-hand` | GET | `STOCK.VIEW` | yes | token (company+branch) |
| `/api/v1/stock/on-hand/by-location` | GET | `STOCK.VIEW` | yes | `companyId`+`branchId` query |
| `/api/v1/stock/on-hand/by-product/uid/{productUid}` | GET | `STOCK.VIEW` | no | `companyId` query |
| `/api/v1/stock-locations` | GET | `STOCK.LOCATION.VIEW` | yes | token (branch) |
| `/api/v1/stock-locations/uid/{uid}` | GET | `STOCK.LOCATION.VIEW` (scoped) | no | uid |
| `/api/v1/stock-locations/active` | GET | `STOCK.LOCATION.VIEW` | no | `branchUid` query |
| `/api/v1/stock-batches` | GET | `STOCK.BATCH.VIEW` ¹ | yes | `companyId`+`locationId`+`productId` query |
| `/api/v1/stock-batches/uid/{uid}` | GET | `STOCK.BATCH.VIEW` (scoped) ¹ | no | uid |
| `/api/v1/stock-batches/expiring` | GET | `INVENTORY.EXPIRY.VIEW` | yes | `companyId`+`horizon` query |
| `/api/v1/stock-serials` | GET | `STOCK.SERIAL.VIEW` ¹ | yes | `companyId`+`locationId`+`productId` (+`status`) query |
| `/api/v1/stock-serials/uid/{uid}` | GET | `STOCK.SERIAL.VIEW` (scoped) ¹ | no | uid |
| `/api/v1/stock-serials/lookup` | GET | `STOCK.SERIAL.VIEW` ¹ | no | `companyId`+`productId`+`serialNumber` query |
| `/api/v1/stock-serials/product/uid/{productUid}` | GET | `STOCK.SERIAL.VIEW` ¹ | yes | `companyId` query |

¹ The `@PreAuthorize` code is `STOCK.BATCH.VIEW` / `STOCK.SERIAL.VIEW`; the seeded code is `INVENTORY.BATCH.VIEW` / `INVENTORY.SERIAL.VIEW`. Confirm the POS role's granted codes — see the permission note above.

# Catalog: Products & Units

How an external POS client (desktop/mobile) loads its sellable catalog from this ERP. This
section covers the product master (`/api/v1/products`), the unit-of-measure master
(`/api/v1/units`), and the dedicated barcode-scan lookup. Everything here is grounded in
`ProductController`, `UnitOfMeasureController`, and their DTOs/services under
`src/main/java/com/erp/modules/products`.

> Prerequisites — read the shared contract first (`01-shared-contract`):
> - **Base URL / versioning:** all paths are under the fixed prefix `/api/v1`.
> - **Envelope:** every success body is `{ "data": <T>, "errors": [], "meta": null|<PageMeta> }`;
>   every error is `{ "data": null, "errors": ["..."], "meta": null }`. Paged list endpoints put
>   a `PageMeta` (`page, size, totalElements, totalPages, hasNext`) in `meta`.
> - **Auth:** send `Authorization: Bearer <accessToken>` on every call below. None of these
>   endpoints are public.
> - **Scope:** all catalog endpoints require an explicit `companyId` query param, and the
>   service calls `ScopeGuard.assertCanActIn(...)` against it. Pass the company you are operating
>   in (the active company from your JWT, or override the branch with `X-Branch-Uid`).
> - **IDs on the wire:** `Long` ids (`id`, `companyId`, `uomId`, etc.) are serialized as JSON
>   **strings** (global Long-as-string config) to avoid JS precision loss. Treat them as opaque
>   strings. Prefer the `uid` for addressing single resources.

---

## What the POS needs from the catalog

A POS cart line eventually hits `POST /api/v1/pos/sales` with `LineItem{ productId, unitId,
quantity, unitPrice, lineDiscountAmount }` (see the sale section). So the POS must, at minimum,
be able to:

1. **List/search products** for the active company and let the cashier pick one.
2. Know which products are **sellable** (filter the picker) and whether they are **stockable**.
3. Resolve each product's **base unit** (`baseUnitUid`) — and any alternative units — to fill
   `LineItem.unitId`. (Note: the POS sale `LineItem.unitId` is a **`Long`** unit id, not a uid.)
4. Read the product's **VAT status** for receipt/tax display.
5. Resolve a **scanned barcode** to a product.

There is **no** "sellable-only" or "POS catalog" endpoint. The product list returns all products
for the company regardless of `sellable`/`status`; the client must filter on the `sellable` flag
(and typically `status == ACTIVE`) itself.

---

## 1. List / search products

```
GET /api/v1/products?companyId={id}&q={text}&page=&size=&sort=
```

- **Source:** `ProductController.list(...)`.
- **Required permission:** `PRODUCT.VIEW` (`@perm.has('PRODUCT.VIEW')`; seed description
  *"View and select products"*).
- **Paged:** yes — `meta` carries a `PageMeta`.

### Query params

| Param | Type | Required | Notes |
|-------|------|----------|-------|
| `companyId` | `Long` (string on wire) | **Yes** | Company scope. `ScopeGuard.assertCanActIn` runs against it (403 if you cannot act in it). |
| `q` | `String` | No | Free-text search. When blank/absent → `findByCompanyId` (all products in the company). When present → `ProductRepository.search`: matches **`LOWER(name) LIKE %q%`** (case-insensitive contains) **OR** **`code = q`** (exact code match). **Barcodes are NOT searched here** despite older docs — use `/barcode-lookup` (section 4) for scans. |
| `page` | int | No | Zero-based, default `0`. |
| `size` | int | No | Default `20`. |
| `sort` | string | No | e.g. `sort=name,asc` (Spring `Pageable` binding; sortable on `ProductDto`/entity fields such as `name`, `code`, `createdAt`). |

> **No sellable / status / type filter.** The endpoint does not accept `sellable`, `status`, or
> `type` params. To show only sellable, active items, fetch and filter client-side on
> `data[].sellable == true` (and usually `status == "ACTIVE"`).

### Response payload — `ProductDto` (one per array element)

The list returns `List<ProductDto>` in `data`. `ProductDto` (record in
`products.domain.dto.ProductDto`) carries the full product. The fields a POS cares about:

| Field | Type | Meaning |
|-------|------|---------|
| `id` | `Long` (string) | Numeric product id. **This is what you put in POS `LineItem.productId`.** |
| `uid` | `String` | Stable public id; use it to address `GET /products/uid/{uid}` and child collections. |
| `companyId` | `Long` (string) | Owning company. |
| `code` | `String` | Product code / SKU (auto `PROD-####` or a supplied, uppercased value, unique per company). There is no separate "sku" field — `code` **is** the SKU. |
| `name` | `String` | Display name. |
| `description` | `String` | Optional long text. |
| `type` | enum `ProductType` | `GOODS` or `SERVICE`. (`SERVICE` is intangible/non-stockable.) |
| `sellable` | `boolean` | **Whether the item may be sold.** Filter your POS picker on this. |
| `stockable` | `boolean` | Whether stock is tracked. Affects whether a POS sale will issue stock. |
| `lotTracked` | `boolean` | Lot/batch tracked. |
| `serialTracked` | `boolean` | Serial tracked. |
| `expiryTracked` | `boolean` | Expiry tracked. |
| `baseUnitUid` | `String` | **uid** of the base unit of measure (the unit `cost`/prices are stated in). Enriched from the `UnitOfMeasure` association; `null` if no base unit. |
| `baseUnitCode` | `String` | Base unit code, e.g. `EA`, `KG` (convenience, enriched). |
| `baseUnitName` | `String` | Base unit display name. |
| `cost` | `MoneyDto` or `null` | `{ "amount": "1500.0000", "currency": "TZS" }`; `amount` is a string. `null` when unset. |
| `vatStatus` | enum `VatStatus` | `STANDARD`, `ZERO_RATED`, or `EXEMPT` — for tax/receipt display. |
| `status` | enum `MasterStatus` | `ACTIVE`, `INACTIVE`, or `ARCHIVED`. Archived products are still returned by the list. |
| `brand`, `manufacturer` | `String` | Descriptive. |
| `weight`, `volume` | `BigDecimal` (string) | Logistics attributes. |
| `dimensions`, `hsCode` | `String` | Logistics attributes. |
| `version` | `Long` | Optimistic-lock version. |
| `createdAt`, `updatedAt` | `String` (ISO-8601) | Audit timestamps. |
| `createdBy`, `updatedBy` | `Long` (string) | Audit actor ids. |
| `reorderLevel`, `reorderQty`, `safetyStock`, `minStock`, `maxStock` | `BigDecimal` (string) | Planning fields (not POS-relevant). |
| `leadTimeDays` | `Integer` | Sourcing. |
| `purchasable` | `boolean` | Whether it can be purchased. |
| `preferredSupplierId` | `Long` (string) or `null` | Sourcing. |

> There is also a lighter `ProductSummaryDto` (`id, uid, companyId, code, name, type, sellable,
> stockable, baseUnitCode, status`) used elsewhere in the platform, but the **list endpoint
> returns full `ProductDto`** — plan for the larger shape.

### Success example

```json
{
  "data": [
    {
      "id": "1042",
      "uid": "prd_7Fk29ZQ",
      "companyId": "7",
      "code": "PROD-0007",
      "name": "Coca-Cola 500ml",
      "description": "PET bottle",
      "type": "GOODS",
      "sellable": true,
      "stockable": true,
      "lotTracked": false,
      "serialTracked": false,
      "expiryTracked": true,
      "baseUnitUid": "uom_EA01",
      "baseUnitCode": "EA",
      "baseUnitName": "Each",
      "cost": { "amount": "850.0000", "currency": "TZS" },
      "vatStatus": "STANDARD",
      "status": "ACTIVE",
      "brand": "Coca-Cola",
      "manufacturer": "SBC Tanzania",
      "weight": "0.520",
      "volume": "0.500",
      "dimensions": null,
      "hsCode": null,
      "version": "3",
      "createdAt": "2026-01-10T08:14:55Z",
      "createdBy": "5",
      "updatedAt": "2026-05-02T11:02:31Z",
      "updatedBy": "5",
      "reorderLevel": "24",
      "reorderQty": "120",
      "safetyStock": "12",
      "minStock": null,
      "maxStock": null,
      "leadTimeDays": 3,
      "purchasable": true,
      "preferredSupplierId": "31"
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

### curl

```bash
# First page of products for company 7, search "cola"
curl -s "https://erp.example.com/api/v1/products?companyId=7&q=cola&page=0&size=20&sort=name,asc" \
  -H "Authorization: Bearer $ACCESS_TOKEN"

# Whole catalog page (no search), filter client-side on .sellable
curl -s "https://erp.example.com/api/v1/products?companyId=7&size=100" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Notable errors

| Status | Cause |
|--------|-------|
| 400 | Missing/uncoercible `companyId` (it is required). |
| 401 | Missing/expired bearer token, or user no longer ACTIVE. |
| 403 | Caller lacks `PRODUCT.VIEW`, or cannot act in `companyId` (scope guard / rejected `X-Branch-Uid`). |

---

## 2. Get a single product

```
GET /api/v1/products/uid/{uid}
```

- **Source:** `ProductController.get(...)`.
- **Required permission:** `PRODUCT.VIEW`.
- **Path param:** `uid` (the product `uid` from the list).
- **Returns:** a single `ProductDto` (same shape as above), wrapped in the envelope (`meta: null`).
  The service scope-checks the loaded product's company.

### curl

```bash
curl -s "https://erp.example.com/api/v1/products/uid/prd_7Fk29ZQ" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Notable errors

| Status | Cause |
|--------|-------|
| 403 | Lacks `PRODUCT.VIEW`, or product belongs to a company you cannot act in. |
| 404 | No product with that `uid` (`NotFoundException("Product", uid)`). |

---

## 3. Units for a product (alternative / pack units)

The POS sale `LineItem.unitId` may be the base unit or an alternative pack unit. A product's
sellable units come from two places:

- **Base unit** — already on `ProductDto` as `baseUnitUid` / `baseUnitCode` / `baseUnitName`.
- **Bulk packs** — alternative units with a conversion factor to the base unit:

```
GET /api/v1/products/uid/{uid}/bulk-packs
```

- **Source:** `ProductController.listBulkPacks(...)`.
- **Required permission:** `PRODUCT.VIEW`.
- **Returns:** `List<ProductBulkPackDto>` (each carries its unit and a `factorToBase`). Use these
  when the cashier sells by carton/case rather than by each.

> The POS sale `LineItem` references a unit by its numeric **`Long unitId`**, not a uid. The
> unit's numeric id is available from the unit master (section 5, `UnitOfMeasureDto.id`) and from
> the bulk-pack DTO; resolve uid → id once at catalog-load time and cache the mapping.

---

## 4. Barcode lookup (scan)

```
GET /api/v1/products/barcode-lookup?companyId={id}&barcode={value}
```

- **Source:** `ProductController.lookupBarcode(...)`.
- **Required permission:** `PRODUCT.VIEW`.
- **Purpose:** resolve a scanned barcode value to its product-barcode row **within the active
  company** (cross-tenant safe; single indexed probe on the
  `uq_product_barcode_company_value` constraint).

### Query params

| Param | Type | Required | Notes |
|-------|------|----------|-------|
| `companyId` | `Long` (string) | **Yes** | Company scope; `ScopeGuard.assertCanActIn` runs against it. |
| `barcode` | `String` | **Yes** | The scanned value (exact match). |

### Response payload — `ProductBarcodeDto`

| Field | Type | Meaning |
|-------|------|---------|
| `id` | `Long` (string) | Barcode row id. |
| `uid` | `String` | Barcode row uid. |
| `productId` | `Long` (string) | **The product to ring up** → use as POS `LineItem.productId`. |
| `companyId` | `Long` (string) | Owning company. |
| `barcode` | `String` | The matched value. |
| `barcodeType` | enum `BarcodeType` | `EAN`, `UPC`, `CODE128`, or `OTHER`. |
| `uomId` | `Long` (string) or `null` | Unit this barcode represents (e.g. a carton barcode). If set, use it for `LineItem.unitId`; otherwise fall back to the product's base unit. |
| `primary` | `boolean` | Whether this is the product's primary barcode. |

> The lookup returns the **barcode row**, not the full product. It gives you `productId` (and an
> optional `uomId`); if you need name/price/VAT, follow up with `GET /products/uid/{uid}` or use
> the product you already cached from the list.

### Success example

```json
{
  "data": {
    "id": "9001",
    "uid": "bcd_Lm3",
    "productId": "1042",
    "companyId": "7",
    "barcode": "5449000000996",
    "barcodeType": "EAN",
    "uomId": "501",
    "primary": true
  },
  "errors": [],
  "meta": null
}
```

### curl

```bash
curl -s "https://erp.example.com/api/v1/products/barcode-lookup?companyId=7&barcode=5449000000996" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Notable errors

| Status | Cause |
|--------|-------|
| 400 | Missing `companyId` or `barcode` (both required). |
| 403 | Lacks `PRODUCT.VIEW`, or cannot act in `companyId`. |
| 404 | No barcode with that value in the company (`"Barcode not found in company: <value>"`). Treat as "unknown item — type/search manually". |

---

## 5. Units of measure master

```
GET /api/v1/units?companyId={id}&q={text}&page=&size=&sort=
```

- **Source:** `UnitOfMeasureController.list(...)`.
- **Required permission:** `UOM.VIEW` (`@perm.has('UOM.VIEW')`; seed description
  *"View units of measure"*). **Note this is a different permission from `PRODUCT.VIEW`** — a POS
  service account that lists units needs both.
- **Paged:** yes (`PageMeta` in `meta`).

Use this to build a `unitId → {code, symbol, decimalPlaces}` map for display and to translate the
product's `baseUnitUid` (a uid) into the numeric `unitId` the POS sale `LineItem` requires.

### Query params

| Param | Type | Required | Notes |
|-------|------|----------|-------|
| `companyId` | `Long` (string) | **Yes** | Company scope. |
| `q` | `String` | No | Free-text search over units (blank → all units for the company). |
| `page` / `size` / `sort` | — | No | Standard `Pageable`. |

### Response payload — `UnitOfMeasureDto`

| Field | Type | Meaning |
|-------|------|---------|
| `id` | `Long` (string) | **Numeric unit id → POS `LineItem.unitId`.** |
| `uid` | `String` | Unit uid (matches `ProductDto.baseUnitUid`). |
| `companyId` | `Long` (string) | Owning company. |
| `code` | `String` | Short code, e.g. `EA`, `KG`. |
| `name` | `String` | Display name, e.g. `Each`. |
| `symbol` | `String` | Symbol for receipts, e.g. `kg`. |
| `dimensionType` | enum `DimensionType` | `COUNT`, `WEIGHT`, `VOLUME`, `LENGTH`, or `TIME`. |
| `decimalPlaces` | `short` | Quantity precision to display/round to. |
| `fractional` | `boolean` | Whether fractional quantities are allowed (e.g. `KG` yes, `EA` typically no). |
| `status` | enum `MasterStatus` | `ACTIVE` / `INACTIVE` / `ARCHIVED`. |
| `version` | `Long` | Optimistic-lock version. |
| `createdAt`, `updatedAt` | `String` (ISO-8601) | Audit timestamps. |
| `createdBy`, `updatedBy` | `Long` (string) | Audit actors. |

### Get a single unit

```
GET /api/v1/units/uid/{uid}
```

- **Source:** `UnitOfMeasureController.get(...)`; permission `UOM.VIEW`; returns one
  `UnitOfMeasureDto` (envelope, `meta: null`). Handy for resolving a product's `baseUnitUid` to
  its numeric `id`.

### Success example (list)

```json
{
  "data": [
    {
      "id": "501",
      "uid": "uom_EA01",
      "companyId": "7",
      "code": "EA",
      "name": "Each",
      "symbol": "ea",
      "dimensionType": "COUNT",
      "decimalPlaces": 0,
      "fractional": false,
      "status": "ACTIVE",
      "version": "1",
      "createdAt": "2026-01-02T07:00:00Z",
      "createdBy": "1",
      "updatedAt": "2026-01-02T07:00:00Z",
      "updatedBy": "1"
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

### curl

```bash
curl -s "https://erp.example.com/api/v1/units?companyId=7&size=200" \
  -H "Authorization: Bearer $ACCESS_TOKEN"

curl -s "https://erp.example.com/api/v1/units/uid/uom_EA01" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Notable errors

| Status | Cause |
|--------|-------|
| 400 | Missing/uncoercible `companyId`. |
| 401 | Missing/expired token, or user no longer ACTIVE. |
| 403 | Lacks `UOM.VIEW`, or cannot act in `companyId`. |
| 404 | (`/uid/{uid}`) no unit with that uid. |

---

## Recommended POS catalog-load flow

1. On login / shift start, page through `GET /api/v1/units?companyId=…` and build a
   `unitUid → {id, code, symbol, decimalPlaces, fractional}` map.
2. Page through `GET /api/v1/products?companyId=…` (use `size` to reduce round-trips), keep only
   `sellable == true && status == "ACTIVE"`, and cache each product with its `id`,
   `baseUnitUid` (resolved to numeric `unitId` via the map), `vatStatus`, and `code`/`name` for
   search.
3. For carton/case selling, lazily fetch `GET /products/uid/{uid}/bulk-packs` per product as
   needed.
4. On a scan, call `GET /api/v1/products/barcode-lookup?companyId=…&barcode=…` → use `productId`
   (and `uomId` if present) to add the cart line.
5. Build the POS sale `LineItem` as `{ productId, unitId, quantity, unitPrice,
   lineDiscountAmount }` (all numeric ids as strings on the wire).

### Permissions summary

| Endpoint(s) | Permission |
|-------------|------------|
| `GET /products`, `GET /products/uid/{uid}`, `GET /products/uid/{uid}/bulk-packs`, `GET /products/barcode-lookup` | `PRODUCT.VIEW` |
| `GET /units`, `GET /units/uid/{uid}` | `UOM.VIEW` |

> A POS service account that both lists the catalog and resolves units must hold **both**
> `PRODUCT.VIEW` and `UOM.VIEW`. (Catalog writes — create/update/archive products and units,
> manage barcodes/prices — require `PRODUCT.MANAGE` / `UOM.MANAGE` and are out of scope for a
> read-only POS client.)

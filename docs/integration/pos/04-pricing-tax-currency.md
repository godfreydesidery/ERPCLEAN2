# Pricing, Tax & Currency

This section explains how an external POS client obtains the correct **price**, **tax (VAT)**, and **currency** for the items it sells, and — critically — **who computes the line and document totals**.

> **TL;DR — the server is authoritative.** When you call `POST /api/v1/pos/sales` (see section 03), the server resolves the unit price from the product's price row, resolves the VAT rate from the company's tax-rate table, and computes every line/header total itself. The `unitPrice` you send in each POS sale line is **ignored** by the sale path. The pricing/tax/currency endpoints below are **read endpoints** you use to *display* prices and let the cashier preview totals — they are not part of committing the sale.

All endpoints share the contract defined in section 01 (base path `/api/v1`, the `ApiResponse<T>` envelope, the error table, JWT bearer auth, the optional `X-Branch-Uid` scope header, and `page`/`size`/`sort` pagination). This section does not re-derive those; it only documents the pricing/tax/currency-specific surface.

`Long` ids are serialised as JSON **strings** by the global Jackson config (e.g. `"id": "42"`), so treat all `id` / `*Id` fields as opaque strings on the wire.

---

## 1. Big picture: how a POS line is priced and taxed

The POS sale path (`PosSaleServiceImpl.processSale`) builds each invoice line through the shared sales-invoice service (`SalesInvoiceServiceImpl.addLine`). For every line the server does exactly this, in order:

1. **Resolve the product** (by the product's `uid`, scoped to the session's company) and assert it is sellable.
2. **Snapshot the list price** — `resolveListPrice(product, companyId, currency)` looks up the product's `ProductPrice` row for that company and takes its amount. If the product has **no price row for the company**, the line fails (see errors below). This is the value stored as both the line's `listPriceAmount` and its initial `unitPriceAmount`.
3. **Snapshot the VAT rate** — `resolveVatRate(companyId, product)` reads the product's `vatStatus` (`STANDARD` / `ZERO_RATED` / `EXEMPT`) and looks up the company's `TaxRate` row for that `vatStatus`, taking its `rate`. `ZERO_RATED` / `EXEMPT` resolve to a 0% effective rate; `STANDARD` resolves to the maintained company rate (e.g. `0.1800` for 18% TZ VAT).
4. **Apply line discount** — the discount you sent as `lineDiscountAmount` on the POS line is recorded on the line (discount-before-VAT).
5. **Recompute totals** via `InvoiceTotalsCalculator` (see §6).

### What this means for the POS line you send

The POS sale `LineItem` (defined in section 03) has fields `productId`, `unitId`, `quantity`, `unitPrice`, `lineDiscountAmount`. Of these, **only `productId`, `unitId`, `quantity`, and `lineDiscountAmount` affect the committed sale.** In `processSale` the line is rebuilt as:

```java
var lineReq = new AddInvoiceLineRequest(
        product.getUid(), unit.getUid(),
        line.quantity(), line.lineDiscountAmount(), null);  // <-- line.unitPrice() is NOT passed
invoiceService.addLine(invoiceUid, lineReq);
```

`line.unitPrice()` is **never read** by the sale path. The server always uses its own resolved list price. (The non-POS sales-invoice flow has a separate `overrideLinePrice` endpoint gated by price-override permission, but the POS quick-sale endpoint exposes no such override — the cashier cannot change price through `POST /pos/sales`.)

**Consequence for the client:** call the read endpoints below to *show* the customer the price, but expect the 201 response's `SalesInvoiceDto` to carry the **server-computed** unit price, VAT, and totals. Always display the price list / tier / customer-price you fetched as a *preview*; treat the finalised invoice DTO returned by the sale as the source of truth for the receipt.

---

## 2. Price lists

**Controller:** `PriceListController` — `@RequestMapping("/api/v1/price-lists")`.

A price list is the master that groups product prices; it carries a currency, an effective date window, a "price includes VAT" flag, a default flag, and an applicability `scope`.

### 2.1 List price lists

`GET /api/v1/price-lists`

- **Permission:** `PRICELIST.VIEW` (`@perm.has`).
- **Query params:**
  - `companyId` (`Long`, **required**) — company to list for.
  - `q` (`String`, optional) — free-text filter.
  - `page`, `size`, `sort` — standard `Pageable` (paged; `meta` populated).
- **Response:** `List<PriceListDto>` in the envelope `data`, with `meta` = `PageMeta`.

`PriceListDto` fields (record `com.erp.modules.products.domain.dto.PriceListDto`):

| field | type | notes |
|---|---|---|
| `id` | string | price-list id (Long-as-string) |
| `uid` | string | stable uid; use in `/uid/{uid}` paths |
| `companyId` | string | owning company |
| `code` | string | |
| `name` | string | |
| `currency` | string | ISO 4217 code, may be null |
| `effectiveFrom` | date (`YYYY-MM-DD`) | nullable |
| `effectiveTo` | date | nullable |
| `priceIncludesVat` | boolean | whether listed prices are VAT-inclusive |
| `isDefault` | boolean | |
| `scope` | enum | `GLOBAL` \| `CUSTOMER` \| `BRANCH` \| `SEGMENT` (`PriceListScope`) |
| `status` | enum | `MasterStatus` (e.g. `ACTIVE` / `INACTIVE`) |
| `version` | string | optimistic-lock version |
| `createdAt` / `createdBy` / `updatedAt` / `updatedBy` | string / string | audit |

**Example success response:**

```json
{
  "data": [
    {
      "id": "12",
      "uid": "pl_7Q3K9",
      "companyId": "1",
      "code": "RETAIL",
      "name": "Retail Price List",
      "currency": "TZS",
      "effectiveFrom": "2026-01-01",
      "effectiveTo": null,
      "priceIncludesVat": false,
      "isDefault": true,
      "scope": "GLOBAL",
      "status": "ACTIVE",
      "version": "0",
      "createdAt": "2026-01-01T08:00:00Z",
      "createdBy": "3",
      "updatedAt": "2026-01-01T08:00:00Z",
      "updatedBy": "3"
    }
  ],
  "errors": [],
  "meta": { "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false }
}
```

**curl:**

```bash
curl -s "https://erp.example.com/api/v1/price-lists?companyId=1&page=0&size=20" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### 2.2 Get one price list

`GET /api/v1/price-lists/uid/{uid}`

- **Permission:** `PRICELIST.VIEW`.
- **Path var:** `uid`.
- **Response:** a single `PriceListDto` (wrapped in the envelope, `meta` null).

```bash
curl -s "https://erp.example.com/api/v1/price-lists/uid/pl_7Q3K9" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### 2.3 Write endpoints (admin — usually NOT used by a POS client)

The same controller also exposes `POST /api/v1/price-lists` (create, gated `PRICELIST.MANAGE` scoped to `companyUid`), `PUT /uid/{uid}` (update), `PUT /uid/{uid}/archive` and `PUT /uid/{uid}/restore` (both 204, `PRICELIST.MANAGE` scoped). A typical POS client only needs `PRICELIST.VIEW`; price-list maintenance is a back-office concern. They are noted here only for completeness.

**Notable errors (price-list reads):** `401` (missing/expired token), `403` (lacks `PRICELIST.VIEW` or company not in active scope), `404` (unknown `uid`), `400` (missing/uncoercible `companyId`).

---

## 3. Pricing rules — tiers, customer prices, promotions

**Controller:** `PricingRuleController` — `@RequestMapping("/api/v1/pricing-rules")`. Reads gated by `SALES.PRICING.RULE.VIEW`, writes by `SALES.PRICING.RULE.MANAGE` (seeded in `R__seed_permissions.sql`).

> **Important caveat:** these rules are **advisory display data** that a POS client can read to show tiered/contract pricing to the cashier. The POS sale path (`processSale` → `addLine` → `resolveListPrice`) resolves price from the product's `ProductPrice` row only — it does **not** automatically apply tiers, customer prices, or promotions when committing the sale. If you want a tier/customer-price/promotion price to take effect on the invoice, your client must read it here and reflect it as a `lineDiscountAmount` on the POS line (the one line field the server honours). Do not assume the server applies these rules on `POST /pos/sales`.

### 3.1 Price tiers (quantity breaks)

`GET /api/v1/pricing-rules/tiers`

- **Permission:** `SALES.PRICING.RULE.VIEW` (`@perm.has`).
- **Query params (all `Long`, all required):** `companyId`, `productId`, `priceListId`.
- **Response:** plain `List<PriceTierDto>` (not paged; `meta` null).

`PriceTierDto` fields: `id`, `uid`, `companyId`, `productId`, `priceListId`, `minQty` (decimal), `maxQty` (decimal, nullable), `unitPriceAmount` (decimal), `currency` (string), `status` (`MasterStatus`).

```json
{
  "data": [
    { "id": "55", "uid": "tier_aZ8", "companyId": "1", "productId": "200",
      "priceListId": "12", "minQty": "10", "maxQty": null,
      "unitPriceAmount": "950.0000", "currency": "TZS", "status": "ACTIVE" }
  ],
  "errors": [],
  "meta": null
}
```

```bash
curl -s "https://erp.example.com/api/v1/pricing-rules/tiers?companyId=1&productId=200&priceListId=12" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

`GET /api/v1/pricing-rules/tiers/uid/{uid}` returns one tier (`SALES.PRICING.RULE.VIEW`, scoped to the tier's company).

### 3.2 Customer-specific prices

`GET /api/v1/pricing-rules/customer-prices`

- **Permission:** `SALES.PRICING.RULE.VIEW`.
- **Query params (both `Long`, required):** `companyId`, `customerId`.
- **Response:** plain `List<CustomerPriceDto>`.

`CustomerPriceDto` fields: `id`, `uid`, `companyId`, `customerId`, `productId`, `unitPriceAmount` (decimal), `currency` (string), `effectiveFrom` (date, nullable), `effectiveTo` (date, nullable), `status`.

```bash
curl -s "https://erp.example.com/api/v1/pricing-rules/customer-prices?companyId=1&customerId=300" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

`GET /api/v1/pricing-rules/customer-prices/uid/{uid}` returns one (scoped `SALES.PRICING.RULE.VIEW`).

### 3.3 Promotions

`GET /api/v1/pricing-rules/promotions`

- **Permission:** `SALES.PRICING.RULE.VIEW`.
- **Query params:** `companyId` (`Long`, required) + standard `Pageable` (`page`/`size`/`sort`).
- **Response:** **paged** — `List<PromotionDto>` in `data`, `PageMeta` in `meta`.

`PromotionDto` fields: `id`, `uid`, `companyId`, `code`, `name`, `target` (`PromotionTarget`), `targetProductId` (nullable), `targetCategory` (nullable), `effect` (`PromotionEffect`, e.g. `PERCENT_DISCOUNT`), `effectValue` (decimal), `effectiveFrom`, `effectiveTo`, `priority` (short), `targetCustomerId` (nullable), `targetBranchId` (nullable), `minThreshold` (decimal, nullable), `usageLimit` (int, nullable), `couponCode` (nullable), `combinable` (boolean), `status`.

```bash
curl -s "https://erp.example.com/api/v1/pricing-rules/promotions?companyId=1&page=0&size=20" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

`GET /api/v1/pricing-rules/promotions/uid/{uid}` returns one (scoped `SALES.PRICING.RULE.VIEW`).

> Write/deactivate endpoints (`POST .../tiers`, `.../customer-prices`, `.../promotions`, and the `DELETE .../uid/{uid}` deactivations) exist on this controller, gated by `SALES.PRICING.RULE.MANAGE`, but are back-office operations a POS client normally will not hold.

**Notable errors (pricing-rule reads):** `400` (missing required query param such as `productId`), `403` (lacks `SALES.PRICING.RULE.VIEW` / outside active scope), `404` (unknown `uid`).

---

## 4. Tax rates & product VAT status

VAT on a POS line is the product of **the product's `vatStatus`** and **the company's `TaxRate` row for that status**.

### 4.1 List tax rates

**Controller:** `TaxRateController` — `@RequestMapping("/api/v1/tax-rates")`.

`GET /api/v1/tax-rates`

- **Permission:** `TAXRATE.VIEW` (`@perm.has`).
- **Query param:** `companyId` (`Long`, **required**).
- **Response:** plain `List<TaxRateDto>` (not paged; `meta` null).

`TaxRateDto` fields (record `com.erp.modules.sales.domain.dto.TaxRateDto`):

| field | type | notes |
|---|---|---|
| `id` | string | |
| `uid` | string | |
| `companyId` | string | |
| `name` | string | |
| `taxType` | enum | `TaxType`: `VAT` \| `WHT` \| `EXCISE` \| `OTHER` (defaults `VAT`) |
| `vatStatus` | enum | `VatStatus`: `STANDARD` \| `ZERO_RATED` \| `EXEMPT` |
| `rate` | decimal | fractional, `0.0000`–`0.9999` (e.g. `0.1800` = 18%) |
| `status` | enum | `MasterStatus` |
| `version` | string | |
| `createdAt`/`createdBy`/`updatedAt`/`updatedBy` | string/string | audit |

**Example success response:**

```json
{
  "data": [
    { "id": "1", "uid": "tax_std", "companyId": "1", "name": "Standard VAT",
      "taxType": "VAT", "vatStatus": "STANDARD", "rate": "0.1800",
      "status": "ACTIVE", "version": "0",
      "createdAt": "2026-01-01T08:00:00Z", "createdBy": "1",
      "updatedAt": "2026-01-01T08:00:00Z", "updatedBy": "1" },
    { "id": "2", "uid": "tax_zero", "companyId": "1", "name": "Zero-rated",
      "taxType": "VAT", "vatStatus": "ZERO_RATED", "rate": "0.0000",
      "status": "ACTIVE", "version": "0",
      "createdAt": "2026-01-01T08:00:00Z", "createdBy": "1",
      "updatedAt": "2026-01-01T08:00:00Z", "updatedBy": "1" }
  ],
  "errors": [],
  "meta": null
}
```

```bash
curl -s "https://erp.example.com/api/v1/tax-rates?companyId=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### 4.2 How VAT is resolved on a line (server-side)

For each line, `SalesInvoiceServiceImpl.resolveVatRate` reads the product's `vatStatus` and does `taxRates.findByCompanyIdAndVatStatus(companyId, vatStatus)` → takes `TaxRate.rate`:

- `STANDARD` → the company's standard `rate` (e.g. `0.1800`).
- `ZERO_RATED` → typically `0.0000` (still a taxable supply, 0%).
- `EXEMPT` → no VAT computed (effectively 0).

If a company has **no `TaxRate` row** for a product's `vatStatus`, the line fails with a `500` (`IllegalStateException`, "VAT rate not configured …"). In a correctly seeded company all three statuses are present, so as a POS client you do not configure this — you only read `/tax-rates` to display the rate.

The product's `vatStatus` itself is an attribute of the **product master** (field `Product.vatStatus`, default `STANDARD`); a POS client reads it from the product/catalog endpoints (see the catalog section), not from `/tax-rates`.

### 4.3 Update a tax rate (admin)

`PUT /api/v1/tax-rates/uid/{uid}` — body `UpdateTaxRateRequest { rate }` (`@NotNull`, `@DecimalMin("0.0000")`, `@DecimalMax("0.9999")`), gated `TAXRATE.MANAGE` (scoped to the tax-rate's company). Back-office only; listed for completeness.

**Notable errors (tax-rate reads):** `400` (missing `companyId`), `403` (lacks `TAXRATE.VIEW`), `404` (unknown `uid` on update). On update, a `rate` outside `[0.0000, 0.9999]` → `400`.

---

## 5. Enabled currencies (FX)

There are **two** FX controllers, both mapped under `@RequestMapping("/api/v1/fx")`:

- `CurrencyController` — the **currency master** (global reference list of all currencies) and exchange rates.
- `CurrencyEnablementController` — the **allow-list** of which currencies a given company/branch may use, plus the resolved default document currency.

For a POS client the most useful call is the **enabled list for the scope you sell in**.

### 5.1 Enabled currencies + resolved default for a scope

`GET /api/v1/fx/currencies/enabled`

- **Permission:** `CURRENCY.VIEW` (`@perm.has`).
- **Query params:**
  - `companyUid` (`String`, **required**).
  - `branchUid` (`String`, optional) — if omitted/blank, returns the **company-level** list; if a branch has no own rows it inherits the company list (same shape).
- **Response:** `EnabledCurrenciesDto { resolvedDefault, enabled }`:
  - `resolvedDefault` (string) — the resolved default document-currency code (branch default → company default → company base).
  - `enabled` (`List<String>`) — allowed ISO 4217 codes, alphabetical.

```json
{
  "data": {
    "resolvedDefault": "TZS",
    "enabled": ["KES", "TZS", "USD"]
  },
  "errors": [],
  "meta": null
}
```

```bash
# Company-level
curl -s "https://erp.example.com/api/v1/fx/currencies/enabled?companyUid=co_abc" \
  -H "Authorization: Bearer $ACCESS_TOKEN"

# Branch-level
curl -s "https://erp.example.com/api/v1/fx/currencies/enabled?companyUid=co_abc&branchUid=br_xyz" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Use `resolvedDefault` to pre-fill the `currency` field of your POS sale request, and use `enabled` to constrain the cashier's currency picker.

### 5.2 Currency master (all currencies)

`GET /api/v1/fx/currencies`

- **Permission:** `CURRENCY.VIEW`.
- **No params.**
- **Response:** `List<CurrencyDto>` of all **active** currencies (global reference data).

`CurrencyDto` fields: `id` (string), `uid` (string), `code` (string, ISO 4217), `name` (string), `symbol` (string), `minorUnits` (short), `numericCode` (string, ISO 4217 numeric, nullable), `active` (boolean), `status` (string).

```bash
curl -s "https://erp.example.com/api/v1/fx/currencies" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

> This list is the **global master** — every currency the system knows. To know which of these the POS may actually transact in, use `/fx/currencies/enabled` (§5.1), not this list. `GET /api/v1/fx/currencies/uid/{uid}` returns one currency.

### 5.3 Currency selection on a sale & the 422 trap

The POS sale request carries a `currency` field (see section 03). The sale is created with that currency, and the single CASH payment must match the header currency. If you send a currency **not enabled** for the session's company/branch scope, the FX layer raises `CurrencyNotEnabledException` → **HTTP 422 Unprocessable Entity** (per the shared error table, ADR-0039). **Always pick `currency` from the `enabled` list returned by §5.1** (defaulting to `resolvedDefault`) to avoid this.

> Note: per-line FX conversion is not something the POS computes — all line prices/VAT/totals are in the document currency. Rate maintenance lives on `CurrencyController` (`POST /api/v1/fx/rates`, `GET /api/v1/fx/rates`, gated `CURRENCY.MANAGE`/`CURRENCY.VIEW`) and is a back-office concern, not part of ringing a sale.

**Notable errors (currency reads):** `400` (missing `companyUid`), `403` (lacks `CURRENCY.VIEW` or company not in active scope; also a rejected `X-Branch-Uid`), `404` (unknown `companyUid` / `branchUid` resolution), `422` (currency not enabled — relevant on the sale, not the read).

---

## 6. Who computes the totals: the server

You do **not** send computed line totals or document totals to the sale endpoint, and the server does not trust any you might send. On every line mutation and at finalise, `InvoiceTotalsCalculator.recompute` runs the authoritative **tax-exclusive** algorithm (ADR-0008 D-4). Summarised:

1. **Per line, raw net:** `rawNet = round(unitPrice × quantity) − lineDiscount`, floored at 0. `lineDiscount` is the line's `lineDiscountAmount` if set (>0), else `unitPrice × quantity × lineDiscountPercent / 100`.
2. **Document discount** (if any) is apportioned pro-rata across lines by each line's raw net; the last line absorbs the rounding residual.
3. **Per line VAT:** `vat = round(discountedNet × vatRate)` (0 for `ZERO_RATED` / `EXEMPT`); `lineGross = discountedNet + vat`.
4. **Header:** `netTotal = Σ discountedNet`, `vatTotal = Σ vat`, `grossTotal = netTotal + vatTotal`.
5. **`tax_summary`:** lines grouped by `(vatStatus, vatRate)` into bands, summing net + VAT per band.

**Rounding:** `HALF_UP` at each boundary (per line, per band). The calculator rounds to **0 decimal places** (`SCALE = 0`) by default (TZS has no minor unit in practice), though storage is `NUMERIC(19,4)`. If your POS shows a local preview, mirror this algorithm exactly (round per line, discount before VAT, VAT per line) to match the receipt the server returns; otherwise just display the totals from the finalised `SalesInvoiceDto` in the 201 response.

The POS sale path then takes the server-computed `grossTotalAmount` and auto-adds a single full **CASH** payment for it before finalising — so you never compute the amount due either.

---

## 7. Recommended client flow for pricing

1. On catalog load, read **`GET /api/v1/tax-rates?companyId=…`** once and cache the rate per `vatStatus`; read product `vatStatus` from the catalog.
2. On scope/login, read **`GET /api/v1/fx/currencies/enabled?companyUid=…[&branchUid=…]`**; default the sale `currency` to `resolvedDefault`, restrict the picker to `enabled`.
3. To display unit prices, read the product's price (catalog) and, where applicable, **`GET /api/v1/pricing-rules/tiers|customer-prices|promotions`** for a richer preview. Reflect any negotiated reduction as a `lineDiscountAmount` on the POS line — the sale path will not auto-apply tiers/promotions.
4. Show a **local preview** of totals using the §6 algorithm if you need an instant subtotal, but treat the **finalised `SalesInvoiceDto`** returned by `POST /api/v1/pos/sales` (HTTP 201) as the authoritative figures for the printed receipt.

---

## 8. Permission summary

| Endpoint(s) | Required permission |
|---|---|
| `GET /api/v1/price-lists`, `GET /api/v1/price-lists/uid/{uid}` | `PRICELIST.VIEW` |
| `POST/PUT /api/v1/price-lists/**` (admin) | `PRICELIST.MANAGE` |
| `GET /api/v1/pricing-rules/tiers`, `/customer-prices`, `/promotions` (+ `/uid/{uid}`) | `SALES.PRICING.RULE.VIEW` |
| `POST/DELETE /api/v1/pricing-rules/**` (admin) | `SALES.PRICING.RULE.MANAGE` |
| `GET /api/v1/tax-rates` | `TAXRATE.VIEW` |
| `PUT /api/v1/tax-rates/uid/{uid}` (admin) | `TAXRATE.MANAGE` |
| `GET /api/v1/fx/currencies`, `GET /api/v1/fx/currencies/enabled`, `GET /api/v1/fx/currencies/uid/{uid}` | `CURRENCY.VIEW` |
| `POST /api/v1/fx/**` (rates / enablement) (admin) | `CURRENCY.MANAGE` |

A read-only POS client typically needs `PRICELIST.VIEW`, `SALES.PRICING.RULE.VIEW`, `TAXRATE.VIEW`, and `CURRENCY.VIEW` (plus the `POS.*` codes from section 03 to actually ring sales). Check the caller's effective codes via `GET /api/v1/auth/me` (see section 01).

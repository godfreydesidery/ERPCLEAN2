# POS supermarket P1 — backend build plan & estimate

**Implements:** [ADR-0044](../decisions/0044-pos-supermarket-readiness.md) P1 cluster (BR-9/10/11/13).
**Status:** plan (code not started). **Date:** 2026-06-20.
**Discipline:** additive-only / durable DB ([ADR-0043](../decisions/0043-schema-freeze-durable-db.md)) — every change is a new `V<n>` applied onto populated tables.

This is a code-grounded scope + dev-day estimate (senior backend engineer, **including unit + Testcontainers IT**) for the four P1 supermarket gaps. It is the implementation plan for ADR-0044's "legal + core grocery" wave.

## Headline

| Feature | Size | Dev-days | Confidence |
|---|---|---|---|
| Age-restricted item gate (BR-11 / D-3a) | S | 2 – 2.5 | high |
| Embedded weight/price barcodes (BR-9 / D-1a) | M | 4 – 6 | medium |
| Weighed goods (BR-13 / D-1b) | M | 3 – 5 | medium |
| Fiscal/EFD **harness** (BR-10 / D-3b) | M | 5 – 8 | high |
| **Total (net of shared work)** | — | **11 – 17** | medium |

> ⚠️ **The 11–17 days is the fiscal _harness_ (SPI + NoOp adapter + invoice columns + finalise hook), NOT a working Tanzania fiscal receipt.** The real **TRA VFD** external integration (device/certificate enrolment, per-receipt signing, sync-vs-async latency, offline retry, QR/verify-URL, void→fiscal-credit) is the **long pole** — a deliberately separate **timeboxed spike → its own ADR**, with an **uncommitted** date. Do not let a stakeholder read "fiscal: 5–8d" as "legal fiscal receipts in 8 days."

## Step 0 — assign the migration-version band up front (the #1 coordination risk)

HEAD is `V70`. All four feature scopes independently claimed `V71`; two concurrent branches both grabbing `V71` **breaks Flyway on merge to develop**. Assign the band centrally before any branch starts, and land the waves **one at a time** (not concurrently):

| Version | Migration | Owner wave |
|---|---|---|
| `V71` | `ALTER products` — `restricted_kind` (age) **+** `is_weighed` / `tare_weight` / `scale_rounding_mode[/_step]` (weighed). **One** additive ALTER serves both features. | Wave 1 |
| `V72` | `barcode_symbology_rules` table (per-company prefix/offset rules) + widen `product_barcodes` `chk_product_barcode_type` to add `EMBEDDED_WEIGHT`/`EMBEDDED_PRICE` (DROP-IF-EXISTS/ADD). | Wave 2 |
| `V73` | `ALTER sales_invoices` — additive **nullable** `fiscal_status`/`fiscal_provider`/`fiscal_receipt_number`/`fiscal_verification_code`/`fiscal_verify_url`/`fiscal_signed_at`/`fiscal_payload`(JSONB)/`fiscal_error` + `CHECK`. | Wave 3 |
| `V74` | `CREATE INDEX CONCURRENTLY ix_sales_invoices_fiscal_status … WHERE fiscal_status IN ('PENDING','FAILED')` — **own** non-transactional migration. | Wave 3 |

All are additive: `products.restricted_kind`/`is_weighed` ship with safe DEFAULTs (one-statement backfill), `sales_invoices.fiscal_*` are nullable — **no expand→backfill→constrain ceremony needed.** Also batch the `R__seed_permissions.sql` edits (three features touch it) in **one coordinated pass** — it's a repeatable-migration merge hotspot.

## Build sequence

### Wave 1 — Age-gate + the shared products migration  (~2 – 2.5d)
- `V71` ALTER products (lands **both** age + weighed columns).
- `Product.restrictedKind` enum (`NONE`/`AGE_18`/`AGE_21`); `ageVerified` ack field on `PosSaleRequest`; server check in `PosSaleServiceImpl.processSale` (the line loop, ~`:136-145`): a sale with a restricted line is **400/409** unless `ageVerified` or an override permission (`POS.SALE.AGE_OVERRIDE`).
- **Lays three shared assets the later waves reuse:** the products-ALTER slot, the `vatStatus`-style Product-DTO mapping template, and **`PermissionResolver` injection into `PosSaleServiceImpl`** (not injected today — confirmed no permission check in that service yet).
- *Slightly optimistic* (the override path + IT add a little) → plan ~2.5d.

### Wave 2 — Grocery item identification: embedded barcodes + weighed goods  (~6 – 9d combined)
Build adjacent — they share the **weight→quantity rounding** concern and the products-DTO plumbing from Wave 1.
- **Embedded barcodes** (`V72`, ~4–6d): per-company `barcode_symbology_rules`; new `BarcodeType` values; upgrade `barcode-lookup` (extending `ProductServiceImpl.lookupBarcode` ~`:348`) to: exact match (unchanged fast path) → else prefix-detect → decompose item-code + embedded value → resolve product → return derived **quantity** (weight) or **line amount** (price). *The real cost is the decode/check-digit/scaling matrix, not the table.* **Keep `ProductBarcodeDto` fields intact** (additive response) — the external client already calls this endpoint.
- **Weighed goods** (uses `V71` columns, ~3–5d): `is_weighed` flag, optional `tareWeight`, scale-division rounding; server validation at the sale-line path that a weighed product's line uses a **WEIGHT-dimension unit**, with tare subtracted and qty rounded. No new endpoints (rides existing product + sale endpoints). Price math already works (price/kg × qty via fractional WEIGHT unit).

### Wave 3 — Fiscal/EFD harness  (~5 – 8d)
- `V73` (nullable fiscal columns) + `V74` (concurrent retry index).
- **SPI:** `FiscalisationAdapter` (`providerKey`/`supports(ctx)`/`fiscalise(req)`) + `FiscalisationService` dispatcher (Spring injects `List<FiscalisationAdapter>`, selects by company regime) — **mirrors the existing `NotificationDispatcherCore` strategy-bean pattern** + `EmailSender` `@ConditionalOnBean` degrade-when-absent.
- **`NoOpFiscalisationAdapter`** (regime `NONE` → deterministic fake `SIGNED`) so cash-and-carry + existing tests stay green with **zero TRA access**.
- **One insertion point:** `SalesInvoiceServiceImpl.finalise` (~`:322-374`), after `FINALISED` + number assignment. POS inherits it automatically via `PosSaleServiceImpl` — no POS change.
- `failOpen` config + the `PENDING`/`FAILED` retry index hedge a slow device.

### Spike (separate, uncommitted) — TRA VFD real adapter → its own ADR
Timeboxed investigation of the external Tanzania VFD: auth/enrolment, **synchronous-in-finalise-TX vs async/outbox post-finalise signing** (likely async), sandbox access, certificate lifecycle, QR/verify-URL format, void→fiscal-credit, legal field set. Output: an ADR + a real adapter estimate. **Not in the 11–17 days.**

## Shared work (why it's 11–17, not a ~14–21 naive sum)
- **One** `V71` products ALTER serves age + weighed (~0.5d saved vs two migrations + two ITs).
- Product-DTO mapping (`restrictedKind`, `isWeighed`) is the **same** `vatStatus`-style template — build the second near-free.
- `PermissionResolver` injection into `PosSaleServiceImpl` is introduced once (Wave 1) and reused.
- `R__seed_permissions.sql` edits batched once (avoids the repeatable-seed merge hotspot).

## Schedule & cross-team risks
- **Fiscal/TRA-VFD is the long pole and is excluded from the committed estimate.** The working-TZ-fiscalisation date stays uncommitted pending the spike.
- **Migration-version collision** across parallel branches → assign the `V71–V74` band centrally; land waves serially.
- **External POS-client contract changes** (cross-team coupling, *not* in backend dev-days): embedded-barcode adds derived qty/amount to the lookup response; age-gate adds the `ageVerified` request field. The client must adopt these — coordinate.
- **Synchronous fiscalisation in the finalise TX** risks holding the transaction open on a slow device; `failOpen` + retry index hedge for NoOp, but the real VFD likely needs async/outbox signing (a Wave-3-vs-spike design call).
- A few **design decisions to lock before build:** rounding semantics (round-to-decimals vs round-to-step), tare storage (net-on-line vs gross+tare audit trail), barcode rule precedence on overlapping prefixes, and embedded check-digit variants.

## Recommendation
Build **Wave 1 → Wave 2 → Wave 3** serially (≈ 11–17 dev-days), **spike the TRA VFD separately**. Start with the **age-gate** — cheapest, fully independent, highest confidence, and it lays the shared `products` migration slot + DTO template + `PermissionResolver` injection the rest of the wave reuses. Ratify the per-item ADR-0044 directions and lock the design decisions above before kicking off.

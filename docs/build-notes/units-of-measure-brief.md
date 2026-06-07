# Units of Measure (UoM) master — build brief (hard cutover)

Introduce a per-company `units_of_measure` master and **hard-cut** the free-text unit fields to FKs.
Owner decisions: **New V4 migration** (V1/V2/V3 frozen); **replace** `products.base_unit` text with
`base_unit_id NOT NULL FK`; **replace** `product_bulk_packs.name` with `unit_id NOT NULL FK`; **seed
default units per company**. Mirror the `price_lists` master (per-company, user-supplied code) and the
just-hardened cross-tenant guards.

## Decisions
- Module: stays in `com.erp.modules.products` (units are a catalogue concern). Entity `UnitOfMeasure`.
- Table `units_of_measure` (plural master): id, uid VARCHAR(26), company_id FK, code VARCHAR(20),
  name VARCHAR(60), status VARCHAR(32), version, audit cols. Constraints:
  `uq_unit_of_measure_uid`, `uq_unit_of_measure_company_code UNIQUE(company_id, code)`,
  `fk_unit_of_measure_company`, `ix_units_of_measure_company`. Code is **user-supplied** (like price_lists),
  not auto-numbered.
- `products`: drop `base_unit`, add `base_unit_id BIGINT NOT NULL` + `fk_product_base_unit → units_of_measure(id)`.
- `product_bulk_packs`: drop `name`, add `unit_id BIGINT NOT NULL` + `fk_product_bulk_pack_unit`;
  change `uq_product_bulk_pack_name` → `uq_product_bulk_pack_unit UNIQUE(product_id, unit_id)`.
- Permissions: `UOM.VIEW`, `UOM.MANAGE` (module products), seeded + granted ORG_ADMIN in V4.
- ScopeGuard: add `case "unit" -> units.findCompanyIdByUid(uid)` + UnitOfMeasureRepository dep.
- Audit: `UOM.CREATE/UPDATE/ARCHIVE` (target_type `units_of_measure`).

## V4__units_of_measure.sql (additive, ordered)
1. `CREATE TABLE units_of_measure (...)` + constraints + `ix_units_of_measure_company`.
2. **Seed default units for every EXISTING company** (so old rows can be backfilled):
   `INSERT INTO units_of_measure (uid, company_id, code, name, status, version, created_at)
    SELECT <ulid>, c.id, u.code, u.name, 'ACTIVE', 0, now() FROM companies c CROSS JOIN (VALUES
    ('PCS','Pieces'),('BOX','Box'),('CARTON','Carton'),('CRATE','Crate'),('PACK','Pack'),
    ('KG','Kilogram'),('GRAM','Gram'),('LITRE','Litre'),('ML','Millilitre'),('DOZEN','Dozen'),
    ('PAIR','Pair'),('SET','Set'),('ROLL','Roll'),('BAG','Bag'),('BOTTLE','Bottle')) AS u(code,name);`
   NOTE: ULIDs can't be generated in pure SQL portably — if Flyway-time ULID is hard, seed with a
   deterministic 26-char value derived from company_id+code (e.g. left-pad), OR do the existing-company
   seed in a Java callback. Simplest: since the DB currently has only bootstrap data and likely ZERO
   products on a fresh/verify DB, prefer seeding defaults at **company-creation time in code** (see below)
   and have V4 seed existing companies via a one-off. Confirm there are no existing products to backfill
   on the target DBs (fresh + verify are empty; owner dev DB may have the 1 verify product — handle by
   reset, acceptable since unmerged).
3. **Backfill products.base_unit_id** from the seeded units by matching old `base_unit` text to a unit
   `code` (best-effort; if old text doesn't match a seeded code, map to PCS as a safe default), then:
   `ALTER TABLE products ADD COLUMN base_unit_id BIGINT;` → backfill → `ALTER ... SET NOT NULL;`
   → `ADD CONSTRAINT fk_product_base_unit ...;` → `ALTER TABLE products DROP COLUMN base_unit;`
4. Same shape for `product_bulk_packs`: add `unit_id`, backfill (no existing rows expected), set NOT NULL,
   add FK, drop `name`, swap the unique constraint.
5. Permission seed + ORG_ADMIN grant (mirror V3 block) for UOM.VIEW / UOM.MANAGE.

> If backfill matching is fiddly and the target DBs have no products/bulk-packs, it's acceptable to:
> add the new NOT NULL FK columns, seed units, and skip row backfill (no rows). Reset the verify/dev DB.
> State clearly in the migration comments what was assumed.

## New company → seed defaults (code path)
Find where a Company is created (BootstrapRunner + any CompanyService.create). After creating a company,
seed the same default unit set for it (so new companies aren't empty). Put the default list + seeding in
a small `UnitOfMeasureSeeder`/service method reused by both bootstrap and company-create. If company
self-service create doesn't exist yet, at least wire it into BootstrapRunner so a fresh bootstrap company
gets units.

## Backend (mirror PriceList)
- `UnitOfMeasure` entity (extends UidEntity), `UnitOfMeasureRepository` (findByUid, findByCompanyIdAndUid,
  existsByCompanyIdAndCode, findByCompanyId(Pageable), search, findCompanyIdByUid).
- `UnitOfMeasureService(+Impl)`: create/list/getByUid/update/archive/restore — **assertCanActIn on every
  read path** (list, getByUid) and resolve companyUid→id + assertCanActIn on create (the patched pattern).
- `UnitOfMeasureController` in com.erp.api: `/api/v1/units` ; gates `@perm.has('UOM.VIEW')` /
  `@perm.scoped(#request.companyUid,'company','UOM.MANAGE')` / `@perm.scoped(#uid,'unit','UOM.MANAGE')`.
- DTOs: `UnitOfMeasureDto`, `CreateUnitOfMeasureRequest(companyUid, code, name)`, `UpdateUnitOfMeasureRequest(name)`.
- **Product cutover**: CreateProductRequest/UpdateProductRequest take `baseUnitUid` (String) instead of
  `baseUnit`. ProductServiceImpl.create/update resolves the unit **scoped to the product's company**
  (`units.findByCompanyIdAndUid(companyId, baseUnitUid)` → NotFound if foreign — same cross-tenant guard
  as the F15 price-list fix). ProductDto exposes `baseUnitUid`, `baseUnitCode`, `baseUnitName` (enriched,
  like ProductPriceDto carries priceList code/name). ProductSummaryDto: show baseUnitCode.
- **Bulk pack cutover**: CreateBulkPackRequest takes `unitUid` (String) + factorToBase. addBulkPack
  resolves the unit scoped to the product's company. ProductBulkPackDto exposes unitUid/unitCode/unitName
  + factorToBase. Keep `findByUidAndProductId` for removeBulkPack (the F16 fix).

## Frontend
- Remove the hardcoded `commonUnits` arrays from product-list + product-detail.
- `unit.model.ts` + add unit methods to product.service.ts (or a unit.service.ts): listUnits(companyId,q),
  getByUid, create, update, archive/restore.
- Product create form (#newBaseUnit) + edit form (#fBaseUnit): replace the text input/datalist with a
  **<select>** populated from the company's units (listUnits). Bind to `baseUnitUid`. List/table column
  shows baseUnitCode.
- Bulk-pack add form (#newBulkPackName): replace text input with a **<select>** of units (unitUid);
  bulk-pack table shows unitCode + factorToBase.
- New **Units admin screen** (`units-of-measure-list.component`) mirroring price-list-list: list +
  create (code+name) + edit name + archive/restore. Route `/admin/units` gated `requirePermission('UOM.VIEW')`;
  nav entry under PRODUCTS group (icon e.g. bi-rulers), permission UOM.VIEW.

## Tests
- UnitOfMeasureServiceImplIT (mirror PriceListServiceImplIT): create/list/getByUid roundtrip;
  cross-company list → Forbidden; code unique per company.
- Update ProductServiceImplIT + PriceListServiceImplIT fixtures: products now need a baseUnitUid —
  create a default unit in setUp() (or use a seeded one) and pass its uid in goodsRequest(); bulk-pack
  tests pass a unitUid. Add: setBaseUnit_crossCompanyUnit_throwsNotFound (mirror F15);
  addBulkPack_crossCompanyUnit_throwsNotFound.
- Keep the F15/F16 regression tests green.
- Web: units-of-measure-list spec (mirror price-list spec); fix product-list/detail specs that referenced
  base unit as free text → now a select of units.

## Verify
Backend `mvn verify` all green; web `npm run build` + `npm test` green. Then browser re-verify the unit
dropdowns + units screen on the isolated stack.

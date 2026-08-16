package com.erp.modules.products.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.domain.dto.AddBarcodeRequest;
import com.erp.modules.products.domain.dto.AddComponentRequest;
import com.erp.modules.products.domain.dto.AssignProductBranchRequest;
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.EmbeddedBarcodeDecode;
import com.erp.modules.products.domain.dto.ProductBarcodeDto;
import com.erp.modules.products.domain.dto.ProductBranchDto;
import com.erp.modules.products.domain.dto.ProductBulkPackDto;
import com.erp.modules.products.domain.dto.ProductComponentDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.dto.ProductPriceDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.SetProductWeighingRequest;
import com.erp.modules.products.domain.dto.UpdateBulkPackRequest;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBarcode;
import com.erp.modules.products.domain.entity.ProductBranch;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductComponent;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.DimensionType;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.RestrictedKind;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.ProductBarcodeRepository;
import com.erp.modules.products.repository.ProductBranchRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductComponentRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product master administration (FR-PROD-01, ADR-0007).
 * Security: assertCanActIn on every read path (brief §3.1 — three patched findings).
 * Guards: ProductBranchGuard (BR-PROD-09), ProductCompositionGuard (BR-PROD-05/06).
 * Denormalisation invariant: barcode/price company_id always set from product.companyId.
 * UoM cutover: baseUnit resolved via units.findByCompanyIdAndUid (cross-tenant safe, brief §F15).
 */
@Service
@Transactional
public class ProductServiceImpl implements ProductService {

    private final ProductRepository products;
    private final ProductBranchRepository productBranches;
    private final ProductBulkPackRepository bulkPacks;
    private final ProductBarcodeRepository barcodes;
    private final ProductPriceRepository prices;
    private final ProductComponentRepository components;
    private final PriceListRepository priceLists;
    private final UnitOfMeasureRepository units;
    private final CompanyRepository companies;
    private final SupplierRepository suppliers;
    private final ProductCodeGenerator codeGen;
    private final ProductBranchGuard branchGuard;
    private final ProductCompositionGuard compositionGuard;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final BarcodeSymbologyRuleService symbologyRules;

    public ProductServiceImpl(ProductRepository products,
                              ProductBranchRepository productBranches,
                              ProductBulkPackRepository bulkPacks,
                              ProductBarcodeRepository barcodes,
                              ProductPriceRepository prices,
                              ProductComponentRepository components,
                              PriceListRepository priceLists,
                              UnitOfMeasureRepository units,
                              CompanyRepository companies,
                              SupplierRepository suppliers,
                              ProductCodeGenerator codeGen,
                              ProductBranchGuard branchGuard,
                              ProductCompositionGuard compositionGuard,
                              ScopeGuard scopeGuard,
                              AuditService audit,
                              BarcodeSymbologyRuleService symbologyRules) {
        this.products = products;
        this.productBranches = productBranches;
        this.bulkPacks = bulkPacks;
        this.barcodes = barcodes;
        this.prices = prices;
        this.components = components;
        this.priceLists = priceLists;
        this.units = units;
        this.companies = companies;
        this.suppliers = suppliers;
        this.codeGen = codeGen;
        this.branchGuard = branchGuard;
        this.compositionGuard = compositionGuard;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.symbologyRules = symbologyRules;
    }

    // -------------------------------------------------------------------------
    // Core CRUD
    // -------------------------------------------------------------------------

    @Override
    public ProductDto create(CreateProductRequest req) {
        // Resolve companyUid → id then assert scope (ADR-0007 D-12, brief §3 finding 1)
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // Defect 6b: SERVICE products cannot be stockable — clear message before DB CHECK fires.
        assertServiceNotStockable(req.type(), req.stockable());

        // Resolve baseUnitUid scoped to this company (cross-tenant safe — brief §F15 pattern)
        UnitOfMeasure baseUnit = resolveUnit(companyId, req.baseUnitUid());

        // Product name is unique per company (case-insensitive, trimmed, all statuses); the DB
        // index uq_product_company_name_ci is the backstop — fail fast with a friendly message.
        String nm = req.name() == null ? "" : req.name().trim();
        if (!nm.isEmpty() && products.existsByCompanyIdAndNormalizedName(companyId, nm)) {
            throw new ConflictException(
                    "A product with this name already exists in this company: " + nm);
        }

        // Code: optional user override (hybrid). Blank → auto-assign PROD-#### (FR-PROD-23);
        // a supplied value is trimmed/uppercased and must be unique per company (BR-PROD-08).
        // uq_product_company_code is the DB backstop against a concurrent duplicate.
        String code = resolveCode(companyId, req.code());
        Product p = new Product(companyId, code, req.name(), req.type(),
                req.sellable(), req.stockable(), baseUnit, actorId());
        p.setDescription(req.description());
        p.setCost(MoneyDto.toMoney(req.cost()));
        p.setVatStatus(req.vatStatus() != null ? req.vatStatus() : VatStatus.STANDARD);
        p.setRestrictedKind(req.restrictedKind() != null ? req.restrictedKind() : RestrictedKind.NONE);
        applyPlanningFields(p, companyId, req.reorderLevel(), req.reorderQty(), req.safetyStock(),
                req.minStock(), req.maxStock(), req.leadTimeDays(), req.purchasable(),
                req.preferredSupplierId());

        Product saved = products.save(p);
        audit.record(AuditEvent.of(AuditActions.PRODUCT_CREATE, "products",
                        saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(),
                        "type", saved.getType().name(),
                        "sellable", String.valueOf(saved.isSellable()),
                        "stockable", String.valueOf(saved.isStockable()))));
        return ProductDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDto getByUid(String uid) {
        // Security fix (finding 2): scope-check loaded entity's company
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        return ProductDto.from(p);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDto getById(Long id) {
        return products.findById(id)
                .map(ProductDto::from)
                .orElseThrow(() -> new NotFoundException("Product not found."));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductDto> list(Long companyId, String q, Pageable pageable) {
        // Security fix (finding 1): guard before querying
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return products.search(companyId, q, pageable).map(ProductDto::from);
        }
        return products.findByCompanyId(companyId, pageable).map(ProductDto::from);
    }

    @Override
    public ProductDto updateByUid(String uid, UpdateProductRequest req) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());

        // Defect 6b: SERVICE products cannot be stockable — clear message before DB CHECK fires.
        assertServiceNotStockable(req.type(), req.stockable());

        // Resolve baseUnitUid scoped to the product's company (cross-tenant safe)
        UnitOfMeasure previousBaseUnit = p.getBaseUnit();
        UnitOfMeasure baseUnit = resolveUnit(p.getCompanyId(), req.baseUnitUid());

        // ADR-0044 D-1b: a weighed product must keep a WEIGHT base unit. The /weighing endpoint
        // enforces this, but a plain product edit must not be able to drop a weighed product onto a
        // non-weight unit through the back door (leaving e.g. weighed=true on a PCS base).
        if (p.isWeighed() && baseUnit.getDimensionType() != DimensionType.WEIGHT) {
            throw new IllegalArgumentException(
                    "This product is sold by weight, so its base unit must be a weight unit such as "
                            + "kilograms. Unmark it as weighed before changing it to " + baseUnit.getName() + ".");
        }

        // Changing the base unit under existing prices silently breaks them, so refuse it while any
        // price row exists.
        //
        // Two things go wrong at once, and neither announces itself. A price amount is PER UNIT: a
        // row saying 20,000 was 20,000 per CARTON, and after the base unit becomes PIECE the same
        // number means 20,000 per piece. Re-keying it would therefore be worse than leaving it —
        // a wrong price prints on a receipt, where a missing one at least fails visibly. And the row
        // does not even survive as a usable base price: it stays keyed on the OLD unit, which the
        // resolver then cannot see (PriceResolutionServiceImpl step 1 skips the explicit lookup for
        // the base unit; step 2 wants unit_id IS NULL). That is how a shop rang 0.00 lines against a
        // priced product on 2026-08-16 with the price plainly visible in the back office.
        //
        // So the operator is asked to remove the prices first, which forces the one decision the
        // system cannot make for them: what the amount should be in the NEW unit. The resolver also
        // tolerates already-drifted rows on read, but that is repair, not licence to create more.
        //
        // Deliberately narrow: only when the base unit actually changes AND priced rows exist.
        // Every other product edit, including changing the base unit of an unpriced product, is
        // untouched — a shopkeeper fixing a data-entry slip on a new product must not be blocked.
        if (!previousBaseUnit.getId().equals(baseUnit.getId())
                && prices.existsByProductId(p.getId())) {
            throw new ConflictException(
                    "This product has prices, so its unit cannot be changed to "
                            + baseUnit.getName() + " — the existing prices are per "
                            + previousBaseUnit.getName() + " and would be wrong. Remove the prices, "
                            + "change the unit, then enter the prices again.");
        }

        String nm = req.name() == null ? "" : req.name().trim();
        if (!nm.isEmpty()
                && products.existsByCompanyIdAndNormalizedNameExcludingId(p.getCompanyId(), nm, p.getId())) {
            throw new ConflictException(
                    "A product with this name already exists in this company: " + nm);
        }

        p.setName(req.name());
        p.setDescription(req.description());
        p.setType(req.type());
        p.setSellable(req.sellable());
        p.setStockable(req.stockable());
        p.setBaseUnit(baseUnit);
        p.setCost(MoneyDto.toMoney(req.cost()));
        p.setVatStatus(req.vatStatus() != null ? req.vatStatus() : VatStatus.STANDARD);
        p.setRestrictedKind(req.restrictedKind() != null ? req.restrictedKind() : RestrictedKind.NONE);
        applyPlanningFields(p, p.getCompanyId(), req.reorderLevel(), req.reorderQty(),
                req.safetyStock(), req.minStock(), req.maxStock(), req.leadTimeDays(),
                req.purchasable(), req.preferredSupplierId());
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(actorId());

        List<String> warnings = baseUnitChangeWarnings(p, previousBaseUnit, baseUnit);
        AuditEvent event = AuditEvent.of(AuditActions.PRODUCT_UPDATE, "products", p.getId(), p.getUid());
        if (!warnings.isEmpty()) {
            // The advisory goes on the append-only audit trail too, so a base-unit change that a
            // client dropped on the floor is still answerable months later ("who re-based this
            // product, and when?"). Warnings are user-safe text — no internal detail.
            event = event.detail(Map.of(
                    "action", "BASE_UNIT_CHANGE",
                    "previousBaseUnitCode", previousBaseUnit != null ? previousBaseUnit.getCode() : "",
                    "newBaseUnitCode", baseUnit.getCode(),
                    "warnings", warnings));
        }
        audit.record(event);
        return ProductDto.from(p).withWarnings(warnings);
    }

    /**
     * Soft signal for a base-unit swap on an existing product (Kilimanjaro finding #1, second half).
     *
     * <p>Changing the base unit does NOT convert anything: {@code stock_on_hand.qty_on_hand},
     * {@code avg_cost}, {@code on_hand_value} and every {@code product_prices} amount stay as the
     * same numbers and are simply re-read against the new unit — 20 "boxes" silently become 20
     * "pieces". The API used to accept that in silence; now it says so.
     *
     * <p>Advisory, never a refusal: re-basing a product is a legitimate correction, and blocking it
     * would strand anyone who picked the wrong unit at creation.
     *
     * <p>The warning fires on ANY base-unit change rather than only when stock exists, because
     * quantity-on-hand lives in the stock module and this module may not read it (module-boundary
     * rule — products talks to stock only through DTOs/events). A conditional version needs a
     * sanctioned cross-module read; unconditional is the honest thing this side of the boundary,
     * and a base-unit change is rare and always consequential.
     */
    private static List<String> baseUnitChangeWarnings(Product p, UnitOfMeasure previousBaseUnit,
                                                       UnitOfMeasure newBaseUnit) {
        if (previousBaseUnit == null || newBaseUnit == null
                || previousBaseUnit.getId() == null
                || previousBaseUnit.getId().equals(newBaseUnit.getId())) {
            return List.of();
        }
        return List.of("The base unit of " + p.getName() + " changed from "
                + previousBaseUnit.getName() + " to " + newBaseUnit.getName()
                + ". Existing stock quantities, average cost and prices are NOT converted — every"
                + " figure already recorded will now be read as " + newBaseUnit.getName()
                + ". Check this product's stock on hand and prices before selling or buying it.");
    }

    @Override
    public void archiveByUid(String uid) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        MasterStatus prev = p.getStatus();
        p.setStatus(MasterStatus.ARCHIVED);
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PRODUCT_ARCHIVE, "products", p.getId(), p.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ARCHIVED.name())));
    }

    @Override
    public void restoreByUid(String uid) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        MasterStatus prev = p.getStatus();
        p.setStatus(MasterStatus.ACTIVE);
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PRODUCT_RESTORE, "products", p.getId(), p.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    // -------------------------------------------------------------------------
    // Branches
    // -------------------------------------------------------------------------

    @Override
    public ProductBranchDto assignBranch(String uid, AssignProductBranchRequest req) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(p.getCompanyId(), req.branchUid());

        if (productBranches.findByProductIdAndBranchId(p.getId(), branchId).isPresent()) {
            throw new ConflictException("Product is already associated with that branch.");
        }
        ProductBranch assoc = productBranches.save(new ProductBranch(p, branchId, actorId()));
        audit.record(AuditEvent.of(AuditActions.PRODUCT_BRANCH_ADD, "products", p.getId(), p.getUid())
                .detail(Map.of("branchUid", req.branchUid())));
        return ProductBranchDto.of(assoc.getBranchId(), assoc.getAssignedAt(), assoc.getAssignedBy());
    }

    @Override
    public void removeBranch(String uid, String branchUid) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(p.getCompanyId(), branchUid);
        productBranches.deleteByProductIdAndBranchId(p.getId(), branchId);
        audit.record(AuditEvent.of(AuditActions.PRODUCT_BRANCH_REMOVE, "products", p.getId(), p.getUid())
                .detail(Map.of("branchUid", branchUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductBranchDto> listBranches(String uid) {
        // Security fix (finding 3): scope-check before listing children
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        return productBranches.findByProductId(p.getId()).stream()
                .map(ProductBranchDto::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Bulk packs
    // -------------------------------------------------------------------------

    @Override
    public ProductBulkPackDto addBulkPack(String uid, CreateBulkPackRequest req) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());

        // Resolve unitUid scoped to the product's company (cross-tenant safe — brief addBulkPack_crossCompanyUnit)
        UnitOfMeasure unit = resolveUnit(p.getCompanyId(), req.unitUid());

        // The base unit is never a pack of itself. Such a row was accepted before, then ignored by
        // computeQtyInBase, listProductUnits and the till — it looked configured and did nothing.
        if (p.getBaseUnit() != null && unit.getId().equals(p.getBaseUnit().getId())) {
            throw new ConflictException(
                    unit.getName() + " is already the base unit of " + p.getName()
                            + ", so it can't also be a pack of it. Add a larger unit such as Box or"
                            + " Carton, and enter how many " + unit.getName() + " it holds.");
        }

        // A pack unit must be dimensionally sensible for the product: a discrete COUNT package
        // (Box/Bag/Carton) or the SAME physical dimension as the base unit (e.g. a KG pack of a
        // GRAM-based product). Reject a cross-dimension unit (e.g. a Litre pack of a weight product).
        DimensionType packDim = unit.getDimensionType();
        DimensionType baseDim = p.getBaseUnit().getDimensionType();
        if (packDim != DimensionType.COUNT && packDim != baseDim) {
            throw new IllegalArgumentException(
                    unit.getName() + " can't be a pack size for " + p.getName()
                            + ", which is measured by " + baseDim.name().toLowerCase()
                            + ". Use a count unit such as Box or Bag, or a "
                            + baseDim.name().toLowerCase() + " unit.");
        }

        // (product_id, unit_id) is UNIQUE. Probe it here so the operator is told WHICH unit clashes
        // and what it currently holds — the DB constraint alone surfaces as an opaque 409, which is
        // what made a wrong factor feel unfixable (re-adding to correct it looked like a dead end).
        bulkPacks.findByProductIdAndUnitId(p.getId(), unit.getId()).ifPresent(existing -> {
            throw new ConflictException(
                    unit.getName() + " is already a pack unit for this product (currently "
                            + plain(existing.getFactorToBase()) + ' ' + baseUnitName(p)
                            + "). Change its size instead of adding it again.");
        });

        List<String> warnings =
                packFactorWarnings(p, unit, req.factorToBase(), bulkPacks.findByProductId(p.getId()));

        ProductBulkPack bp = bulkPacks.save(new ProductBulkPack(p, unit, req.factorToBase(), actorId()));
        // factorToBase is recorded unconditionally: it used to appear only when a warning fired,
        // which left a silently-wrong factor untraceable after the fact.
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("action", "BULK_PACK_ADD");
        detail.put("unitUid", req.unitUid());
        detail.put("factorToBase", req.factorToBase().toPlainString());
        if (!warnings.isEmpty()) {
            detail.put("warnings", warnings);
        }
        audit.record(AuditEvent.of(AuditActions.PRODUCT_UPDATE, "products", p.getId(), p.getUid())
                .detail(detail));
        return ProductBulkPackDto.from(bp, warnings);
    }

    @Override
    public ProductBulkPackDto updateBulkPack(String productUid, String bulkPackUid,
                                             UpdateBulkPackRequest req) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // Resolve the child scoped to its parent product (same rule as remove — SR finding F16).
        ProductBulkPack bp = bulkPacks.findByUidAndProductId(bulkPackUid, p.getId())
                .orElseThrow(() -> new NotFoundException("BulkPack not found."));

        BigDecimal previous = bp.getFactorToBase();
        // Warn against the OTHER packs only — comparing a pack with itself is not a clash.
        List<ProductBulkPack> siblings = bulkPacks.findByProductId(p.getId()).stream()
                .filter(other -> !bulkPackUid.equals(other.getUid()))
                .toList();
        List<String> warnings = packFactorWarnings(p, bp.getUnit(), req.factorToBase(), siblings);

        bp.setFactorToBase(req.factorToBase());
        bp.setUpdatedAt(Instant.now());
        bp.setUpdatedBy(actorId());

        // Old AND new: a wrong factor is only diagnosable later if the trail shows what it replaced.
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("action", "BULK_PACK_UPDATE");
        detail.put("packUid", bulkPackUid);
        if (bp.getUnit() != null) {
            detail.put("unitUid", bp.getUnit().getUid());
        }
        detail.put("previousFactorToBase", plain(previous));
        detail.put("factorToBase", req.factorToBase().toPlainString());
        if (!warnings.isEmpty()) {
            detail.put("warnings", warnings);
        }
        audit.record(AuditEvent.of(AuditActions.PRODUCT_UPDATE, "products", p.getId(), p.getUid())
                .detail(detail));
        return ProductBulkPackDto.from(bp, warnings);
    }

    /**
     * Soft signal for a pack that holds LESS than one base unit (Kilimanjaro finding #1).
     *
     * <p>K4 added this check in the Angular product-detail screen, which protects exactly one
     * screen: the API still accepted {@code factorToBase = 0.0208333} on a Box of a piece-counted
     * product with a clean 201, and bulk import and any direct API call bypassed the warning
     * entirely. That factor is the classic inversion — the operator typed "1 piece is 1/48 of a
     * box" where the field means "1 box contains N pieces" — and it silently divides every
     * purchase, sale and stock figure booked in that pack by ~48.
     *
     * <p>Deliberately a WARNING, not a rejection. A sub-1 factor is legitimate whenever the pack is
     * a smaller measure of the same dimension (a 0.5 kg pack of a kg-based product), so a hard
     * {@code >= 1} rule would contradict ratified ADR-0007. The response and the audit trail carry
     * the signal; the pack is saved either way.
     *
     * <p>Also compares the factor against the product's OTHER packs ({@code siblings}, the pack
     * itself excluded on update). The client defect that prompted this — one OUTER deducting a
     * CARTON's 48 pieces — is invisible to a single-row check: 48 is a perfectly ordinary factor
     * until you notice the CARTON next to it already holds 48.
     */
    private static List<String> packFactorWarnings(Product p, UnitOfMeasure packUnit,
                                                   BigDecimal factorToBase,
                                                   List<ProductBulkPack> siblings) {
        if (factorToBase == null || factorToBase.signum() <= 0) {
            return List.of();
        }
        String baseUnitName = baseUnitName(p);
        List<String> warnings = new java.util.ArrayList<>();

        if (factorToBase.compareTo(BigDecimal.ONE) < 0) {
            StringBuilder message = new StringBuilder()
                    .append("A ").append(packUnit.getName()).append(" is being set to ")
                    .append(plain(factorToBase)).append(' ')
                    .append(baseUnitName).append(" — LESS than one ").append(baseUnitName)
                    .append(". This field means \"how many ").append(baseUnitName)
                    .append(" are in one ").append(packUnit.getName()).append("\".");
            // The inverse is what the operator almost certainly meant when the factor looks like 1/N.
            BigDecimal inverse = BigDecimal.ONE.divide(factorToBase, 6, RoundingMode.HALF_UP);
            message.append(" If one ").append(packUnit.getName()).append(" holds ")
                    .append(inverse.setScale(0, RoundingMode.HALF_UP).toPlainString()).append(' ')
                    .append(baseUnitName).append(", enter that instead.");
            if (packUnit.getDimensionType() == DimensionType.COUNT
                    && p.getBaseUnit() != null
                    && p.getBaseUnit().getDimensionType() == DimensionType.COUNT) {
                message.append(" A counted pack smaller than one counted item is almost always a"
                        + " typo — stock, costs and prices booked in this pack will be out by this"
                        + " factor.");
            } else {
                message.append(" Ignore this if the pack really is a fraction of the base unit.");
            }
            warnings.add(message.toString());
        }

        for (ProductBulkPack sibling : siblings) {
            BigDecimal siblingFactor = sibling.getFactorToBase();
            if (siblingFactor == null || siblingFactor.signum() <= 0) {
                continue;
            }
            String siblingName = sibling.getUnit() != null
                    ? sibling.getUnit().getName()
                    : "another pack";
            if (siblingFactor.compareTo(factorToBase) == 0) {
                warnings.add("A " + siblingName + " already holds " + plain(siblingFactor) + ' '
                        + baseUnitName + " on this product — is a " + packUnit.getName()
                        + " really " + plain(factorToBase) + ' ' + baseUnitName + " too? Two packs"
                        + " of the same size usually means one of the two factors is wrong.");
            } else if (!nests(siblingFactor, factorToBase)) {
                boolean newIsLarger = factorToBase.compareTo(siblingFactor) > 0;
                BigDecimal larger = newIsLarger ? factorToBase : siblingFactor;
                BigDecimal smaller = newIsLarger ? siblingFactor : factorToBase;
                String largerName = newIsLarger ? packUnit.getName() : siblingName;
                String smallerName = newIsLarger ? siblingName : packUnit.getName();
                warnings.add("A " + largerName + " holds " + plain(larger) + ' ' + baseUnitName
                        + " and a " + smallerName + " holds " + plain(smaller) + ' ' + baseUnitName
                        + " — so a " + largerName + " is not a whole number of " + smallerName
                        + " packs. Pack sizes normally nest; check both.");
            }
        }
        return List.copyOf(warnings);
    }

    /**
     * True when the two whole-number pack sizes nest (one is an exact multiple of the other).
     * Restricted to integers >= 1 on purpose: fractional same-dimension packs (0.5 kg / 0.75 kg of
     * a kg product) are legitimate and would otherwise warn on every combination.
     */
    private static boolean nests(BigDecimal a, BigDecimal b) {
        BigDecimal larger = a.max(b).stripTrailingZeros();
        BigDecimal smaller = a.min(b).stripTrailingZeros();
        if (larger.scale() > 0 || smaller.scale() > 0 || smaller.compareTo(BigDecimal.ONE) < 0) {
            return true;
        }
        return larger.remainder(smaller).signum() == 0;
    }

    private static String baseUnitName(Product p) {
        return p.getBaseUnit() != null ? p.getBaseUnit().getName() : "base unit";
    }

    /** Human-facing rendering of a factor: 48.000000 → "48", 0.0208333 → "0.0208333". */
    private static String plain(BigDecimal value) {
        return value == null ? "?" : value.stripTrailingZeros().toPlainString();
    }

    @Override
    public void removeBulkPack(String productUid, String bulkPackUid) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // Security: resolve the child scoped to its parent product (SR finding F16).
        ProductBulkPack bp = bulkPacks.findByUidAndProductId(bulkPackUid, p.getId())
                .orElseThrow(() -> new NotFoundException("BulkPack not found."));
        bulkPacks.delete(bp);
        audit.record(AuditEvent.of(AuditActions.PRODUCT_UPDATE, "products", p.getId(), p.getUid())
                .detail(Map.of("action", "BULK_PACK_REMOVE", "packUid", bulkPackUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductBulkPackDto> listBulkPacks(String uid) {
        // Security fix (finding 3)
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        return bulkPacks.findByProductId(p.getId()).stream()
                .map(ProductBulkPackDto::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnitOfMeasureDto> listProductUnits(String productUid) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());

        // Base unit is always first and always valid.
        java.util.LinkedHashMap<Long, UnitOfMeasureDto> result = new java.util.LinkedHashMap<>();
        UnitOfMeasure base = p.getBaseUnit();
        result.put(base.getId(), UnitOfMeasureDto.from(base));

        // Each ACTIVE bulk-pack unit follows; skip inactive/archived units and any that
        // duplicate the base unit.
        for (ProductBulkPack bp : bulkPacks.findByProductId(p.getId())) {
            UnitOfMeasure packUnit = bp.getUnit();
            if (packUnit.getStatus() == MasterStatus.ACTIVE) {
                result.putIfAbsent(packUnit.getId(), UnitOfMeasureDto.from(packUnit));
            }
        }

        return List.copyOf(result.values());
    }

    // -------------------------------------------------------------------------
    // Barcodes
    // -------------------------------------------------------------------------

    @Override
    public ProductBarcodeDto addBarcode(String uid, AddBarcodeRequest req) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // company_id denormalised at ProductBarcode construction — invariant enforced there
        ProductBarcode barcode = new ProductBarcode(p, req.barcode(), req.primary(), actorId());
        // Optionally bind the barcode to a specific sellable unit (base or a configured pack) so a
        // scan resolves to that pack size (ADR-0048 per-pack pricing) — persona finding: scanning a
        // 1kg-bag vs 2kg-bag barcode was previously indistinguishable.
        if (req.unitUid() != null && !req.unitUid().isBlank()) {
            UnitOfMeasure unit = resolveUnit(p.getCompanyId(), req.unitUid());
            assertSellableUnitForProduct(p, unit);
            barcode.setUomId(unit.getId());
        }
        barcode = barcodes.save(barcode);
        audit.record(AuditEvent.of(AuditActions.PRODUCT_BARCODE_ADD, "product_barcodes",
                        p.getId(), p.getUid())
                .detail(Map.of("barcode", req.barcode(), "isPrimary", String.valueOf(req.primary()),
                        "unitUid", String.valueOf(req.unitUid()))));
        return ProductBarcodeDto.from(barcode);
    }

    /**
     * Asserts {@code unit} is a valid transaction unit for {@code product}: its base unit or a
     * configured bulk-pack unit. Mirrors the sale-line rule so a barcode can only bind to a unit the
     * product can actually be sold in.
     */
    private void assertSellableUnitForProduct(Product product, UnitOfMeasure unit) {
        boolean isBase = unit.getId().equals(product.getBaseUnit().getId());
        boolean isPack = bulkPacks.findByProductId(product.getId()).stream()
                .anyMatch(bp -> bp.getUnit().getId().equals(unit.getId()));
        if (!isBase && !isPack) {
            throw new IllegalArgumentException(
                    unit.getName() + " is not a valid unit for " + product.getName()
                            + ". A barcode can only address the base unit or a configured pack unit.");
        }
    }

    @Override
    public void removeBarcode(String productUid, String barcodeUid) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // Security: resolve the child scoped to its parent product (SR finding F16).
        ProductBarcode barcode = barcodes.findByUidAndProductId(barcodeUid, p.getId())
                .orElseThrow(() -> new NotFoundException("Barcode not found."));
        barcodes.delete(barcode);
        audit.record(AuditEvent.of(AuditActions.PRODUCT_BARCODE_REMOVE, "product_barcodes",
                        p.getId(), p.getUid())
                .detail(Map.of("barcodeUid", barcodeUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductBarcodeDto> listBarcodes(String uid) {
        // Security fix (finding 3)
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        return barcodes.findByProductId(p.getId()).stream()
                .map(ProductBarcodeDto::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductBarcodeDto lookupBarcode(Long companyId, String barcode) {
        // Security fix (barcode lookup): constrain by active company — cross-tenant leak if not.
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // Fast path: exact-match against product_barcodes (covers standard EAN/UPC/CODE128).
        // Derived fields are null — backward-compatible for POS clients (ADR-0044 D-1a v1).
        java.util.Optional<com.erp.modules.products.domain.entity.ProductBarcode> exact =
                barcodes.findByCompanyIdAndBarcode(companyId, barcode);
        if (exact.isPresent()) {
            return ProductBarcodeDto.from(exact.get());
        }

        // Slow path: attempt embedded-weight/price decode via per-company symbology rules (BR-9).
        // Only reached when the exact-match 404s (variable-digit scale labels never exact-match).
        EmbeddedBarcodeDecode decode = symbologyRules.decode(companyId, barcode)
                .orElseThrow(() -> new NotFoundException("Barcode not found in company: " + barcode));

        // Resolve the product using the item_match strategy of the matched rule.
        // decode.itemCode() is the substring extracted from the scanned barcode.
        // We need the ProductBarcode row so the response DTO shape is consistent.
        // BARCODE  → look up product_barcodes by barcode value (the item-code barcode).
        // PRODUCT_CODE → look up products.code, then return its primary (or first) barcode row.
        com.erp.modules.products.domain.entity.ProductBarcode resolvedBarcode =
                resolveEmbeddedItemCode(companyId, barcode, decode);

        return ProductBarcodeDto.fromDecode(resolvedBarcode, decode);
    }

    /**
     * Resolves the item-code extracted from an embedded barcode to a ProductBarcode row.
     * Strategy: try BARCODE lookup first (item code is a stored barcode value), then fall back
     * to PRODUCT_CODE (item code is products.code). Both paths are O(index).
     * The original scanned barcode string is used in 404 messages for diagnostics.
     */
    private ProductBarcode resolveEmbeddedItemCode(Long companyId, String scannedBarcode,
                                                   EmbeddedBarcodeDecode decode) {
        // BARCODE path: item code is itself a barcode value stored on a product.
        var byBarcode = barcodes.findByCompanyIdAndBarcode(companyId, decode.itemCode());
        if (byBarcode.isPresent()) {
            return byBarcode.get();
        }
        // PRODUCT_CODE path: item code matches products.code; return primary/first barcode row.
        Product product = products.findByCompanyIdAndCode(companyId, decode.itemCode())
                .orElseThrow(() -> new NotFoundException(
                        "Product not found for embedded barcode: " + scannedBarcode));
        return barcodes.findByProductId(product.getId()).stream()
                .min(java.util.Comparator.comparing(b -> b.isPrimary() ? 0 : 1))
                .orElseThrow(() -> new NotFoundException(
                        "Product has no barcode row for embedded decode: " + scannedBarcode));
    }

    // -------------------------------------------------------------------------
    // Prices
    // -------------------------------------------------------------------------

    @Override
    public ProductPriceDto setPrice(String uid, SetProductPriceRequest req) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());

        // Security: resolve price list scoped to the product's company (SR finding F15).
        var priceList = priceLists.findByCompanyIdAndUid(p.getCompanyId(), req.priceListUid())
                .orElseThrow(() -> new NotFoundException("Price list not found."));

        // ADR-0048 D-1: null/blank unitUid (or a uid that resolves to the product's own base
        // unit) targets the base-unit row (unit_id IS NULL); any other uid must be a configured
        // product_bulk_pack unit for a per-unit (pack-specific) price row.
        UnitOfMeasure unit = resolvePriceUnit(p, req.unitUid());

        // upsert: one price per (product, price_list, unit) — base row when unit is null.
        ProductPrice pp = (unit == null
                ? prices.findByProductIdAndPriceListIdAndUnitIdIsNull(p.getId(), priceList.getId())
                : prices.findByProductIdAndPriceListIdAndUnitId(p.getId(), priceList.getId(), unit.getId()))
                .orElseGet(() -> new ProductPrice(p, priceList, unit, null, actorId()));

        pp.setPrice(MoneyDto.toMoney(req.price()));
        pp.setUpdatedAt(Instant.now());
        pp.setUpdatedBy(actorId());

        ProductPrice saved = prices.save(pp);
        audit.record(AuditEvent.of(AuditActions.PRODUCT_PRICE_SET, "product_prices", p.getId(), p.getUid())
                .detail(Map.of("priceListUid", req.priceListUid(),
                        "amount", req.price().amount(),
                        "currency", req.price().currency(),
                        "unit", unit == null ? "BASE" : "PACK")));
        return ProductPriceDto.from(saved);
    }

    @Override
    public void removePrice(String productUid, String priceListUid, String unitUid) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // Security: scope the price list to the product's company (SR finding F15).
        var priceList = priceLists.findByCompanyIdAndUid(p.getCompanyId(), priceListUid)
                .orElseThrow(() -> new NotFoundException("Price list not found."));

        Long unitId = resolveRemovalUnitId(p, unitUid);
        Optional<ProductPrice> pp = unitId == null
                ? prices.findByProductIdAndPriceListIdAndUnitIdIsNull(p.getId(), priceList.getId())
                : prices.findByProductIdAndPriceListIdAndUnitId(p.getId(), priceList.getId(), unitId);

        // Same drifted shape the resolver now tolerates: a row keyed on the base unit id rather than
        // on NULL, left behind by a base-unit change made before updateByUid started refusing them.
        // resolveRemovalUnitId coerces a base-unit uid to null exactly as the write path does, so
        // Remove looked for the NULL row, found nothing, and — because this is an ifPresent — did
        // nothing at all and said nothing. The row was unsellable AND unremovable: the one screen
        // that could have repaired it had a button that silently no-opped.
        if (pp.isEmpty() && unitId == null) {
            pp = prices.findByProductIdAndPriceListIdAndUnitId(
                    p.getId(), priceList.getId(), p.getBaseUnit().getId());
        }

        pp.ifPresent(price -> {
            prices.delete(price);
            audit.record(AuditEvent.of(AuditActions.PRODUCT_PRICE_REMOVE, "product_prices",
                            p.getId(), p.getUid())
                    .detail(Map.of("priceListUid", priceListUid,
                            "unit", unitId == null ? "BASE" : "PACK")));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductPriceDto> listPrices(String uid) {
        // Security fix (finding 3)
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        return prices.findByProductId(p.getId()).stream()
                .map(ProductPriceDto::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Weighed goods (ADR-0044 D-1b)
    // -------------------------------------------------------------------------

    @Override
    public ProductDto setWeighing(String uid, SetProductWeighingRequest req) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());

        // Range checks with friendly, field-name-free messages (error-hygiene rule).
        if (req.tareWeight() != null && req.tareWeight().signum() < 0) {
            throw new IllegalArgumentException("Tare weight cannot be negative.");
        }
        if (req.scaleStep() != null && req.scaleStep().signum() <= 0) {
            throw new IllegalArgumentException("Scale step must be greater than zero.");
        }
        if (req.maxSaleWeight() != null && req.maxSaleWeight().signum() <= 0) {
            throw new IllegalArgumentException("Maximum sale weight must be greater than zero.");
        }

        // A weighed product must be priced per weight-unit, so its base unit must be a WEIGHT unit
        // (e.g. kilograms). Validating here means the sale path can trust the flag; misconfiguration
        // is rejected at set-up time with a friendly, actionable message.
        if (req.weighed()
                && (p.getBaseUnit() == null || p.getBaseUnit().getDimensionType() != DimensionType.WEIGHT)) {
            throw new IllegalArgumentException(
                    "A weighed product must use a weight base unit such as kilograms. "
                            + "Set the product's base unit to a weight unit first, then mark it weighed.");
        }

        p.setWeighed(req.weighed());
        // Tare / scale-step / max-weight are only meaningful for a weighed product; clear them when
        // unmarking so a later re-mark starts clean and non-weighed rows never carry stray config.
        p.setTareWeight(req.weighed() ? req.tareWeight() : null);
        p.setScaleStep(req.weighed() ? req.scaleStep() : null);
        p.setMaxSaleWeight(req.weighed() ? req.maxSaleWeight() : null);
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PRODUCT_UPDATE, "products", p.getId(), p.getUid())
                .detail(Map.of(
                        "weighed", String.valueOf(req.weighed()),
                        "tareWeight", String.valueOf(p.getTareWeight()),
                        "scaleStep", String.valueOf(p.getScaleStep()),
                        "maxSaleWeight", String.valueOf(p.getMaxSaleWeight()))));
        return ProductDto.from(p);
    }

    // -------------------------------------------------------------------------
    // Components
    // -------------------------------------------------------------------------

    @Override
    public ProductComponentDto addComponent(String uid, AddComponentRequest req) {
        Product composed = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), composed.getCompanyId());

        Product component = Lookups.orNotFound(
                products.findByUid(req.componentProductUid()), "Product", req.componentProductUid());

        // BR-PROD-05 (archived) + BR-PROD-06 (same company)
        compositionGuard.assertCanAddComponent(composed, component);

        if (components.findByComposedProductIdAndComponentProductId(
                composed.getId(), component.getId()).isPresent()) {
            throw new ConflictException("Component is already part of this product's recipe.");
        }

        ProductComponent pc = components.save(
                new ProductComponent(composed, component, req.quantity(), actorId()));
        audit.record(AuditEvent.of(AuditActions.PRODUCT_COMPONENT_ADD, "product_components",
                        composed.getId(), composed.getUid())
                .detail(Map.of("componentUid", req.componentProductUid(),
                        "quantity", req.quantity().toPlainString())));
        return ProductComponentDto.from(pc);
    }

    @Override
    public void removeComponent(String composedUid, String componentUid) {
        Product composed = require(composedUid);
        scopeGuard.assertCanActIn(RequestContext.get(), composed.getCompanyId());

        Product component = Lookups.orNotFound(
                products.findByUid(componentUid), "Product", componentUid);

        components.findByComposedProductIdAndComponentProductId(composed.getId(), component.getId())
                .ifPresent(pc -> {
                    components.delete(pc);
                    audit.record(AuditEvent.of(AuditActions.PRODUCT_COMPONENT_REMOVE,
                                    "product_components", composed.getId(), composed.getUid())
                            .detail(Map.of("componentUid", componentUid)));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductComponentDto> listComponents(String uid) {
        // Security fix (finding 3)
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        return components.findByComposedProductId(p.getId()).stream()
                .map(ProductComponentDto::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Product require(String uid) {
        return Lookups.orNotFound(products.findByUid(uid), "Product", uid);
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
    }

    /**
     * Hybrid product code: a blank request code auto-assigns the next PROD-#### for the company
     * (FR-PROD-23); a supplied code is trimmed/uppercased and must be unique within the company
     * (BR-PROD-08). The uq_product_company_code constraint is the DB backstop against a race.
     */
    private String resolveCode(Long companyId, String requestedCode) {
        if (requestedCode == null || requestedCode.isBlank()) {
            return codeGen.next(companyId);
        }
        String code = requestedCode.trim().toUpperCase();
        if (products.existsByCompanyIdAndCode(companyId, code)) {
            throw new ConflictException("Product code already exists in this company: " + code);
        }
        return code;
    }

    /**
     * Resolve a UnitOfMeasure uid scoped to {@code companyId} — cross-tenant safe (brief §F15 pattern).
     * Throws NotFoundException (mapped to 404) if the uid belongs to a different company or doesn't exist.
     */
    private UnitOfMeasure resolveUnit(Long companyId, String unitUid) {
        return units.findByCompanyIdAndUid(companyId, unitUid)
                .orElseThrow(() -> new NotFoundException("Unit of measure not found."));
    }

    /**
     * Resolves {@code unitUid} to the unit a price row should be keyed on (ADR-0048 D-1 invariant).
     * Null/blank → {@code null} (the base-unit row, unit_id IS NULL). A uid that resolves to the
     * product's own base unit is coerced to {@code null} too — base prices always live on the NULL
     * row. Any other uid must be a configured {@code product_bulk_pack} unit for this product, else
     * a price for it could never be applied to a line (mirrors {@code computeQtyInBase}'s guard).
     */
    private UnitOfMeasure resolvePriceUnit(Product product, String unitUid) {
        if (unitUid == null || unitUid.isBlank()) {
            return null;
        }
        UnitOfMeasure unit = resolveUnit(product.getCompanyId(), unitUid);
        if (unit.getId().equals(product.getBaseUnit().getId())) {
            return null;
        }
        boolean configuredPack = bulkPacks.findByProductId(product.getId()).stream()
                .anyMatch(bp -> bp.getUnit().getId().equals(unit.getId()));
        if (!configuredPack) {
            throw new IllegalArgumentException(
                    "This unit is not configured for this product. Add it as a bulk pack first, "
                            + "or use the product's base unit.");
        }
        return unit;
    }

    /**
     * Resolves {@code unitUid} to the unit id a price row was keyed on, for removal — same base
     * coercion as {@link #resolvePriceUnit} but without the configured-pack check (a row simply
     * won't exist for a unit that was never a valid price target, so the lookup is a safe no-op).
     */
    private Long resolveRemovalUnitId(Product product, String unitUid) {
        if (unitUid == null || unitUid.isBlank()) {
            return null;
        }
        UnitOfMeasure unit = resolveUnit(product.getCompanyId(), unitUid);
        return unit.getId().equals(product.getBaseUnit().getId()) ? null : unit.getId();
    }

    /**
     * Applies D-10 planning + sourcing fields to a Product.
     * preferredSupplierId is validated to belong to the same company before being set.
     */
    private void applyPlanningFields(Product p, Long companyId,
                                     java.math.BigDecimal reorderLevel,
                                     java.math.BigDecimal reorderQty,
                                     java.math.BigDecimal safetyStock,
                                     java.math.BigDecimal minStock,
                                     java.math.BigDecimal maxStock,
                                     Integer leadTimeDays,
                                     Boolean purchasable,
                                     Long preferredSupplierId) {
        p.setReorderLevel(reorderLevel);
        p.setReorderQty(reorderQty);
        p.setSafetyStock(safetyStock);
        p.setMinStock(minStock);
        p.setMaxStock(maxStock);
        p.setLeadTimeDays(leadTimeDays);
        if (purchasable != null) {
            p.setPurchasable(purchasable);
        }
        if (preferredSupplierId != null) {
            suppliers.findById(preferredSupplierId).ifPresentOrElse(s -> {
                if (!s.getCompanyId().equals(companyId)) {
                    throw new com.erp.platform.common.api.NotFoundException(
                            "The selected supplier was not found in this company.");
                }
                p.setPreferredSupplierId(preferredSupplierId);
            }, () -> {
                throw new com.erp.platform.common.api.NotFoundException(
                        "Supplier not found.");
            });
        } else {
            p.setPreferredSupplierId(null);
        }
    }

    /**
     * Defect 6b: rejects a SERVICE product with stockable=true before the DB CHECK
     * ({@code chk_product_service_stockable}) fires a generic constraint error (BR-PROD-01).
     */
    private static void assertServiceNotStockable(ProductType type, boolean stockable) {
        if (ProductType.SERVICE.equals(type) && stockable) {
            // BR-PROD-01
            throw new IllegalArgumentException(
                    "Service products cannot be marked as stockable.");
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}

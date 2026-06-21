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
import com.erp.modules.products.domain.dto.ProductPriceDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBarcode;
import com.erp.modules.products.domain.entity.ProductBranch;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductComponent;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
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
        UnitOfMeasure baseUnit = resolveUnit(p.getCompanyId(), req.baseUnitUid());

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

        audit.record(AuditEvent.of(AuditActions.PRODUCT_UPDATE, "products", p.getId(), p.getUid()));
        return ProductDto.from(p);
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

        ProductBulkPack bp = bulkPacks.save(new ProductBulkPack(p, unit, req.factorToBase(), actorId()));
        audit.record(AuditEvent.of(AuditActions.PRODUCT_UPDATE, "products", p.getId(), p.getUid())
                .detail(Map.of("action", "BULK_PACK_ADD", "unitUid", req.unitUid())));
        return ProductBulkPackDto.from(bp);
    }

    @Override
    public void removeBulkPack(String productUid, String bulkPackUid) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // Security: resolve the child scoped to its parent product (SR finding F16).
        ProductBulkPack bp = bulkPacks.findByUidAndProductId(bulkPackUid, p.getId())
                .orElseThrow(() -> new NotFoundException("BulkPack not found: " + bulkPackUid));
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

    // -------------------------------------------------------------------------
    // Barcodes
    // -------------------------------------------------------------------------

    @Override
    public ProductBarcodeDto addBarcode(String uid, AddBarcodeRequest req) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // company_id denormalised at ProductBarcode construction — invariant enforced there
        ProductBarcode barcode = barcodes.save(new ProductBarcode(p, req.barcode(), req.primary(), actorId()));
        audit.record(AuditEvent.of(AuditActions.PRODUCT_BARCODE_ADD, "product_barcodes",
                        p.getId(), p.getUid())
                .detail(Map.of("barcode", req.barcode(), "isPrimary", String.valueOf(req.primary()))));
        return ProductBarcodeDto.from(barcode);
    }

    @Override
    public void removeBarcode(String productUid, String barcodeUid) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // Security: resolve the child scoped to its parent product (SR finding F16).
        ProductBarcode barcode = barcodes.findByUidAndProductId(barcodeUid, p.getId())
                .orElseThrow(() -> new NotFoundException("Barcode not found: " + barcodeUid));
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
                .orElseThrow(() -> new NotFoundException("PriceList not found: " + req.priceListUid()));

        // upsert: one price per (product, price_list)
        ProductPrice pp = prices.findByProductIdAndPriceListId(p.getId(), priceList.getId())
                .orElseGet(() -> new ProductPrice(p, priceList, null, actorId()));

        pp.setPrice(MoneyDto.toMoney(req.price()));
        pp.setUpdatedAt(Instant.now());
        pp.setUpdatedBy(actorId());

        ProductPrice saved = prices.save(pp);
        audit.record(AuditEvent.of(AuditActions.PRODUCT_PRICE_SET, "product_prices", p.getId(), p.getUid())
                .detail(Map.of("priceListUid", req.priceListUid(),
                        "amount", req.price().amount(),
                        "currency", req.price().currency())));
        return ProductPriceDto.from(saved);
    }

    @Override
    public void removePrice(String productUid, String priceListUid) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        // Security: scope the price list to the product's company (SR finding F15).
        var priceList = priceLists.findByCompanyIdAndUid(p.getCompanyId(), priceListUid)
                .orElseThrow(() -> new NotFoundException("PriceList not found: " + priceListUid));
        prices.findByProductIdAndPriceListId(p.getId(), priceList.getId())
                .ifPresent(pp -> {
                    prices.delete(pp);
                    audit.record(AuditEvent.of(AuditActions.PRODUCT_PRICE_REMOVE, "product_prices",
                                    p.getId(), p.getUid())
                            .detail(Map.of("priceListUid", priceListUid)));
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
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyUid));
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
                .orElseThrow(() -> new NotFoundException("UnitOfMeasure not found: " + unitUid));
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
                            "Supplier not found in this company: " + preferredSupplierId);
                }
                p.setPreferredSupplierId(preferredSupplierId);
            }, () -> {
                throw new com.erp.platform.common.api.NotFoundException(
                        "Supplier not found: " + preferredSupplierId);
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
            throw new IllegalArgumentException(
                    "Service products cannot be stockable (BR-PROD-01).");
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}

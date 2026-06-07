package com.erp.modules.products.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.dto.AddBarcodeRequest;
import com.erp.modules.products.domain.dto.AddComponentRequest;
import com.erp.modules.products.domain.dto.AssignProductBranchRequest;
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
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
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.ProductBarcodeRepository;
import com.erp.modules.products.repository.ProductBranchRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductComponentRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
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
    private final CompanyRepository companies;
    private final ProductCodeGenerator codeGen;
    private final ProductBranchGuard branchGuard;
    private final ProductCompositionGuard compositionGuard;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ProductServiceImpl(ProductRepository products,
                              ProductBranchRepository productBranches,
                              ProductBulkPackRepository bulkPacks,
                              ProductBarcodeRepository barcodes,
                              ProductPriceRepository prices,
                              ProductComponentRepository components,
                              PriceListRepository priceLists,
                              CompanyRepository companies,
                              ProductCodeGenerator codeGen,
                              ProductBranchGuard branchGuard,
                              ProductCompositionGuard compositionGuard,
                              ScopeGuard scopeGuard,
                              AuditService audit) {
        this.products = products;
        this.productBranches = productBranches;
        this.bulkPacks = bulkPacks;
        this.barcodes = barcodes;
        this.prices = prices;
        this.components = components;
        this.priceLists = priceLists;
        this.companies = companies;
        this.codeGen = codeGen;
        this.branchGuard = branchGuard;
        this.compositionGuard = compositionGuard;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    // -------------------------------------------------------------------------
    // Core CRUD
    // -------------------------------------------------------------------------

    @Override
    public ProductDto create(CreateProductRequest req) {
        // Resolve companyUid → id then assert scope (ADR-0007 D-12, brief §3 finding 1)
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String code = codeGen.next(companyId);
        Product p = new Product(companyId, code, req.name(), req.type(),
                req.sellable(), req.stockable(), req.baseUnit(), actorId());
        p.setDescription(req.description());
        p.setCost(MoneyDto.toMoney(req.cost()));

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

        p.setName(req.name());
        p.setDescription(req.description());
        p.setType(req.type());
        p.setSellable(req.sellable());
        p.setStockable(req.stockable());
        p.setBaseUnit(req.baseUnit());
        p.setCost(MoneyDto.toMoney(req.cost()));
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
        ProductBulkPack bp = bulkPacks.save(new ProductBulkPack(p, req.name(), req.factorToBase(), actorId()));
        audit.record(AuditEvent.of(AuditActions.PRODUCT_UPDATE, "products", p.getId(), p.getUid())
                .detail(Map.of("action", "BULK_PACK_ADD", "packName", req.name())));
        return ProductBulkPackDto.from(bp);
    }

    @Override
    public void removeBulkPack(String productUid, String bulkPackUid) {
        Product p = require(productUid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());
        ProductBulkPack bp = bulkPacks.findByUid(bulkPackUid)
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
        ProductBarcode barcode = barcodes.findByUid(barcodeUid)
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
        // Security fix (barcode lookup): constrain by active company — cross-tenant leak if not (brief §3.1)
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return barcodes.findByCompanyIdAndBarcode(companyId, barcode)
                .map(ProductBarcodeDto::from)
                .orElseThrow(() -> new NotFoundException("Barcode not found in company: " + barcode));
    }

    // -------------------------------------------------------------------------
    // Prices
    // -------------------------------------------------------------------------

    @Override
    public ProductPriceDto setPrice(String uid, SetProductPriceRequest req) {
        Product p = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), p.getCompanyId());

        var priceList = priceLists.findByUid(req.priceListUid())
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
        var priceList = priceLists.findByUid(priceListUid)
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

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}

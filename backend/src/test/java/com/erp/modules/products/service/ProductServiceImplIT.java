package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.dto.AddBarcodeRequest;
import com.erp.modules.products.domain.dto.AddComponentRequest;
import com.erp.modules.products.domain.dto.AssignProductBranchRequest;
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.ProductBarcodeDto;
import com.erp.modules.products.domain.dto.ProductBulkPackDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditLog;
import com.erp.platform.audit.AuditRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link ProductServiceImpl} against real Postgres (Testcontainers).
 *
 * <p>Covers: per-company PROD-#### code generation; cross-tenant list blocked; getByUid scope;
 * child-list scope; barcode-lookup scope; DB CHECK/unique fires; guards throw cross-company;
 * numbering per-company isolation; denormalisation invariant; audit written on create.
 * UoM cutover: goodsRequest() uses baseUnitUid; bulk-pack tests pass unitUid.
 * Regression: setBaseUnit_crossCompanyUnit, addBulkPack_crossCompanyUnit (brief §Tests).
 */
class ProductServiceImplIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private AuditRepository auditRepository;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company companyA;
    private Branch branchA;
    private Long rootId;
    /** A PCS unit seeded in companyA — used by goodsRequest(). */
    private String pcsUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("Prod Test Org"));
        companyA = companies.save(new Company(org, "PRCA", "Product Co A"));
        branchA = branches.save(new Branch(companyA, "PR-A1", "Prod Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "prod_root", passwordEncoder.encode("RootPass1!"), "Products Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyA.getId(), branchA.getId(), null));

        // Seed a default unit for companyA so goodsRequest() has a valid baseUnitUid (UoM cutover).
        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Code allocation: PROD-0001, PROD-0002
    // -----------------------------------------------------------------------

    @Test
    void create_product_codeIsProd0001AndFieldsRoundTrip() {
        ProductDto dto = productService.create(goodsRequest(companyA.getUid(), "Water Bottle"));

        assertThat(dto.code()).isEqualTo("PROD-0001");
        assertThat(dto.companyId()).isEqualTo(companyA.getId());
        assertThat(dto.type()).isEqualTo(ProductType.GOODS);
        assertThat(dto.sellable()).isTrue();
        assertThat(dto.stockable()).isTrue();
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);
        assertThat(dto.uid()).isNotBlank();
        // UoM enrichment
        assertThat(dto.baseUnitUid()).isEqualTo(pcsUid);
        assertThat(dto.baseUnitCode()).isEqualTo("PCS");
        assertThat(dto.baseUnitName()).isEqualTo("Pieces");
    }

    @Test
    void create_secondProduct_codeIsProd0002() {
        productService.create(goodsRequest(companyA.getUid(), "Item One"));
        ProductDto second = productService.create(goodsRequest(companyA.getUid(), "Item Two"));
        assertThat(second.code()).isEqualTo("PROD-0002");
    }

    // -----------------------------------------------------------------------
    // Hybrid code: optional user-supplied code (blank → auto), uppercased, unique per company
    // -----------------------------------------------------------------------

    @Test
    void create_withSuppliedCode_usesItUppercased() {
        ProductDto dto = productService.create(new CreateProductRequest(
                companyA.getUid(), "wtr-500", "Water 500ml", null,
                ProductType.GOODS, true, true, pcsUid, null, null, null, null, null, null, null, null, null, null, null));
        assertThat(dto.code()).isEqualTo("WTR-500");
    }

    @Test
    void create_withDuplicateSuppliedCode_throwsConflict() {
        productService.create(new CreateProductRequest(
                companyA.getUid(), "DUP-1", "First", null,
                ProductType.GOODS, true, true, pcsUid, null, null, null, null, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> productService.create(new CreateProductRequest(
                companyA.getUid(), "dup-1", "Second", null,
                ProductType.GOODS, true, true, pcsUid, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class);
    }

    // -----------------------------------------------------------------------
    // Per-company numbering isolation: different companies each get PROD-0001
    // -----------------------------------------------------------------------

    @Test
    void differentCompanies_eachProductGetsProd0001() {
        Company companyB = companies.save(new Company(org, "PRCB", "Product Co B"));
        Branch branchB = branches.save(new Branch(companyB, "PR-B1", "Prod Branch B1"));

        ProductDto prodA = productService.create(goodsRequest(companyA.getUid(), "A Item"));

        // Switch to companyB context and seed a unit for it
        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        UnitOfMeasureDto pcsB = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS", "Pieces"));
        ProductDto prodB = productService.create(
                new CreateProductRequest(companyB.getUid(), null, "B Item", null,
                        ProductType.GOODS, true, true, pcsB.uid(), null, null, null, null, null, null, null, null, null, null, null));

        assertThat(prodA.code()).isEqualTo("PROD-0001");
        assertThat(prodB.code()).isEqualTo("PROD-0001");
        assertThat(prodA.companyId()).isNotEqualTo(prodB.companyId());
    }

    // -----------------------------------------------------------------------
    // Cross-tenant read blocked: list must only return own-company products
    // -----------------------------------------------------------------------

    @Test
    void list_returnsOnlyOwnCompanyProducts() {
        Company companyB = companies.save(new Company(org, "PRCC", "Product Co C"));
        Branch branchB = branches.save(new Branch(companyB, "PR-C1", "Prod Branch C1"));

        productService.create(goodsRequest(companyA.getUid(), "A Product"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        UnitOfMeasureDto pcsB = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS", "Pieces"));
        productService.create(new CreateProductRequest(companyB.getUid(), null, "B Product", null,
                ProductType.GOODS, true, true, pcsB.uid(), null, null, null, null, null, null, null, null, null, null, null));

        // back to A context: list for A must see only A's product
        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyA.getId(), branchA.getId(), null));
        Page<ProductDto> pageA = productService.list(companyA.getId(), null, Pageable.unpaged());
        assertThat(pageA.getTotalElements()).isEqualTo(1);
        assertThat(pageA.getContent().get(0).name()).isEqualTo("A Product");
    }

    @Test
    void list_crossCompany_withNonRootToken_throwsForbidden() {
        Company companyB = companies.save(new Company(org, "PRCD", "Product Co D"));

        com.erp.modules.iam.domain.entity.AppUser userA =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "user_a_pr", passwordEncoder.encode("Pass1!"), "User A");
        userA = users.save(userA);

        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "user_a_pr", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> productService.list(companyB.getId(), null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // getByUid cross-company → 403 (finding 2)
    // -----------------------------------------------------------------------

    @Test
    void getByUid_crossCompany_throwsForbidden() {
        Company companyB = companies.save(new Company(org, "PRCE", "Product Co E"));
        Branch branchB = branches.save(new Branch(companyB, "PR-E1", "Prod Branch E1"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        UnitOfMeasureDto pcsB = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS", "Pieces"));
        ProductDto prodB = productService.create(new CreateProductRequest(
                companyB.getUid(), null, "B Product E", null, ProductType.GOODS, true, true, pcsB.uid(), null, null, null, null, null, null, null, null, null, null, null));

        com.erp.modules.iam.domain.entity.AppUser userA =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "user_a_pr2", passwordEncoder.encode("Pass1!"), "User A2");
        userA = users.save(userA);
        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "user_a_pr2", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> productService.getByUid(prodB.uid()))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // Child list cross-company → 403 (finding 3)
    // -----------------------------------------------------------------------

    @Test
    void listBarcodes_crossCompany_throwsForbidden() {
        Company companyB = companies.save(new Company(org, "PRCF", "Product Co F"));
        Branch branchB = branches.save(new Branch(companyB, "PR-F1", "Prod Branch F1"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        UnitOfMeasureDto pcsB = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS", "Pieces"));
        ProductDto prodB = productService.create(new CreateProductRequest(
                companyB.getUid(), null, "B Product F", null, ProductType.GOODS, true, true, pcsB.uid(), null, null, null, null, null, null, null, null, null, null, null));

        com.erp.modules.iam.domain.entity.AppUser userA =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "user_a_pr3", passwordEncoder.encode("Pass1!"), "User A3");
        userA = users.save(userA);
        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "user_a_pr3", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> productService.listBarcodes(prodB.uid()))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // Barcode lookup cross-company → 403
    // -----------------------------------------------------------------------

    @Test
    void lookupBarcode_crossCompany_throwsForbidden() {
        Company companyB = companies.save(new Company(org, "PRCG", "Product Co G"));

        com.erp.modules.iam.domain.entity.AppUser userA =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "user_a_pr4", passwordEncoder.encode("Pass1!"), "User A4");
        userA = users.save(userA);
        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "user_a_pr4", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> productService.lookupBarcode(companyB.getId(), "EAN-001"))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // DB CHECK: service type=SERVICE AND stockable=true → constraint violation
    // -----------------------------------------------------------------------

    @Test
    void create_serviceStockable_throwsConstraintViolation() {
        CreateProductRequest req = new CreateProductRequest(
                companyA.getUid(), null, "Repair Service", null, ProductType.SERVICE,
                true, true,  // stockable=true for a SERVICE → DB CHECK fails
                pcsUid, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> productService.create(req))
                .isInstanceOf(Exception.class);
    }

    // -----------------------------------------------------------------------
    // Branch guard: BR-PROD-09 — cross-company branch assign rejected
    // -----------------------------------------------------------------------

    @Test
    void assignBranch_sameCo_succeeds() {
        ProductDto dto = productService.create(goodsRequest(companyA.getUid(), "Branch Product"));
        productService.assignBranch(dto.uid(), new AssignProductBranchRequest(branchA.getUid()));

        assertThat(productService.listBranches(dto.uid())).hasSize(1);
    }

    @Test
    void assignBranch_differentCo_throwsForbidden_BR_PROD_09() {
        Company companyB = companies.save(new Company(org, "PRCH", "Product Co H"));
        Branch branchB = branches.save(new Branch(companyB, "PR-H1", "Prod Branch H1"));

        ProductDto dto = productService.create(goodsRequest(companyA.getUid(), "Guard Product"));

        assertThatThrownBy(() ->
                productService.assignBranch(dto.uid(), new AssignProductBranchRequest(branchB.getUid())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("BR-PROD-09");
    }

    // -----------------------------------------------------------------------
    // Composition guard: BR-PROD-06 — cross-company component rejected
    // -----------------------------------------------------------------------

    @Test
    void addComponent_differentCo_throwsForbidden_BR_PROD_06() {
        Company companyB = companies.save(new Company(org, "PRCI", "Product Co I"));
        Branch branchB = branches.save(new Branch(companyB, "PR-I1", "Prod Branch I1"));

        ProductDto composed = productService.create(goodsRequest(companyA.getUid(), "Composed A"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        UnitOfMeasureDto pcsB = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS", "Pieces"));
        ProductDto componentB = productService.create(new CreateProductRequest(
                companyB.getUid(), null, "Component B", null, ProductType.GOODS, true, true, pcsB.uid(), null, null, null, null, null, null, null, null, null, null, null));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyA.getId(), branchA.getId(), null));
        assertThatThrownBy(() ->
                productService.addComponent(composed.uid(),
                        new AddComponentRequest(componentB.uid(), new BigDecimal("1.0"))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("BR-PROD-06");
    }

    @Test
    void addComponent_archivedComponent_throwsIllegalArgument_BR_PROD_05() {
        ProductDto composed = productService.create(goodsRequest(companyA.getUid(), "Composed Arch"));
        ProductDto component = productService.create(goodsRequest(companyA.getUid(), "Component Arch"));

        productService.archiveByUid(component.uid());

        assertThatThrownBy(() ->
                productService.addComponent(composed.uid(),
                        new AddComponentRequest(component.uid(), new BigDecimal("1.0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-PROD-05");
    }

    @Test
    void addComponent_sameCompany_succeeds() {
        ProductDto composed = productService.create(goodsRequest(companyA.getUid(), "Ugali Meat"));
        ProductDto component = productService.create(goodsRequest(companyA.getUid(), "Ugali"));

        var componentDto = productService.addComponent(composed.uid(),
                new AddComponentRequest(component.uid(), new BigDecimal("1.000000")));

        assertThat(componentDto.composedProductId()).isEqualTo(composed.id());
        assertThat(componentDto.componentProductUid()).isEqualTo(component.uid());
        assertThat(componentDto.quantity()).isEqualByComparingTo(new BigDecimal("1.000000"));
    }

    // -----------------------------------------------------------------------
    // Denormalisation invariant: barcode company_id == product company_id
    // -----------------------------------------------------------------------

    @Test
    void addBarcode_companyIdMatchesProduct() {
        ProductDto prod = productService.create(goodsRequest(companyA.getUid(), "Barcode Product"));
        ProductBarcodeDto barcode = productService.addBarcode(prod.uid(),
                new AddBarcodeRequest("EAN-12345678", false));

        assertThat(barcode.companyId()).isEqualTo(prod.companyId());
    }

    // -----------------------------------------------------------------------
    // Audit: create writes log with PRODUCT.CREATE, target_type "products"
    // -----------------------------------------------------------------------

    @Test
    void create_writesAuditLog_withProductCreateAction() {
        ProductDto dto = productService.create(goodsRequest(companyA.getUid(), "Audited Product"));

        List<AuditLog> createLogs = auditRepository.findAll().stream()
                .filter(l -> AuditActions.PRODUCT_CREATE.equals(l.getAction()))
                .toList();
        assertThat(createLogs).hasSize(1);
        AuditLog log = createLogs.get(0);
        assertThat(log.getTargetType()).isEqualTo("products");
        assertThat(log.getActorUserId()).isEqualTo(rootId);
        assertThat(log.getTargetUid()).isEqualTo(dto.uid());
    }

    // -----------------------------------------------------------------------
    // Archive / restore status transitions
    // -----------------------------------------------------------------------

    @Test
    void archiveByUid_setsStatusArchived() {
        ProductDto created = productService.create(goodsRequest(companyA.getUid(), "Archive Me"));
        productService.archiveByUid(created.uid());

        ProductDto fetched = productService.getByUid(created.uid());
        assertThat(fetched.status()).isEqualTo(MasterStatus.ARCHIVED);
    }

    @Test
    void restoreByUid_setsStatusActive() {
        ProductDto created = productService.create(goodsRequest(companyA.getUid(), "Restore Me"));
        productService.archiveByUid(created.uid());
        productService.restoreByUid(created.uid());

        ProductDto fetched = productService.getByUid(created.uid());
        assertThat(fetched.status()).isEqualTo(MasterStatus.ACTIVE);
    }

    // -----------------------------------------------------------------------
    // Cost money round-trip
    // -----------------------------------------------------------------------

    @Test
    void create_withCost_costRoundTrips() {
        CreateProductRequest req = new CreateProductRequest(
                companyA.getUid(), null, "Costed Product", null, ProductType.GOODS,
                true, true, pcsUid, new MoneyDto("2500.00", "TZS"), null, null, null, null, null, null, null, null, null, null);

        ProductDto dto = productService.create(req);

        assertThat(dto.cost()).isNotNull();
        assertThat(new java.math.BigDecimal(dto.cost().amount()))
                .isEqualByComparingTo(new java.math.BigDecimal("2500.00"));
        assertThat(dto.cost().currency()).isEqualTo("TZS");
    }

    // -----------------------------------------------------------------------
    // Search: contains, case-insensitive
    // -----------------------------------------------------------------------

    @Test
    void search_byMidNameWord_matchesContains_caseInsensitive() {
        productService.create(goodsRequest(companyA.getUid(), "Mineral Water Bottle"));
        productService.create(goodsRequest(companyA.getUid(), "Orange Juice"));

        assertThat(productService.list(companyA.getId(), "Water", Pageable.unpaged()).getContent())
                .extracting(ProductDto::name).containsExactly("Mineral Water Bottle");
        assertThat(productService.list(companyA.getId(), "water", Pageable.unpaged()).getContent())
                .extracting(ProductDto::name).containsExactly("Mineral Water Bottle");
        assertThat(productService.list(companyA.getId(), "zzz", Pageable.unpaged()).getContent())
                .isEmpty();
    }

    @Test
    void search_byPartialCode_matchesContains_caseInsensitive() {
        productService.create(new CreateProductRequest(
                companyA.getUid(), "ZEB-777", "Striped Thing", null,
                ProductType.GOODS, true, true, pcsUid,
                null, null, null, null, null, null, null, null, null, null, null));
        productService.create(goodsRequest(companyA.getUid(), "Plain Thing"));

        // A lower-case fragment of the code must match (contains, case-insensitive) — the old
        // query only matched the code exactly, so partial-code lookups returned nothing.
        assertThat(productService.list(companyA.getId(), "zeb-7", Pageable.unpaged()).getContent())
                .extracting(ProductDto::name).containsExactly("Striped Thing");
    }

    @Test
    void search_byExactBarcode_matchesProduct() {
        ProductDto scanned = productService.create(goodsRequest(companyA.getUid(), "Scannable Item"));
        productService.addBarcode(scanned.uid(), new AddBarcodeRequest("6901234567890", false));
        productService.create(goodsRequest(companyA.getUid(), "No Barcode Item"));

        // Scanning the full barcode resolves the product — barcode was previously not searched at all.
        assertThat(productService.list(companyA.getId(), "6901234567890", Pageable.unpaged()).getContent())
                .extracting(ProductDto::uid).containsExactly(scanned.uid());
    }

    // -----------------------------------------------------------------------
    // Security regression — F15: price list must be same-company as the product
    // -----------------------------------------------------------------------

    @Test
    void setPrice_crossCompanyPriceList_throwsNotFound() {
        Company companyB = companies.save(new Company(org, "PRCG", "Product Co G"));
        branches.save(new Branch(companyB, "PR-G1", "Prod Branch G1"));

        ProductDto prodA = productService.create(goodsRequest(companyA.getUid(), "Priced A"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), null, null));
        PriceListDto plB = priceListService.create(
                new CreatePriceListRequest(companyB.getUid(), "RETAIL_G", "Retail G"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> productService.setPrice(prodA.uid(),
                new SetProductPriceRequest(plB.uid(), new MoneyDto("1000.00", "TZS"))))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);

        assertThat(productService.listPrices(prodA.uid())).isEmpty();
    }

    @Test
    void setPrice_sameCompanyPriceList_succeedsAndShowsListMetadata() {
        ProductDto prodA = productService.create(goodsRequest(companyA.getUid(), "Priced A2"));
        PriceListDto plA = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL_A2", "Retail A2"));

        productService.setPrice(prodA.uid(),
                new SetProductPriceRequest(plA.uid(), new MoneyDto("3000.00", "TZS")));

        var prices = productService.listPrices(prodA.uid());
        assertThat(prices).hasSize(1);
        assertThat(prices.get(0).priceListUid()).isEqualTo(plA.uid());
        assertThat(prices.get(0).priceListCode()).isEqualTo("RETAIL_A2");
    }

    // -----------------------------------------------------------------------
    // Security regression — F15 (UoM): baseUnit must be same-company
    // -----------------------------------------------------------------------

    @Test
    void create_crossCompanyUnit_throwsNotFound() {
        // Unit owned by companyB — must not be usable when creating a companyA product.
        Company companyB = companies.save(new Company(org, "PRCX", "Product Co X"));
        Branch branchB = branches.save(new Branch(companyB, "PR-X1", "Prod Branch X1"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        UnitOfMeasureDto foreignUnit = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "KG", "Kilogram"));

        // Back to companyA — try to use companyB's unit uid
        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> productService.create(new CreateProductRequest(
                companyA.getUid(), null, "Cross Unit Product", null,
                ProductType.GOODS, true, true, foreignUnit.uid(), null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // Security regression — F16: child remove must be scoped to the parent product
    // -----------------------------------------------------------------------

    @Test
    void removeBarcode_otherProductsBarcode_throwsNotFound() {
        ProductDto owner = productService.create(goodsRequest(companyA.getUid(), "Owner Prod"));
        ProductDto other = productService.create(goodsRequest(companyA.getUid(), "Other Prod"));
        ProductBarcodeDto otherBarcode = productService.addBarcode(other.uid(),
                new AddBarcodeRequest("EAN-OTHER-1", false));

        assertThatThrownBy(() -> productService.removeBarcode(owner.uid(), otherBarcode.uid()))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);

        assertThat(productService.listBarcodes(other.uid()))
                .extracting(ProductBarcodeDto::barcode).contains("EAN-OTHER-1");
    }

    @Test
    void removeBulkPack_otherProductsBulkPack_throwsNotFound() {
        ProductDto owner = productService.create(goodsRequest(companyA.getUid(), "Owner Prod BP"));
        ProductDto other = productService.create(goodsRequest(companyA.getUid(), "Other Prod BP"));
        ProductBulkPackDto otherPack = productService.addBulkPack(other.uid(),
                new CreateBulkPackRequest(pcsUid, new BigDecimal("12")));

        assertThatThrownBy(() -> productService.removeBulkPack(owner.uid(), otherPack.uid()))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);

        assertThat(productService.listBulkPacks(other.uid()))
                .extracting(ProductBulkPackDto::unitCode).contains("PCS");
    }

    // -----------------------------------------------------------------------
    // UoM cutover — addBulkPack cross-company unit must be rejected
    // -----------------------------------------------------------------------

    @Test
    void addBulkPack_crossCompanyUnit_throwsNotFound() {
        Company companyB = companies.save(new Company(org, "PRCY", "Product Co Y"));
        Branch branchB = branches.save(new Branch(companyB, "PR-Y1", "Prod Branch Y1"));

        // Seed a unit in companyB
        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        UnitOfMeasureDto foreignUnit = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "BOX", "Box"));

        // Back in companyA — create product then try to add bulk pack using companyB's unit
        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyA.getId(), branchA.getId(), null));
        ProductDto prod = productService.create(goodsRequest(companyA.getUid(), "Bulk Pack Product"));

        assertThatThrownBy(() -> productService.addBulkPack(prod.uid(),
                new CreateBulkPackRequest(foreignUnit.uid(), new BigDecimal("12"))))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // listProductUnits: base unit first, then bulk-pack units, de-duplicated
    // -----------------------------------------------------------------------

    @Test
    void listProductUnits_noPacksConfigured_returnsBaseUnitOnly() {
        ProductDto prod = productService.create(goodsRequest(companyA.getUid(), "Units-NoPack"));

        List<UnitOfMeasureDto> units = productService.listProductUnits(prod.uid());

        assertThat(units).hasSize(1);
        assertThat(units.get(0).uid()).isEqualTo(pcsUid);
    }

    @Test
    void listProductUnits_withOneBulkPack_returnsBaseUnitThenPackUnit() {
        ProductDto prod = productService.create(goodsRequest(companyA.getUid(), "Units-OnePack"));

        UnitOfMeasureDto carton = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "CTN", "Carton"));
        productService.addBulkPack(prod.uid(),
                new CreateBulkPackRequest(carton.uid(), new BigDecimal("12")));

        List<UnitOfMeasureDto> units = productService.listProductUnits(prod.uid());

        assertThat(units).hasSize(2);
        // Base unit is first.
        assertThat(units.get(0).uid()).isEqualTo(pcsUid);
        // Pack unit is second.
        assertThat(units.get(1).uid()).isEqualTo(carton.uid());
    }

    @Test
    void listProductUnits_baseUnitAlsoAddedAsPack_isNotDuplicated() {
        // Pathological case: someone adds the base unit as a bulk pack — must de-duplicate.
        ProductDto prod = productService.create(goodsRequest(companyA.getUid(), "Units-DedupPack"));
        productService.addBulkPack(prod.uid(),
                new CreateBulkPackRequest(pcsUid, new BigDecimal("1")));

        List<UnitOfMeasureDto> units = productService.listProductUnits(prod.uid());

        // pcsUid appears only once.
        assertThat(units).hasSize(1);
        assertThat(units.get(0).uid()).isEqualTo(pcsUid);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Creates a GOODS product request using companyA's seeded PCS unit (code auto-assigned). */
    private CreateProductRequest goodsRequest(String companyUid, String name) {
        return new CreateProductRequest(
                companyUid, null, name, null, ProductType.GOODS, true, true, pcsUid, null, null, null, null, null, null, null, null, null, null, null);
    }
}

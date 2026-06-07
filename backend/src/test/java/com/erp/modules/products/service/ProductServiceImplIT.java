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
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.ProductBarcodeDto;
import com.erp.modules.products.domain.dto.ProductDto;
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
 */
class ProductServiceImplIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
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
    }

    @Test
    void create_secondProduct_codeIsProd0002() {
        productService.create(goodsRequest(companyA.getUid(), "Item One"));
        ProductDto second = productService.create(goodsRequest(companyA.getUid(), "Item Two"));
        assertThat(second.code()).isEqualTo("PROD-0002");
    }

    // -----------------------------------------------------------------------
    // Per-company numbering isolation: different companies each get PROD-0001
    // -----------------------------------------------------------------------

    @Test
    void differentCompanies_eachProductGetsProd0001() {
        Company companyB = companies.save(new Company(org, "PRCB", "Product Co B"));
        Branch branchB = branches.save(new Branch(companyB, "PR-B1", "Prod Branch B1"));

        ProductDto prodA = productService.create(goodsRequest(companyA.getUid(), "A Item"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        ProductDto prodB = productService.create(goodsRequest(companyB.getUid(), "B Item"));

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
        productService.create(goodsRequest(companyB.getUid(), "B Product"));

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

        // Non-root principal locked to companyA
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

        // Create product in companyB
        RequestContext.set(new RequestContext.Principal(
                rootId, "prod_root", true, companyB.getId(), branchB.getId(), null));
        ProductDto prodB = productService.create(goodsRequest(companyB.getUid(), "B Product E"));

        // Switch to non-root companyA user
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
        ProductDto prodB = productService.create(goodsRequest(companyB.getUid(), "B Product F"));

        // Switch to non-root companyA user
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

        // Non-root user locked to companyA
        com.erp.modules.iam.domain.entity.AppUser userA =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "user_a_pr4", passwordEncoder.encode("Pass1!"), "User A4");
        userA = users.save(userA);
        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "user_a_pr4", false, companyA.getId(), branchA.getId(), null));

        // Try to look up barcode scoped to companyB
        assertThatThrownBy(() -> productService.lookupBarcode(companyB.getId(), "EAN-001"))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // DB CHECK: service type=SERVICE AND stockable=true → constraint violation
    // -----------------------------------------------------------------------

    @Test
    void create_serviceStockable_throwsConstraintViolation() {
        CreateProductRequest req = new CreateProductRequest(
                companyA.getUid(), "Repair Service", null, ProductType.SERVICE,
                true, true,  // stockable=true for a SERVICE → DB CHECK fails
                "unit", null);

        assertThatThrownBy(() -> productService.create(req))
                .isInstanceOf(Exception.class); // DB constraint violation
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
        ProductDto componentB = productService.create(goodsRequest(companyB.getUid(), "Component B"));

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
                companyA.getUid(), "Costed Product", null, ProductType.GOODS,
                true, true, "piece", new MoneyDto("2500.00", "TZS"));

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

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static CreateProductRequest goodsRequest(String companyUid, String name) {
        return new CreateProductRequest(
                companyUid, name, null, ProductType.GOODS, true, true, "piece", null);
    }
}

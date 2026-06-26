package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.purchases.domain.dto.CreatePurchaseRequisitionRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseRequisitionRequest.RequisitionLineRequest;
import com.erp.modules.purchases.domain.dto.PurchaseRequisitionDto;
import com.erp.modules.purchases.repository.PurchaseRequisitionRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression guard for the confused-deputy vulnerability fixed on
 * POST /api/v1/purchase-requisitions and POST /api/v1/rfqs (confirmed + structural, 2026-06-26).
 *
 * <p><b>Vulnerability:</b> {@code PurchaseRequisitionServiceImpl.create()} and
 * {@code RfqServiceImpl.create()} previously loaded line products/units via bare
 * {@code findById(id)} with no company-ownership check. A Company-A caller could supply
 * Company-B's numeric {@code productId}/{@code unitId} and receive the foreign company's
 * {@code code} and {@code name} in the 201 response AND persisted into Company-A's document
 * (cross-tenant data disclosure + cross-tenant write).
 *
 * <p><b>Fix:</b> both sites now use {@code findByCompanyIdAndId(companyId, id)}, which returns
 * {@code Optional.empty()} when the id belongs to a different company, causing a 404
 * {@link NotFoundException} with no existence leak (the error does not mention the foreign id).
 *
 * <p><b>Manual repro (live stack):</b>
 * <ol>
 *   <li>Log in as B_ADMIN (company B). Note the numeric ids of any product and unit.</li>
 *   <li>Log in as A_ADMIN (company A). POST /api/v1/purchase-requisitions with
 *       {@code companyUid}=A's own uid but {@code lines[0].productId}=B's product id and
 *       {@code lines[0].unitId}=B's unit id.</li>
 *   <li>Before fix: HTTP 201, response contains B's {@code productCode} and {@code productName}.
 *       After fix: HTTP 404.</li>
 * </ol>
 */
class PurchaseRequisitionTenantIsolationIT extends PostgresIntegrationTest {

    @Autowired private PurchaseRequisitionService    requisitionService;
    @Autowired private ProductService                productService;
    @Autowired private UnitOfMeasureService          unitService;
    @Autowired private PurchaseRequisitionRepository requisitionRepo;
    @Autowired private OrganisationRepository        organisations;
    @Autowired private CompanyRepository             companies;
    @Autowired private BranchRepository              branches;
    @Autowired private AppUserRepository             users;
    @Autowired private PasswordEncoder               passwordEncoder;
    @Autowired private IamTestData                   testData;
    @Autowired private EntityManager                 em;
    @Autowired private PlatformTransactionManager    txManager;

    private Company          companyA;
    private Branch           branchA;
    private Company          companyB;
    private Branch           branchB;
    private Long             rootId;

    /** Product and unit that belong exclusively to Company B — must never reach Company A. */
    private ProductDto       productB;
    private UnitOfMeasureDto unitB;

    /** Unit belonging to Company A — used to isolate individual guard assertions. */
    private UnitOfMeasureDto unitA;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        clearProcurementTables();

        Organisation org = organisations.save(new Organisation("TenantGuard IT Org"));
        companyA = companies.save(new Company(org, "TGCA", "TenantGuard Co A"));
        companyB = companies.save(new Company(org, "TGCB", "TenantGuard Co B"));
        branchA  = branches.save(new Branch(companyA, "TG-A1", "TenantGuard Branch A1"));
        branchB  = branches.save(new Branch(companyB, "TG-B1", "TenantGuard Branch B1"));

        AppUser root = new AppUser("tg_root", passwordEncoder.encode("RootPass1!"), "TenantGuard Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        // Company A baseline data
        asCompanyA();
        unitA = unitService.create(new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces A"));

        // Company B "secret" data — must not be readable by Company A
        asCompanyB();
        unitB    = unitService.create(new CreateUnitOfMeasureRequest(companyB.getUid(), "CRATE", "Crate B"));
        productB = productService.create(new CreateProductRequest(
                companyB.getUid(), null, "BSecret-widget", null,
                ProductType.GOODS, true, true, unitB.uid(), null,
                VatStatus.STANDARD, null, null, null, null, null, null, null, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Guard 1: Company-B productId injected into a Company-A requisition → 404
    // -------------------------------------------------------------------------

    @Test
    void createRequisition_withForeignCompanyProductId_throwsNotFound() {
        asCompanyA();
        long countBefore = requisitionRepo.count();

        assertThatThrownBy(() ->
                requisitionService.create(new CreatePurchaseRequisitionRequest(
                        companyA.getUid(), null, null, "cross-tenant product attempt",
                        List.of(new RequisitionLineRequest(
                                productB.id(),  // Company B's product — must be rejected
                                unitA.id(),     // Company A's own unit (isolates the product guard)
                                BigDecimal.ONE, null, null)))))
                .isInstanceOf(NotFoundException.class);

        // No header row must survive — the TX must have rolled back
        assertThat(requisitionRepo.count()).isEqualTo(countBefore);
    }

    // -------------------------------------------------------------------------
    // Guard 2: Company-B unitId injected into a Company-A requisition → 404
    // -------------------------------------------------------------------------

    @Test
    void createRequisition_withForeignCompanyUnitId_throwsNotFound() {
        asCompanyA();
        ProductDto productA = stockableProductInA("A-widget-for-unit-test");
        long countBefore = requisitionRepo.count();

        assertThatThrownBy(() ->
                requisitionService.create(new CreatePurchaseRequisitionRequest(
                        companyA.getUid(), null, null, "cross-tenant unit attempt",
                        List.of(new RequisitionLineRequest(
                                productA.id(),  // Company A's own product (isolates the unit guard)
                                unitB.id(),     // Company B's unit — must be rejected
                                BigDecimal.ONE, null, null)))))
                .isInstanceOf(NotFoundException.class);

        assertThat(requisitionRepo.count()).isEqualTo(countBefore);
    }

    // -------------------------------------------------------------------------
    // Guard 3: both ids from own company → succeeds, productName is A's own name
    // -------------------------------------------------------------------------

    @Test
    void createRequisition_withOwnCompanyIds_succeedsAndProductNameIsCorrect() {
        asCompanyA();
        ProductDto productA = stockableProductInA("A-widget-correct");

        PurchaseRequisitionDto result = requisitionService.create(
                new CreatePurchaseRequisitionRequest(
                        companyA.getUid(), null, null, "legitimate",
                        List.of(new RequisitionLineRequest(
                                productA.id(), unitA.id(), BigDecimal.TEN, null, null))));

        assertThat(result.lines()).hasSize(1);
        // Ensure the persisted name is A's product name, NOT the B-company secret name
        assertThat(result.lines().get(0).productName()).isEqualTo("A-widget-correct");
        assertThat(result.lines().get(0).productName()).doesNotContain("BSecret");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void asCompanyA() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "tg_root", true, companyA.getId(), branchA.getId(), null));
    }

    private void asCompanyB() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "tg_root", true, companyB.getId(), branchB.getId(), null));
    }

    private ProductDto stockableProductInA(String name) {
        asCompanyA();
        return productService.create(new CreateProductRequest(
                companyA.getUid(), null, name, null,
                ProductType.GOODS, true, true, unitA.uid(), null,
                VatStatus.STANDARD, null, null, null, null, null, null, null, null, null));
    }

    /**
     * Clears procurement tables not covered by {@link IamTestData#clearAll()} (which covers only
     * purchase_orders / goods_receipts). FK order: quote children → rfq children → requisition
     * children → headers; purchase_settings cleared so per-company unique constraint resets.
     */
    void clearProcurementTables() {
        // Native bulk TRUNCATEs require an active transaction; run them in a programmatic one
        // (an internal @Transactional call from setUp would bypass the Spring proxy).
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            em.createNativeQuery(
                    "TRUNCATE supplier_quote_lines, supplier_quotes, rfq_supplier, rfq_lines, rfqs " +
                    "RESTART IDENTITY CASCADE").executeUpdate();
            em.createNativeQuery(
                    "TRUNCATE purchase_requisition_lines, purchase_requisitions " +
                    "RESTART IDENTITY CASCADE").executeUpdate();
            em.createNativeQuery(
                    "TRUNCATE purchase_settings RESTART IDENTITY CASCADE").executeUpdate();
        });
    }
}

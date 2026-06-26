package com.erp.modules.manufacturing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.costing.domain.entity.Dimension;
import com.erp.modules.costing.domain.entity.DimensionValue;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.repository.DimensionRepository;
import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.manufacturing.domain.dto.CreateWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.UpdateWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;
import com.erp.modules.manufacturing.repository.WorkOrderRepository;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression tests for the confused-deputy tenant-isolation fix on WorkOrderServiceImpl.
 *
 * <p>Confirmed attack vectors (2026-06-26 audit):
 * <ul>
 *   <li>POST /api/v1/work-orders with {@code branchUid} belonging to Company B while the caller
 *       is scoped to Company A → the created WO would carry Company B's branchId (cross-tenant
 *       referential contamination + branch-existence oracle).
 *   <li>POST /api/v1/work-orders with {@code costCentreValueUid} belonging to Company B → created
 *       WO bound to Company B's dimension value (cross-tenant cost-allocation contamination).
 *   <li>Same gaps on PUT /api/v1/work-orders/uid/{uid} (update path).
 * </ul>
 *
 * <p>Each test asserts that the service throws {@link NotFoundException} (404, no existence leak)
 * and that no work order is persisted with a foreign-company branch or cost-centre value.
 *
 * <p><b>Manual repro (pre-fix):</b>
 * <ol>
 *   <li>Bootstrap two companies (A, id=4; B, id=5) with distinct branches.
 *   <li>Log in as Company-A admin. POST /api/v1/work-orders with {@code branchUid} = Company B's
 *       branch uid. Expect 201 before the fix; expect 404 after.
 *   <li>POST again with Company A's branch but {@code costCentreValueUid} = Company B's value uid.
 *       Same expectation.
 *   <li>Repeat both scenarios via PUT on an existing PLANNED WO.
 * </ol>
 */
class WorkOrderTenantIsolationIT extends PostgresIntegrationTest {

    @Autowired private WorkOrderService        workOrderService;
    @Autowired private WorkOrderRepository     workOrderRepository;

    @Autowired private ProductService          productService;
    @Autowired private UnitOfMeasureService    uomService;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private ManufacturingGlSeeder   manufacturingGlSeeder;

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    @Autowired private DimensionRepository     dimensions;
    @Autowired private DimensionValueRepository dimensionValues;

    // Company A (the caller's company)
    private Company companyA;
    private Branch  branchA;
    private Long    rootAId;
    private String  fgProductUid;
    private String  uomUid;

    // Company B (the foreign company whose resources must be rejected)
    private Branch  branchB;
    private String  foreignDimValueUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("IsoTestOrg"));

        // Company A — the legitimate caller
        companyA = companies.save(new Company(org, "CO_A", "Company A"));
        branchA  = branches.save(new Branch(companyA, "BRA", "Branch A"));

        AppUser rootA = new AppUser("isort_a", passwordEncoder.encode("IsoRoot2024!"), "Iso Root A");
        rootA.setRoot(true);
        rootA = users.save(rootA);
        rootAId = rootA.getId();

        RequestContext.set(new RequestContext.Principal(
                rootAId, "isort_a", true, companyA.getId(), branchA.getId(), null));

        // Seed GL for Company A (required by ManufacturingGlSeeder)
        chartOfAccountService.seedDefaults(companyA.getId());
        fiscalCalendarService.seedCurrentYear(companyA.getId());
        glConfigService.seedDefaults(companyA.getId());
        manufacturingGlSeeder.seedDefaults(companyA.getId());

        // Create UoM + finished-goods product under Company A
        uomUid = uomService.create(new CreateUnitOfMeasureRequest(
                companyA.getUid(), "PCS", "Pieces")).uid();

        fgProductUid = productService.create(new CreateProductRequest(
                companyA.getUid(), "FG_ISO", "Isolation FG", null,
                ProductType.GOODS, true, true, uomUid, null,
                VatStatus.EXEMPT, null, null, null, null, null, null, null, null, null)).uid();

        // Company B — attacker-controlled tenant
        Company companyB = companies.save(new Company(org, "CO_B", "Company B"));
        branchB = branches.save(new Branch(companyB, "BRB", "Branch B"));

        // Seed a Dimension + DimensionValue for Company B directly via repositories
        // (bypasses Company-B service layer; we need only the DB rows to test the lookup path)
        Dimension dimB = dimensions.save(
                new Dimension(companyB.getId(), DimensionSlot.COST_CENTRE,
                        "CC_B", "Cost Centre B", false, rootAId));
        DimensionValue dvB = dimensionValues.save(
                new DimensionValue(companyB.getId(), dimB.getId(),
                        "ZLEAKB", "Foreign CC Value", null, rootAId));
        foreignDimValueUid = dvB.getUid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
    }

    // -------------------------------------------------------------------------
    // create() — foreign branch
    // -------------------------------------------------------------------------

    @Test
    void create_withForeignBranchUid_throwsNotFound_noWoPersisted() {
        long countBefore = workOrderRepository.count();

        assertThatThrownBy(() -> workOrderService.create(new CreateWorkOrderRequest(
                fgProductUid,
                new BigDecimal("2"),
                branchB.getUid(),          // Company B's branch — must be rejected
                null,
                LocalDate.now().plusDays(1),
                null,
                null)))
                .isInstanceOf(NotFoundException.class);

        // No WO must have been persisted
        assertThat(workOrderRepository.count()).isEqualTo(countBefore);
    }

    // -------------------------------------------------------------------------
    // create() — foreign cost-centre value
    // -------------------------------------------------------------------------

    @Test
    void create_withForeignCostCentreValueUid_throwsNotFound_noWoPersisted() {
        long countBefore = workOrderRepository.count();

        assertThatThrownBy(() -> workOrderService.create(new CreateWorkOrderRequest(
                fgProductUid,
                new BigDecimal("2"),
                branchA.getUid(),           // own branch — OK
                null,
                LocalDate.now().plusDays(1),
                foreignDimValueUid,         // Company B's DimensionValue — must be rejected
                null)))
                .isInstanceOf(NotFoundException.class);

        assertThat(workOrderRepository.count()).isEqualTo(countBefore);
    }

    // -------------------------------------------------------------------------
    // update() — foreign branch
    // -------------------------------------------------------------------------

    @Test
    void update_withForeignBranchUid_throwsNotFound_woUnchanged() {
        // Arrange: create a valid PLANNED WO under Company A
        WorkOrderDto planned = workOrderService.create(new CreateWorkOrderRequest(
                fgProductUid, new BigDecimal("3"), branchA.getUid(),
                null, LocalDate.now().plusDays(2), null, null));

        Long branchIdBefore = workOrderRepository.findByUid(planned.uid())
                .orElseThrow().getBranchId();

        // Act + Assert: update with foreign branch must be rejected
        assertThatThrownBy(() -> workOrderService.update(
                planned.uid(),
                new UpdateWorkOrderRequest(null, null, branchB.getUid(), null, null, null)))
                .isInstanceOf(NotFoundException.class);

        // WO's branchId must remain unchanged (Company A's branch)
        Long branchIdAfter = workOrderRepository.findByUid(planned.uid())
                .orElseThrow().getBranchId();
        assertThat(branchIdAfter).isEqualTo(branchIdBefore);
    }

    // -------------------------------------------------------------------------
    // update() — foreign cost-centre value
    // -------------------------------------------------------------------------

    @Test
    void update_withForeignCostCentreValueUid_throwsNotFound_woUnchanged() {
        WorkOrderDto planned = workOrderService.create(new CreateWorkOrderRequest(
                fgProductUid, new BigDecimal("3"), branchA.getUid(),
                null, LocalDate.now().plusDays(2), null, null));

        Long ccBefore = workOrderRepository.findByUid(planned.uid())
                .orElseThrow().getCostCentreValueId();

        assertThatThrownBy(() -> workOrderService.update(
                planned.uid(),
                new UpdateWorkOrderRequest(null, null, null, null, foreignDimValueUid, null)))
                .isInstanceOf(NotFoundException.class);

        Long ccAfter = workOrderRepository.findByUid(planned.uid())
                .orElseThrow().getCostCentreValueId();
        assertThat(ccAfter).isEqualTo(ccBefore);
    }
}

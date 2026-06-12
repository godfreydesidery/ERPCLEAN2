package com.erp.modules.manufacturing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.gl.repository.JournalLineRepository;
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
import com.erp.modules.manufacturing.domain.dto.CloseWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.CompleteWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.CreateWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.IssueComponentsRequest;
import com.erp.modules.manufacturing.domain.dto.ReleaseWorkOrderRequest;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;
import com.erp.modules.manufacturing.domain.enums.WorkOrderStatus;
import com.erp.modules.manufacturing.repository.WorkOrderRepository;
import com.erp.modules.manufacturing.service.WipReconQuery;
import com.erp.modules.products.domain.dto.ActivateBomRequest;
import com.erp.modules.products.domain.dto.AddBomComponentRequest;
import com.erp.modules.products.domain.dto.CreateBomRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.BomComponentService;
import com.erp.modules.products.service.BomService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.stock.domain.dto.OpeningBalanceRequest;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockService;
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
 * Integration test for the core Manufacturing flow (ADR-0035):
 *   create WO → release (BOM pinned) → issue components (WIP GL) →
 *   complete (FG receipt at computed cost) → close (variance clear) →
 *   WIP recon ties.
 *
 * <p>Requires Docker/Testcontainers. Uses the singleton-container pattern (PostgresIntegrationTest).
 * compile-clean is the primary gate; full IT run is Docker-dependent.
 */
class WorkOrderServiceImplIT extends PostgresIntegrationTest {

    @Autowired private WorkOrderService        workOrderService;
    @Autowired private WorkOrderCostingService costingService;
    @Autowired private WipReconQuery           wipReconQuery;
    @Autowired private WorkOrderRepository     workOrderRepository;

    @Autowired private ProductService          productService;
    @Autowired private UnitOfMeasureService    uomService;
    @Autowired private BomService              bomService;
    @Autowired private BomComponentService     bomComponentService;
    @Autowired private InventoryValuationService valuationService;
    @Autowired private StockService            stockService;
    @Autowired private StockOnHandRepository   stockOnHandRepo;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private ManufacturingGlSeeder   manufacturingGlSeeder;

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  uomUid;
    private String  rawMatProductUid;
    private String  fgProductUid;
    private String  bomUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("MfgTestOrg"));
        company = companies.save(new Company(org, "MFG", "MfgCo"));
        branch  = branches.save(new Branch(company, "BR1", "Branch One"));

        AppUser root = new AppUser("mfgroot", passwordEncoder.encode("MfgRoot2024!"), "Mfg Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();

        // Set up GL for the company
        RequestContext.set(new RequestContext.Principal(
                rootId, "mfgroot", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        manufacturingGlSeeder.seedDefaults(company.getId());

        // Create UoM
        uomUid = uomService.create(new CreateUnitOfMeasureRequest(
                company.getUid(), "PCS", "Pieces")).uid();

        // Create raw material product
        rawMatProductUid = productService.create(new CreateProductRequest(
                company.getUid(), "RM001", "Raw Material One", null,
                ProductType.GOODS, true, true, uomUid, null, VatStatus.EXEMPT))
                .uid();

        // Create finished-goods product
        fgProductUid = productService.create(new CreateProductRequest(
                company.getUid(), "FG001", "Finished Good One", null,
                ProductType.GOODS, true, true, uomUid, null, VatStatus.EXEMPT))
                .uid();

        // Create and activate BOM: 2 units RM001 → 1 FG001
        bomUid = bomService.create(company.getId(), new CreateBomRequest(
                fgProductUid, BigDecimal.ONE, null, null, null)).uid();
        bomComponentService.add(bomUid, new AddBomComponentRequest(
                rawMatProductUid, new BigDecimal("2"), ComponentSourcing.BUY, null, null));
        bomService.activate(bomUid, new ActivateBomRequest(LocalDate.now()));

        // Seed opening inventory for RM001 at 1000 units @ 50.00
        // Step 1: create the SOH row via qty-only opening balance
        stockService.openingBalance(new OpeningBalanceRequest(rawMatProductUid, new BigDecimal("1000"), null));
        // Step 2: get the SOH uid and set the unit cost
        StockOnHand soh = stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(
                        company.getId(),
                        branch.getId(),
                        productService.getByUid(rawMatProductUid).id())
                .orElseThrow();
        valuationService.setOpeningValue(
                new SetOpeningValuationRequest(soh.getUid(), new BigDecimal("50.00")),
                LocalDate.now());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
    }

    @Test
    void fullCoreFlow_release_issue_complete_close_wipReconciles() {
        // 1. Create WO: plan 5 units of FG001
        WorkOrderDto created = workOrderService.create(new CreateWorkOrderRequest(
                fgProductUid, new BigDecimal("5"), branch.getUid(),
                null, LocalDate.now().plusDays(3), null, null));
        assertThat(created.status()).isEqualTo(WorkOrderStatus.PLANNED);
        assertThat(created.woNumber()).startsWith("WO-");

        // 2. Release — pins ACTIVE BOM automatically
        WorkOrderDto released = workOrderService.release(created.uid(), new ReleaseWorkOrderRequest(null));
        assertThat(released.status()).isEqualTo(WorkOrderStatus.RELEASED);
        assertThat(released.bomUid()).isNotNull();

        // 3. Issue components (2 RM001 × 5 = 10 units at avg_cost 50.00 = 500.00 total)
        WorkOrderDto issued = costingService.issueComponents(released.uid(),
                new IssueComponentsRequest(true, null, LocalDate.now()));
        assertThat(issued.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
        assertThat(issued.wipDebitTotal())
                .usingComparator(BigDecimal::compareTo)
                .isGreaterThan(BigDecimal.ZERO);

        // 4. Complete: 5 good units
        WorkOrderDto completed = costingService.complete(issued.uid(),
                new CompleteWorkOrderRequest(new BigDecimal("5"), null, false, LocalDate.now()));
        assertThat(completed.status()).isEqualTo(WorkOrderStatus.COMPLETED);
        assertThat(completed.computedUnitCost())
                .usingComparator(BigDecimal::compareTo)
                .isGreaterThan(BigDecimal.ZERO);

        // 5. Close (clear residual variance)
        WorkOrderDto closed = costingService.close(completed.uid(),
                new CloseWorkOrderRequest(LocalDate.now()));
        assertThat(closed.status()).isEqualTo(WorkOrderStatus.CLOSED);

        // 6. WIP reconciliation: computed open WIP = 0 (order is CLOSED, not in sumOpenWip window)
        //    The GL balance for WIP_INVENTORY should also be ~0 since all WIP was relieved.
        var recon = wipReconQuery.reconcile(company.getId());
        assertThat(recon.computed()).usingComparator(BigDecimal::compareTo).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(recon.ties()).isTrue();
    }
}

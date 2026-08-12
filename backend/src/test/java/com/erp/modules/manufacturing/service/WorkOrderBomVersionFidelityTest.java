package com.erp.modules.manufacturing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.manufacturing.domain.dto.IssueComponentsRequest;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;
import com.erp.modules.manufacturing.domain.entity.WorkOrder;
import com.erp.modules.manufacturing.domain.entity.WorkOrderComponent;
import com.erp.modules.manufacturing.repository.WorkOrderComponentRepository;
import com.erp.modules.manufacturing.repository.WorkOrderOperationRepository;
import com.erp.modules.manufacturing.repository.WorkOrderRepository;
import com.erp.modules.products.domain.dto.BomCostRollUpDto;
import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.entity.BomComponent;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.repository.BomComponentRepository;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.service.BomCostRollUpService;
import com.erp.modules.products.service.BomCostRollUpServiceImpl;
import com.erp.modules.products.service.BomExplosionServiceImpl;
import com.erp.modules.products.service.LeafCostResolver;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.LocationResolver;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.audit.AuditService;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * BOM version fidelity on a work order (UAT 2026-08-12, defect D2) and the roll-up ↔ work-order
 * agreement the investigation found nothing was asserting anywhere.
 *
 * <p>A work order pins a BOM version at release. Component materialisation then used to explode the
 * finished product's <em>ACTIVE</em> version instead of the pinned one, so activating a new version
 * between release and first issue made the factory consume one recipe while the order recorded
 * another.
 *
 * <p>Fixture (deliberately different components per version, so "which version" is readable off the
 * product id and the amount):
 * <pre>
 *   v1 (pinned at release, now superseded) : 1 FG from 2 × RM-A @ 10.00
 *   v2 (ACTIVE)                            : 1 FG from 5 × RM-B @ 100.00
 *   work order: 100 FG planned → v1 needs 200 RM-A = 2,000.0000
 * </pre>
 *
 * <p>Both services here are REAL ({@link BomExplosionServiceImpl}, {@link BomCostRollUpServiceImpl},
 * {@link WorkOrderCostingServiceImpl}) — mocking the explosion would mock away the very resolution
 * being tested.
 */
class WorkOrderBomVersionFidelityTest {

    private static final Long COMPANY_ID    = 10L;
    private static final Long BRANCH_ID     = 5L;
    private static final Long USER_ID       = 7L;
    private static final Long LOCATION_ID   = 500L;
    private static final Long WO_ID         = 900L;
    private static final Long FG_PRODUCT_ID = 1L;
    private static final Long RM_A_ID       = 55L;  // v1's only component
    private static final Long RM_B_ID       = 56L;  // v2's only component

    private static final String WO_UID     = "WOUID00000000000000000001";
    private static final String FG_UID     = "PRODFG0000000000000000001";
    private static final String BRANCH_UID = "BRANCH00000000000000000001";
    private static final String BOM_V1_UID = "BOMV1000000000000000000001";
    private static final String BOM_V2_UID = "BOMV2000000000000000000002";

    private static final BigDecimal HUNDRED     = BigDecimal.valueOf(100);
    private static final BigDecimal PLANNED_QTY = new BigDecimal("100");
    private static final BigDecimal RM_A_AVG_COST = new BigDecimal("10");
    private static final BigDecimal RM_B_AVG_COST = new BigDecimal("100");

    // ─── products-side collaborators ──────────────────────────────────────────
    private final BomRepository          bomsRepo     = mock(BomRepository.class);
    private final BomComponentRepository bomCompsRepo = mock(BomComponentRepository.class);
    private final ProductRepository      productsRepo = mock(ProductRepository.class);
    private final LeafCostResolver       leafCosts    = mock(LeafCostResolver.class);
    private final ScopeGuard             scopeGuard   = mock(ScopeGuard.class);
    private final BranchRepository       branchesRepo = mock(BranchRepository.class);

    private final BomExplosionServiceImpl explosion = new BomExplosionServiceImpl(
            bomsRepo, bomCompsRepo, productsRepo,
            mock(BomCostRollUpService.class), // only used by explode(withCost) — not exercised here
            scopeGuard, 20);

    private final BomCostRollUpServiceImpl rollUp = new BomCostRollUpServiceImpl(
            explosion, leafCosts, bomsRepo, productsRepo, branchesRepo, scopeGuard);

    // ─── manufacturing-side collaborators ─────────────────────────────────────
    private final WorkOrderRepository          workOrders  = mock(WorkOrderRepository.class);
    private final WorkOrderComponentRepository components  = mock(WorkOrderComponentRepository.class);
    private final WorkOrderOperationRepository operations  = mock(WorkOrderOperationRepository.class);
    private final StockPostingService          stockPosting = mock(StockPostingService.class);
    private final InventoryValuationService    valuation   = mock(InventoryValuationService.class);
    private final LocationResolver             locations   = mock(LocationResolver.class);
    private final ManufacturingGlPoster        glPoster    = mock(ManufacturingGlPoster.class);
    private final AuditService                 audit       = mock(AuditService.class);
    private final OutboxPublisher              outbox      = mock(OutboxPublisher.class);

    private final WorkOrderCostingServiceImpl costing = new WorkOrderCostingServiceImpl(
            workOrders, components, operations, explosion, stockPosting, valuation,
            locations, glPoster, scopeGuard, audit, outbox, branchesRepo);

    private WorkOrder workOrder;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Principal(
                USER_ID, "supervisor@test.com", false, COMPANY_ID, BRANCH_ID, null));
        doNothing().when(scopeGuard).assertCanActIn(any(), anyLong());

        // --- BOM versions: v1 is what the order pinned, v2 was activated afterwards -------------
        Bom v1 = bom(101L, BOM_V1_UID, 1, BomStatus.ARCHIVED);
        Bom v2 = bom(102L, BOM_V2_UID, 2, BomStatus.ACTIVE);
        when(bomsRepo.findByUid(BOM_V1_UID)).thenReturn(Optional.of(v1));
        when(bomsRepo.findByUid(BOM_V2_UID)).thenReturn(Optional.of(v2));
        when(bomsRepo.findByParentProductIdAndStatus(FG_PRODUCT_ID, BomStatus.ACTIVE))
                .thenReturn(Optional.of(v2));
        when(bomCompsRepo.findByBomIdOrderByLineNo(101L))
                .thenReturn(List.of(component(101L, RM_A_ID, "RM-A", "Raw A", new BigDecimal("2"))));
        when(bomCompsRepo.findByBomIdOrderByLineNo(102L))
                .thenReturn(List.of(component(102L, RM_B_ID, "RM-B", "Raw B", new BigDecimal("5"))));

        Product fg = product(FG_PRODUCT_ID, FG_UID);
        when(productsRepo.findByUid(FG_UID)).thenReturn(Optional.of(fg));
        when(productsRepo.findById(FG_PRODUCT_ID)).thenReturn(Optional.of(fg));
        Map<Long, String> leafUids = Map.of(
                RM_A_ID, "PRODRMA000000000000000001",
                RM_B_ID, "PRODRMB000000000000000002");
        when(productsRepo.findAllById(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            List<Product> found = new ArrayList<>();
            ids.forEach(id -> found.add(product(id, leafUids.get(id))));
            return found;
        });

        // --- branch: one branch, one location, shared by the roll-up and the work order ---------
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        Branch branch = mock(Branch.class);
        when(branch.getCompany()).thenReturn(company);
        when(branch.getId()).thenReturn(BRANCH_ID);
        when(branchesRepo.findWithCompanyByUid(BRANCH_UID)).thenReturn(Optional.of(branch));
        when(branchesRepo.findById(BRANCH_ID)).thenReturn(Optional.of(branch));
        when(locations.defaultLocationId(COMPANY_ID, BRANCH_ID)).thenReturn(LOCATION_ID);

        // --- avg_cost, read identically by both sides (see the divergence notes below) ----------
        Map<Long, BigDecimal> avgCostByProduct = Map.of(
                RM_A_ID, RM_A_AVG_COST, RM_B_ID, RM_B_AVG_COST);
        when(leafCosts.avgCosts(anyLong(), anyLong(), anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(2);
            Map<Long, BigDecimal> out = new HashMap<>();
            ids.forEach(id -> out.put(id, avgCostByProduct.get(id)));
            return out;
        });
        when(valuation.costIssue(eq(COMPANY_ID), eq(BRANCH_ID), anyLong(), any(BigDecimal.class)))
                .thenAnswer(inv -> {
                    BigDecimal avg = avgCostByProduct.get((Long) inv.getArgument(2));
                    return avg == null ? null : ((BigDecimal) inv.getArgument(3)).multiply(avg);
                });

        // --- the work order: released against v1, no component lines materialised yet -----------
        workOrder = new WorkOrder(COMPANY_ID, BRANCH_ID, FG_PRODUCT_ID, "FG-001", "Finished Good",
                PLANNED_QTY, "WO-0001");
        workOrder.setCreatedBy(USER_ID);
        ReflectionTestUtils.setField(workOrder, "id", WO_ID);
        ReflectionTestUtils.setField(workOrder, "uid", WO_UID);
        workOrder.release(101L, BOM_V1_UID, USER_ID);
        when(workOrders.findByUid(WO_UID)).thenReturn(Optional.of(workOrder));
        when(components.findByWorkOrderIdOrderByLineNoAsc(WO_ID)).thenReturn(List.of());
        when(components.save(any(WorkOrderComponent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // D2 — the pinned version is the version that gets consumed
    // -------------------------------------------------------------------------

    @Test
    void workOrderReleasedAgainstV1_stillExplodesV1_afterV2IsActivated() {
        WorkOrderDto dto = costing.issueComponents(WO_UID,
                new IssueComponentsRequest(true, null, LocalDate.now(), null));

        ArgumentCaptor<WorkOrderComponent> lineCap = ArgumentCaptor.forClass(WorkOrderComponent.class);
        verify(components).save(lineCap.capture());
        assertThat(lineCap.getValue().getComponentProductId()).isEqualTo(RM_A_ID);
        assertThat(lineCap.getValue().getPlannedQty()).isEqualByComparingTo("200"); // 100 FG × 2

        ArgumentCaptor<Long>       productCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<BigDecimal> qtyCap     = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockPosting).post(eq(COMPANY_ID), eq(BRANCH_ID), eq(LOCATION_ID),
                productCap.capture(), qtyCap.capture(), eq(MovementType.PRODUCTION_ISSUE),
                isNull(), eq("WORK_ORDER"), eq(WO_UID), isNull(), anyString(),
                any(Instant.class), eq(USER_ID), any(), any());
        assertThat(productCap.getValue()).isEqualTo(RM_A_ID);   // never RM-B, the ACTIVE recipe
        assertThat(qtyCap.getValue()).isEqualByComparingTo("-200");

        // v1's materials: 200 × 10.00. The ACTIVE v2 would have been 500 × 100.00 = 50,000.
        assertThat(dto.wipDebitTotal()).isEqualByComparingTo("2000");
        assertThat(dto.bomUid()).isEqualTo(BOM_V1_UID);
    }

    // -------------------------------------------------------------------------
    // The roll-up and the work order must agree on the same version + quantity
    // -------------------------------------------------------------------------

    /**
     * Nothing anywhere asserted that a cost roll-up and a work order over the same BOM and quantity
     * produce the same materials cost — which is how D1 and D2 both stayed invisible.
     *
     * <p>The two numbers are NOT equal in general; the investigation documented four by-design
     * divergences. Each is <strong>neutralised in this fixture</strong> so an exact equality is
     * meaningful. Do not "fix" a future failure here by loosening the assertion — that would delete
     * the only check that the two code paths explode the same recipe:
     * <ol>
     *   <li><strong>Measurement point.</strong> The roll-up reads {@code avg_cost} as it stands now;
     *       the work order reads it at issue time. Neutralised: the {@code costIssue} stub returns
     *       qty × the same unit avg cost {@code LeafCostResolver} reports, i.e. no stock movement
     *       happens between the two reads.</li>
     *   <li><strong>Yield / scrap inflation.</strong> The roll-up inflates by header yield and line
     *       scrap. Neutralised: yield 100% and scrap 0% make both inflation factors exactly 1, and
     *       the order issues its full planned quantity.</li>
     *   <li><strong>Cost scope.</strong> The roll-up reads avg_cost at a branch named in the
     *       request; the work order reads it at its own branch. Neutralised: the roll-up is asked
     *       for the work order's branch, and the fixture has a single location under it.</li>
     *   <li><strong>Labour and overhead.</strong> WIP carries applied labour/overhead; the roll-up
     *       is materials only. Neutralised: no {@code applyCost} call, so {@code wip_debit_total}
     *       is pure materials.</li>
     * </ol>
     */
    @Test
    void rollUpAndWorkOrder_overTheSameBomAndQuantity_agreeOnMaterialsCost() {
        BomCostRollUpDto roll = rollUp.rollUp(null, BOM_V1_UID, BRANCH_UID, PLANNED_QTY);

        WorkOrderDto dto = costing.issueComponents(WO_UID,
                new IssueComponentsRequest(true, null, LocalDate.now(), null));

        assertThat(roll.bomUid()).isEqualTo(dto.bomUid());
        assertThat(roll.standardCostAmount()).isEqualByComparingTo("2000.0000");
        assertThat(dto.wipDebitTotal()).isEqualByComparingTo(roll.standardCostAmount());
        assertThat(roll.complete()).isTrue();
        assertThat(dto.incompleteCost()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private static Bom bom(long id, String uid, int versionNo, BomStatus status) {
        // output_qty 1, yield 100% — divergence (2) neutralised at the header.
        Bom b = new Bom(COMPANY_ID, FG_PRODUCT_ID, versionNo, BigDecimal.ONE, HUNDRED,
                null, null, USER_ID);
        b.setStatus(status);
        ReflectionTestUtils.setField(b, "id", id);
        ReflectionTestUtils.setField(b, "uid", uid);
        return b;
    }

    private static BomComponent component(long bomId, Long productId, String code, String name,
                                          BigDecimal qtyPer) {
        // scrap 0% — divergence (2) neutralised at the line.
        return new BomComponent(bomId, COMPANY_ID, (short) 1, productId, code, name,
                qtyPer, ComponentSourcing.BUY, BigDecimal.ZERO, null, USER_ID);
    }

    private static Product product(Long id, String uid) {
        Product p = mock(Product.class);
        when(p.getId()).thenReturn(id);
        when(p.getUid()).thenReturn(uid);
        when(p.getCompanyId()).thenReturn(COMPANY_ID);
        return p;
    }
}

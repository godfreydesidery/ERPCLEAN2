package com.erp.modules.manufacturing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.manufacturing.domain.dto.IssueComponentLine;
import com.erp.modules.manufacturing.domain.dto.IssueComponentsRequest;
import com.erp.modules.manufacturing.domain.dto.WorkOrderDto;
import com.erp.modules.manufacturing.domain.entity.WorkOrder;
import com.erp.modules.manufacturing.domain.entity.WorkOrderComponent;
import com.erp.modules.manufacturing.domain.enums.ComponentLineStatus;
import com.erp.modules.manufacturing.repository.WorkOrderComponentRepository;
import com.erp.modules.manufacturing.repository.WorkOrderOperationRepository;
import com.erp.modules.manufacturing.repository.WorkOrderRepository;
import com.erp.modules.manufacturing.service.ManufacturingGlPoster.ComponentCostLeg;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.service.BomExplosionService;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.LocationResolver;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link WorkOrderCostingServiceImpl#issueComponents} — the per-line
 * actual-issue-quantity override (feat/production-actual-issue-qty).
 *
 * <p>Before this change, "Issue Components" always issued the remaining PLANNED quantity.
 * A production supervisor now records the real raw-material quantity consumed via
 * {@code IssueComponentsRequest#lines()}, which takes precedence over {@code full}/
 * {@code componentUids} when non-empty. Over-issue vs remaining planned is allowed by design
 * (flows to WIP, surfaces as a material variance at close) — see test (b).
 */
class WorkOrderCostingServiceImplTest {

    private static final Long COMPANY_ID  = 1L;
    private static final Long BRANCH_ID   = 10L;
    private static final Long USER_ID     = 1L;
    private static final Long LOCATION_ID = 500L;
    private static final String WO_UID    = "WOUID00000000000000000001";

    private WorkOrderRepository          workOrders;
    private WorkOrderComponentRepository components;
    private WorkOrderOperationRepository operations;
    private ProductRepository            products;
    private BomExplosionService          bomExplosion;
    private StockPostingService          stockPosting;
    private InventoryValuationService    valuation;
    private LocationResolver             locationResolver;
    private ManufacturingGlPoster        glPoster;
    private ScopeGuard                   scopeGuard;
    private AuditService                 audit;
    private OutboxPublisher              outbox;
    private BranchRepository             branches;

    private WorkOrderCostingServiceImpl service;

    @BeforeEach
    void setUp() {
        workOrders       = mock(WorkOrderRepository.class);
        components       = mock(WorkOrderComponentRepository.class);
        operations       = mock(WorkOrderOperationRepository.class);
        products         = mock(ProductRepository.class);
        bomExplosion     = mock(BomExplosionService.class);
        stockPosting     = mock(StockPostingService.class);
        valuation        = mock(InventoryValuationService.class);
        locationResolver = mock(LocationResolver.class);
        glPoster         = mock(ManufacturingGlPoster.class);
        scopeGuard       = mock(ScopeGuard.class);
        audit            = mock(AuditService.class);
        outbox           = mock(OutboxPublisher.class);
        branches         = mock(BranchRepository.class);

        service = new WorkOrderCostingServiceImpl(workOrders, components, operations, products,
                bomExplosion, stockPosting, valuation, locationResolver, glPoster,
                scopeGuard, audit, outbox, branches);

        RequestContext.set(new RequestContext.Principal(
                USER_ID, "supervisor@test.com", false, COMPANY_ID, BRANCH_ID, null));

        when(locationResolver.defaultLocationId(COMPANY_ID, BRANCH_ID)).thenReturn(LOCATION_ID);
        when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(new Branch(null, "BR-01", "Head Office")));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // (a) actual qty < remaining planned — issues exactly the requested qty
    // -------------------------------------------------------------------------
    @Test
    void issueComponents_perLineQtyLessThanPlanned_issuesExactQtyAtCost() {
        WorkOrder wo = releasedWorkOrder(900L);
        WorkOrderComponent comp = componentLine(wo.getId(), "COMPUID0000000000000000001",
                55L, "RM-001", "Raw Material 1", new BigDecimal("100"));
        stubWorkOrderAndComponents(wo, comp);

        when(valuation.costIssue(COMPANY_ID, BRANCH_ID, 55L, new BigDecimal("30")))
                .thenReturn(new BigDecimal("150.0000")); // unit cost 5.0000

        WorkOrderDto dto = service.issueComponents(WO_UID, new IssueComponentsRequest(
                false, null, LocalDate.now(),
                List.of(new IssueComponentLine("COMPUID0000000000000000001", new BigDecimal("30")))));

        // Costed at exactly the requested qty — not the remaining planned (100).
        verify(valuation).costIssue(COMPANY_ID, BRANCH_ID, 55L, new BigDecimal("30"));

        ArgumentCaptor<BigDecimal> qtyCap      = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> unitCostCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> valueCap    = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockPosting).post(eq(COMPANY_ID), eq(BRANCH_ID), eq(LOCATION_ID), eq(55L),
                qtyCap.capture(), eq(MovementType.PRODUCTION_ISSUE),
                isNull(), eq("WORK_ORDER"), eq(WO_UID),
                isNull(), anyString(), any(Instant.class), eq(USER_ID),
                unitCostCap.capture(), valueCap.capture());
        assertThat(qtyCap.getValue()).isEqualByComparingTo("-30");
        assertThat(unitCostCap.getValue()).isEqualByComparingTo("5.0000");
        assertThat(valueCap.getValue()).isEqualByComparingTo("-150.0000");

        assertThat(comp.getIssuedQty()).isEqualByComparingTo("30");
        assertThat(comp.getIssuedValue()).isEqualByComparingTo("150.0000");
        assertThat(comp.getStatus()).isEqualTo(ComponentLineStatus.PARTIAL);
        assertThat(comp.isCostSkipped()).isFalse();

        assertThat(dto.wipDebitTotal()).isEqualByComparingTo("150.0000");
        assertThat(dto.incompleteCost()).isFalse();

        ArgumentCaptor<List<ComponentCostLeg>> legsCap = ArgumentCaptor.forClass(List.class);
        verify(glPoster).postComponentIssue(eq(wo), legsCap.capture(), any(LocalDate.class), eq(USER_ID));
        assertThat(legsCap.getValue()).hasSize(1);
        assertThat(legsCap.getValue().get(0).value()).isEqualByComparingTo("150.0000");
    }

    // -------------------------------------------------------------------------
    // (b) actual qty > remaining planned — over-issue is allowed, not capped
    // -------------------------------------------------------------------------
    @Test
    void issueComponents_perLineQtyGreaterThanRemainingPlanned_overIssueAllowed() {
        WorkOrder wo = releasedWorkOrder(901L);
        WorkOrderComponent comp = componentLine(wo.getId(), "COMPUID0000000000000000002",
                56L, "RM-002", "Raw Material 2", new BigDecimal("100"));
        preIssue(comp, new BigDecimal("80"), new BigDecimal("800.0000"), ComponentLineStatus.PARTIAL);
        stubWorkOrderAndComponents(wo, comp);

        // Remaining planned is only 20, but the supervisor issues 50 (real consumption).
        when(valuation.costIssue(COMPANY_ID, BRANCH_ID, 56L, new BigDecimal("50")))
                .thenReturn(new BigDecimal("500.0000")); // unit cost 10.0000

        WorkOrderDto dto = service.issueComponents(WO_UID, new IssueComponentsRequest(
                false, null, LocalDate.now(),
                List.of(new IssueComponentLine("COMPUID0000000000000000002", new BigDecimal("50")))));

        // costIssue is invoked with the FULL requested 50 — never capped to the remaining 20.
        verify(valuation).costIssue(COMPANY_ID, BRANCH_ID, 56L, new BigDecimal("50"));

        ArgumentCaptor<BigDecimal> qtyCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockPosting).post(eq(COMPANY_ID), eq(BRANCH_ID), eq(LOCATION_ID), eq(56L),
                qtyCap.capture(), eq(MovementType.PRODUCTION_ISSUE),
                isNull(), eq("WORK_ORDER"), eq(WO_UID),
                isNull(), anyString(), any(Instant.class), eq(USER_ID),
                any(BigDecimal.class), any(BigDecimal.class));
        assertThat(qtyCap.getValue()).isEqualByComparingTo("-50");

        // 80 + 50 = 130, over the planned 100 — status still resolves to ISSUED (fully covered).
        assertThat(comp.getIssuedQty()).isEqualByComparingTo("130");
        assertThat(comp.getIssuedValue()).isEqualByComparingTo("1300.0000");
        assertThat(comp.getStatus()).isEqualTo(ComponentLineStatus.ISSUED);

        assertThat(dto.wipDebitTotal()).isEqualByComparingTo("500.0000");
    }

    // -------------------------------------------------------------------------
    // (c) full = true still issues remaining planned (regression, lines absent)
    // -------------------------------------------------------------------------
    @Test
    void issueComponents_fullTrue_stillIssuesRemainingPlanned() {
        WorkOrder wo = releasedWorkOrder(902L);
        WorkOrderComponent comp = componentLine(wo.getId(), "COMPUID0000000000000000003",
                57L, "RM-003", "Raw Material 3", new BigDecimal("100"));
        stubWorkOrderAndComponents(wo, comp);

        when(valuation.costIssue(COMPANY_ID, BRANCH_ID, 57L, new BigDecimal("100")))
                .thenReturn(new BigDecimal("1000.0000"));

        WorkOrderDto dto = service.issueComponents(WO_UID,
                new IssueComponentsRequest(true, null, LocalDate.now(), null));

        verify(valuation).costIssue(COMPANY_ID, BRANCH_ID, 57L, new BigDecimal("100"));
        assertThat(comp.getIssuedQty()).isEqualByComparingTo("100");
        assertThat(comp.getStatus()).isEqualTo(ComponentLineStatus.ISSUED);
        assertThat(dto.wipDebitTotal()).isEqualByComparingTo("1000.0000");
    }

    // -------------------------------------------------------------------------
    // (d) qty <= 0 rejected
    // -------------------------------------------------------------------------
    @Test
    void issueComponents_perLineQtyZeroOrNegative_rejected() {
        WorkOrder wo = releasedWorkOrder(903L);
        WorkOrderComponent comp = componentLine(wo.getId(), "COMPUID0000000000000000004",
                58L, "RM-004", "Raw Material 4", new BigDecimal("100"));
        stubWorkOrderAndComponents(wo, comp);

        assertThatThrownBy(() -> service.issueComponents(WO_UID, new IssueComponentsRequest(
                false, null, LocalDate.now(),
                List.of(new IssueComponentLine("COMPUID0000000000000000004", BigDecimal.ZERO)))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.issueComponents(WO_UID, new IssueComponentsRequest(
                false, null, LocalDate.now(),
                List.of(new IssueComponentLine("COMPUID0000000000000000004", new BigDecimal("-5"))))))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(stockPosting, glPoster);
        assertThat(comp.getIssuedQty()).isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------
    // (e) unknown / foreign componentUid rejected
    // -------------------------------------------------------------------------
    @Test
    void issueComponents_unknownComponentUid_rejected() {
        WorkOrder wo = releasedWorkOrder(904L);
        WorkOrderComponent comp = componentLine(wo.getId(), "COMPUID0000000000000000005",
                59L, "RM-005", "Raw Material 5", new BigDecimal("100"));
        stubWorkOrderAndComponents(wo, comp);

        assertThatThrownBy(() -> service.issueComponents(WO_UID, new IssueComponentsRequest(
                false, null, LocalDate.now(),
                List.of(new IssueComponentLine("SOME-OTHER-WO-COMPONENT-UID", new BigDecimal("10"))))))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(stockPosting, glPoster);
        assertThat(comp.getIssuedQty()).isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------
    // (f) avg_cost NULL — cost-skipped for that line (BR-MFG-06), no GL leg
    // -------------------------------------------------------------------------
    @Test
    void issueComponents_perLineAvgCostNull_costSkippedNoGlLeg() {
        WorkOrder wo = releasedWorkOrder(905L);
        WorkOrderComponent comp = componentLine(wo.getId(), "COMPUID0000000000000000006",
                60L, "RM-006", "Raw Material 6", new BigDecimal("100"));
        stubWorkOrderAndComponents(wo, comp);

        when(valuation.costIssue(COMPANY_ID, BRANCH_ID, 60L, new BigDecimal("40"))).thenReturn(null);

        WorkOrderDto dto = service.issueComponents(WO_UID, new IssueComponentsRequest(
                false, null, LocalDate.now(),
                List.of(new IssueComponentLine("COMPUID0000000000000000006", new BigDecimal("40")))));

        ArgumentCaptor<BigDecimal> qtyCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockPosting).post(eq(COMPANY_ID), eq(BRANCH_ID), eq(LOCATION_ID), eq(60L),
                qtyCap.capture(), eq(MovementType.PRODUCTION_ISSUE),
                isNull(), eq("WORK_ORDER"), eq(WO_UID),
                isNull(), anyString(), any(Instant.class), eq(USER_ID),
                isNull(), isNull());
        assertThat(qtyCap.getValue()).isEqualByComparingTo("-40");

        assertThat(comp.isCostSkipped()).isTrue();
        assertThat(comp.getIssuedQty()).isEqualByComparingTo("40");
        assertThat(comp.getIssuedValue()).isEqualByComparingTo("0");
        assertThat(comp.getStatus()).isEqualTo(ComponentLineStatus.PARTIAL);

        assertThat(dto.incompleteCost()).isTrue();
        verify(glPoster, never()).postComponentIssue(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubWorkOrderAndComponents(WorkOrder wo, WorkOrderComponent... comps) {
        when(workOrders.findByUid(WO_UID)).thenReturn(Optional.of(wo));
        when(components.findByWorkOrderIdOrderByLineNoAsc(wo.getId())).thenReturn(List.of(comps));
    }

    private static WorkOrder releasedWorkOrder(long id) {
        WorkOrder wo = new WorkOrder(COMPANY_ID, BRANCH_ID, 999L, "FG-001", "Finished Good",
                BigDecimal.TEN, "WO-0001");
        wo.setCreatedBy(USER_ID);
        ReflectionTestUtils.setField(wo, "id", id);
        ReflectionTestUtils.setField(wo, "uid", WO_UID);
        wo.release(20L, "BOMUID0000000000000000001", USER_ID);
        return wo;
    }

    private static WorkOrderComponent componentLine(long workOrderId, String uid, long componentProductId,
                                                      String code, String name, BigDecimal plannedQty) {
        WorkOrderComponent c = new WorkOrderComponent(workOrderId, COMPANY_ID, (short) 1,
                componentProductId, code, name, plannedQty, USER_ID);
        ReflectionTestUtils.setField(c, "uid", uid);
        return c;
    }

    private static void preIssue(WorkOrderComponent c, BigDecimal issuedQtySoFar,
                                  BigDecimal issuedValueSoFar, ComponentLineStatus status) {
        ReflectionTestUtils.setField(c, "issuedQty", issuedQtySoFar);
        ReflectionTestUtils.setField(c, "issuedValue", issuedValueSoFar);
        ReflectionTestUtils.setField(c, "status", status);
    }
}

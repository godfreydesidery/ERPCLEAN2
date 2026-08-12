package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.ApprovePoRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderApprovalSnapshotDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.VoidPurchaseOrderRequest;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.modules.purchases.domain.entity.PurchaseRequisition;
import com.erp.modules.purchases.domain.entity.PurchaseRequisitionLine;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.purchases.repository.PurchaseRequisitionLineRepository;
import com.erp.modules.purchases.repository.PurchaseRequisitionRepository;
import com.erp.modules.purchases.repository.SupplierQuoteLineRepository;
import com.erp.modules.purchases.repository.SupplierQuoteRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for PurchaseOrderServiceImpl — approval gate (ADR-0027 D-6, FR-PROC-13)
 * and void-order guards (FLOW-PROCURE-TO-PAY-035).
 */
class PurchaseOrderServiceImplTest {

    private PurchaseOrderRepository       orders;
    private PurchaseOrderLineRepository   lines;
    private GoodsReceiptRepository        receipts;
    private SupplierRepository            suppliers;
    private PaymentTermsRepository        paymentTerms;
    private ProductRepository             products;
    private UnitOfMeasureRepository       units;
    private ProductBulkPackRepository     bulkPacks;
    private CompanyRepository             companies;
    private PurchaseNumberGenerator       numberGen;
    private PurchaseOrderTotalsCalculator totals;
    private ScopeGuard                    scopeGuard;
    private AuditService                  audit;
    private PoApprovalGate                approvalGate;
    private SupplierQuoteRepository       quotes;
    private SupplierQuoteLineRepository   quoteLines;
    private BranchRepository              branches;
    private PurchaseRequisitionRepository     requisitions;
    private PurchaseRequisitionLineRepository requisitionLines;

    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orders       = mock(PurchaseOrderRepository.class);
        lines        = mock(PurchaseOrderLineRepository.class);
        receipts     = mock(GoodsReceiptRepository.class);
        suppliers    = mock(SupplierRepository.class);
        paymentTerms = mock(PaymentTermsRepository.class);
        products     = mock(ProductRepository.class);
        units        = mock(UnitOfMeasureRepository.class);
        bulkPacks    = mock(ProductBulkPackRepository.class);
        companies    = mock(CompanyRepository.class);
        numberGen    = mock(PurchaseNumberGenerator.class);
        totals       = mock(PurchaseOrderTotalsCalculator.class);
        scopeGuard   = mock(ScopeGuard.class);
        audit        = mock(AuditService.class);
        approvalGate = mock(PoApprovalGate.class);
        quotes       = mock(SupplierQuoteRepository.class);
        quoteLines   = mock(SupplierQuoteLineRepository.class);
        branches     = mock(BranchRepository.class);
        requisitions     = mock(PurchaseRequisitionRepository.class);
        requisitionLines = mock(PurchaseRequisitionLineRepository.class);

        service = new PurchaseOrderServiceImpl(
                orders, lines, receipts, suppliers, paymentTerms, products, units, bulkPacks,
                companies, numberGen, totals, scopeGuard, audit, approvalGate,
                quotes, quoteLines, branches, requisitions, requisitionLines);
    }

    /** RequestContext is a ThreadLocal — leaving it set would bleed into the next test. */
    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // placeOrder — approval gate (ADR-0027 D-6, FR-PROC-13)
    // -------------------------------------------------------------------------

    @Test
    void placeOrder_approvalGateDisabled_noApprovalRequired_succeeds() {
        PurchaseOrder po = stubDraftPo(1L, "PO-UID-1", 10L, new BigDecimal("5000.00"));
        PurchaseOrderLine line1 = stubLine();
        when(orders.findByUid("PO-UID-1")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(1L)).thenReturn(List.of(line1));
        when(approvalGate.requiresApproval(po, null)).thenReturn(false);
        when(numberGen.nextPurchaseOrder(10L)).thenReturn("PO-0001");

        service.placeOrder("PO-UID-1");

        verify(approvalGate).requiresApproval(po, null);
        verify(po).setStatus(PurchaseOrderStatus.ORDERED);
    }

    @Test
    void placeOrder_approvalRequired_poNotApproved_throws() {
        PurchaseOrder po = stubDraftPo(2L, "PO-UID-2", 10L, new BigDecimal("50000.00"));
        PurchaseOrderLine line2 = stubLine();
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.NOT_REQUIRED);
        when(orders.findByUid("PO-UID-2")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(2L)).thenReturn(List.of(line2));
        when(approvalGate.requiresApproval(po, null)).thenReturn(true);

        assertThatThrownBy(() -> service.placeOrder("PO-UID-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires approval before it can be placed");

        verify(po, never()).setStatus(PurchaseOrderStatus.ORDERED);
    }

    @Test
    void placeOrder_approvalRequired_poPending_throws() {
        PurchaseOrder po = stubDraftPo(3L, "PO-UID-3", 10L, new BigDecimal("50000.00"));
        PurchaseOrderLine line3 = stubLine();
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.PENDING);
        when(orders.findByUid("PO-UID-3")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(3L)).thenReturn(List.of(line3));
        when(approvalGate.requiresApproval(po, null)).thenReturn(true);

        assertThatThrownBy(() -> service.placeOrder("PO-UID-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires approval before it can be placed");
    }

    @Test
    void placeOrder_approvalRequired_poApproved_succeeds() {
        PurchaseOrder po = stubDraftPo(4L, "PO-UID-4", 10L, new BigDecimal("50000.00"));
        PurchaseOrderLine line4 = stubLine();
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.APPROVED);
        when(orders.findByUid("PO-UID-4")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(4L)).thenReturn(List.of(line4));
        when(approvalGate.requiresApproval(po, null)).thenReturn(true);
        when(numberGen.nextPurchaseOrder(10L)).thenReturn("PO-0002");

        service.placeOrder("PO-UID-4");

        verify(po).setStatus(PurchaseOrderStatus.ORDERED);
    }

    // -------------------------------------------------------------------------
    // Approval-engine reconciliation (dead-end fix): an inbox decision only lands on the engine's
    // own approval_request row; the PO stays PENDING forever unless this is reconciled back.
    // -------------------------------------------------------------------------

    @Test
    void getByUid_pendingApproval_engineNowApproved_reconcilesStatusAndDto() {
        PurchaseOrder po = stubPendingApprovalPo(8L, "PO-UID-8", 10L, new BigDecimal("50000.00"),
                "APR-UID-1");
        when(orders.findByUid("PO-UID-8")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(8L)).thenReturn(List.of());
        when(approvalGate.queryState("PO-UID-8", 10L))
                .thenReturn(Optional.of(engineState("PO-UID-8", ApprovalRequestStatus.APPROVED)));

        PurchaseOrderDto dto = service.getByUid("PO-UID-8");

        verify(po).setApprovalStatus(PoApprovalStatus.APPROVED);
        assertThat(dto.approvalStatus()).isEqualTo("APPROVED");
    }

    @Test
    void getByUid_pendingApproval_engineStillPending_leavesStatusUntouched() {
        // Defensive: an in-flight (or unreadable) engine state must never be mistaken for a decision.
        PurchaseOrder po = stubPendingApprovalPo(11L, "PO-UID-11", 10L, new BigDecimal("50000.00"),
                "APR-UID-3");
        when(orders.findByUid("PO-UID-11")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(11L)).thenReturn(List.of());
        when(approvalGate.queryState("PO-UID-11", 10L))
                .thenReturn(Optional.of(engineState("PO-UID-11", ApprovalRequestStatus.PENDING)));

        PurchaseOrderDto dto = service.getByUid("PO-UID-11");

        verify(po, never()).setApprovalStatus(any(PoApprovalStatus.class));
        assertThat(dto.approvalStatus()).isEqualTo("PENDING");
    }

    // -------------------------------------------------------------------------
    // findApprovalSnapshots — the read seam for screens that only DISPLAY approval state
    // -------------------------------------------------------------------------

    @Test
    void findApprovalSnapshots_readsTheStoredRow_withoutPollingTheApprovalEngine() {
        // The whole point of this accessor: getByUid reconciles (poll + mutate + audit), which a
        // read-only caller such as the AP bill list must not do — in a readOnly transaction
        // Hibernate flushes MANUALLY and the mutation is discarded silently anyway.
        when(scopeGuard.canActIn(any(), eq(10L))).thenReturn(true);
        when(orders.findApprovalSnapshotsByUidIn(any())).thenReturn(List.of(
                snapshot("PO-UID-A", 10L, PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.PENDING)));

        var snapshots = service.findApprovalSnapshots(List.of("PO-UID-A"));

        assertThat(snapshots).containsOnlyKeys("PO-UID-A");
        assertThat(snapshots.get("PO-UID-A").origin())
                .isEqualTo(PurchaseOrderOrigin.DIRECT_RECEIPT);
        assertThat(snapshots.get("PO-UID-A").approvalStatus()).isEqualTo(PoApprovalStatus.PENDING);
        verify(approvalGate, never()).queryState(anyString(), any());
        verify(audit, never()).record(any());
    }

    @Test
    @SuppressWarnings("unchecked") // ArgumentCaptor cannot express Collection<String> generically
    void findApprovalSnapshots_hitsTheRepositoryOnce_withDistinctNonBlankUids() {
        when(scopeGuard.canActIn(any(), eq(10L))).thenReturn(true);
        when(orders.findApprovalSnapshotsByUidIn(any())).thenReturn(List.of());

        service.findApprovalSnapshots(java.util.Arrays.asList(
                "PO-UID-A", "PO-UID-B", "PO-UID-A", null, "   "));

        ArgumentCaptor<java.util.Collection<String>> uids =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(orders).findApprovalSnapshotsByUidIn(uids.capture());
        assertThat(uids.getValue()).containsExactly("PO-UID-A", "PO-UID-B");
    }

    @Test
    void findApprovalSnapshots_nothingToLookUp_touchesTheDatabaseNotAtAll() {
        assertThat(service.findApprovalSnapshots(List.of())).isEmpty();
        assertThat(service.findApprovalSnapshots(null)).isEmpty();
        assertThat(service.findApprovalSnapshots(java.util.Arrays.asList(null, "  "))).isEmpty();

        verify(orders, never()).findApprovalSnapshotsByUidIn(any());
    }

    @Test
    void findApprovalSnapshots_dropsOrdersOutsideTheCallersScope_ratherThanThrowing() {
        // Tenancy is scoped from the LOADED row (never a caller parameter). Dropping rather than
        // refusing is deliberate: this feeds a listing, and one stray reference must not 500 a page.
        when(scopeGuard.canActIn(any(), eq(10L))).thenReturn(true);
        when(scopeGuard.canActIn(any(), eq(99L))).thenReturn(false);
        when(orders.findApprovalSnapshotsByUidIn(any())).thenReturn(List.of(
                snapshot("PO-UID-A", 10L, PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.PENDING),
                snapshot("PO-UID-X", 99L, PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.PENDING)));

        var snapshots = service.findApprovalSnapshots(List.of("PO-UID-A", "PO-UID-X"));

        assertThat(snapshots).containsOnlyKeys("PO-UID-A");
    }

    private static PurchaseOrderApprovalSnapshotDto snapshot(String uid, Long companyId,
                                                             PurchaseOrderOrigin origin,
                                                             PoApprovalStatus approvalStatus) {
        return new PurchaseOrderApprovalSnapshotDto(uid, companyId, origin, approvalStatus);
    }

    @Test
    void placeOrder_approvalRequired_storedPending_engineNowApproved_reconcilesAndSucceeds() {
        // The critical unblock: the inbox approved this PO (engine state), but the stored
        // approval_status on the PO row was never updated — placeOrder must still succeed.
        PurchaseOrder po = stubPendingApprovalPo(9L, "PO-UID-9", 10L, new BigDecimal("50000.00"),
                "APR-UID-2");
        PurchaseOrderLine line = stubLine();
        when(orders.findByUid("PO-UID-9")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(9L)).thenReturn(List.of(line));
        when(approvalGate.requiresApproval(po, null)).thenReturn(true);
        when(approvalGate.queryState("PO-UID-9", 10L))
                .thenReturn(Optional.of(engineState("PO-UID-9", ApprovalRequestStatus.APPROVED)));
        when(numberGen.nextPurchaseOrder(10L)).thenReturn("PO-0003");

        service.placeOrder("PO-UID-9");

        verify(po).setApprovalStatus(PoApprovalStatus.APPROVED);
        verify(po).setStatus(PurchaseOrderStatus.ORDERED);
    }

    @Test
    void purchaseOrderDto_from_carriesApprovalStatusEnumName() {
        PurchaseOrder po = new PurchaseOrder(10L, 20L, 1L, "SUP-01", "Acme", "TZS", 1L);
        po.setApprovalStatus(PoApprovalStatus.REJECTED);

        PurchaseOrderDto dto = PurchaseOrderDto.from(po, List.of());

        assertThat(dto.approvalStatus()).isEqualTo("REJECTED");
    }

    // -------------------------------------------------------------------------
    // voidOrder — FLOW-PROCURE-TO-PAY-035: null reason → 400; DRAFT assigns number first
    // -------------------------------------------------------------------------

    @Test
    void voidOrder_nullReason_throws400() {
        PurchaseOrder po = stubDraftPo(5L, "PO-UID-5", 10L, BigDecimal.ZERO);
        when(orders.findByUid("PO-UID-5")).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.voidOrder("PO-UID-5", new VoidPurchaseOrderRequest(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A reason is required to void a purchase order.");

        verify(po, never()).setStatus(PurchaseOrderStatus.VOID);
    }

    @Test
    void voidOrder_blankReason_throws400() {
        PurchaseOrder po = stubDraftPo(6L, "PO-UID-6", 10L, BigDecimal.ZERO);
        when(orders.findByUid("PO-UID-6")).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.voidOrder("PO-UID-6", new VoidPurchaseOrderRequest("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A reason is required to void a purchase order.");

        verify(po, never()).setStatus(PurchaseOrderStatus.VOID);
    }

    @Test
    void voidOrder_draftPo_noOrderNumber_assignsNumberBeforeVoid() {
        // A DRAFT PO has no order_number. The DB constraint chk_purchase_order_number_when_ordered
        // requires non-null order_number for any status != DRAFT. Fix: assign a number before voiding.
        PurchaseOrder po = stubDraftPo(7L, "PO-UID-7", 10L, BigDecimal.ZERO);
        // Mock reflects the setter: getOrderNumber() is null until setOrderNumber() is called,
        // then returns the assigned number (so the audit Map.of sees a non-null value, as in prod).
        when(po.getOrderNumber()).thenReturn(null);
        doAnswer(inv -> {
            when(po.getOrderNumber()).thenReturn(inv.getArgument(0, String.class));
            return null;
        }).when(po).setOrderNumber(anyString());
        when(orders.findByUid("PO-UID-7")).thenReturn(Optional.of(po));
        when(numberGen.nextPurchaseOrder(10L)).thenReturn("PO-VOID-0001");
        when(receipts.findByPurchaseOrderId(7L)).thenReturn(List.of());

        service.voidOrder("PO-UID-7", new VoidPurchaseOrderRequest("duplicate entry"));

        // number must be assigned BEFORE status is set to VOID
        verify(po).setOrderNumber("PO-VOID-0001");
        verify(po).setStatus(PurchaseOrderStatus.VOID);
    }

    // -------------------------------------------------------------------------
    // createFromRequisition — FIX F (D-3 requisition Convert → PO)
    // -------------------------------------------------------------------------

    @Test
    void createFromRequisition_nullEstimatedUnitCost_defaultsToZeroAndDoesNotThrow() {
        stubRequisitionConversion(30L, "REQ-UID-30", 200L, 300L, new BigDecimal("5"), null);

        PurchaseOrderDto dto = service.createFromRequisition("REQ-UID-30", "SUP-UID-1", "TZS");

        assertThat(dto).isNotNull();
        ArgumentCaptor<PurchaseOrderLine> captor = ArgumentCaptor.forClass(PurchaseOrderLine.class);
        verify(lines).save(captor.capture());
        assertThat(captor.getValue().getUnitCostAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createFromRequisition_nonNullEstimatedUnitCost_copiedThroughAndLineTotalComputed() {
        stubRequisitionConversion(31L, "REQ-UID-31", 200L, 300L,
                new BigDecimal("5"), new BigDecimal("12.50"));

        service.createFromRequisition("REQ-UID-31", "SUP-UID-1", "TZS");

        ArgumentCaptor<PurchaseOrderLine> captor = ArgumentCaptor.forClass(PurchaseOrderLine.class);
        verify(lines).save(captor.capture());
        assertThat(captor.getValue().getUnitCostAmount()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(captor.getValue().getLineTotalAmount()).isEqualByComparingTo(new BigDecimal("62.50"));
    }

    @Test
    void createFromRequisition_archivedSupplier_rejected() {
        PurchaseRequisition requisition = mockRequisition(32L, "REQ-UID-32", 10L, 20L);
        when(requisitions.findByUid("REQ-UID-32")).thenReturn(Optional.of(requisition));

        Supplier archived = mock(Supplier.class);
        when(archived.getStatus()).thenReturn(MasterStatus.ARCHIVED);
        when(suppliers.findByCompanyIdAndUid(10L, "SUP-UID-ARCHIVED")).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.createFromRequisition("REQ-UID-32", "SUP-UID-ARCHIVED", "TZS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archived");

        verify(orders, never()).save(any(PurchaseOrder.class));
    }

    @Test
    void createFromRequisition_stampsConvertedToPoLineUidOnRequisitionLine() {
        PurchaseRequisitionLine reqLine = stubRequisitionConversion(
                33L, "REQ-UID-33", 200L, 300L, new BigDecimal("5"), new BigDecimal("4"));

        service.createFromRequisition("REQ-UID-33", "SUP-UID-1", "TZS");

        verify(reqLine).setConvertedToPoLineUid(any());
        verify(requisitionLines).save(reqLine);
    }

    @Test
    void createFromRequisition_nullCurrency_defaultsToCompanyBaseCurrency() {
        stubRequisitionConversion(34L, "REQ-UID-34", 200L, 300L, new BigDecimal("5"), new BigDecimal("4"));
        Company company = mock(Company.class);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findScopedById(10L)).thenReturn(Optional.of(company));

        service.createFromRequisition("REQ-UID-34", "SUP-UID-1", null);

        ArgumentCaptor<PurchaseOrder> poCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(orders).save(poCaptor.capture());
        assertThat(poCaptor.getValue().getCurrency().value()).isEqualTo("TZS");
    }

    @Test
    void createFromRequisition_producesAManualOrder() {
        stubRequisitionConversion(35L, "REQ-UID-35", 200L, 300L, new BigDecimal("5"), new BigDecimal("4"));

        service.createFromRequisition("REQ-UID-35", "SUP-UID-1", "TZS");

        ArgumentCaptor<PurchaseOrder> poCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(orders).save(poCaptor.capture());
        assertThat(poCaptor.getValue().getOrigin())
                .as("converting a requisition is a person raising an order — V96 MANUAL")
                .isEqualTo(PurchaseOrderOrigin.MANUAL);
    }

    // -------------------------------------------------------------------------
    // V96 / K3 — provenance (purchase_orders.origin)
    // -------------------------------------------------------------------------

    @Test
    void create_stampsManualOrigin() {
        stubHeaderOnlyCreate();

        PurchaseOrderDto dto = service.create(new CreatePurchaseOrderRequest(
                "CO-UID-1", "SUP-UID-1", "TZS", null, null, null, List.of()));

        ArgumentCaptor<PurchaseOrder> poCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(orders).save(poCaptor.capture());
        assertThat(poCaptor.getValue().getOrigin()).isEqualTo(PurchaseOrderOrigin.MANUAL);
        assertThat(dto.origin())
                .as("the UI reads provenance off the DTO to badge a synthesised order")
                .isEqualTo(PurchaseOrderOrigin.MANUAL);
    }

    @Test
    void createWithOrigin_directReceipt_stampsDirectReceiptOrigin() {
        stubHeaderOnlyCreate();

        PurchaseOrderDto dto = service.createWithOrigin(new CreatePurchaseOrderRequest(
                        "CO-UID-1", "SUP-UID-1", "TZS", null, null, null, List.of()),
                PurchaseOrderOrigin.DIRECT_RECEIPT);

        ArgumentCaptor<PurchaseOrder> poCaptor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(orders).save(poCaptor.capture());
        assertThat(poCaptor.getValue().getOrigin())
                .as("stamped at construction, so the row is never briefly mislabelled")
                .isEqualTo(PurchaseOrderOrigin.DIRECT_RECEIPT);
        assertThat(dto.origin()).isEqualTo(PurchaseOrderOrigin.DIRECT_RECEIPT);
    }

    @Test
    void list_byDefault_excludesOrdersSynthesisedForADirectReceipt() {
        Pageable page = Pageable.unpaged();
        when(orders.findByCompanyIdAndOriginIn(eq(10L), any(), any())).thenReturn(Page.empty());

        service.list(10L, null, false, page);

        // The buyer's list shows the orders people raised, not the receipt book-keeping.
        verify(orders).findByCompanyIdAndOriginIn(
                10L, EnumSet.of(PurchaseOrderOrigin.MANUAL), page);
    }

    @Test
    void list_withOptIn_includesEveryOrigin() {
        Pageable page = Pageable.unpaged();
        when(orders.findByCompanyIdAndOriginIn(eq(10L), any(), any())).thenReturn(Page.empty());

        service.list(10L, null, true, page);

        verify(orders).findByCompanyIdAndOriginIn(
                10L, EnumSet.allOf(PurchaseOrderOrigin.class), page);
    }

    @Test
    void list_searchPath_appliesTheSameOriginFilterAsBrowse() {
        Pageable page = Pageable.unpaged();
        when(orders.search(eq(10L), eq("acme"), any(), any())).thenReturn(Page.empty());

        service.list(10L, "acme", false, page);

        // Otherwise typing a supplier name leaks the rows the browse page hides.
        verify(orders).search(10L, "acme", EnumSet.of(PurchaseOrderOrigin.MANUAL), page);
    }

    // -------------------------------------------------------------------------
    // PO authorisation (UAT wave 1) — every test below runs as a NON-root buyer/approver, because
    // root short-circuits the permission layer and would mask exactly these holes.
    //
    // (b) /approve had no state guard: an order that was never submitted could be stamped APPROVED.
    // (c) the "not required" message claimed a threshold had been checked when approval was off.
    // (d) submit-for-approval answered the same situation 409 for one user and 200 for another.
    // -------------------------------------------------------------------------

    @Test
    void approvePo_orderThatWasNeverSubmitted_isRefused() {
        // PO-0004 live: approvalStatus null/NOT_REQUIRED, no approval request behind it — yet it
        // flipped to APPROVED and returned 200, back-dating a review that never happened.
        asNonRootApprover();
        PurchaseOrder po = stubDraftPo(20L, "PO-UID-20", 10L, new BigDecimal("100000000.00"));
        when(orders.findByUid("PO-UID-20")).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.approvePo("PO-UID-20", new ApprovePoRequest("CO-UID-1", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not awaiting approval");

        verify(po, never()).setApprovalStatus(PoApprovalStatus.APPROVED);
        verify(audit, never()).record(any());
    }

    @Test
    void approvePo_orderGenuinelyAwaitingADecision_isApproved() {
        asNonRootApprover();
        PurchaseOrder po = stubPendingApprovalPo(21L, "PO-UID-21", 10L, new BigDecimal("100000000.00"),
                null);   // no engine request: this IS the permission-gated fallback approve
        when(orders.findByUid("PO-UID-21")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(21L)).thenReturn(List.of());

        PurchaseOrderDto dto = service.approvePo("PO-UID-21", new ApprovePoRequest("CO-UID-1", null));

        assertThat(dto.approvalStatus()).isEqualTo("APPROVED");
        verify(po).setApprovalStatus(PoApprovalStatus.APPROVED);
    }

    @Test
    void approvePo_orderAlreadyDecided_isRefusedRatherThanOverwritten() {
        asNonRootApprover();
        PurchaseOrder po = stubDraftPo(22L, "PO-UID-22", 10L, new BigDecimal("100000000.00"));
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.REJECTED);
        when(orders.findByUid("PO-UID-22")).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.approvePo("PO-UID-22", new ApprovePoRequest("CO-UID-1", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been rejected");

        verify(po, never()).setApprovalStatus(PoApprovalStatus.APPROVED);
    }

    @Test
    void rejectPo_orderThatWasNeverSubmitted_isRefused() {
        // A REJECTED badge on an unreviewed order falsifies the record exactly as an APPROVED one does.
        asNonRootApprover();
        PurchaseOrder po = stubDraftPo(23L, "PO-UID-23", 10L, new BigDecimal("100000000.00"));
        when(orders.findByUid("PO-UID-23")).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.rejectPo("PO-UID-23",
                new ApprovePoRequest("CO-UID-1", "Too expensive")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not awaiting approval");

        verify(po, never()).setApprovalStatus(PoApprovalStatus.REJECTED);
    }

    @Test
    void submitForApproval_approvalSwitchedOffCompanyWide_saysSo_insteadOfInventingAThreshold() {
        asNonRootBuyer();
        PurchaseOrder po = stubDraftPo(24L, "PO-UID-24", 10L, new BigDecimal("100000000.00"));
        when(orders.findByUid("PO-UID-24")).thenReturn(Optional.of(po));
        when(approvalGate.evaluate(po)).thenReturn(disabledCompanyWide());

        assertThatThrownBy(() -> service.submitForApproval("PO-UID-24"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("switched off for this company")
                .hasMessageNotContaining("threshold")
                .hasMessageNotContaining("below");

        verify(approvalGate, never()).submit(any(), anyString());
    }

    @Test
    void submitForApproval_genuinelyBelowTheCeiling_namesTheCeiling_andReadsNothingLikeSwitchedOff() {
        asNonRootBuyer();
        PurchaseOrder po = stubDraftPo(25L, "PO-UID-25", 10L, new BigDecimal("100000.00"));
        when(orders.findByUid("PO-UID-25")).thenReturn(Optional.of(po));
        when(approvalGate.evaluate(po)).thenReturn(belowThreshold(new BigDecimal("5000000.0000")));

        assertThatThrownBy(() -> service.submitForApproval("PO-UID-25"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("below TZS 5000000.00")
                .hasMessageNotContaining("switched off");
    }

    @Test
    void approvalRefusals_leakNoPermissionCode_statusCode_orInternalName() {
        asNonRootBuyer();
        PurchaseOrder off = stubDraftPo(26L, "PO-UID-26", 10L, new BigDecimal("100000000.00"));
        PurchaseOrder small = stubDraftPo(27L, "PO-UID-27", 10L, new BigDecimal("100000.00"));
        PurchaseOrder unsubmitted = stubDraftPo(28L, "PO-UID-28", 10L, new BigDecimal("100000000.00"));
        when(orders.findByUid("PO-UID-26")).thenReturn(Optional.of(off));
        when(orders.findByUid("PO-UID-27")).thenReturn(Optional.of(small));
        when(orders.findByUid("PO-UID-28")).thenReturn(Optional.of(unsubmitted));
        when(approvalGate.evaluate(off)).thenReturn(disabledCompanyWide());
        when(approvalGate.evaluate(small)).thenReturn(belowThreshold(new BigDecimal("5000000.0000")));

        List<String> messages = List.of(
                messageOf(() -> service.submitForApproval("PO-UID-26")),
                messageOf(() -> service.submitForApproval("PO-UID-27")),
                messageOf(() -> service.approvePo("PO-UID-28", new ApprovePoRequest("CO-UID-1", null))));

        assertThat(messages).allSatisfy(m -> assertThat(m)
                .doesNotContain("PURCHASE.")        // permission code
                .doesNotContain("409")              // HTTP status
                .doesNotContain("NOT_REQUIRED")     // enum constant
                .doesNotContain("PoApproval")       // internal class name
                .doesNotContain("approval_status")  // column name
                .doesNotContain("Exception"));
        // The two "no approval needed" messages must not be interchangeable — that was the defect.
        assertThat(messages.get(0)).isNotEqualTo(messages.get(1));
    }

    @Test
    void submitForApproval_orderAlreadyAwaitingADecision_isIdempotent_notARefusal() {
        // Finding (d): the same situation answered 409 (stored status PENDING) or 200 (stored status
        // stale, engine idempotency absorbed the re-submit). The engine's request row is the truth,
        // so both users now get the same answer — success, with no second request raised.
        asNonRootBuyer();
        // The stale half of the split: the engine holds a live request, the PO's mirror column
        // still says NOT_REQUIRED. Wiring the mock's setter to its getter makes the correction
        // observable on the response.
        PurchaseOrder po = stubDraftPo(29L, "PO-UID-29", 10L, new BigDecimal("100000000.00"));
        doAnswer(inv -> {
            when(po.getApprovalStatus()).thenReturn(inv.getArgument(0, PoApprovalStatus.class));
            return null;
        }).when(po).setApprovalStatus(any(PoApprovalStatus.class));
        when(orders.findByUid("PO-UID-29")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(29L)).thenReturn(List.of());
        when(approvalGate.queryState("PO-UID-29", 10L))
                .thenReturn(Optional.of(engineState("PO-UID-29", ApprovalRequestStatus.PENDING)));

        PurchaseOrderDto dto = service.submitForApproval("PO-UID-29");

        assertThat(dto.approvalStatus()).isEqualTo("PENDING");
        verify(approvalGate, never()).submit(any(), anyString());
        verify(approvalGate, never()).evaluate(any());   // settings can't change the answer here
    }

    @Test
    void submitForApproval_approvalAlreadyClosed_isRefusedWithoutRaisingASecondRequest() {
        asNonRootBuyer();
        PurchaseOrder po = stubDraftPo(30L, "PO-UID-30", 10L, new BigDecimal("100000000.00"));
        when(orders.findByUid("PO-UID-30")).thenReturn(Optional.of(po));
        when(approvalGate.queryState("PO-UID-30", 10L))
                .thenReturn(Optional.of(engineState("PO-UID-30", ApprovalRequestStatus.REJECTED)));

        assertThatThrownBy(() -> service.submitForApproval("PO-UID-30"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be reopened");

        verify(approvalGate, never()).submit(any(), anyString());
    }

    /** The buyer who raises orders — non-root, so every permission gate is really exercised. */
    private void asNonRootBuyer() {
        RequestContext.set(new RequestContext.Principal(7L, "buyer", false, 10L, 20L, null));
    }

    /** The manager who decides on them — also non-root. */
    private void asNonRootApprover() {
        RequestContext.set(new RequestContext.Principal(8L, "procurement.manager", false, 10L, 20L, null));
    }

    private static PoApprovalGate.Decision disabledCompanyWide() {
        return new PoApprovalGate.Decision(
                PoApprovalGate.ApprovalRequirement.DISABLED_COMPANY_WIDE, null, null);
    }

    private static PoApprovalGate.Decision belowThreshold(BigDecimal threshold) {
        return new PoApprovalGate.Decision(
                PoApprovalGate.ApprovalRequirement.BELOW_THRESHOLD, threshold, "TZS");
    }

    private static String messageOf(Runnable call) {
        try {
            call.run();
            throw new AssertionError("Expected the call to be refused, but it succeeded.");
        } catch (IllegalStateException ex) {
            return ex.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Wires the minimum for a header-only {@code create}: a resolvable company, an ACTIVE supplier,
     * an echoing {@code orders.save}, and an empty line read for the DTO mapping. A RequestContext
     * with a branch is required — {@code branchIdFromContext} refuses to create a PO without one.
     */
    private void stubHeaderOnlyCreate() {
        RequestContext.set(new RequestContext.Principal(7L, "buyer", false, 10L, 20L, null));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(10L);
        when(companies.findByUid("CO-UID-1")).thenReturn(Optional.of(company));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(9L);
        when(supplier.getCode()).thenReturn("SUP-01");
        when(supplier.getDisplayName()).thenReturn("Acme");
        when(supplier.getStatus()).thenReturn(MasterStatus.ACTIVE);
        when(suppliers.findByCompanyIdAndUid(10L, "SUP-UID-1")).thenReturn(Optional.of(supplier));

        when(orders.save(any(PurchaseOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lines.findByPurchaseOrderIdOrderByLineNo(any())).thenReturn(List.of());
    }

    private PurchaseRequisition mockRequisition(long id, String uid, long companyId, long branchId) {
        PurchaseRequisition r = mock(PurchaseRequisition.class);
        when(r.getId()).thenReturn(id);
        when(r.getUid()).thenReturn(uid);
        when(r.getCompanyId()).thenReturn(companyId);
        when(r.getBranchId()).thenReturn(branchId);
        return r;
    }

    /**
     * Wires a full happy-path requisition→PO conversion: an APPROVED-shaped requisition (company
     * 10L/branch 20L) with one line, an ACTIVE supplier at "SUP-UID-1", a base-unit product/unit
     * pair (factor 1, no bulk-pack lookup needed), and {@code orders}/{@code lines} save() stubs
     * that echo the argument back (mirrors real repository behaviour). Returns the mocked
     * requisition line so callers can assert on it directly (FIX F).
     */
    private PurchaseRequisitionLine stubRequisitionConversion(long reqId, String reqUid,
                                                               long productId, long unitId,
                                                               BigDecimal requestedQty,
                                                               BigDecimal estimatedUnitCost) {
        long companyId = 10L;
        long branchId  = 20L;

        PurchaseRequisition requisition = mockRequisition(reqId, reqUid, companyId, branchId);
        when(requisitions.findByUid(reqUid)).thenReturn(Optional.of(requisition));

        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(9L);
        when(supplier.getCode()).thenReturn("SUP-01");
        when(supplier.getDisplayName()).thenReturn("Acme");
        when(supplier.getStatus()).thenReturn(MasterStatus.ACTIVE);
        when(suppliers.findByCompanyIdAndUid(companyId, "SUP-UID-1")).thenReturn(Optional.of(supplier));

        PurchaseRequisitionLine reqLine = mock(PurchaseRequisitionLine.class);
        when(reqLine.getProductId()).thenReturn(productId);
        when(reqLine.getUnitId()).thenReturn(unitId);
        when(reqLine.getRequestedQty()).thenReturn(requestedQty);
        when(reqLine.getEstimatedUnitCost()).thenReturn(estimatedUnitCost);
        when(requisitionLines.findByPurchaseRequisitionIdOrderByLineNo(reqId)).thenReturn(List.of(reqLine));

        UnitOfMeasure unit = mock(UnitOfMeasure.class);
        when(unit.getId()).thenReturn(unitId);
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getCode()).thenReturn("PRD-01");
        when(product.getName()).thenReturn("Widget");
        when(product.getBaseUnit()).thenReturn(unit);
        when(products.findByCompanyIdAndId(companyId, productId)).thenReturn(Optional.of(product));
        when(units.findByCompanyIdAndId(companyId, unitId)).thenReturn(Optional.of(unit));

        when(orders.save(any(PurchaseOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lines.findMaxLineNo(any())).thenReturn(0);
        when(lines.save(any(PurchaseOrderLine.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lines.findByPurchaseOrderIdOrderByLineNo(any())).thenReturn(List.of());

        return reqLine;
    }

    private PurchaseOrder stubDraftPo(Long id, String uid, Long companyId, BigDecimal total) {
        PurchaseOrder po = mock(PurchaseOrder.class);
        when(po.getId()).thenReturn(id);
        when(po.getUid()).thenReturn(uid);
        when(po.getCompanyId()).thenReturn(companyId);
        when(po.getBranchId()).thenReturn(20L);
        when(po.getStatus()).thenReturn(PurchaseOrderStatus.DRAFT);
        when(po.getOrderTotalAmount()).thenReturn(total);
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.NOT_REQUIRED);
        return po;
    }

    /**
     * A DRAFT PO already submitted for approval (PENDING, with an approval_request_uid) whose
     * mocked {@code setApprovalStatus}/{@code getApprovalStatus} are wired together so that a
     * reconcile call is observable on subsequent getter reads — mirrors the real entity's field.
     */
    private PurchaseOrder stubPendingApprovalPo(long id, String uid, long companyId, BigDecimal total,
                                                String approvalRequestUid) {
        PurchaseOrder po = stubDraftPo(id, uid, companyId, total);
        when(po.getApprovalRequestUid()).thenReturn(approvalRequestUid);
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.PENDING);
        doAnswer(inv -> {
            when(po.getApprovalStatus()).thenReturn(inv.getArgument(0, PoApprovalStatus.class));
            return null;
        }).when(po).setApprovalStatus(any(PoApprovalStatus.class));
        return po;
    }

    private ApprovalRequestDto engineState(String documentUid, ApprovalRequestStatus status) {
        return new ApprovalRequestDto(
                1L, "APR-UID-1", 10L, 20L, "HQ Branch", "HQ", "APR-0001", "PURCHASE_ORDER", documentUid,
                BigDecimal.TEN, "TZS", status, 1, false,
                null, null, "summary", 1L, "Submitter Name", Instant.now(), Instant.now(),
                1L, "Resolver Name", List.of());
    }

    private PurchaseOrderLine stubLine() {
        PurchaseOrder parentPo = mock(PurchaseOrder.class);
        when(parentPo.getId()).thenReturn(1L);
        PurchaseOrderLine l = mock(PurchaseOrderLine.class);
        when(l.getPurchaseOrder()).thenReturn(parentPo);
        when(l.getId()).thenReturn(1L);
        when(l.getUid()).thenReturn("LINE-UID-1");
        when(l.getLineNo()).thenReturn((short) 1);
        when(l.getProductId()).thenReturn(100L);
        when(l.getOrderedQtyInBase()).thenReturn(BigDecimal.ONE);
        when(l.getReceivedQtyInBase()).thenReturn(BigDecimal.ZERO);
        return l;
    }
}

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
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
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

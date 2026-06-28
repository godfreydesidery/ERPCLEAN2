package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.VoidPurchaseOrderRequest;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.purchases.repository.SupplierQuoteLineRepository;
import com.erp.modules.purchases.repository.SupplierQuoteRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        service = new PurchaseOrderServiceImpl(
                orders, lines, receipts, suppliers, paymentTerms, products, units, bulkPacks,
                companies, numberGen, totals, scopeGuard, audit, approvalGate,
                quotes, quoteLines, branches);
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
    // Helpers
    // -------------------------------------------------------------------------

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

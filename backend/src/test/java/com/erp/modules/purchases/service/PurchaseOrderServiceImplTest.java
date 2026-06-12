package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
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
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PurchaseOrderServiceImpl — approval gate (ADR-0027 D-6, FR-PROC-13).
 * Proves fix for adversarial-review BLOCKER: placeOrder() must enforce the approval gate.
 */
class PurchaseOrderServiceImplTest {

    private PurchaseOrderRepository       orders;
    private PurchaseOrderLineRepository   lines;
    private GoodsReceiptRepository        receipts;
    private SupplierRepository            suppliers;
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

    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orders      = mock(PurchaseOrderRepository.class);
        lines       = mock(PurchaseOrderLineRepository.class);
        receipts    = mock(GoodsReceiptRepository.class);
        suppliers   = mock(SupplierRepository.class);
        products    = mock(ProductRepository.class);
        units       = mock(UnitOfMeasureRepository.class);
        bulkPacks   = mock(ProductBulkPackRepository.class);
        companies   = mock(CompanyRepository.class);
        numberGen   = mock(PurchaseNumberGenerator.class);
        totals      = mock(PurchaseOrderTotalsCalculator.class);
        scopeGuard  = mock(ScopeGuard.class);
        audit       = mock(AuditService.class);
        approvalGate = mock(PoApprovalGate.class);
        quotes      = mock(SupplierQuoteRepository.class);
        quoteLines  = mock(SupplierQuoteLineRepository.class);

        service = new PurchaseOrderServiceImpl(
                orders, lines, receipts, suppliers, products, units, bulkPacks,
                companies, numberGen, totals, scopeGuard, audit, approvalGate,
                quotes, quoteLines);
    }

    // -------------------------------------------------------------------------
    // Fix 1 (BLOCKER): placeOrder() must call requiresApproval gate (ADR-0027 D-6, FR-PROC-13)
    // -------------------------------------------------------------------------

    @Test
    void placeOrder_approvalGateDisabled_noApprovalRequired_succeeds() {
        // arrange
        PurchaseOrder po = stubDraftPo(1L, "PO-UID-1", 10L, new BigDecimal("5000.00"));
        when(orders.findByUid("PO-UID-1")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(1L)).thenReturn(List.of(stubLine()));
        when(approvalGate.requiresApproval(po, null)).thenReturn(false);
        when(numberGen.nextPurchaseOrder(10L)).thenReturn("PO-0001");

        // act
        service.placeOrder("PO-UID-1");

        // assert: PO was placed (status transition invoked via setter)
        verify(approvalGate).requiresApproval(po, null);
        verify(po).setStatus(PurchaseOrderStatus.ORDERED);
    }

    @Test
    void placeOrder_approvalRequired_poNotApproved_throws() {
        // arrange: approval gate enabled, PO total >= threshold, status is NOT_REQUIRED
        PurchaseOrder po = stubDraftPo(2L, "PO-UID-2", 10L, new BigDecimal("50000.00"));
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.NOT_REQUIRED);
        when(orders.findByUid("PO-UID-2")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(2L)).thenReturn(List.of(stubLine()));
        when(approvalGate.requiresApproval(po, null)).thenReturn(true);

        // act + assert
        assertThatThrownBy(() -> service.placeOrder("PO-UID-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PO requires approval")
                .hasMessageContaining("FR-PROC-13");

        // assert: PO was NOT placed (status setter never called with ORDERED)
        verify(po, never()).setStatus(PurchaseOrderStatus.ORDERED);
    }

    @Test
    void placeOrder_approvalRequired_poPending_throws() {
        // arrange: gate on, PO status = PENDING
        PurchaseOrder po = stubDraftPo(3L, "PO-UID-3", 10L, new BigDecimal("50000.00"));
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.PENDING);
        when(orders.findByUid("PO-UID-3")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(3L)).thenReturn(List.of(stubLine()));
        when(approvalGate.requiresApproval(po, null)).thenReturn(true);

        assertThatThrownBy(() -> service.placeOrder("PO-UID-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PO requires approval");
    }

    @Test
    void placeOrder_approvalRequired_poApproved_succeeds() {
        // arrange: gate on but PO already APPROVED — must proceed to ORDERED
        PurchaseOrder po = stubDraftPo(4L, "PO-UID-4", 10L, new BigDecimal("50000.00"));
        when(po.getApprovalStatus()).thenReturn(PoApprovalStatus.APPROVED);
        when(orders.findByUid("PO-UID-4")).thenReturn(Optional.of(po));
        when(lines.findByPurchaseOrderIdOrderByLineNo(4L)).thenReturn(List.of(stubLine()));
        when(approvalGate.requiresApproval(po, null)).thenReturn(true);
        when(numberGen.nextPurchaseOrder(10L)).thenReturn("PO-0002");

        // act — must not throw
        service.placeOrder("PO-UID-4");

        verify(po).setStatus(PurchaseOrderStatus.ORDERED);
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
        PurchaseOrderLine l = mock(PurchaseOrderLine.class);
        when(l.getOrderedQtyInBase()).thenReturn(BigDecimal.ONE);
        when(l.getReceivedQtyInBase()).thenReturn(BigDecimal.ZERO);
        return l;
    }
}

package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import com.erp.modules.purchases.repository.GoodsReceiptLineRepository;
import com.erp.modules.purchases.repository.GoodsReceiptLineSerialRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.purchases.repository.PurchaseSettingsRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The guards in front of a goods-receipt void (2026-10-10 adversarial review).
 *
 * <p>A void reverses the WHOLE receipt — stock, GRNI and the PO's received quantity. It must not
 * run once part of the receipt has already left by another document, or that part is reversed
 * twice.
 */
class GoodsReceiptVoidGuardTest {

    private GoodsReceiptRepository     receipts;
    private GoodsReceiptLineRepository grLines;
    private PurchaseOrderRepository    orders;
    private OutstandingTracker         tracker;
    private PurchaseOrderServiceImpl   poService;
    private OutboxPublisher            outbox;
    private ReceiptVoidStockGuard      stockGuard;
    private GoodsReceiptServiceImpl    service;

    private GoodsReceipt gr;

    @BeforeEach
    void setUp() {
        receipts  = mock(GoodsReceiptRepository.class);
        grLines   = mock(GoodsReceiptLineRepository.class);
        orders    = mock(PurchaseOrderRepository.class);
        tracker   = mock(OutstandingTracker.class);
        poService = mock(PurchaseOrderServiceImpl.class);
        outbox    = mock(OutboxPublisher.class);
        stockGuard = mock(ReceiptVoidStockGuard.class);
        service = new GoodsReceiptServiceImpl(
                receipts, grLines, mock(GoodsReceiptLineSerialRepository.class), orders,
                mock(PurchaseOrderLineRepository.class), mock(ProductRepository.class),
                mock(PurchaseSettingsRepository.class), mock(PurchaseNumberGenerator.class),
                tracker, poService, mock(ScopeGuard.class), mock(AuditService.class), outbox,
                mock(GoodsReceiptPrintQuery.class), stockGuard);

        RequestContext.set(new RequestContext.Principal(1L, "u@test", false, 10L, 20L, null));

        gr = mock(GoodsReceipt.class);
        when(gr.getId()).thenReturn(40L);
        when(gr.getUid()).thenReturn("GR-UID");
        when(gr.getCompanyId()).thenReturn(10L);
        when(gr.getBranchId()).thenReturn(20L);
        when(gr.getPurchaseOrderId()).thenReturn(30L);
        when(gr.getReceiptNumber()).thenReturn("GRN-0001");
        when(gr.getStatus()).thenReturn(GoodsReceiptStatus.RECEIVED);
        when(receipts.findByUid("GR-UID")).thenReturn(Optional.of(gr));
        when(orders.findById(30L)).thenReturn(Optional.of(mock(PurchaseOrder.class)));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void void_isRefusedOnceGoodsHaveBeenReturnedToTheSupplier() {
        GoodsReceiptLine returned = line(new BigDecimal("25"));
        when(grLines.findByGoodsReceiptIdOrderByLineNo(40L)).thenReturn(List.of(returned));

        assertThatThrownBy(() -> service.voidReceipt("GR-UID", new VoidGoodsReceiptRequest("typo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been returned to the supplier");

        verify(gr, never()).setStatus(any());
        verify(tracker, never()).reverseReceipt(anyList());
        verify(outbox, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void void_withNoReturns_stillReversesTheReceipt() {
        GoodsReceiptLine untouched = line(BigDecimal.ZERO);
        when(grLines.findByGoodsReceiptIdOrderByLineNo(40L)).thenReturn(List.of(untouched));

        service.voidReceipt("GR-UID", new VoidGoodsReceiptRequest("typo"));

        verify(gr).setStatus(GoodsReceiptStatus.VOID);
        verify(tracker).reverseReceipt(anyList());
        verify(outbox).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void void_isRefusedWhenTheStockGuardFindsTheGoodsPartlySold() {
        GoodsReceiptLine untouched = line(BigDecimal.ZERO);
        List<GoodsReceiptLine> lines = List.of(untouched);
        when(grLines.findByGoodsReceiptIdOrderByLineNo(40L)).thenReturn(lines);
        org.mockito.Mockito.doThrow(new IllegalStateException("already been sold or used"))
                .when(stockGuard).assertStockStillOnHand(gr, lines);

        assertThatThrownBy(() -> service.voidReceipt("GR-UID", new VoidGoodsReceiptRequest("typo")))
                .hasMessageContaining("already been sold or used");
        verify(gr, never()).setStatus(any());
        verify(outbox, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void void_ofAnAlreadyVoidedReceipt_saysSoInPlainWords() {
        when(gr.getStatus()).thenReturn(GoodsReceiptStatus.VOID);

        assertThatThrownBy(() -> service.voidReceipt("GR-UID", new VoidGoodsReceiptRequest("again")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This goods receipt has already been voided.");
        verify(tracker, never()).reverseReceipt(anyList());
    }

    private GoodsReceiptLine line(BigDecimal returnedQtyInBase) {
        GoodsReceiptLine l = mock(GoodsReceiptLine.class);
        when(l.getId()).thenReturn(7L);
        when(l.getGoodsReceipt()).thenReturn(gr);   // GoodsReceiptLineDto.from reads the parent id
        when(l.getProductId()).thenReturn(1L);
        when(l.getQtyInBase()).thenReturn(new BigDecimal("200"));
        when(l.getReturnedQtyInBase()).thenReturn(returnedQtyInBase);
        return l;
    }
}

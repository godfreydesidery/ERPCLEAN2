package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
import com.erp.modules.stock.service.StockReservationService;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OPN-13 (owner ruling 2026-10-10): no void once the receipt's stock has partly been consumed. */
class ReceiptVoidStockGuardTest {

    private StockReservationService stock;
    private ProductRepository       products;
    private DomainEventRepository   events;
    private ReceiptVoidStockGuard   guard;
    private GoodsReceipt            gr;

    @BeforeEach
    void setUp() {
        stock    = mock(StockReservationService.class);
        products = mock(ProductRepository.class);
        events   = mock(DomainEventRepository.class);
        guard    = new ReceiptVoidStockGuard(stock, products, events);

        gr = mock(GoodsReceipt.class);
        when(gr.getId()).thenReturn(40L);
        when(gr.getCompanyId()).thenReturn(10L);
        when(gr.getBranchId()).thenReturn(20L);
        when(gr.getReceiptNumber()).thenReturn("GRN-0001");

        Product stockable = mock(Product.class);
        when(stockable.isStockable()).thenReturn(true);
        when(products.findByCompanyIdAndId(eq(10L), anyLong())).thenReturn(Optional.of(stockable));
    }

    @Test
    void refuses_whenTheBranchHoldsLessThanTheVoidWouldReverse() {
        onHand(1L, "120");   // 200 bottles received, 80 sold

        assertThatThrownBy(() -> guard.assertStockStillOnHand(gr, List.of(line(1L, "200", "0"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Some of the goods on this receipt have already been sold or used: only 120 "
                        + "of the 200 Safari Lager it brought in (in the stock unit) are still in stock, "
                        + "so it can't be voided. Correct it with a purchase return or a stock "
                        + "adjustment instead.");
    }

    @Test
    void allows_whenEverythingTheReceiptBroughtInIsStillThere() {
        onHand(1L, "200");
        assertThatCode(() -> guard.assertStockStillOnHand(gr, List.of(line(1L, "200", "0"))))
                .doesNotThrowAnyException();
    }

    @Test
    void sumsTwoLinesOfTheSameProductAndIgnoresWhatWasAlreadyReturned() {
        onHand(1L, "150");
        // 100 + (100 − 25 returned) = 175 to reverse > 150 on hand
        assertThatThrownBy(() -> guard.assertStockStillOnHand(gr,
                List.of(line(1L, "100", "0"), line(1L, "100", "25"))))
                .hasMessageContaining("only 150 of the 175");
    }

    @Test
    void skipsTheCheckWhileTheReceiptHasNotReachedStockYet() {
        when(events.existsByAggregateTypeAndAggregateIdAndEventTypeAndStatusIn(
                eq(DomainEventType.AGG_GOODS_RECEIPT), eq(40L), eq(DomainEventType.STOCK_RECEIVED), any()))
                .thenReturn(true);

        assertThatCode(() -> guard.assertStockStillOnHand(gr, List.of(line(1L, "200", "0"))))
                .doesNotThrowAnyException();
        verify(stock, never()).getAvailability(any(), any(), any());
    }

    @Test
    void ignoresNonStockableLines() {
        Product service = mock(Product.class);
        when(service.isStockable()).thenReturn(false);
        when(products.findByCompanyIdAndId(10L, 2L)).thenReturn(Optional.of(service));

        assertThatCode(() -> guard.assertStockStillOnHand(gr, List.of(line(2L, "5", "0"))))
                .doesNotThrowAnyException();
        verify(stock, never()).getAvailability(any(), any(), any());
    }

    private void onHand(Long productId, String qty) {
        when(stock.getAvailability(10L, 20L, productId)).thenReturn(new StockAvailabilityDto(
                10L, 20L, productId, new BigDecimal(qty), BigDecimal.ZERO, new BigDecimal(qty)));
    }

    private static GoodsReceiptLine line(Long productId, String qtyInBase, String returned) {
        GoodsReceiptLine l = mock(GoodsReceiptLine.class);
        when(l.getProductId()).thenReturn(productId);
        when(l.getProductName()).thenReturn("Safari Lager");
        when(l.getQtyInBase()).thenReturn(new BigDecimal(qtyInBase));
        when(l.getReturnedQtyInBase()).thenReturn(new BigDecimal(returned));
        return l;
    }
}

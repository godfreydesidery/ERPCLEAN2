package com.erp.modules.stock.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockBatchService;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.modules.stock.service.StockSerialService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Unit tests for {@link GoodsReceiptStockHandler} — the receiving side of the "repeated product
 * loses all but the first movement" defect.
 *
 * <p>A GRN routinely lists one product twice: two lots, two expiry dates, sometimes two unit costs.
 * With a single event-wide {@code source_event_uid}, the second line looked to the
 * {@code (source_event_uid, product_id)} backstop like a redelivery of the first and its quantity
 * never landed — while {@code recomputeOnReceipt} had already folded both lines into the moving
 * average and the DR INVENTORY journal had already been raised for both. Inventory was debited for
 * goods that never appeared on hand.
 *
 * <p>Aggregating the lines (which is safe for sales, where units of a product are fungible) is
 * <em>not</em> an option here: lot number and expiry differ per line and drive FEFO.
 */
class GoodsReceiptStockHandlerTest {

    private IdempotencyGuard          guard;
    private StockPostingService       posting;
    private ProductService            productService;
    private InventoryValuationService valuation;
    private InventoryGlPoster         glPoster;
    private StockBatchService         batchService;
    private StockSerialService        serialService;
    private StockLocationRepository   locationRepo;
    private ObjectMapper              objectMapper;

    private GoodsReceiptStockHandler handler;

    private static final Long   COMPANY_ID  = 1L;
    private static final Long   BRANCH_ID   = 2L;
    private static final Long   LOCATION_ID = 10L;
    private static final Long   PRODUCT_ID  = 500L;
    private static final String PRODUCT_UID = "01KVJT7VQ0XWKE53X4MGM87PRD";
    private static final String EVENT_UID   = "01KVJT7VQ0XWKE53X4MGM87BYN";
    private static final String RECEIPT_UID = "01KVJT7VQ0XWKE53X4MGM87GRN";

    @BeforeEach
    void setUp() {
        guard          = mock(IdempotencyGuard.class);
        posting        = mock(StockPostingService.class);
        productService = mock(ProductService.class);
        valuation      = mock(InventoryValuationService.class);
        glPoster       = mock(InventoryGlPoster.class);
        batchService   = mock(StockBatchService.class);
        serialService  = mock(StockSerialService.class);
        locationRepo   = mock(StockLocationRepository.class);
        objectMapper   = new ObjectMapper().findAndRegisterModules();

        handler = new GoodsReceiptStockHandler(guard, posting, productService, valuation,
                glPoster, batchService, serialService, locationRepo, objectMapper);

        when(guard.alreadyProcessed(anyString(), anyString())).thenReturn(false);
        when(productService.getByUid(PRODUCT_UID)).thenReturn(stockableProduct());
        when(posting.alreadyPosted(anyString(), anyLong())).thenReturn(false);
        when(valuation.recomputeOnReceipt(anyLong(), anyLong(), anyLong(),
                any(BigDecimal.class), any(BigDecimal.class)))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(3))
                        .multiply(inv.getArgument(4)));

        StockLocation location = mock(StockLocation.class);
        when(location.getId()).thenReturn(LOCATION_ID);
        when(locationRepo.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.of(location));
    }

    @Test
    void sameProductInTwoLots_bothLotsLand() throws Exception {
        handler.handle(receiptOfTwoLots());

        ArgumentCaptor<BigDecimal> qty = ArgumentCaptor.forClass(BigDecimal.class);
        verify(posting, times(2)).post(anyLong(), anyLong(), anyLong(),
                qty.capture(), any(MovementType.class),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());

        assertThat(qty.getAllValues().get(0)).isEqualByComparingTo(new BigDecimal("12"));
        assertThat(qty.getAllValues().get(1)).isEqualByComparingTo(new BigDecimal("8"));
        assertThat(qty.getAllValues().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("Σ movement quantity must equal Σ received quantity")
                .isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    void sameProductInTwoLots_eachLineGetsItsOwnIdempotencyKey() throws Exception {
        handler.handle(receiptOfTwoLots());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(posting, times(2)).post(anyLong(), anyLong(), anyLong(),
                any(BigDecimal.class), any(MovementType.class),
                key.capture(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());

        assertThat(key.getAllValues())
                .doesNotHaveDuplicates()
                .doesNotContain(EVENT_UID)
                .allSatisfy(k -> assertThat(k).hasSize(26));
    }

    @Test
    void sameProductInTwoLots_bothBatchRowsAreWritten() throws Exception {
        handler.handle(receiptOfTwoLots());

        // FEFO depends on this: the second lot's expiry must reach stock_batches, not be dropped
        // with the movement the backstop suppressed.
        verify(batchService).receiveQty(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                "LOT-A", null, LocalDate.of(2027, 1, 31), new BigDecimal("12"), null);
        verify(batchService).receiveQty(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                "LOT-B", null, LocalDate.of(2026, 9, 30), new BigDecimal("8"), null);
    }

    @Test
    void suppressedRedelivery_doesNotRecomputeTheAverageEither() throws Exception {
        when(posting.alreadyPosted(anyString(), eq(PRODUCT_ID))).thenReturn(true);

        handler.handle(receiptOfTwoLots());

        // recomputeOnReceipt writes avg_cost and on_hand_value straight away; running it for a
        // posting that post() then suppresses inflates the valuation against a ledger that never
        // moved. Nothing at all should happen for an already-applied line.
        verify(valuation, never()).recomputeOnReceipt(anyLong(), anyLong(), anyLong(), any(), any());
        verify(posting, never()).post(anyLong(), anyLong(), anyLong(),
                any(BigDecimal.class), any(MovementType.class),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());
        verify(batchService, never()).receiveQty(anyLong(), anyLong(), anyLong(), anyLong(),
                anyString(), any(), any(), any(), any());
    }

    @Test
    void probeHappensBeforeTheAverageRecompute() throws Exception {
        handler.handle(receiptOfTwoLots());

        InOrder order = inOrder(posting, valuation);
        order.verify(posting).alreadyPosted(anyString(), eq(PRODUCT_ID));
        order.verify(valuation).recomputeOnReceipt(anyLong(), anyLong(), anyLong(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DomainEvent receiptOfTwoLots() throws Exception {
        StockReceivedPayload payload = new StockReceivedPayload(
                RECEIPT_UID, COMPANY_ID, BRANCH_ID, Instant.now(),
                List.of(
                        new StockReceivedPayload.LineItem(PRODUCT_ID, PRODUCT_UID, 1L,
                                new BigDecimal("12"), new BigDecimal("300"),
                                "LOT-A", null, LocalDate.of(2027, 1, 31), List.of()),
                        new StockReceivedPayload.LineItem(PRODUCT_ID, PRODUCT_UID, 1L,
                                new BigDecimal("8"), new BigDecimal("320"),
                                "LOT-B", null, LocalDate.of(2026, 9, 30), List.of())),
                "GRN-0042");

        DomainEvent event = mock(DomainEvent.class);
        when(event.getUid()).thenReturn(EVENT_UID);
        when(event.getCompanyId()).thenReturn(COMPANY_ID);
        when(event.getBranchId()).thenReturn(BRANCH_ID);
        when(event.getPayload()).thenReturn(objectMapper.writeValueAsString(payload));
        when(event.getEventType()).thenReturn(DomainEventType.STOCK_RECEIVED);
        return event;
    }

    private static ProductDto stockableProduct() {
        return new ProductDto(PRODUCT_ID, PRODUCT_UID, COMPANY_ID,
                "PROD-0500", "Lot Tracked Widget", null, null,
                true, true, true, false, true, null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false, null, null,
                false, null, null, null);
    }
}

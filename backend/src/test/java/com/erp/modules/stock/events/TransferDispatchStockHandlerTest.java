package com.erp.modules.stock.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.dto.TransferDispatchedPayload;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link TransferDispatchStockHandler} — regression for STOCK-039.
 *
 * <p>Proves that TRANSFER_OUT at source and TRANSFER_IN at in-transit use distinct
 * sourceEventUid values ({@code eventUid + ":OUT"} / {@code ":IN"}), so the
 * idempotency backstop in StockPostingServiceImpl does not swallow the second leg.
 */
class TransferDispatchStockHandlerTest {

    private IdempotencyGuard          guard;
    private StockPostingService       posting;
    private InventoryValuationService valuation;
    private ObjectMapper              objectMapper;

    private TransferDispatchStockHandler handler;

    private static final Long   COMPANY_ID       = 1L;
    private static final Long   BRANCH_ID        = 2L;
    private static final Long   SRC_LOCATION_ID  = 10L;
    private static final Long   TRANSIT_LOC_ID   = 99L;
    private static final Long   PRODUCT_ID       = 100L;
    private static final String EVENT_UID        = "EVT-DISPATCH-001";

    @BeforeEach
    void setUp() {
        guard        = mock(IdempotencyGuard.class);
        posting      = mock(StockPostingService.class);
        valuation    = mock(InventoryValuationService.class);
        objectMapper = new ObjectMapper()
                .findAndRegisterModules(); // needed for Instant serialisation

        handler = new TransferDispatchStockHandler(guard, posting, valuation, objectMapper);

        when(guard.alreadyProcessed(anyString(), anyString())).thenReturn(false);
        when(posting.post(anyLong(), anyLong(), anyLong(), anyLong(), any(BigDecimal.class),
                any(MovementType.class), anyString(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(),
                any(), any())).thenReturn("MVT-UID");
    }

    // ── STOCK-039 regression: two distinct sourceEventUid suffixes ─────────────

    @Test
    void handle_postsTransferOutAndTransferInWithDistinctSourceEventUids() throws Exception {
        TransferDispatchedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        // Capture all post() calls
        ArgumentCaptor<String> sourceEventUidCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MovementType> movementTypeCaptor = ArgumentCaptor.forClass(MovementType.class);

        verify(posting, times(2)).post(
                anyLong(), anyLong(), anyLong(), anyLong(), any(BigDecimal.class),
                movementTypeCaptor.capture(),
                sourceEventUidCaptor.capture(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(), any(), any());

        List<String> eventUids = sourceEventUidCaptor.getAllValues();
        List<MovementType> types = movementTypeCaptor.getAllValues();

        // First post: TRANSFER_OUT with ":OUT" suffix
        assertThat(types.get(0)).isEqualTo(MovementType.TRANSFER_OUT);
        assertThat(eventUids.get(0)).isEqualTo(EVENT_UID + ":OUT");

        // Second post: TRANSFER_IN with ":IN" suffix
        assertThat(types.get(1)).isEqualTo(MovementType.TRANSFER_IN);
        assertThat(eventUids.get(1)).isEqualTo(EVENT_UID + ":IN");
    }

    @Test
    void handle_outAndInSourceEventUidsAreDifferent() throws Exception {
        // The core invariant: the two legs must NOT share the same sourceEventUid,
        // otherwise StockPostingServiceImpl's (sourceEventUid, productId) idempotency
        // backstop skips the second leg and the in-transit location is never credited.
        TransferDispatchedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        ArgumentCaptor<String> uidCaptor = ArgumentCaptor.forClass(String.class);
        verify(posting, times(2)).post(
                anyLong(), anyLong(), anyLong(), anyLong(), any(BigDecimal.class),
                any(MovementType.class),
                uidCaptor.capture(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(), any(), any());

        String outUid = uidCaptor.getAllValues().get(0);
        String inUid  = uidCaptor.getAllValues().get(1);
        assertThat(outUid).isNotEqualTo(inUid);
    }

    @Test
    void handle_postsTwoMovements_outAtSourceAndInAtTransit() throws Exception {
        TransferDispatchedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        ArgumentCaptor<Long> locationIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(posting, times(2)).post(
                anyLong(), anyLong(), locationIdCaptor.capture(), anyLong(), any(BigDecimal.class),
                any(MovementType.class),
                anyString(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(), any(), any());

        assertThat(locationIdCaptor.getAllValues().get(0)).isEqualTo(SRC_LOCATION_ID);    // OUT at source
        assertThat(locationIdCaptor.getAllValues().get(1)).isEqualTo(TRANSIT_LOC_ID);     // IN at transit
    }

    @Test
    void handle_outQtyIsNegated() throws Exception {
        TransferDispatchedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        ArgumentCaptor<BigDecimal> qtyCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(posting, times(2)).post(
                anyLong(), anyLong(), anyLong(), anyLong(), qtyCaptor.capture(),
                any(MovementType.class),
                anyString(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(), any(), any());

        BigDecimal outQty = qtyCaptor.getAllValues().get(0);
        BigDecimal inQty  = qtyCaptor.getAllValues().get(1);
        assertThat(outQty).isEqualByComparingTo(new BigDecimal("-5")); // negate of 5
        assertThat(inQty).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    void handle_idempotencyGuardSkipsAlreadyProcessed() throws Exception {
        when(guard.alreadyProcessed(TransferDispatchStockHandler.CONSUMER, EVENT_UID))
                .thenReturn(true);

        DomainEvent event = buildEvent(EVENT_UID, buildPayload());
        handler.handle(event);

        verify(posting, never()).post(anyLong(), anyLong(), anyLong(), anyLong(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private TransferDispatchedPayload buildPayload() {
        TransferDispatchedPayload.LineItem line = new TransferDispatchedPayload.LineItem(
                PRODUCT_ID, null, null,
                new BigDecimal("5"),
                new BigDecimal("100"),
                new BigDecimal("500"));
        return new TransferDispatchedPayload(
                "XFER-UID-1", COMPANY_ID,
                BRANCH_ID, SRC_LOCATION_ID,
                TRANSIT_LOC_ID,
                Instant.now(),
                List.of(line));
    }

    private DomainEvent buildEvent(String uid, TransferDispatchedPayload payload) throws Exception {
        DomainEvent event = mock(DomainEvent.class);
        when(event.getUid()).thenReturn(uid);
        when(event.getCompanyId()).thenReturn(COMPANY_ID);
        when(event.getBranchId()).thenReturn(BRANCH_ID);
        when(event.getPayload()).thenReturn(objectMapper.writeValueAsString(payload));
        when(event.getEventType()).thenReturn(DomainEventType.STOCK_TRANSFER_DISPATCHED);
        return event;
    }
}

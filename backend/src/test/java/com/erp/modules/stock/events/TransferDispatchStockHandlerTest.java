package com.erp.modules.stock.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
 * {@code source_event_uid} values that are exactly 26 chars (matching the
 * {@code VARCHAR(26)} column in {@code stock_movements}). The earlier fix appended
 * {@code ":OUT"} / {@code ":IN"} to the 26-char event ULID, producing 30-char strings that
 * overflowed the column — PostgreSQL raised {@code 22001 value too long}, rolling back the
 * transaction and leaving the destination un-credited. The correct fix truncates the event uid
 * to 24 chars and appends a 2-char leg code ({@code D1} / {@code D2}).
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
    /**
     * Production ULID — exactly 26 chars like a real domain_events.uid.
     * Using a realistic value ensures the per-leg key computation is tested against a real-width input.
     */
    private static final String EVENT_UID        = "01KVJT7VQ0XWKE53X4MGM87BYN";

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

    // ── STOCK-039 regression: two distinct sourceEventUid values, each exactly 26 chars ───────

    @Test
    void handle_postsTransferOutAndTransferInWithDistinctSourceEventUids() throws Exception {
        TransferDispatchedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        ArgumentCaptor<String> sourceEventUidCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MovementType> movementTypeCaptor = ArgumentCaptor.forClass(MovementType.class);

        verify(posting, times(2)).post(
                anyLong(), anyLong(), anyLong(), anyLong(), any(BigDecimal.class),
                movementTypeCaptor.capture(),
                sourceEventUidCaptor.capture(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(), any(), any());

        List<String> eventUids = sourceEventUidCaptor.getAllValues();
        List<MovementType> types = movementTypeCaptor.getAllValues();

        // First post: TRANSFER_OUT with D1 leg code
        assertThat(types.get(0)).isEqualTo(MovementType.TRANSFER_OUT);
        assertThat(eventUids.get(0)).isEqualTo(EVENT_UID.substring(0, 24) + "D1");

        // Second post: TRANSFER_IN with D2 leg code
        assertThat(types.get(1)).isEqualTo(MovementType.TRANSFER_IN);
        assertThat(eventUids.get(1)).isEqualTo(EVENT_UID.substring(0, 24) + "D2");
    }

    /**
     * STOCK-039 root-cause regression: the per-leg source_event_uid must be exactly 26 chars.
     * The broken approach appended ":OUT" / ":IN" producing 30-char strings that exceeded the
     * VARCHAR(26) column and caused a PostgreSQL 22001 error, rolling back the entire handler TX.
     */
    @Test
    void handle_sourceEventUidKeysAreExactly26Chars() throws Exception {
        TransferDispatchedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        ArgumentCaptor<String> uidCaptor = ArgumentCaptor.forClass(String.class);
        verify(posting, times(2)).post(
                anyLong(), anyLong(), anyLong(), anyLong(), any(BigDecimal.class),
                any(MovementType.class),
                uidCaptor.capture(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(), any(), any());

        for (String legKey : uidCaptor.getAllValues()) {
            assertThat(legKey)
                    .as("source_event_uid leg key must be exactly 26 chars to fit VARCHAR(26)")
                    .hasSize(26);
        }
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
    void handle_valuationTransferCostCalledForTransitCredit() throws Exception {
        TransferDispatchedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        verify(valuation).transferCost(
                COMPANY_ID,
                BRANCH_ID, SRC_LOCATION_ID,
                BRANCH_ID, TRANSIT_LOC_ID,
                PRODUCT_ID, new BigDecimal("5"));
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

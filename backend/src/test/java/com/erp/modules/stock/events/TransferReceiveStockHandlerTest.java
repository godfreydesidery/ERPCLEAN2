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
import com.erp.modules.stock.domain.dto.TransferReceivedPayload;
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
 * Unit tests for {@link TransferReceiveStockHandler} — regression for STOCK-039.
 *
 * <p>The critical defect: both the TRANSFER_OUT (clear transit) and TRANSFER_IN (credit dest)
 * previously passed the same {@code sourceEventUid}. The idempotency backstop in
 * {@code StockPostingServiceImpl} deduplicates on {@code (sourceEventUid, productId)}, so the
 * second leg was silently skipped — the destination was never credited and the transit was left
 * negative/uncleared.
 *
 * <p>Fix: append {@code ":OUT"} / {@code ":IN"} suffix so the two legs have distinct keys.
 */
class TransferReceiveStockHandlerTest {

    private IdempotencyGuard          guard;
    private StockPostingService       posting;
    private InventoryValuationService valuation;
    private ObjectMapper              objectMapper;

    private TransferReceiveStockHandler handler;

    private static final Long   COMPANY_ID      = 1L;
    private static final Long   BRANCH_ID       = 2L;
    private static final Long   DST_LOCATION_ID = 20L;
    private static final Long   TRANSIT_LOC_ID  = 99L;
    private static final Long   PRODUCT_ID      = 100L;
    private static final String EVENT_UID       = "EVT-RECEIVE-001";

    @BeforeEach
    void setUp() {
        guard        = mock(IdempotencyGuard.class);
        posting      = mock(StockPostingService.class);
        valuation    = mock(InventoryValuationService.class);
        objectMapper = new ObjectMapper()
                .findAndRegisterModules(); // needed for Instant serialisation

        handler = new TransferReceiveStockHandler(guard, posting, valuation, objectMapper);

        when(guard.alreadyProcessed(anyString(), anyString())).thenReturn(false);
        when(posting.post(anyLong(), anyLong(), anyLong(), anyLong(), any(BigDecimal.class),
                any(MovementType.class), anyString(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(),
                any(), any())).thenReturn("MVT-UID");
    }

    // ── STOCK-039 regression: destination must be credited ──────────────────

    @Test
    void handle_postsTransferOutAtTransitAndTransferInAtDest() throws Exception {
        TransferReceivedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        ArgumentCaptor<MovementType> typeCaptor     = ArgumentCaptor.forClass(MovementType.class);
        ArgumentCaptor<Long>         locationCaptor = ArgumentCaptor.forClass(Long.class);

        verify(posting, times(2)).post(
                anyLong(), anyLong(), locationCaptor.capture(), anyLong(), any(BigDecimal.class),
                typeCaptor.capture(),
                anyString(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(), any(), any());

        // Leg 1: TRANSFER_OUT at in-transit (clears transit)
        assertThat(typeCaptor.getAllValues().get(0)).isEqualTo(MovementType.TRANSFER_OUT);
        assertThat(locationCaptor.getAllValues().get(0)).isEqualTo(TRANSIT_LOC_ID);

        // Leg 2: TRANSFER_IN at destination (credits dest) — this was the missing leg (STOCK-039)
        assertThat(typeCaptor.getAllValues().get(1)).isEqualTo(MovementType.TRANSFER_IN);
        assertThat(locationCaptor.getAllValues().get(1)).isEqualTo(DST_LOCATION_ID);
    }

    @Test
    void handle_outAndInSourceEventUidsAreDifferent() throws Exception {
        // Core invariant: the two legs must have distinct sourceEventUid values so the
        // idempotency backstop does NOT suppress the destination TRANSFER_IN.
        TransferReceivedPayload payload = buildPayload();
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
    void handle_sourceEventUidSuffixesAreOutAndIn() throws Exception {
        TransferReceivedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        ArgumentCaptor<String> uidCaptor = ArgumentCaptor.forClass(String.class);
        verify(posting, times(2)).post(
                anyLong(), anyLong(), anyLong(), anyLong(), any(BigDecimal.class),
                any(MovementType.class),
                uidCaptor.capture(), anyString(), anyString(),
                any(), any(), any(Instant.class), any(), any(), any());

        assertThat(uidCaptor.getAllValues().get(0)).isEqualTo(EVENT_UID + ":OUT");
        assertThat(uidCaptor.getAllValues().get(1)).isEqualTo(EVENT_UID + ":IN");
    }

    @Test
    void handle_outQtyIsNegatedInAtDestIsPositive() throws Exception {
        TransferReceivedPayload payload = buildPayload();
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
        assertThat(outQty).isEqualByComparingTo(new BigDecimal("-3")); // transit cleared
        assertThat(inQty).isEqualByComparingTo(new BigDecimal("3"));   // dest credited
    }

    @Test
    void handle_valuationTransferCostCalledForDestinationCredit() throws Exception {
        // valuation.transferCost must move cost from transit → dest (issue #12)
        TransferReceivedPayload payload = buildPayload();
        DomainEvent event = buildEvent(EVENT_UID, payload);

        handler.handle(event);

        verify(valuation).transferCost(
                eq(COMPANY_ID),
                eq(BRANCH_ID), eq(TRANSIT_LOC_ID),
                eq(BRANCH_ID), eq(DST_LOCATION_ID),
                eq(PRODUCT_ID), eq(new BigDecimal("3")));
    }

    @Test
    void handle_idempotencyGuardSkipsAlreadyProcessed() throws Exception {
        when(guard.alreadyProcessed(TransferReceiveStockHandler.CONSUMER, EVENT_UID))
                .thenReturn(true);

        DomainEvent event = buildEvent(EVENT_UID, buildPayload());
        handler.handle(event);

        verify(posting, never()).post(anyLong(), anyLong(), anyLong(), anyLong(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(valuation, never()).transferCost(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private TransferReceivedPayload buildPayload() {
        TransferDispatchedPayload.LineItem line = new TransferDispatchedPayload.LineItem(
                PRODUCT_ID, null, null,
                new BigDecimal("3"),
                new BigDecimal("50"),
                new BigDecimal("150"));
        return new TransferReceivedPayload(
                "XFER-UID-1", COMPANY_ID,
                BRANCH_ID, DST_LOCATION_ID,
                TRANSIT_LOC_ID,
                Instant.now(),
                List.of(line));
    }

    private DomainEvent buildEvent(String uid, TransferReceivedPayload payload) throws Exception {
        DomainEvent event = mock(DomainEvent.class);
        when(event.getUid()).thenReturn(uid);
        when(event.getCompanyId()).thenReturn(COMPANY_ID);
        when(event.getBranchId()).thenReturn(BRANCH_ID);
        when(event.getPayload()).thenReturn(objectMapper.writeValueAsString(payload));
        when(event.getEventType()).thenReturn(DomainEventType.STOCK_TRANSFER_RECEIVED);
        return event;
    }
}

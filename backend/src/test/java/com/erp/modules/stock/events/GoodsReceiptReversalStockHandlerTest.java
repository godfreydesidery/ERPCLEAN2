package com.erp.modules.stock.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.dto.StockReceiptVoidedPayload;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GoodsReceiptReversalStockHandler} — adversarial-review FIX A/B/C.
 *
 * <p>FIX A: reversal quantity is sourced from the SAME payload line that supplies the lot, never
 * from the ledger movement's own quantity (the movement finder has no {@code ORDER BY}).
 * FIX B: batch reversal also fires when the line's {@code lotTracked} flag is set (not just when
 * lot/expiry data is present), so a lot-tracked product received with a blank lot (the forward
 * path's {@code "UNTRACKED"} sentinel) is reversed instead of silently skipped.
 * FIX C: batch reversal uses the movement's OWN receipt-time {@code locationId}, not a location
 * re-resolved (and possibly since-changed) at void time.
 */
class GoodsReceiptReversalStockHandlerTest {

    private static final Long COMPANY_ID  = 1L;
    private static final Long BRANCH_ID   = 2L;
    private static final Long PRODUCT_ID  = 100L;
    private static final Long MOVEMENT_LOCATION_ID = 55L;
    private static final Long STALE_DEFAULT_LOCATION_ID = 999L;
    private static final String RECEIPT_UID = "01RECEIPTUIDGOODSRECEIPT01";
    private static final String EVENT_UID   = "01KVJT7VQ0XWKE53X4MGM87BYN";

    private IdempotencyGuard          guard;
    private StockPostingService       posting;
    private StockMovementRepository   movementRepository;
    private InventoryValuationService valuation;
    private InventoryGlPoster         glPoster;
    private StockBatchService         batchService;
    private StockSerialService        serialService;
    private StockLocationRepository   locationRepo;
    private ObjectMapper              objectMapper;

    private GoodsReceiptReversalStockHandler handler;

    @BeforeEach
    void setUp() {
        guard              = mock(IdempotencyGuard.class);
        posting            = mock(StockPostingService.class);
        movementRepository = mock(StockMovementRepository.class);
        valuation          = mock(InventoryValuationService.class);
        glPoster           = mock(InventoryGlPoster.class);
        batchService       = mock(StockBatchService.class);
        serialService      = mock(StockSerialService.class);
        locationRepo       = mock(StockLocationRepository.class);
        objectMapper       = new ObjectMapper().findAndRegisterModules();

        handler = new GoodsReceiptReversalStockHandler(
                guard, posting, movementRepository, valuation, glPoster,
                batchService, serialService, locationRepo, objectMapper);

        when(guard.alreadyProcessed(anyString(), anyString())).thenReturn(false);
        // A stale branch-default location — distinct from the movements' own location, so any
        // test that would (wrongly) fall back to it is caught by the assertion (FIX C).
        when(locationRepo.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.empty());
        when(glPoster.postReceiptReversalInNewTx(anyLong(), anyLong(), any(), anyString(), any(),
                anyString(), any(BigDecimal.class))).thenReturn("GL-UID-1");
    }

    @AfterEach
    void tearDown() {
        com.erp.platform.security.RequestContext.clear();
    }

    // ── FIX A — two lines, same product, different lots: never crossed ────────────────────────

    @Test
    void handle_twoMovementsSameProductDifferentLots_reversesEachLotByItsOwnLineQty() throws Exception {
        StockReceiptVoidedPayload.LineItem lineA = new StockReceiptVoidedPayload.LineItem(
                PRODUCT_ID, "PRD-UID-1", 10L, new BigDecimal("5"),
                "LOT-A", null, null, List.of(), false);
        StockReceiptVoidedPayload.LineItem lineB = new StockReceiptVoidedPayload.LineItem(
                PRODUCT_ID, "PRD-UID-1", 10L, new BigDecimal("8"),
                "LOT-B", null, null, List.of(), false);
        StockReceiptVoidedPayload payload = new StockReceiptVoidedPayload(
                RECEIPT_UID, COMPANY_ID, BRANCH_ID, List.of(lineA, lineB), "GRN-0001");

        // Movements come back in the OPPOSITE order to the payload lines (no ORDER BY on the
        // finder) — the movement that happens to be iterated first actually originated from
        // line B's receipt (qty 8), the second from line A's receipt (qty 5).
        StockMovement movementForLineB = buildMovement(new BigDecimal("8"));
        StockMovement movementForLineA = buildMovement(new BigDecimal("5"));
        when(movementRepository.findBySourceDocumentUidAndMovementType(RECEIPT_UID, MovementType.GOODS_RECEIPT))
                .thenReturn(List.of(movementForLineB, movementForLineA));

        DomainEvent event = buildEvent(payload);
        handler.handle(event);

        // Lot A must lose exactly its own line's qty (5), never movementForLineB's qty (8).
        verify(batchService).reverseReceiptQty(
                COMPANY_ID, BRANCH_ID, MOVEMENT_LOCATION_ID, PRODUCT_ID,
                "LOT-A", new BigDecimal("5"), null);
        // Lot B must lose exactly its own line's qty (8), never movementForLineA's qty (5).
        verify(batchService).reverseReceiptQty(
                COMPANY_ID, BRANCH_ID, MOVEMENT_LOCATION_ID, PRODUCT_ID,
                "LOT-B", new BigDecimal("8"), null);
    }

    // ── FIX B — lot-tracked product, blank lot → UNTRACKED sentinel is reversed ────────────────

    @Test
    void handle_lotTrackedProductBlankLot_reversesUntrackedSentinelBatchWithLineQty() throws Exception {
        StockReceiptVoidedPayload.LineItem line = new StockReceiptVoidedPayload.LineItem(
                PRODUCT_ID, "PRD-UID-1", 10L, new BigDecimal("12"),
                null, null, null, List.of(), true /* lotTracked */);
        StockReceiptVoidedPayload payload = new StockReceiptVoidedPayload(
                RECEIPT_UID, COMPANY_ID, BRANCH_ID, List.of(line), "GRN-0002");

        StockMovement movement = buildMovement(new BigDecimal("12"));
        when(movementRepository.findBySourceDocumentUidAndMovementType(RECEIPT_UID, MovementType.GOODS_RECEIPT))
                .thenReturn(List.of(movement));

        DomainEvent event = buildEvent(payload);
        handler.handle(event);

        verify(batchService).reverseReceiptQty(
                COMPANY_ID, BRANCH_ID, MOVEMENT_LOCATION_ID, PRODUCT_ID,
                "UNTRACKED", new BigDecimal("12"), null);
    }

    @Test
    void handle_notLotTrackedNoLotData_batchReversalSkipped() throws Exception {
        StockReceiptVoidedPayload.LineItem line = new StockReceiptVoidedPayload.LineItem(
                PRODUCT_ID, "PRD-UID-1", 10L, new BigDecimal("3"),
                null, null, null, List.of(), false /* not lotTracked, no lot data */);
        StockReceiptVoidedPayload payload = new StockReceiptVoidedPayload(
                RECEIPT_UID, COMPANY_ID, BRANCH_ID, List.of(line), "GRN-0003");

        StockMovement movement = buildMovement(new BigDecimal("3"));
        when(movementRepository.findBySourceDocumentUidAndMovementType(RECEIPT_UID, MovementType.GOODS_RECEIPT))
                .thenReturn(List.of(movement));

        handler.handle(buildEvent(payload));

        verify(batchService, never()).reverseReceiptQty(
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any(), any());
    }

    // ── FIX C — batch reversal uses the movement's OWN location, not a re-resolved default ─────

    @Test
    void handle_batchReversal_usesMovementsOwnLocationNotReResolvedDefault() throws Exception {
        StockReceiptVoidedPayload.LineItem line = new StockReceiptVoidedPayload.LineItem(
                PRODUCT_ID, "PRD-UID-1", 10L, new BigDecimal("6"),
                "LOT-X", null, null, List.of(), false);
        StockReceiptVoidedPayload payload = new StockReceiptVoidedPayload(
                RECEIPT_UID, COMPANY_ID, BRANCH_ID, List.of(line), "GRN-0004");

        // The branch default location (if the code wrongly re-resolved it) would be a DIFFERENT,
        // stale location — stub it non-empty to prove the movement's own location wins.
        com.erp.modules.stock.domain.entity.StockLocation staleDefault =
                mock(com.erp.modules.stock.domain.entity.StockLocation.class);
        when(staleDefault.getId()).thenReturn(STALE_DEFAULT_LOCATION_ID);
        when(locationRepo.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.of(staleDefault));

        StockMovement movement = buildMovement(new BigDecimal("6"));
        when(movementRepository.findBySourceDocumentUidAndMovementType(RECEIPT_UID, MovementType.GOODS_RECEIPT))
                .thenReturn(List.of(movement));

        handler.handle(buildEvent(payload));

        verify(batchService).reverseReceiptQty(
                COMPANY_ID, BRANCH_ID, MOVEMENT_LOCATION_ID, PRODUCT_ID,
                "LOT-X", new BigDecimal("6"), null);
        verify(batchService, never()).reverseReceiptQty(
                any(), any(), org.mockito.ArgumentMatchers.eq(STALE_DEFAULT_LOCATION_ID),
                any(), any(), any(), any());
    }

    // ── Serials — location-independent, unchanged ───────────────────────────────────────────────

    @Test
    void handle_serials_removeReceivedCalledPerSerialWithReceiptUid() throws Exception {
        StockReceiptVoidedPayload.LineItem line = new StockReceiptVoidedPayload.LineItem(
                PRODUCT_ID, "PRD-UID-1", 10L, new BigDecimal("2"),
                null, null, null, List.of("SN-1", "SN-2"), false);
        StockReceiptVoidedPayload payload = new StockReceiptVoidedPayload(
                RECEIPT_UID, COMPANY_ID, BRANCH_ID, List.of(line), "GRN-0005");

        StockMovement movement = buildMovement(new BigDecimal("2"));
        when(movementRepository.findBySourceDocumentUidAndMovementType(RECEIPT_UID, MovementType.GOODS_RECEIPT))
                .thenReturn(List.of(movement));

        handler.handle(buildEvent(payload));

        verify(serialService).removeReceived(COMPANY_ID, PRODUCT_ID, "SN-1", RECEIPT_UID);
        verify(serialService).removeReceived(COMPANY_ID, PRODUCT_ID, "SN-2", RECEIPT_UID);
    }

    // ── Idempotency ──────────────────────────────────────────────────────────────────────────

    @Test
    void handle_idempotencyGuardSkipsAlreadyProcessed() throws Exception {
        when(guard.alreadyProcessed(GoodsReceiptReversalStockHandler.CONSUMER, EVENT_UID)).thenReturn(true);

        StockReceiptVoidedPayload payload = new StockReceiptVoidedPayload(
                RECEIPT_UID, COMPANY_ID, BRANCH_ID, List.of(), "GRN-0006");
        handler.handle(buildEvent(payload));

        verify(movementRepository, never())
                .findBySourceDocumentUidAndMovementType(anyString(), any(MovementType.class));
        verify(batchService, never()).reverseReceiptQty(
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private StockMovement buildMovement(BigDecimal qty) {
        return new StockMovement(COMPANY_ID, BRANCH_ID, MOVEMENT_LOCATION_ID, PRODUCT_ID,
                MovementType.GOODS_RECEIPT, qty,
                "SOME-EVENT-UID-000000000A", "GOODS_RECEIPT", RECEIPT_UID,
                null, null, Instant.now(), null,
                new BigDecimal("10.00"), qty.multiply(new BigDecimal("10.00")));
    }

    private DomainEvent buildEvent(StockReceiptVoidedPayload payload) throws Exception {
        DomainEvent event = mock(DomainEvent.class);
        when(event.getUid()).thenReturn(EVENT_UID);
        when(event.getCompanyId()).thenReturn(COMPANY_ID);
        when(event.getBranchId()).thenReturn(BRANCH_ID);
        when(event.getPayload()).thenReturn(objectMapper.writeValueAsString(payload));
        when(event.getEventType()).thenReturn(DomainEventType.STOCK_RECEIPT_VOIDED);
        return event;
    }
}

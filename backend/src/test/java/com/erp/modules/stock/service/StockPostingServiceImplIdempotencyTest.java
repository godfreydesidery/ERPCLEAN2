package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for FOLLOW-003: {@link StockPostingServiceImpl} must be idempotent when
 * the same (source_event_uid, product_id) pair is posted more than once (event redelivery).
 *
 * <p>On the first call the movement is inserted and on-hand delta applied.
 * On a second call with the same (source_event_uid, product_id) the service must silently skip
 * both the insert and the delta — no exception, no double-count.
 */
class StockPostingServiceImplIdempotencyTest {

    private StockMovementRepository movementRepo;
    private StockOnHandRepository   onHandRepo;
    private LocationResolver        locationResolver;
    private StockPostingServiceImpl service;

    private static final Long   COMPANY_ID       = 1L;
    private static final Long   BRANCH_ID        = 2L;
    private static final Long   LOCATION_ID      = 10L;
    private static final Long   PRODUCT_ID       = 100L;
    private static final String SOURCE_EVENT_UID = "EVT-REDELIVERY-001";

    @BeforeEach
    void setUp() {
        movementRepo     = mock(StockMovementRepository.class);
        onHandRepo       = mock(StockOnHandRepository.class);
        locationResolver = mock(LocationResolver.class);
        service = new StockPostingServiceImpl(movementRepo, onHandRepo, locationResolver);

        // Default: no existing movement (first call scenario)
        when(movementRepo.existsBySourceEventUidAndProductId(anyString(), anyLong()))
                .thenReturn(false);

        // Stub save to return a StockMovement with a uid
        when(movementRepo.save(any(StockMovement.class))).thenAnswer(inv -> {
            StockMovement m = inv.getArgument(0);
            // inject uid via reflection (it's set in the constructor via UidEntity)
            return m;
        });

        // Stub on-hand repo to return an empty Optional (triggers upsert path)
        when(onHandRepo.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Optional.empty());
        when(onHandRepo.save(any(StockOnHand.class))).thenAnswer(inv -> inv.getArgument(0));

        when(locationResolver.defaultLocationId(anyLong(), anyLong())).thenReturn(LOCATION_ID);
    }

    @Test
    void post_firstCall_insertsMovementAndAppliesOnHandDelta() {
        // arrange: no existing movement
        when(movementRepo.existsBySourceEventUidAndProductId(SOURCE_EVENT_UID, PRODUCT_ID))
                .thenReturn(false);

        // act
        service.post(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                new BigDecimal("10"), MovementType.GOODS_RECEIPT,
                SOURCE_EVENT_UID, "GOODS_RECEIPT", "GRN-UID-1",
                null, null, Instant.now(), null,
                new BigDecimal("50"), new BigDecimal("500"),
                null, null, null, null);

        // assert: movement saved once, on-hand saved at least once (upsert creates + delta applies)
        verify(movementRepo, times(1)).save(any(StockMovement.class));
        verify(onHandRepo, org.mockito.Mockito.atLeastOnce()).save(any(StockOnHand.class));
    }

    @Test
    void post_secondCallWithSameEventAndProduct_isNoOpSuccess() {
        // arrange: movement already exists (redelivery scenario)
        when(movementRepo.existsBySourceEventUidAndProductId(SOURCE_EVENT_UID, PRODUCT_ID))
                .thenReturn(true);

        StockMovement existingMovement = mock(StockMovement.class);
        when(existingMovement.getProductId()).thenReturn(PRODUCT_ID);
        when(existingMovement.getUid()).thenReturn("EXISTING-MVT-UID");
        when(movementRepo.findBySourceEventUid(SOURCE_EVENT_UID))
                .thenReturn(List.of(existingMovement));

        // act — must NOT throw
        String result = service.post(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                new BigDecimal("10"), MovementType.GOODS_RECEIPT,
                SOURCE_EVENT_UID, "GOODS_RECEIPT", "GRN-UID-1",
                null, null, Instant.now(), null,
                new BigDecimal("50"), new BigDecimal("500"),
                null, null, null, null);

        // assert: no new movement inserted, no on-hand mutation
        verify(movementRepo, never()).save(any(StockMovement.class));
        verify(onHandRepo, never()).save(any(StockOnHand.class));

        // result is the existing movement uid (caller can log it; outbox marks dispatched)
        assertThat(result).isEqualTo("EXISTING-MVT-UID");
    }

    @Test
    void post_nullSourceEventUid_doesNotCheckIdempotency_alwaysInserts() {
        // Manual adjustments have no source_event_uid — idempotency guard must NOT fire.
        when(movementRepo.existsBySourceEventUidAndProductId(null, PRODUCT_ID))
                .thenReturn(false); // should never be called, but safe default

        service.post(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                new BigDecimal("5"), MovementType.ADJUSTMENT,
                null, null, null,      // sourceEventUid = null
                "DAMAGE", null, Instant.now(), 99L,
                null, null,
                null, null, null, null);

        // assert: movement always saved for manual ops (no idempotency guard on null event uid)
        verify(movementRepo, times(1)).save(any(StockMovement.class));
        // existsBySourceEventUidAndProductId must NOT be invoked for null sourceEventUid
        verify(movementRepo, never()).existsBySourceEventUidAndProductId(any(), anyLong());
    }
}

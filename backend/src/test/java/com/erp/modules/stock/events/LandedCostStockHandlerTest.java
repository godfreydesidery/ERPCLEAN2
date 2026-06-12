package com.erp.modules.stock.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.purchases.domain.dto.LandedCostAllocatedPayload;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for LandedCostStockHandler.
 *
 * <p>Proves fix for adversarial-review MEDIUM finding 5: GL posting amount must equal the SUM of
 * allocated line amounts, not payload.totalAmount() which can be null/zero/corrupted.
 */
class LandedCostStockHandlerTest {

    private IdempotencyGuard          guard;
    private InventoryValuationService valuation;
    private InventoryGlPoster         glPoster;
    private ObjectMapper              objectMapper;

    private LandedCostStockHandler handler;

    @BeforeEach
    void setUp() {
        guard        = mock(IdempotencyGuard.class);
        valuation    = mock(InventoryValuationService.class);
        glPoster     = mock(InventoryGlPoster.class);
        objectMapper = new ObjectMapper();

        handler = new LandedCostStockHandler(guard, valuation, glPoster, objectMapper);

        when(guard.alreadyProcessed(anyString(), anyString())).thenReturn(false);
        when(glPoster.postLandedCostInNewTx(anyLong(), anyLong(), any(), anyString(), anyString(), any()))
                .thenReturn("GL-ENTRY-1");
    }

    // -------------------------------------------------------------------------
    // Fix 5 (MEDIUM): GL amount = sum of lines, NOT payload.totalAmount()
    // -------------------------------------------------------------------------

    @Test
    void handle_glAmountEqualsLineSumNotPayloadTotal() throws Exception {
        // arrange: 3 lines summing to 100.00; payload.totalAmount is intentionally set to a
        // different value (simulates corruption / null-during-serialisation scenario)
        LandedCostAllocatedPayload payload = new LandedCostAllocatedPayload(
                "LC-UID-1", 10L, 20L,
                new BigDecimal("999.99"),   // corrupt totalAmount — must NOT be used for GL
                "TZS",
                List.of(
                        new LandedCostAllocatedPayload.AllocationLine(1L, "GRL-1", 1L, new BigDecimal("33.33")),
                        new LandedCostAllocatedPayload.AllocationLine(2L, "GRL-2", 2L, new BigDecimal("33.33")),
                        new LandedCostAllocatedPayload.AllocationLine(3L, "GRL-3", 3L, new BigDecimal("33.34"))
                ));

        DomainEvent event = buildEvent("EVT-LC-1", payload);

        // act
        handler.handle(event);

        // assert: GL posted with 33.33 + 33.33 + 33.34 = 100.00, NOT the 999.99 totalAmount
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(glPoster).postLandedCostInNewTx(
                eq(10L), eq(20L), any(LocalDate.class),
                eq("LC-UID-1"), eq("TZS"), amountCaptor.capture());

        BigDecimal postedAmount = amountCaptor.getValue();
        assertThat(postedAmount).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void handle_nullTotalAmount_glStillPostsLineSums() throws Exception {
        // payload.totalAmount = null  — the old code guarded "!= null && > 0" so would skip GL;
        // the fixed code always sums lines regardless.
        LandedCostAllocatedPayload payload = new LandedCostAllocatedPayload(
                "LC-UID-2", 10L, 20L,
                null,   // null totalAmount
                "TZS",
                List.of(
                        new LandedCostAllocatedPayload.AllocationLine(1L, "GRL-A", 1L, new BigDecimal("50.00")),
                        new LandedCostAllocatedPayload.AllocationLine(2L, "GRL-B", 2L, new BigDecimal("50.00"))
                ));

        DomainEvent event = buildEvent("EVT-LC-2", payload);

        handler.handle(event);

        // assert: GL still posted with sum = 100.00
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(glPoster).postLandedCostInNewTx(
                eq(10L), eq(20L), any(LocalDate.class),
                eq("LC-UID-2"), eq("TZS"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void handle_allZeroAllocations_glSkipped() throws Exception {
        // All line amounts are zero — GL must be skipped (nothing to post)
        LandedCostAllocatedPayload payload = new LandedCostAllocatedPayload(
                "LC-UID-3", 10L, 20L,
                BigDecimal.ZERO,
                "TZS",
                List.of(
                        new LandedCostAllocatedPayload.AllocationLine(1L, "GRL-C", 1L, BigDecimal.ZERO)
                ));

        DomainEvent event = buildEvent("EVT-LC-3", payload);

        handler.handle(event);

        // assert: GL NOT posted — nothing to capitalise
        verify(glPoster, never()).postLandedCostInNewTx(anyLong(), anyLong(), any(), anyString(), anyString(), any());
    }

    @Test
    void handle_valuationCalledForEachLine() throws Exception {
        // Verify that applyLandedCost is called once per allocation line
        LandedCostAllocatedPayload payload = new LandedCostAllocatedPayload(
                "LC-UID-4", 10L, 20L,
                new BigDecimal("200.00"),
                "TZS",
                List.of(
                        new LandedCostAllocatedPayload.AllocationLine(1L, "GRL-D1", 1L, new BigDecimal("100.00")),
                        new LandedCostAllocatedPayload.AllocationLine(2L, "GRL-D2", 2L, new BigDecimal("100.00"))
                ));

        DomainEvent event = buildEvent("EVT-LC-4", payload);

        handler.handle(event);

        // assert: two calls to applyLandedCost, one per product
        verify(valuation, times(2)).applyLandedCost(eq(10L), eq(20L), anyLong(), any(BigDecimal.class));
        verify(valuation).applyLandedCost(10L, 20L, 1L, new BigDecimal("100.00"));
        verify(valuation).applyLandedCost(10L, 20L, 2L, new BigDecimal("100.00"));
    }

    @Test
    void handle_idempotencyGuardSkipsAlreadyProcessed() throws Exception {
        when(guard.alreadyProcessed(LandedCostStockHandler.CONSUMER, "EVT-DUP")).thenReturn(true);

        LandedCostAllocatedPayload payload = new LandedCostAllocatedPayload(
                "LC-UID-5", 10L, 20L, new BigDecimal("100.00"), "TZS",
                List.of(new LandedCostAllocatedPayload.AllocationLine(1L, "GRL-E", 1L, new BigDecimal("100.00"))));

        DomainEvent event = buildEvent("EVT-DUP", payload);

        handler.handle(event);

        // assert: valuation and GL skipped for duplicate event
        verify(valuation, never()).applyLandedCost(anyLong(), anyLong(), anyLong(), any());
        verify(glPoster, never()).postLandedCostInNewTx(anyLong(), anyLong(), any(), anyString(), anyString(), any());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private DomainEvent buildEvent(String uid, LandedCostAllocatedPayload payload) throws Exception {
        DomainEvent event = mock(DomainEvent.class);
        when(event.getUid()).thenReturn(uid);
        when(event.getCompanyId()).thenReturn(10L);
        when(event.getBranchId()).thenReturn(20L);
        when(event.getPayload()).thenReturn(objectMapper.writeValueAsString(payload));
        when(event.getEventType()).thenReturn(DomainEventType.LANDED_COST_ALLOCATED);
        return event;
    }
}

package com.erp.modules.sales.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.sales.domain.dto.DropshipFulfilledPayload;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link DropshipFulfilCogsHandler} (ADR-0029 D-11, finding 1).
 *
 * <p>Verifies:
 * <ul>
 *   <li>DR COGS / CR GRNI journal is posted at qty × supplierUnitCost</li>
 *   <li>Journal balances (Σdebit == Σcredit)</li>
 *   <li>Correct JournalSourceType.COGS</li>
 *   <li>sourceRef = salesOrderLineUid</li>
 *   <li>Idempotency: already-processed events are no-ops</li>
 * </ul>
 */
class DropshipFulfilCogsHandlerTest {

    private IdempotencyGuard     guard;
    private GLPostingSafeInvoker glInvoker;
    private GLConfigResolver     glConfig;
    private ObjectMapper         objectMapper;
    private DropshipFulfilCogsHandler handler;

    @BeforeEach
    void setUp() {
        guard        = mock(IdempotencyGuard.class);
        glInvoker    = mock(GLPostingSafeInvoker.class);
        glConfig     = mock(GLConfigResolver.class);
        objectMapper = new ObjectMapper();
        handler      = new DropshipFulfilCogsHandler(guard, glInvoker, glConfig, objectMapper);
    }

    @Test
    void eventType_isDropshipFulfilled() {
        assertThat(handler.eventType()).isEqualTo(DomainEventType.DROPSHIP_FULFILLED);
    }

    /**
     * Core invariant: 10 units at 100 TZS = COGS 1000 TZS.
     * DR COGS 1000 / CR GRNI 1000 — balanced.
     */
    @Test
    void handle_postsCogsJournal_balancedDrCogsCrGrni() throws Exception {
        DropshipFulfilledPayload payload = new DropshipFulfilledPayload(
                "SOL-001", "PO-001", 1L, 2L, 100L,
                new BigDecimal("10"), new BigDecimal("100.00"), "TZS");

        DomainEvent event = buildEvent(payload, "EVT-001");

        when(guard.alreadyProcessed(DropshipFulfilCogsHandler.CONSUMER, "EVT-001")).thenReturn(false);
        ChartOfAccount cogsAcct = mockCoa(51L);
        ChartOfAccount grniAcct = mockCoa(21L);
        when(glConfig.resolve(1L, GlConfigKey.COGS)).thenReturn(cogsAcct);
        when(glConfig.resolve(1L, GlConfigKey.GRNI)).thenReturn(grniAcct);
        when(glInvoker.postInNewTx(any())).thenReturn(
                new JournalEntryDto(77L, "JE-001", 1L, null, null, null, null, null, null, null, null, null));

        handler.handle(event);

        ArgumentCaptor<JournalEntryDraft> captor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glInvoker).postInNewTx(captor.capture());
        JournalEntryDraft draft = captor.getValue();

        // Source type = COGS (not POS_VARIANCE, not SALES)
        assertThat(draft.sourceType()).isEqualTo(JournalSourceType.COGS);
        // sourceRef = salesOrderLineUid
        assertThat(draft.sourceRef()).isEqualTo("SOL-001");

        // Balanced: Σdebit == Σcredit
        BigDecimal totalDebit  = draft.lines().stream()
                .map(l -> l.debitAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = draft.lines().stream()
                .map(l -> l.creditAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);

        // Amount = 10 × 100 = 1000.0000
        assertThat(totalDebit).isEqualByComparingTo(new BigDecimal("1000.0000"));

        // DR leg = COGS account
        var drLeg = draft.lines().stream()
                .filter(l -> l.debitAmount().compareTo(BigDecimal.ZERO) > 0)
                .findFirst().orElseThrow();
        assertThat(drLeg.accountId()).isEqualTo(51L);

        // CR leg = GRNI account
        var crLeg = draft.lines().stream()
                .filter(l -> l.creditAmount().compareTo(BigDecimal.ZERO) > 0)
                .findFirst().orElseThrow();
        assertThat(crLeg.accountId()).isEqualTo(21L);

        // Currency from payload
        draft.lines().forEach(l -> assertThat(l.currency()).isEqualTo("TZS"));

        // Idempotency mark
        verify(guard).markProcessed(DropshipFulfilCogsHandler.CONSUMER, "EVT-001");
    }

    /**
     * Idempotency: already-processed event must no-op — no GL post.
     */
    @Test
    void handle_alreadyProcessed_noGlPost() throws Exception {
        DropshipFulfilledPayload payload = new DropshipFulfilledPayload(
                "SOL-002", "PO-002", 1L, 2L, 100L,
                new BigDecimal("5"), new BigDecimal("50.00"), "TZS");

        DomainEvent event = buildEvent(payload, "EVT-002");
        when(guard.alreadyProcessed(DropshipFulfilCogsHandler.CONSUMER, "EVT-002")).thenReturn(true);

        handler.handle(event);

        verify(glInvoker, never()).postInNewTx(any());
        verify(guard, never()).markProcessed(any(), any());
    }

    /**
     * COGS amount rounds HALF_UP (ADR-0005).
     * qty=3, cost=1.005 → 3.015 rounded to 4dp = 3.0150
     */
    @Test
    void handle_cogsAmount_roundsHalfUp() throws Exception {
        DropshipFulfilledPayload payload = new DropshipFulfilledPayload(
                "SOL-003", "PO-003", 1L, 2L, 100L,
                new BigDecimal("3"), new BigDecimal("1.0050"), "TZS");

        DomainEvent event = buildEvent(payload, "EVT-003");
        when(guard.alreadyProcessed(DropshipFulfilCogsHandler.CONSUMER, "EVT-003")).thenReturn(false);
        when(glConfig.resolve(1L, GlConfigKey.COGS)).thenReturn(mockCoa(51L));
        when(glConfig.resolve(1L, GlConfigKey.GRNI)).thenReturn(mockCoa(21L));
        when(glInvoker.postInNewTx(any())).thenReturn(
                new JournalEntryDto(78L, "JE-002", 1L, null, null, null, null, null, null, null, null, null));

        handler.handle(event);

        ArgumentCaptor<JournalEntryDraft> captor = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glInvoker).postInNewTx(captor.capture());
        BigDecimal posted = captor.getValue().lines().stream()
                .map(l -> l.debitAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 3 × 1.0050 = 3.0150 (4dp HALF_UP)
        assertThat(posted).isEqualByComparingTo(new BigDecimal("3.0150"));
    }

    /**
     * Zero qty: handler skips GL, marks idempotency, does not throw.
     */
    @Test
    void handle_zeroQty_skipsGlAndMarksProcessed() throws Exception {
        DropshipFulfilledPayload payload = new DropshipFulfilledPayload(
                "SOL-004", "PO-004", 1L, 2L, 100L,
                BigDecimal.ZERO, new BigDecimal("100.00"), "TZS");

        DomainEvent event = buildEvent(payload, "EVT-004");
        when(guard.alreadyProcessed(DropshipFulfilCogsHandler.CONSUMER, "EVT-004")).thenReturn(false);

        handler.handle(event);

        verify(glInvoker, never()).postInNewTx(any());
        verify(guard).markProcessed(DropshipFulfilCogsHandler.CONSUMER, "EVT-004");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DomainEvent buildEvent(DropshipFulfilledPayload payload, String uid)
            throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        DomainEvent event = new DomainEvent(
                DomainEventType.DROPSHIP_FULFILLED, DomainEventType.AGG_SALES_ORDER,
                1L, "SO-001", 1L, 2L, json);
        // inject uid via reflection (it's set by @PrePersist in production; null here without DB)
        var uidField = DomainEvent.class.getDeclaredField("uid");
        uidField.setAccessible(true);
        uidField.set(event, uid);
        return event;
    }

    private ChartOfAccount mockCoa(Long id) {
        ChartOfAccount coa = mock(ChartOfAccount.class);
        when(coa.getId()).thenReturn(id);
        return coa;
    }
}

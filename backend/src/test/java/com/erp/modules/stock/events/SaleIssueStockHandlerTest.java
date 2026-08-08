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
import com.erp.modules.sales.domain.dto.SaleFinalisedPayload;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.RecipeExplosionResolver;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.modules.stock.service.StockReservationService;
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
import org.mockito.InOrder;

/**
 * Unit tests for {@link SaleIssueStockHandler} — the two stock-correctness defects found in the
 * persona UAT.
 *
 * <p><b>C1 — a repeated product lost every line but the first.</b> All lines of one invoice shared
 * the invoice's event uid as their {@code source_event_uid}, so the
 * {@code (source_event_uid, product_id)} idempotency backstop read line 2 of the same product as a
 * redelivery of line 1 and skipped it. Live evidence: invoice INV-0458 carried 3 + 4 of one product,
 * charged for 7 and posted COGS for 7, but wrote a single {@code −3} movement.
 *
 * <p><b>C1b — valuation moved even when the quantity did not.</b> {@code costIssue} mutates and
 * saves {@code on_hand_value} immediately, and it ran <em>before</em> {@code post} decided whether to
 * no-op. A suppressed posting therefore still credited value out of inventory.
 *
 * <p><b>C2 (release half) — the sale's reservation must be released as the movement posts.</b>
 * {@code NegativeStockGuard} claims the quantity synchronously at finalise because the deduction
 * below is asynchronous; the claim has to lift in the very transaction that finally deducts, or the
 * stock is counted against twice.
 */
class SaleIssueStockHandlerTest {

    private IdempotencyGuard          guard;
    private StockPostingService       posting;
    private ProductService            productService;
    private RecipeExplosionResolver   explosion;
    private InventoryValuationService valuation;
    private InventoryGlPoster         glPoster;
    private StockReservationService   reservations;
    private ObjectMapper              objectMapper;

    private SaleIssueStockHandler handler;

    private static final Long   COMPANY_ID  = 1L;
    private static final Long   BRANCH_ID   = 2L;
    private static final Long   PRODUCT_ID  = 852L;
    private static final String PRODUCT_UID = "01KVJT7VQ0XWKE53X4MGM87PRD";
    private static final String EVENT_UID   = "01KZGM9TRHK3VCSQ039835FQP7";
    private static final String INVOICE_UID = "01KZGM9TRHK3VCSQ039835INVC";

    @BeforeEach
    void setUp() {
        guard          = mock(IdempotencyGuard.class);
        posting        = mock(StockPostingService.class);
        productService = mock(ProductService.class);
        explosion      = mock(RecipeExplosionResolver.class);
        valuation      = mock(InventoryValuationService.class);
        glPoster       = mock(InventoryGlPoster.class);
        reservations   = mock(StockReservationService.class);
        objectMapper   = new ObjectMapper().findAndRegisterModules();

        handler = new SaleIssueStockHandler(guard, posting, productService, explosion,
                valuation, glPoster, reservations, objectMapper);

        when(guard.alreadyProcessed(anyString(), anyString())).thenReturn(false);
        when(productService.getByUid(PRODUCT_UID)).thenReturn(stockableProduct());
        when(explosion.shouldExplodeAtIssue(anyString(), any(Boolean.class))).thenReturn(false);
        // 300 per unit — the live avg_cost of the product on INV-0458.
        when(valuation.costIssue(anyLong(), anyLong(), anyLong(), any(BigDecimal.class)))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(3)).multiply(new BigDecimal("300")));
        when(posting.alreadyPosted(anyString(), anyLong())).thenReturn(false);
    }

    // =========================================================================
    // C1 — two lines of the SAME product on one invoice
    // =========================================================================

    @Test
    void twoLinesOfTheSameProduct_postBothQuantities_notJustTheFirst() throws Exception {
        handler.handle(eventWithLines(new BigDecimal("3"), new BigDecimal("4")));

        ArgumentCaptor<BigDecimal> qty = ArgumentCaptor.forClass(BigDecimal.class);
        verify(posting, times(2)).post(anyLong(), anyLong(), anyLong(),
                qty.capture(), any(MovementType.class),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());

        // The whole defect in one assertion: Σ movement qty must equal Σ line qty.
        BigDecimal totalMoved = qty.getAllValues().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalMoved)
                .as("sum of movement quantities must equal sum of line quantities")
                .isEqualByComparingTo(new BigDecimal("-7"));
        assertThat(qty.getAllValues().get(0)).isEqualByComparingTo(new BigDecimal("-3"));
        assertThat(qty.getAllValues().get(1)).isEqualByComparingTo(new BigDecimal("-4"));
    }

    @Test
    void twoLinesOfTheSameProduct_useDistinctIdempotencyKeysOfTheRightWidth() throws Exception {
        handler.handle(eventWithLines(new BigDecimal("3"), new BigDecimal("4")));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(posting, times(2)).post(anyLong(), anyLong(), anyLong(),
                any(BigDecimal.class), any(MovementType.class),
                key.capture(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());

        assertThat(key.getAllValues())
                .as("sharing one key is what let the backstop drop line 2")
                .doesNotHaveDuplicates()
                .allSatisfy(k -> assertThat(k).hasSize(26));
        // Neither key may be the bare event uid — that is the shape that collided.
        assertThat(key.getAllValues()).doesNotContain(EVENT_UID);
    }

    @Test
    void twoLinesOfTheSameProduct_valueMovesForBothLines_matchingTheQuantity() throws Exception {
        handler.handle(eventWithLines(new BigDecimal("3"), new BigDecimal("4")));

        // Ledger value must reconcile to the on_hand_value delta: costIssue is called once per line
        // and post() records the same figure on the movement row.
        verify(valuation).costIssue(COMPANY_ID, BRANCH_ID, PRODUCT_ID, new BigDecimal("3"));
        verify(valuation).costIssue(COMPANY_ID, BRANCH_ID, PRODUCT_ID, new BigDecimal("4"));

        ArgumentCaptor<BigDecimal> value = ArgumentCaptor.forClass(BigDecimal.class);
        verify(posting, times(2)).post(anyLong(), anyLong(), anyLong(),
                any(BigDecimal.class), any(MovementType.class),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), value.capture());

        BigDecimal ledgerValue = value.getAllValues().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ledgerValue)
                .as("Σ movement value must equal the on_hand_value credited by costIssue (7 × 300)")
                .isEqualByComparingTo(new BigDecimal("2100"));
    }

    // =========================================================================
    // C1b — valuation must not run when the posting is going to be suppressed
    // =========================================================================

    @Test
    void suppressedRedelivery_doesNotMoveValueEither() throws Exception {
        // The line's key is already in the ledger — a genuine redelivery.
        when(posting.alreadyPosted(anyString(), eq(PRODUCT_ID))).thenReturn(true);

        handler.handle(eventWithLines(new BigDecimal("3")));

        // Before the fix, costIssue ran first and unconditionally: value left inventory while the
        // quantity, correctly suppressed by the backstop, stayed put — silently decoupling
        // on_hand_value from the ledger it is supposed to summarise.
        verify(valuation, never()).costIssue(anyLong(), anyLong(), anyLong(), any());
        verify(posting, never()).post(anyLong(), anyLong(), anyLong(),
                any(BigDecimal.class), any(MovementType.class),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any());
        verify(reservations, never()).applyReservationDelta(anyLong(), anyLong(), anyLong(),
                any(), any());
    }

    @Test
    void probeHappensBeforeValuation_notAfter() throws Exception {
        handler.handle(eventWithLines(new BigDecimal("3")));

        InOrder order = inOrder(posting, valuation);
        order.verify(posting).alreadyPosted(anyString(), eq(PRODUCT_ID));
        order.verify(valuation).costIssue(anyLong(), anyLong(), anyLong(), any());
    }

    // =========================================================================
    // C2 (release half) — the finalise-time claim is released as the movement posts
    // =========================================================================

    @Test
    void releasesTheReservationForEachIssuedLine() throws Exception {
        handler.handle(eventWithLines(new BigDecimal("3"), new BigDecimal("4")));

        verify(reservations).applyReservationDelta(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, new BigDecimal("-3"), null);
        verify(reservations).applyReservationDelta(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, new BigDecimal("-4"), null);
    }

    @Test
    void releasesNothingForARevenueOnlyInvoice_becauseTheGuardClaimedNothing() throws Exception {
        // SO-sourced invoice (issuesStock=false): the delivery already issued the stock and
        // NegativeStockGuard was never called, so there is no claim to release.
        SaleFinalisedPayload payload = new SaleFinalisedPayload(
                INVOICE_UID, COMPANY_ID, BRANCH_ID, Instant.now(),
                List.of(new SaleFinalisedPayload.LineItem(
                        PRODUCT_ID, PRODUCT_UID, 1L, new BigDecimal("3"))),
                false, "INV-0458");

        handler.handle(event(payload));

        verify(reservations, never()).applyReservationDelta(anyLong(), anyLong(), anyLong(),
                any(), any());
    }

    @Test
    void releasesNothingForANonStockableLine_mirroringTheGuardsOwnSkip() throws Exception {
        when(productService.getByUid(PRODUCT_UID)).thenReturn(serviceProduct());

        handler.handle(eventWithLines(new BigDecimal("3")));

        // The guard skips non-stockable products, so releasing here would steal another sale's claim.
        verify(reservations, never()).applyReservationDelta(anyLong(), anyLong(), anyLong(),
                any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DomainEvent eventWithLines(BigDecimal... quantities) throws Exception {
        List<SaleFinalisedPayload.LineItem> lines = new java.util.ArrayList<>();
        for (BigDecimal q : quantities) {
            lines.add(new SaleFinalisedPayload.LineItem(PRODUCT_ID, PRODUCT_UID, 1L, q));
        }
        return event(new SaleFinalisedPayload(
                INVOICE_UID, COMPANY_ID, BRANCH_ID, Instant.now(), lines, true, "INV-0458"));
    }

    private DomainEvent event(SaleFinalisedPayload payload) throws Exception {
        DomainEvent event = mock(DomainEvent.class);
        when(event.getUid()).thenReturn(EVENT_UID);
        when(event.getCompanyId()).thenReturn(COMPANY_ID);
        when(event.getBranchId()).thenReturn(BRANCH_ID);
        when(event.getPayload()).thenReturn(objectMapper.writeValueAsString(payload));
        when(event.getEventType()).thenReturn(DomainEventType.SALE_FINALISED);
        return event;
    }

    private static ProductDto stockableProduct() {
        return product(true);
    }

    private static ProductDto serviceProduct() {
        return product(false);
    }

    private static ProductDto product(boolean stockable) {
        return new ProductDto(PRODUCT_ID, PRODUCT_UID, COMPANY_ID,
                "PROD-0852", "FC Product 9175011-5", null, null,
                true, stockable, false, false, false, null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false, null, null,
                false, null, null, null);
    }
}

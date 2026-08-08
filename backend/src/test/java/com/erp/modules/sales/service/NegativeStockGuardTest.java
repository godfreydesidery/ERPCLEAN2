package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
import com.erp.modules.stock.service.RecipeExplosionResolver;
import com.erp.modules.stock.service.StockReservationService;
import com.erp.platform.common.api.ConflictException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NegativeStockGuard} — the synchronous "block negative stock on sale"
 * pre-check ({@code sales_settings.allow_negative_stock}, V87).
 *
 * <p>See {@link NegativeStockSettingCrossLayerContractTest} for the companion test that pins this
 * guard's answer to the one the Sales Settings API reports for the same company state — the two
 * used to disagree for a company with no settings row, and this suite alone could not see it.
 */
@ExtendWith(MockitoExtension.class)
class NegativeStockGuardTest {

    @Mock SalesSettingsRepository settings;
    @Mock StockReservationService stock;
    @Mock RecipeExplosionResolver explosion;

    @InjectMocks NegativeStockGuard guard;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID  = 10L;
    private static final Long PRODUCT_ID = 100L;
    private static final String PRODUCT_UID = "PRODUID0000000000000000001";

    @Test
    void blocksWhenRequestedExceedsAvailable_andSettingIsOff() {
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("5")));

        assertThatThrownBy(() -> guard.assertAvailable(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("8")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Not enough stock of Widget")
                .hasMessageContaining("5")
                .hasMessageContaining("8")
                .hasMessageContaining("Ask a supervisor to enable backorder");
    }

    // -------------------------------------------------------------------------
    // Busy-day-sim FIX 5 (nit): message hygiene on the block — no raw 6dp negative,
    // "out of stock" phrasing for non-positive on-hand, trimmed precision otherwise.
    // -------------------------------------------------------------------------

    @Test
    void blocksWithNegativeOnHand_phrasesAsOutOfStock_noRawNegativeNumber() {
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        // Legacy overselling from before this guard existed — on-hand already negative.
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("-2240.000000"), BigDecimal.ZERO, new BigDecimal("-2240.000000")));

        assertThatThrownBy(() -> guard.assertAvailable(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("10")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("out of stock")
                .hasMessageContaining("10")
                .hasMessageNotContaining("-2240")
                .hasMessageNotContaining("2240.000000");
    }

    @Test
    void blocksWithFractionalAvailable_trimsTrailingZeros() {
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("2.500000"), BigDecimal.ZERO, new BigDecimal("2.500000")));

        assertThatThrownBy(() -> guard.assertAvailable(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("8.000000")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("2.5 available")
                .hasMessageContaining("8 requested")
                .hasMessageNotContaining("2.500000")
                .hasMessageNotContaining("8.000000");
    }

    @Test
    void allowsWhenRequestedWithinAvailable_settingOff() {
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("10"), new BigDecimal("2"), new BigDecimal("8")));

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("8")); // exactly at the boundary — must NOT throw
    }

    @Test
    void allowsOverdraftWhenSettingIsOn_butStillTakesTheReservation() {
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(true)));
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("5")));

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("1000"));

        // Backorder is allowed, so nothing is blocked — but the claim is STILL taken. The release
        // side (SaleIssueStockHandler) has no visibility of this sales setting and releases every
        // issued line unconditionally; reserving conditionally here would make its release steal a
        // concurrent sale's claim, and a setting flipped between finalise and dispatch would
        // permanently strand one.
        verify(stock).reserve(COMPANY_ID, BRANCH_ID, PRODUCT_ID, new BigDecimal("1000"), null);
    }

    @Test
    void takesTheReservationInTheSameStepAsTheCheck_notAPlainRead() {
        // THE fix for the oversell race. The quantity is not deducted until the outbox poller runs
        // the stock handler ~1s later, so a guard that only READ availability let every sale started
        // inside that window pass: 198 on hand, blocking on, eight sales of 30 — eight acceptances
        // and −42 on hand. The guard must claim what it just checked, under the same row lock, in
        // this transaction. Reading without claiming is the defect, so this test pins the write.
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("198"), BigDecimal.ZERO, new BigDecimal("198")));

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("30"));

        verify(stock).reserve(COMPANY_ID, BRANCH_ID, PRODUCT_ID, new BigDecimal("30"), null);
        verify(stock, never()).getAvailability(anyLong(), anyLong(), anyLong());
    }

    @Test
    void secondSaleInsideTheAsyncDeductionWindowIsRefused_becauseTheFirstReservedItsUnits() {
        // Two tills, 198 on hand, 30 each, and NO stock movement yet — the deduction is still on the
        // outbox. The second caller's reserve() answers with the first caller's claim already
        // applied (that is what the row lock in StockReservationServiceImpl guarantees), so a big
        // enough second sale is refused instead of quietly overselling.
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        // quantity still 198 (nothing issued yet); 180 already reserved by six earlier sales.
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("198"), new BigDecimal("180"), new BigDecimal("18")));

        assertThatThrownBy(() -> guard.assertAvailable(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("30")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("18 available")
                .hasMessageContaining("30 requested");
    }

    @Test
    void blocksWhenNoSettingsRowYet() {
        // Fail-safe: a company with no Sales Settings row IS guarded. A row is provisioned on
        // company creation (SalesSettingsSeeder), so this state should not occur — and when it does,
        // the guard must enforce what the Sales Settings screen reports for the same missing row
        // (block), not the opposite. The earlier "allow until configured" fallback is what let a
        // company that had never opened the screen oversell while being told it was protected.
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThatThrownBy(() -> guard.assertAvailable(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Not enough stock of Widget");

        // Availability IS read — the guard no longer short-circuits on a missing row.
        verify(stock).reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any());
    }

    @Test
    void skipsNonStockableProducts_neverChecksAvailability() {
        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, false,
                "Consulting Service", new BigDecimal("1000"));

        verify(stock, never()).reserve(anyLong(), anyLong(), anyLong(), any(), any());
        verify(settings, never()).findByCompanyId(any());
    }

    // -------------------------------------------------------------------------
    // ADR-0058: the guard must branch on exactly the predicate the deducting handler uses
    // (SaleIssueStockHandler.processLine → shouldExplodeAtIssue), or the two disagree about which
    // product's stock the sale touches — and the setting silently stops protecting that product.
    // -------------------------------------------------------------------------

    @Test
    void skipsProductsThatExplodeAtIssue_neverChecksAvailability() {
        // A point-of-sale kit: it is issued as its COMPONENTS, so it has no on-hand row of its own
        // and checking the parent would false-positive block every kit sale. Still skipped.
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(true);

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Bundle Kit", new BigDecimal("1000"));

        verify(stock, never()).reserve(anyLong(), anyLong(), anyLong(), any(), any());
        verify(settings, never()).findByCompanyId(any());
    }

    @Test
    void checksStockableFinishedGoodWithBomButNoComponents_becauseItIsIssuedAsItself() {
        // Make-to-stock finished good: an ACTIVE manufacturing BOM but no product_components, so
        // shouldExplodeAtIssue is FALSE and the handler issues the product ITSELF. The pre-fix guard
        // branched on the broader "has any recipe" predicate, which an ACTIVE BOM alone satisfies:
        // it skipped this shape and let its own on-hand go straight negative even with the setting
        // on — the reported overselling shape. This case is what pins the fix.
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        when(stock.reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any()))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("2")));

        assertThatThrownBy(() -> guard.assertAvailable(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Assembled Cabinet", new BigDecimal("6")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Not enough stock of Assembled Cabinet")
                .hasMessageContaining("2 available")
                .hasMessageContaining("6 requested");

        verify(stock).reserve(eq(COMPANY_ID), eq(BRANCH_ID), eq(PRODUCT_ID), any(), any());
    }

    @Test
    void skipsZeroOrNegativeQuantity() {
        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", BigDecimal.ZERO);

        verify(stock, never()).reserve(anyLong(), anyLong(), anyLong(), any(), any());
    }

    // -------------------------------------------------------------------------

    private static SalesSettings settingsRow(boolean allowNegativeStock) {
        SalesSettings s = new SalesSettings(COMPANY_ID, null);
        s.setAllowNegativeStock(allowNegativeStock);
        return s;
    }
}

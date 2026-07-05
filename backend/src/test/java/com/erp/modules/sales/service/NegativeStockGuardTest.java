package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * pre-check (owner decision 2026-07-05, {@code sales_settings.allow_negative_stock}, V87).
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
        when(explosion.isComposed(PRODUCT_UID)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        when(stock.getAvailability(COMPANY_ID, BRANCH_ID, PRODUCT_ID))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("5")));

        assertThatThrownBy(() -> guard.assertAvailable(
                COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("8")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Not enough stock of Widget")
                .hasMessageContaining("5")
                .hasMessageContaining("8")
                .hasMessageContaining("enable backorder in Sales Settings");
    }

    @Test
    void allowsWhenRequestedWithinAvailable_settingOff() {
        when(explosion.isComposed(PRODUCT_UID)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(false)));
        when(stock.getAvailability(COMPANY_ID, BRANCH_ID, PRODUCT_ID))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        new BigDecimal("10"), new BigDecimal("2"), new BigDecimal("8")));

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("8")); // exactly at the boundary — must NOT throw
    }

    @Test
    void allowsOverdraftWhenSettingIsOn() {
        when(explosion.isComposed(PRODUCT_UID)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(settingsRow(true)));

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("1000"));

        // Setting is on — never even needs to read availability.
        verify(stock, never()).getAvailability(anyLong(), anyLong(), anyLong());
    }

    @Test
    void allowsWhenNoSettingsRowYet_backorderUntilConfigured() {
        // Owner decision "allow until configured": a company with no Sales Settings row is NOT
        // guarded — the sale proceeds (backorder) and availability is never even read. Blocking
        // begins only once a settings row exists (the toggle/entity default is block).
        when(explosion.isComposed(PRODUCT_UID)).thenReturn(false);
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", new BigDecimal("1"));

        verify(stock, never()).getAvailability(anyLong(), anyLong(), anyLong());
    }

    @Test
    void skipsNonStockableProducts_neverChecksAvailability() {
        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, false,
                "Consulting Service", new BigDecimal("1000"));

        verify(stock, never()).getAvailability(anyLong(), anyLong(), anyLong());
        verify(settings, never()).findByCompanyId(any());
    }

    @Test
    void skipsComposedProducts_neverChecksAvailability() {
        when(explosion.isComposed(PRODUCT_UID)).thenReturn(true);

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Bundle Kit", new BigDecimal("1000"));

        verify(stock, never()).getAvailability(anyLong(), anyLong(), anyLong());
        verify(settings, never()).findByCompanyId(any());
    }

    @Test
    void skipsZeroOrNegativeQuantity() {
        when(explosion.isComposed(PRODUCT_UID)).thenReturn(false);

        guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                "Widget", BigDecimal.ZERO);

        verify(stock, never()).getAvailability(anyLong(), anyLong(), anyLong());
    }

    // -------------------------------------------------------------------------

    private static SalesSettings settingsRow(boolean allowNegativeStock) {
        SalesSettings s = new SalesSettings(COMPANY_ID, null);
        s.setAllowNegativeStock(allowNegativeStock);
        return s;
    }
}

package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.MassPriceChangeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request to apply a rule-based mass price change to every price on one price list (a "price change"
 * mass operation). {@code dryRun} previews the effect (counts + samples) without writing.
 * {@code roundToDecimals} defaults to 2 when null. Negative results are clamped to zero.
 */
public record MassPriceChangeRequest(
        @NotBlank String priceListUid,
        @NotNull MassPriceChangeType type,
        @NotNull BigDecimal value,
        Integer roundToDecimals,
        boolean dryRun
) {
}

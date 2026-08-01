package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.enums.PurchaseCostSource;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A suggested unit cost for a purchase-order line, with the provenance behind it.
 *
 * <p>Always a hint, never a lock: the buyer keeps a freely editable cost field. The service returns
 * no suggestion at all (rather than a zero) when nothing is found, so an unpriced product leaves the
 * field blank instead of quietly ordering at zero.
 *
 * @param amount   the suggested unit cost; never null, never zero-as-a-fallback
 * @param currency the currency the suggestion is expressed in — may differ from the PO's currency;
 *                 no FX conversion is attempted, the web shows the code alongside the amount
 * @param source   which of the price sources produced it (fallback chain order)
 * @param asOf     the date the price is from — quote date / order date; null for
 *                 {@link PurchaseCostSource#PRODUCT_COST}, which carries no meaningful date
 */
public record PurchaseCostSuggestionDto(
        BigDecimal         amount,
        String             currency,
        PurchaseCostSource source,
        LocalDate          asOf
) {
}

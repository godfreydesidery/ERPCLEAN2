package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of a {@link MassPriceChangeRequest}: how many price rows the list holds, how many the rule
 * changed, whether it was a dry-run, and a bounded set of before/after samples for the preview.
 */
public record MassPriceChangeResult(
        String priceListUid,
        String priceListCode,
        String priceListName,
        int totalRows,
        int affected,
        boolean dryRun,
        List<Sample> samples
) {

    /** One before/after price for the preview table. */
    public record Sample(String productCode, BigDecimal oldAmount, BigDecimal newAmount, String currency) {
    }
}

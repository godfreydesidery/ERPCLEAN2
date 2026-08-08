package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Period totals for the Stock Movement report (K9), summed across EVERY matching product — not just
 * the current page — so the footer does not change as the user pages.
 *
 * <p>Closes arithmetically: {@code closingQty = openingQty + purchasesIn − salesOut
 * + adjustmentsOther}.
 */
public record StockMovementReportTotalsDto(
        BigDecimal openingQty,
        BigDecimal purchasesIn,
        BigDecimal salesOut,
        BigDecimal adjustmentsOther,
        BigDecimal closingQty) {

    /** All-zero totals — used when the period has no matching movements at all. */
    public static StockMovementReportTotalsDto zero() {
        return new StockMovementReportTotalsDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}

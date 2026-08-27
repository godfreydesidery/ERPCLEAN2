package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * Column totals for the Sales Report (SAM Electronix go-live).
 *
 * @param margin            summed over the rows whose cost of sale is known. Rows with an
 *                          unestablished cost contribute nothing rather than their full sale
 *                          value, so this understates rather than overstates.
 * @param marginRowsUnknown how many rows were left out of {@code margin} for that reason. Above
 *                          zero, the margin total covers only part of the sales and must be
 *                          presented as partial — a figure that silently omits rows while looking
 *                          complete is worse than one that admits what it is missing.
 */
public record SalesReportTotalsDto(
        BigDecimal qtySold,
        BigDecimal discount,
        BigDecimal vat,
        BigDecimal margin,
        BigDecimal amount,
        int marginRowsUnknown) {
}

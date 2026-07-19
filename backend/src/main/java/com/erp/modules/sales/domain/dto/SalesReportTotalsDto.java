package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/** Column totals for the Sales Report (SAM Electronix go-live). */
public record SalesReportTotalsDto(
        BigDecimal qtySold,
        BigDecimal discount,
        BigDecimal vat,
        BigDecimal margin,
        BigDecimal amount) {
}

package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One row in the per-product Sales Report (SAM Electronix go-live). {@code amount} is GROSS
 * (VAT-inclusive); {@code margin} = net sales − cost-of-sale at time of sale.
 */
public record SalesReportRowDto(
        String     productCode,
        String     productName,
        BigDecimal currentStock,
        BigDecimal qtySold,
        BigDecimal discount,
        BigDecimal vat,
        BigDecimal margin,
        BigDecimal amount) {
}

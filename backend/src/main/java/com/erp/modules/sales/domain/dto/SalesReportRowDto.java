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
        /**
         * Net sales less cost of sale, or NULL when the cost of sale was never established for
         * this product (stock sold before anything gave it an avg_cost). NULL means "not known",
         * not "nothing": treating the missing cost as zero would report the entire sale as profit.
         */
        BigDecimal margin,
        BigDecimal amount) {
}

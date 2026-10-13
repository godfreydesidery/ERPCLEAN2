package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One row in the per-product Sales Report (SAM Electronix go-live). {@code amount} is GROSS
 * (VAT-inclusive); {@code margin} = net sales − cost-of-sale at time of sale.
 *
 * <p>{@code qtySold} is in the product's BASE unit (RPT-01 / LSF-11: crates and bottles used to be
 * added together as if they were the same thing); {@code baseUnit} names that unit. {@code discount}
 * is everything taken off the VAT-inclusive list value — line amount, line percentage and the share
 * of any document discount (RPT-16 / LSF-12).
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
        BigDecimal amount,
        /** The unit {@code qtySold} is counted in (the product's base unit). Additive. */
        String     baseUnit) {

    /** The original shape, without the base-unit label. */
    public SalesReportRowDto(String productCode, String productName, BigDecimal currentStock,
                             BigDecimal qtySold, BigDecimal discount, BigDecimal vat,
                             BigDecimal margin, BigDecimal amount) {
        this(productCode, productName, currentStock, qtySold, discount, vat, margin, amount, null);
    }
}

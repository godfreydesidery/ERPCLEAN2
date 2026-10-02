package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * One stock line at or below its reorder level.
 *
 * <p>The reorder level is set per on-hand line (product × branch × location), so that is the
 * grain here: a product short in two stores prints twice.
 *
 * @param shortfall           reorder level − on hand (how far below the trigger it is)
 * @param suggestedOrderQty   up to the maximum level when one is set (max − on hand), else the
 *                            product's standard reorder quantity, else the shortfall — never less
 *                            than the shortfall
 * @param lastCost            unit cost of the most recent goods receipt of the product anywhere in
 *                            the company; null when hidden (see {@link ReorderReportDto#costVisible})
 *                            or when the product has never been received
 * @param estimatedOrderValue suggested qty × last cost; null whenever last cost is
 */
public record ReorderRowDto(
        String productUid,
        String productCode,
        String productName,
        String unitName,
        String branchName,
        String locationCode,
        String locationName,
        BigDecimal onHand,
        BigDecimal reorderLevel,
        BigDecimal maxQty,
        BigDecimal shortfall,
        BigDecimal suggestedOrderQty,
        String supplierUid,
        String supplierName,
        BigDecimal lastCost,
        BigDecimal estimatedOrderValue) {
}

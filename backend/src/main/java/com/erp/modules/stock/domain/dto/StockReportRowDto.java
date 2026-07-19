package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * One row in the printable Stock Report (SAM Electronix go-live): current on-hand + buying
 * (average cost) and selling price. Distinct from {@link StockValuationRowDto} (the valuation +
 * GL-recon report) — this one is a floor/warehouse listing, no GL tie-out.
 */
public record StockReportRowDto(
        String     productCode,
        String     productName,
        BigDecimal quantityOnHand,
        BigDecimal buyingPrice,
        BigDecimal sellingPrice,
        BigDecimal value) {
}

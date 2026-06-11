package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * One row in the stock valuation report: per-product on-hand value (ADR-0020 D-6).
 */
public record StockValuationRowDto(
        Long       productId,
        String     productUid,
        String     productCode,
        String     productName,
        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal value,      // = on_hand_value (authoritative carried figure)
        String     currency
) {}

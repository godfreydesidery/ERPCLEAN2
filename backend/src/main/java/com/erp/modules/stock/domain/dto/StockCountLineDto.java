package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Response DTO for a single stock count line (ADR-0028 D-6).
 */
public record StockCountLineDto(
        Long id,
        String uid,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        String unitName,
        BigDecimal systemQty,
        BigDecimal countedQty,
        BigDecimal varianceQty,
        BigDecimal unitCostAmount,
        BigDecimal varianceValue,
        String reasonCode,
        String movementUid,
        String currency
) {}

package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * Response DTO for a blanket order line (ADR-0029 D-7).
 */
public record BlanketOrderLineDto(
        Long id,
        String uid,
        Long blanketOrderId,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal committedQtyBase,
        BigDecimal drawnQtyBase,
        BigDecimal remainingQtyBase,
        BigDecimal unitPriceAmount,
        String currency
) {}

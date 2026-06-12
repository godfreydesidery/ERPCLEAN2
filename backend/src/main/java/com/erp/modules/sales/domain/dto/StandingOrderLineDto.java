package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * Response DTO for a standing order line (ADR-0029 D-8).
 */
public record StandingOrderLineDto(
        Long id,
        String uid,
        Long standingOrderId,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal qty,
        BigDecimal qtyBase,
        BigDecimal unitPriceAmount,
        String currency
) {}

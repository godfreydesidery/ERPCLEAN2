package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Response DTO for a van reconciliation line (ADR-0051 D-8).
 */
public record VanReconciliationLineDto(
        Long id,
        Integer lineNo,
        String productUid,
        String productName,
        BigDecimal unitCostAmount,
        BigDecimal loadedQty,
        BigDecimal soldQty,
        BigDecimal returnedQty,
        BigDecimal expectedQty,
        BigDecimal physicalQty,
        BigDecimal varianceQty,
        BigDecimal varianceValue
) {}

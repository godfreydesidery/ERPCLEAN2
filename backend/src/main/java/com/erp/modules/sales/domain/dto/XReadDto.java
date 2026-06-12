package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * X-read: mid-session sales summary without closing the session (ADR-0029 D-5).
 */
public record XReadDto(
        String sessionUid,
        Long posTillId,
        Long cashierId,
        String openedAt,
        BigDecimal openingFloatAmount,
        BigDecimal totalSalesAmount,
        BigDecimal totalPayoutsNetAmount,
        BigDecimal expectedCashAmount,
        long invoiceCount
) {}

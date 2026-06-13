package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * Z-read: end-of-session reconciliation report (ADR-0029 D-5).
 * Produced at reconcile time; includes the variance posting reference.
 */
public record ZReadDto(
        String sessionUid,
        Long posTillId,
        Long cashierId,
        String openedAt,
        String closedAt,
        String reconciledAt,
        BigDecimal openingFloatAmount,
        BigDecimal totalSalesAmount,
        BigDecimal totalPayoutsNetAmount,
        BigDecimal expectedCashAmount,
        BigDecimal countedCashAmount,
        BigDecimal varianceAmount,
        Long varianceJournalId,
        long invoiceCount
) {}

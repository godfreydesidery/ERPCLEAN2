package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Z-read: end-of-session reconciliation report (ADR-0029 D-5).
 * Produced at reconcile time; includes the variance posting reference.
 *
 * <p>{@code totalSalesAmount} is gross turnover across every tender type — a reporting figure
 * only. {@code cashTenderAmount} is the net CASH tender actually retained in the drawer (change
 * netted, BR-SALES-07) — this, not {@code totalSalesAmount}, feeds {@code expectedCashAmount}
 * and the variance calculation.
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
        BigDecimal cashTenderAmount,
        BigDecimal totalPayoutsNetAmount,
        BigDecimal expectedCashAmount,
        BigDecimal countedCashAmount,
        BigDecimal varianceAmount,
        Long varianceJournalId,
        long invoiceCount,
        List<TenderSubtotalDto> tenderSubtotals
) {}

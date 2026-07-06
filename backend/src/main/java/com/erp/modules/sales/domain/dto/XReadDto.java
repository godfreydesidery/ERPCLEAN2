package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * X-read: mid-session sales summary without closing the session (ADR-0029 D-5).
 *
 * <p>{@code totalSalesAmount} is gross turnover across every tender type — a reporting figure
 * only. {@code cashTenderAmount} is the net CASH tender actually retained in the drawer (change
 * netted, BR-SALES-07) — this, not {@code totalSalesAmount}, feeds {@code expectedCashAmount}.
 * {@code tenderSubtotals} breaks turnover down per tender type for the cashier/manager to see at
 * a glance why "sales" and "cash" can legitimately differ.
 */
public record XReadDto(
        String sessionUid,
        Long posTillId,
        Long cashierId,
        String openedAt,
        BigDecimal openingFloatAmount,
        BigDecimal totalSalesAmount,
        BigDecimal cashTenderAmount,
        BigDecimal totalPayoutsNetAmount,
        BigDecimal expectedCashAmount,
        long invoiceCount,
        List<TenderSubtotalDto> tenderSubtotals
) {}

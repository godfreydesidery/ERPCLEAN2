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
 *
 * <p>{@code payoutSubtotals} does the same for {@code totalPayoutsNetAmount}: one row per
 * {@link com.erp.modules.sales.domain.enums.PosPayoutType} (zero-filled, enum order) so refunds,
 * drawer drops and till EXPENSES each print as their own line rather than one merged figure. It
 * always sums back to {@code totalPayoutsNetAmount}.
 *
 * <p><b>{@code tillName} / {@code cashierName} / {@code branchName}</b> (UAT, 2026-08): the same
 * labels the Z-read carries, for the same reason — a mid-shift snapshot that names only
 * {@code posTillId: "1"} cannot be read at a glance. Kept identical to the Z-read so the two
 * reports of one shift never describe it differently.
 *
 * <p>An X-read is a mid-shift, NON-resetting read and may be taken on an OPEN or a CLOSED session
 * — a shift that has ended can still be re-examined. Once the session is RECONCILED the Z-read is
 * the authoritative report.
 */
public record XReadDto(
        String sessionUid,
        Long posTillId,
        String tillName,
        Long cashierId,
        String cashierName,
        Long branchId,
        String branchName,
        String openedAt,
        BigDecimal openingFloatAmount,
        BigDecimal totalSalesAmount,
        BigDecimal cashTenderAmount,
        BigDecimal totalPayoutsNetAmount,
        BigDecimal expectedCashAmount,
        long invoiceCount,
        List<TenderSubtotalDto> tenderSubtotals,
        List<PayoutSubtotalDto> payoutSubtotals
) {}

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
 *
 * <p>{@code payoutSubtotals} breaks {@code totalPayoutsNetAmount} down per
 * {@link com.erp.modules.sales.domain.enums.PosPayoutType} (zero-filled, enum order) so refunds,
 * drawer drops and till EXPENSES each print as their own line instead of one merged "Payouts"
 * figure — a printed Z that lumps a refund together with a paid-out expense is what K8 fixes. It
 * always sums back to {@code totalPayoutsNetAmount}.
 *
 * <p>The Z-read is <em>re-readable</em>: reconciling a session returns this record once, and
 * {@code GET /api/v1/pos/sessions/uid/&#123;uid&#125;/z-read} recomputes the identical record from
 * persisted data for any RECONCILED session, so the report can be reprinted or re-opened after the
 * dialog closes. Both paths build the record through the same code, so the two are identical.
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
        List<TenderSubtotalDto> tenderSubtotals,
        List<PayoutSubtotalDto> payoutSubtotals
) {}

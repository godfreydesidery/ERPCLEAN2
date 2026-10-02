package com.erp.modules.reporting.domain.dto;

import java.math.BigDecimal;

/**
 * One component (column of the textbook layout, row here) of the Statement of Changes in Equity:
 * a posted EQUITY account, or one of the two earnings-fold lines the Balance Sheet presents.
 *
 * <p>{@code opening} and {@code closing} are read from the Balance Sheet as-at the day before the
 * period and as-at its last day, so every closing figure is the Balance Sheet's own equity line.
 * The movement columns are aggregated independently from the period's journal lines; {@code ties}
 * says whether {@code opening + movements == closing} for this component.
 *
 * <p>Sign: every amount is presented credit-positive (an increase in equity is positive), so the
 * row adds across. Drawings are therefore negative.
 *
 * @param earningsFold          true for the two synthetic earnings lines (no account behind them)
 * @param profitForPeriod       the period's net profit, excluding year-end close journals — equal to
 *                              the Income Statement's net profit. Only on the current-year line.
 * @param openingBalancesPosted equity postings made by opening-balance journals
 * @param capitalIntroduced     credits to the account from any other journal (capital injected,
 *                              and any other credit — the ledger does not label them further)
 * @param drawingsAndDividends  debits to the account from any other journal (drawings, dividends,
 *                              and any other debit), as a negative amount
 * @param transfers             movements between equity components: the year-end close (out of
 *                              earnings, into retained earnings) and the roll-forward of current-year
 *                              earnings into prior years when the period crosses a fiscal-year start.
 *                              The column totals zero.
 */
public record EquityMovementRowDto(
        Long       accountId,
        String     accountUid,
        String     accountCode,
        String     component,
        boolean    earningsFold,
        BigDecimal opening,
        BigDecimal profitForPeriod,
        BigDecimal openingBalancesPosted,
        BigDecimal capitalIntroduced,
        BigDecimal drawingsAndDividends,
        BigDecimal transfers,
        BigDecimal closing,
        boolean    ties
) {

    /** opening + every movement column. */
    public BigDecimal rolledForward() {
        return opening.add(profitForPeriod).add(openingBalancesPosted).add(capitalIntroduced)
                .add(drawingsAndDividends).add(transfers);
    }
}

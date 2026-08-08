package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The cross-session till-expense report (K8) — every EXPENSE payout a company's tills made over a
 * date range, with a category roll-up. This is the report {@code POS.EXPENSE.VIEW} gates: a cashier
 * records expenses on their own session but cannot read the picture across sessions and tills.
 *
 * <p>Only EXPENSE payouts appear. Refunds and drawer drops are deliberately excluded — folding them
 * in is exactly the defect this feature exists to fix. Per-session detail across all payout types
 * stays on the session's own payout list.
 *
 * <p>{@code totalAmount} always equals the sum of {@code byCategory} and of {@code rows}: the
 * roll-up neither drops nor double-counts an expense.
 *
 * @param companyId   the company reported on
 * @param fromDate    inclusive start of the reported period, ISO date
 * @param toDate      inclusive end of the reported period, ISO date
 * @param totalAmount total till expense over the period (always positive)
 * @param count       how many individual expenses that total is made of
 * @param byCategory  the roll-up, biggest spend first
 * @param rows        the individual expenses, oldest first
 */
public record TillExpenseReportDto(
        Long companyId,
        String fromDate,
        String toDate,
        BigDecimal totalAmount,
        long count,
        List<TillExpenseCategoryTotalDto> byCategory,
        List<PosPayoutDto> rows
) {}

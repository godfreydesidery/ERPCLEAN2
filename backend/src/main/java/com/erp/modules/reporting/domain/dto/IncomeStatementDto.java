package com.erp.modules.reporting.domain.dto;

import java.util.List;

/**
 * Income Statement / P&amp;L DTO (ADR-0018 D-5, FR-REP-01).
 *
 * <p>Sections: REVENUE, COST_OF_SALES, OPERATING_EXPENSES.
 * Subtotals: grossProfit = REVENUE − COST_OF_SALES; netProfit = grossProfit − OPERATING_EXPENSES.
 * Self-check: {@link ReconciliationDto} asserts netProfit == period INCOME − EXPENSE GL movement.
 */
public record IncomeStatementDto(
        StatementHeaderDto        header,
        List<StatementSectionDto> sections,
        AmountPairDto             grossProfit,
        AmountPairDto             netProfit,
        ReconciliationDto         reconciliation
) {}

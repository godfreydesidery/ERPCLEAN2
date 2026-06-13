package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;

/**
 * Finance panel: P&amp;L summary cards + TB health + cash position (ADR-0037 D-6).
 * Sourced from AccountMovementQuery.periodMovementByAccountType + AccountMovementQuery.netIncomeForPeriod
 * + TrialBalanceQuery.compute + CashAccountStatementQuery + CashGlReconciliationQuery.
 */
public record FinanceSummaryDto(
        BigDecimal     netProfitPeriod,
        BigDecimal     revenue,
        BigDecimal     cogs,
        BigDecimal     grossProfit,
        BigDecimal     grossMarginPct,
        BigDecimal     opex,
        BigDecimal     netProfit,
        boolean        tbTies,
        BigDecimal     tbTotalDebit,
        BigDecimal     tbTotalCredit,
        CashPositionDto cash
) {}

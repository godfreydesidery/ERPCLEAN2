package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;

/**
 * Finance panel: P&amp;L summary cards + TB health + cash position (ADR-0037 D-6).
 * Sourced from AccountMovementQuery.periodMovementByAccountType + AccountMovementQuery.netIncomeForPeriod
 * + TrialBalanceQuery.compute + CashAccountStatementQuery + CashGlReconciliationQuery.
 *
 * <p>Adversarial-review fix (HIGH-2): COGS/grossProfit/grossMarginPct were removed because the
 * five-type account-type model cannot derive COGS without the per-account code split the income
 * statement uses (StatementClassifier 5100–5199 band). The service now splits opex into
 * cogsExpense + opex using the same classifier, so the values match the income statement by
 * construction. A fabricated 100% gross-margin (cogs hardcoded to ZERO) is worse than a
 * missing metric, so the gross-margin% field is dropped; the income statement is the
 * authoritative gross-profit surface.
 */
public record FinanceSummaryDto(
        BigDecimal     netProfitPeriod,
        BigDecimal     revenue,
        BigDecimal     opex,
        BigDecimal     netProfit,
        boolean        tbTies,
        BigDecimal     tbTotalDebit,
        BigDecimal     tbTotalCredit,
        CashPositionDto cash
) {}

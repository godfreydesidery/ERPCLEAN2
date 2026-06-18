package com.erp.modules.budgeting.domain.enums;

/**
 * The kind of budget (ADR-0041 D7).
 * OPERATING = revenue/expense; CAPITAL = capex; CASH = cash-flow budget.
 */
public enum BudgetType {
    OPERATING,
    CAPITAL,
    CASH
}

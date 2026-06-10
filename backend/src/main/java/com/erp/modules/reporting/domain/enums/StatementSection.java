package com.erp.modules.reporting.domain.enums;

/**
 * The statement sections used for auto-derive classification (ADR-0018 D-1/D-4).
 *
 * <p>Balance Sheet: CURRENT_ASSETS, NON_CURRENT_ASSETS, CURRENT_LIABILITIES,
 * NON_CURRENT_LIABILITIES, EQUITY.
 *
 * <p>P&amp;L: REVENUE, COST_OF_SALES, OPERATING_EXPENSES.
 *
 * <p>Cash Flow: OPERATING, INVESTING, FINANCING.
 */
public enum StatementSection {
    // Balance Sheet
    CURRENT_ASSETS,
    NON_CURRENT_ASSETS,
    CURRENT_LIABILITIES,
    NON_CURRENT_LIABILITIES,
    EQUITY,
    // Income Statement / P&L
    REVENUE,
    COST_OF_SALES,
    OPERATING_EXPENSES,
    // Cash Flow
    OPERATING,
    INVESTING,
    FINANCING
}

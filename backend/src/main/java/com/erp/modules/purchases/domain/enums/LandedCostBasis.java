package com.erp.modules.purchases.domain.enums;

/**
 * Allocation basis for landed-cost distribution across receipt lines (ADR-0027 D-2, OQ-PROC-03).
 * BY_VALUE = pro-rata to each line's line_cost_amount (default).
 * BY_QUANTITY = pro-rata to each line's qty_in_base.
 */
public enum LandedCostBasis {
    BY_VALUE,
    BY_QUANTITY
}

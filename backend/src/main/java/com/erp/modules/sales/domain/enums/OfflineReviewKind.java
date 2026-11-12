package com.erp.modules.sales.domain.enums;

/** Why an offline sale is on the manager review list (ADR-0068 D-5, D-8). */
public enum OfflineReviewKind {
    /** Posted at the price the customer paid, which differs from the server's price at sync. */
    PRICE_DIFFERENCE,
    /** Posted, and it takes the branch's stock below zero. */
    NEGATIVE_STOCK,
    /** Posted, but the day it was sold falls in a closed fiscal period (GL goes to exceptions). */
    CLOSED_PERIOD,
    /** Posted, with a line at or below average cost while the company's below-cost policy is on. */
    BELOW_COST,
    /** NOT posted: something offline selling never permits; kept with the sale as rung. */
    HELD
}

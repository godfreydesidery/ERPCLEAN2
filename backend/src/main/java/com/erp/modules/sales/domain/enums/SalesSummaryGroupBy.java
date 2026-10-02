package com.erp.modules.sales.domain.enums;

/**
 * What one row of the Sales Summary stands for.
 *
 * <p>One report, six questions: sales by customer, agent performance, sales by route, sales per
 * branch, the daily summary and the cashier summary. Every option is an attribute of the INVOICE
 * (never of a line), so each finalised invoice falls in exactly one group and the group totals
 * always add up to the grand total.
 */
public enum SalesSummaryGroupBy {
    /** The invoice's customer. */
    CUSTOMER,
    /** The sales agent on the invoice. */
    AGENT,
    /** The delivery route on the invoice; invoices with none form one "(no route)" group. */
    ROUTE,
    /** The branch the invoice was raised in. */
    BRANCH,
    /** The calendar day the invoice was finalised, in the company's time zone. */
    DAY,
    /** The user who created the invoice — at a till, the cashier who rang the sale. */
    CASHIER
}

package com.erp.modules.sales.domain.enums;

/** Sales order lifecycle states — exact 8-value set (ADR-0021 D-2). */
public enum SalesOrderStatus {
    DRAFT,
    CONFIRMED,
    PARTIALLY_FULFILLED,
    FULFILLED,
    PARTIALLY_INVOICED,
    INVOICED,
    CLOSED,
    CANCELLED
}

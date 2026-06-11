package com.erp.modules.sales.domain.enums;

/** Quotation lifecycle states (ADR-0021 D-2). */
public enum QuotationStatus {
    DRAFT,
    SENT,
    ACCEPTED,
    EXPIRED,
    REJECTED
}

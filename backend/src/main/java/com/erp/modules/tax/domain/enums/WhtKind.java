package com.erp.modules.tax.domain.enums;

/** Whether WHT is withheld on a payment (we withhold) or on a receipt (customer withholds) (ADR-0017 D-2d). */
public enum WhtKind {
    WHT_ON_PAYMENT,
    WHT_ON_RECEIPT
}

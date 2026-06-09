package com.erp.modules.ap.domain.enums;

/** Status lifecycle of a supplier bill (ADR-0015 D-2a). */
public enum SupplierBillStatus {
    DRAFT,
    MATCHED,
    HELD,
    APPROVED,
    PARTIALLY_PAID,
    PAID
}

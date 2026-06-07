package com.erp.modules.sales.domain.enums;

/**
 * Sales document channel discriminator (ADR-0008 D-7, NFR-SALES-07).
 * v1 admits only INVOICE; POS and SALES_ORDER are reserved for future channels.
 */
public enum DocumentType {
    INVOICE
    // POS,         // reserved — deferred (OQ-SALES-01)
    // SALES_ORDER  // reserved — deferred (OQ-SALES-01)
}

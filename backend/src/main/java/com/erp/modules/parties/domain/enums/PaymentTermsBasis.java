package com.erp.modules.parties.domain.enums;

/**
 * Basis for computing a due date from a PaymentTerms master (D-2, ADR-0040).
 */
public enum PaymentTermsBasis {
    /** Net days counted from invoice date. */
    DAYS_AFTER_INVOICE,
    /** Net days counted from the end of the month in which the invoice falls. */
    DAYS_AFTER_MONTH_END,
    /** Payment is due on receipt of invoice (net_days ignored). */
    DUE_ON_RECEIPT
}

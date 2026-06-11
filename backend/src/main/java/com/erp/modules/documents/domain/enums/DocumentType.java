package com.erp.modules.documents.domain.enums;

/**
 * The set of renderable document types (ADR-0023 D-3).
 * The v1 renderable set: INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT,
 * DELIVERY_NOTE, CREDIT_NOTE. The remaining values are reserved for future modules
 * and are NOT rendered in v1 (OQ-DOC-08).
 */
public enum DocumentType {

    // -- v1 renderable types --------------------------------------------------

    /** Sales invoice (sales_invoices, FINALISED/VOID). Rendered by TRANSACTIONAL_PDF. */
    INVOICE,

    /** Customer statement (open items + ageing). Rendered by STATEMENT_PDF (reused pipeline). */
    AR_STATEMENT,

    /** Purchase order (purchase_orders). Rendered by TRANSACTIONAL_PDF. */
    PURCHASE_ORDER,

    /** Goods-receipt note (goods_receipts, qty-only). Rendered by TRANSACTIONAL_PDF. */
    GOODS_RECEIPT,

    /** Delivery note (deliveries, qty-only, no prices — ADR-0021 D-7). Rendered by TRANSACTIONAL_PDF. */
    DELIVERY_NOTE,

    /** AR credit note (ar_credit_notes). Rendered by TRANSACTIONAL_PDF. */
    CREDIT_NOTE,

    // -- reserved, NOT rendered in v1 (OQ-DOC-08) ----------------------------

    /** Payroll payslip — HR not built; enum slot reserved. */
    PAYSLIP,

    /** Sales quotation — deferred display tweak. */
    QUOTATION,

    /** AP debit note — deferred. */
    DEBIT_NOTE
}

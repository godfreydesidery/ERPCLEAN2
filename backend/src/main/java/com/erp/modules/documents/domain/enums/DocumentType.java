package com.erp.modules.documents.domain.enums;

/**
 * The set of renderable document types (ADR-0023 D-3).
 * The renderable set: INVOICE, AR_STATEMENT, PURCHASE_ORDER, GOODS_RECEIPT,
 * DELIVERY_NOTE, CREDIT_NOTE, QUOTATION. The remaining values are reserved for future modules
 * and are NOT rendered (OQ-DOC-08).
 *
 * <p>Persisted as {@code EnumType.STRING} in {@code document_templates.document_type} and
 * {@code generated_documents.document_type}, both of which carry a V19 CHECK constraint listing
 * every name below — so declaration order is free to change but a NEW name would need a migration.
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

    /**
     * Sales quotation (quotations). Rendered by TRANSACTIONAL_PDF.
     *
     * <p><b>Prints as "PROFORMA INVOICE"</b> — that is the title clients ask for and the title the
     * default {@code document_templates} row carries ({@code DocumentBrandingSeeder}). The internal
     * type stays QUOTATION deliberately: it is the name already permitted by the V19 CHECK
     * constraints on both document tables, so no schema change is needed, and the quotation
     * lifecycle (send / accept / expire) is unchanged. A separate PROFORMA type would buy nothing
     * but a migration. Stock-neutral like every other document: rendering only reads.
     */
    QUOTATION,

    // -- reserved, NOT rendered (OQ-DOC-08) ----------------------------------

    /** Payroll payslip — HR not built; enum slot reserved. */
    PAYSLIP,

    /** AP debit note — deferred. */
    DEBIT_NOTE
}

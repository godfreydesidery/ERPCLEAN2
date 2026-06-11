package com.erp.modules.documents.domain.enums;

/**
 * Which renderer builds a given document type (ADR-0023 D-3).
 * TRANSACTIONAL_PDF uses DocumentPdfRenderer; STATEMENT_PDF reuses PdfStatementRenderer.
 */
public enum RendererKey {

    /** Renders a DocumentRenderModel via DocumentPdfRenderer (header + line table + totals). */
    TRANSACTIONAL_PDF,

    /** Renders a StatementRenderModel via PdfStatementRenderer (the lifted reporting pipeline). */
    STATEMENT_PDF
}

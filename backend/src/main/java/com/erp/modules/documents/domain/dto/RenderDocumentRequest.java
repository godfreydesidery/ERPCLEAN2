package com.erp.modules.documents.domain.dto;

import com.erp.modules.documents.domain.enums.DocumentType;

/**
 * Request to render a document to PDF (ADR-0023 D-11).
 * Provide either sourceUid (single-document types) or sourceParams (parameterised — AR_STATEMENT).
 */
public record RenderDocumentRequest(
        DocumentType documentType,
        /** The source document uid — required for INVOICE, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE. */
        String sourceUid,
        /** JSON string of parameters for a parameterised render (AR_STATEMENT: customerUid, fromDate, toDate). */
        String sourceParams
) {}

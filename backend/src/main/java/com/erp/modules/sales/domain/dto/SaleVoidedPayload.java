package com.erp.modules.sales.domain.dto;

/**
 * Typed payload record for the {@code SALE.VOIDED} outbox event (ADR-0008 D-9, ADR-0009 D-3).
 *
 * <p>The void payload carries the invoice identity and scope so the stock consumer can look up
 * any issued movements and reverse them (OQ-STOCK-10 — reverse only what was issued). The full
 * line detail is not repeated here; the consumer traces the movement ledger via the source event uid
 * of the original {@code SALE.FINALISED} event.
 */
public record SaleVoidedPayload(
        String invoiceUid,
        Long companyId,
        Long branchId,
        /** Human-readable invoice number — used in GL journal memos (FOLLOW-001). */
        String invoiceNumber
) {
    /** Back-compat: pre-FOLLOW-001 callers that do not supply invoiceNumber. */
    public SaleVoidedPayload(String invoiceUid, Long companyId, Long branchId) {
        this(invoiceUid, companyId, branchId, null);
    }
}

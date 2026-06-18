package com.erp.modules.sales.domain.dto;

/**
 * Request DTO to finalise a draft invoice (ADR-0008 D-7, FR-SALES-02).
 * The service validates paid-in-full from the stored payments before finalising.
 *
 * <p>ADR-0041 D1 — {@code paymentTermsUid} is an OPTIONAL override for a DIRECT (walk-in) invoice:
 * when present the service resolves it and stamps {@code payment_terms_id} (else the customer's
 * default term). It takes priority over any term already stamped at create time. SO-sourced
 * invoices already inherit the SO's term at delivery-billing time and ignore this field.
 */
public record FinaliseInvoiceRequest(
        String paymentTermsUid
) {
    /** Back-compat: finalise with no payment-terms override → use the customer default. */
    public FinaliseInvoiceRequest() {
        this(null);
    }
}

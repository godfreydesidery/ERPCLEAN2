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
        String paymentTermsUid,
        /**
         * Below-cost approval acknowledgement (V93). Optional — clients that omit it are treated as
         * {@code false}. Only consulted when the company's below-cost policy is
         * {@code APPROVE}: the sale then goes through if this is {@code true} AND the caller holds
         * {@code SALES.BELOW_COST.OVERRIDE}. The flag on its own authorises nothing.
         */
        Boolean belowCostApproved
) {
    /** Back-compat: finalise with no payment-terms override → use the customer default. */
    public FinaliseInvoiceRequest() {
        this(null, null);
    }

    /** Back-compat: callers that predate the below-cost policy (V93) supply no approval. */
    public FinaliseInvoiceRequest(String paymentTermsUid) {
        this(paymentTermsUid, null);
    }
}

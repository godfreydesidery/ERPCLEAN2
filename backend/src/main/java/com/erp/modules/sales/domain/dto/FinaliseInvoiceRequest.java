package com.erp.modules.sales.domain.dto;

/**
 * Request DTO to finalise a draft invoice (ADR-0008 D-7, FR-SALES-02).
 * The service validates paid-in-full from the stored payments before finalising.
 * Body is empty for now; extended later if finalise carries additional parameters.
 */
public record FinaliseInvoiceRequest() {
}

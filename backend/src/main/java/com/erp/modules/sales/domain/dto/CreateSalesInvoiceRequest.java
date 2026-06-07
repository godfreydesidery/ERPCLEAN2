package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to create a sales invoice draft (ADR-0008 D-12, FR-SALES-01).
 * Branch comes from RequestContext — NOT the body (brief §4b).
 * agentUid is optional; when absent the service auto-defaults to the logged-in user's
 * internal agent record (FR-SALES-15).
 */
public record CreateSalesInvoiceRequest(
        @NotBlank String companyUid,
        @NotBlank String customerUid,
        String agentUid,
        @NotBlank String currency,
        String notes
) {
}

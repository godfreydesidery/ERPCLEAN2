package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to create a sales invoice draft (ADR-0008 D-12, FR-SALES-01).
 * Branch comes from RequestContext — NOT the body (brief §4b).
 * agentUid is optional; when absent the service auto-defaults to the logged-in user's
 * internal agent record (FR-SALES-15).
 * routeUid is optional; when absent the service auto-defaults from the agent's primary route
 * (FR-ROUTE-13, ADR-0012 D-6); sending null explicitly clears the default.
 */
public record CreateSalesInvoiceRequest(
        @NotBlank String companyUid,
        @NotBlank String customerUid,
        String agentUid,
        @NotBlank String currency,
        String notes,
        String routeUid
) {
}

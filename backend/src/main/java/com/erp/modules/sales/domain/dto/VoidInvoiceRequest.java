package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to void a finalised invoice (ADR-0008 D-7, FR-SALES-22).
 * Requires SALES.INVOICE.VOID permission. The invoice retains its number (void ≠ delete).
 */
public record VoidInvoiceRequest(
        @NotBlank String reason
) {
}

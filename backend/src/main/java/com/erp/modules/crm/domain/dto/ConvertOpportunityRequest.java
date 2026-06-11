package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request to convert an opportunity to a quote or SO (ADR-0031 D-7).
 * target = QUOTATION | SALES_ORDER.
 */
public record ConvertOpportunityRequest(
        @NotNull ConvertTarget target,
        /** Optional: valid-until date for a quotation (defaults to today+30 days if null). */
        java.time.LocalDate validUntil
) {
    public enum ConvertTarget { QUOTATION, SALES_ORDER }
}

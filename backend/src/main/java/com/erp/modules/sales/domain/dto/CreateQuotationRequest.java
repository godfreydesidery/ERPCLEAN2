package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateQuotationRequest(
        @NotBlank String companyUid,
        @NotBlank String customerUid,
        String agentUid,
        @NotBlank String currency,
        @NotNull LocalDate quoteDate,
        @NotNull LocalDate validUntil,
        BigDecimal docDiscountAmount,
        BigDecimal docDiscountPercent,
        String notes,
        /** Optional: CRM opportunity that converted to this quote (ADR-0031 D-7, V52). Null for direct creates. */
        String sourceOpportunityUid
) {}

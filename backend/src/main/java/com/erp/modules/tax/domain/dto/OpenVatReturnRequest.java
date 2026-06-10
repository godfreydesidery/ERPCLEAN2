package com.erp.modules.tax.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request to open (create) a new DRAFT VAT return for a company-month (ADR-0017 D-4, FR-VAT-01).
 */
public record OpenVatReturnRequest(
        @NotBlank String companyUid,
        @NotNull @Min(2000) @Max(2100) Integer periodYear,
        @NotNull @Min(1) @Max(12) Integer periodMonth
) {}

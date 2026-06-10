package com.erp.modules.tax.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request to file (lock) a DRAFT VAT return (ADR-0017 D-4, FR-VAT-08).
 */
public record FileVatReturnRequest(
        /** The TRA acknowledgement reference entered by the operator (no e-filing in v1). */
        @NotBlank String filingReference,
        @NotNull LocalDate filingDate
) {}

package com.erp.modules.fixedassets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Post the depreciation run for a company + fiscal period (ADR-0030 D-4). */
public record RunDepreciationRequest(
        @NotNull Long companyId,
        @NotBlank String fiscalPeriodUid,
        @NotNull LocalDate postingDate
) {}

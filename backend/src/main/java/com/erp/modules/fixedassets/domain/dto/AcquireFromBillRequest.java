package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Acquire a fixed asset from an existing matched AP supplier bill (ADR-0030 D-7).
 * FA reads the bill net as acquisition_cost; posts its own capitalisation journal.
 */
public record AcquireFromBillRequest(
        @NotNull Long companyId,
        @NotNull Long branchId,
        @NotBlank String billUid,
        @NotBlank String billLineUid,
        @NotNull Long categoryId,
        @NotBlank String name,
        BigDecimal salvageValue,
        @NotNull DepreciationMethod depreciationMethod,
        @Min(1) int lifePeriods,
        BigDecimal reducingRate,
        @NotNull LocalDate acquisitionDate,
        @NotNull LocalDate depreciationStartDate,
        String location,
        Long costCentreId,
        String assetTag
) {}

package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RegisterAssetRequest(
        @NotNull Long companyId,
        @NotNull Long branchId,
        @NotNull Long categoryId,
        @NotBlank String name,
        @NotNull BigDecimal acquisitionCost,
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

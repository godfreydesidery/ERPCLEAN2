package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdateAssetCategoryRequest(
        @NotBlank String name,
        @NotNull DepreciationMethod defaultMethod,
        @Min(1) int defaultLifePeriods,
        BigDecimal defaultReducingRate,
        @NotNull Long assetAccountId,
        @NotNull Long accumDepAccountId,
        @NotNull Long depExpenseAccountId
) {}

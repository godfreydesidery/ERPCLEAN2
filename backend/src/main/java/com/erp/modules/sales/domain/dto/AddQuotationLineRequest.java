package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record AddQuotationLineRequest(
        @NotBlank String productUid,
        @NotBlank String unitUid,
        @NotNull @Positive BigDecimal quantity,
        @PositiveOrZero BigDecimal unitPriceOverride,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent
) {}

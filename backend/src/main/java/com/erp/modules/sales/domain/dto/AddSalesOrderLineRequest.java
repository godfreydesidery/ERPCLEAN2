package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record AddSalesOrderLineRequest(
        @NotBlank String productUid,
        @NotBlank String unitUid,
        @NotNull BigDecimal quantity,
        BigDecimal unitPriceOverride,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent
) {}

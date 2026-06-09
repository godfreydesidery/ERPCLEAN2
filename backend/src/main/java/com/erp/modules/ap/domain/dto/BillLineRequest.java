package com.erp.modules.ap.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record BillLineRequest(
        Long productId,
        String poLineUid,
        String grLineUid,
        @NotBlank String description,
        @NotNull @Positive BigDecimal billedQty,
        @NotNull BigDecimal unitCostAmount
) {}

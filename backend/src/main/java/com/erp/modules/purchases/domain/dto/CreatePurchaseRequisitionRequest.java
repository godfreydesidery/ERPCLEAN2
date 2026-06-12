package com.erp.modules.purchases.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreatePurchaseRequisitionRequest(
        @NotBlank String companyUid,
        LocalDate requiredByDate,
        String costCentreCode,
        String notes,
        @NotEmpty @Valid List<LineRequest> lines
) {
    public record LineRequest(
            @NotNull Long productId,
            @NotNull Long unitId,
            @NotNull @Positive BigDecimal requestedQty,
            BigDecimal estimatedUnitCost,
            String note
    ) {}
}

package com.erp.modules.manufacturing.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request body for POST /api/v1/work-orders/uid/{uid}/operations (ADR-0035 D-1). */
public record AddOperationRequest(
        @NotNull @Min(1) Integer seqNo,
        @NotBlank String description,
        String workCentre,
        BigDecimal labourAmount,
        BigDecimal overheadAmount
) {}

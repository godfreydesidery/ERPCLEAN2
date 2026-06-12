package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdatePipelineStageRequest(
        @NotBlank String name,
        @NotNull Short displayOrder,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal defaultProbability,
        boolean active
) {}

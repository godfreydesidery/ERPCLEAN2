package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.NotBlank;

public record AdvanceStageRequest(
        @NotBlank String pipelineStageUid,
        /** Override win probability; null → use stage default. */
        java.math.BigDecimal winProbabilityOverride
) {}

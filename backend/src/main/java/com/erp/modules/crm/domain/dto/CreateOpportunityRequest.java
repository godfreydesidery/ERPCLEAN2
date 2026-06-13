package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateOpportunityRequest(
        @NotNull Long companyId,
        @NotBlank String customerUid,
        @NotBlank String title,
        @NotBlank String pipelineStageUid,
        @NotBlank String currency,
        BigDecimal estimatedValueAmount,
        BigDecimal winProbability,
        LocalDate expectedCloseDate,
        String agentUid,
        Long ownerUserId,
        /** Optional: the lead this opportunity originated from (becomes CONVERTED). */
        String sourceLeadUid,
        String notes
) {}

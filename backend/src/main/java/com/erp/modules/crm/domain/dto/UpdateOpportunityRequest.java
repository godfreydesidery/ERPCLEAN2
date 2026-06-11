package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateOpportunityRequest(
        @NotBlank String title,
        BigDecimal estimatedValueAmount,
        BigDecimal winProbability,
        LocalDate expectedCloseDate,
        String agentUid,
        Long ownerUserId,
        String notes
) {}

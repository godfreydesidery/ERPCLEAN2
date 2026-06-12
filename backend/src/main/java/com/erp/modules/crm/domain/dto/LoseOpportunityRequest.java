package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.NotBlank;

public record LoseOpportunityRequest(
        @NotBlank String lossReason
) {}

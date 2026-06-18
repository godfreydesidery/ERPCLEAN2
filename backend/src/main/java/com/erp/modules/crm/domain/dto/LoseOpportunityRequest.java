package com.erp.modules.crm.domain.dto;

import com.erp.modules.crm.domain.enums.OpportunityLossReason;
import jakarta.validation.constraints.NotNull;

public record LoseOpportunityRequest(
        @NotNull OpportunityLossReason lossReason
) {}

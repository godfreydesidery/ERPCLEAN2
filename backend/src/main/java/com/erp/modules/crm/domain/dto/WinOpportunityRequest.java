package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record WinOpportunityRequest(
        @NotNull Instant wonAt
) {}

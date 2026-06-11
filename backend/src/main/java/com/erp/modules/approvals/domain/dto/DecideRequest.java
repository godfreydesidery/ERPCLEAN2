package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.DecisionAction;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for approve/reject actions on the current open step (ADR-0022 D-6, FR-APR-07/08).
 * Comment is optional; action is mandatory.
 */
public record DecideRequest(
        @NotNull DecisionAction action,
        String comment
) {}

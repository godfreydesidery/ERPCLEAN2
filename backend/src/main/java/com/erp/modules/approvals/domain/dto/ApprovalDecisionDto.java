package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.DecisionAction;
import java.time.Instant;

/**
 * Response DTO for an append-only decision record (ADR-0022 D-4).
 */
public record ApprovalDecisionDto(
        Long id,
        String uid,
        Long approvalRequestStepId,
        DecisionAction action,
        Long decidedBy,
        Instant decidedAt,
        String comment
) {}

package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.ApprovalStepStatus;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for a frozen request step (ADR-0022 D-5).
 */
public record ApprovalRequestStepDto(
        Long id,
        String uid,
        int sequence,
        String approverRoleCode,
        ApprovalStepStatus status,
        Long resolvedBy,
        Instant resolvedAt,
        List<ApprovalDecisionDto> decisions
) {}

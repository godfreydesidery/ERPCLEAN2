package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.ApprovalStepStatus;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for a frozen request step (ADR-0022 D-5).
 *
 * <p>{@code approverRoleName} is a read-time enrichment — the role's friendly display name for
 * {@code approverRoleCode}, resolved by {@code ApprovalEngineImpl.toDto} against the IAM
 * {@code RoleRepository} (null-safe: a missing/deleted role code yields {@code null}, never a
 * failed read).
 */
public record ApprovalRequestStepDto(
        Long id,
        String uid,
        int sequence,
        String approverRoleCode,
        String approverRoleName,
        ApprovalStepStatus status,
        Long resolvedBy,
        Instant resolvedAt,
        List<ApprovalDecisionDto> decisions
) {}

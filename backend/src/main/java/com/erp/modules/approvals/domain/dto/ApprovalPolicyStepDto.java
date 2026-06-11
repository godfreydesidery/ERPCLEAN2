package com.erp.modules.approvals.domain.dto;

/**
 * Response DTO for one policy step (ADR-0022 D-3).
 */
public record ApprovalPolicyStepDto(
        Long id,
        String uid,
        int sequence,
        String approverRoleCode
) {}

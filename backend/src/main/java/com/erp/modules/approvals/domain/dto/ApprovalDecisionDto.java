package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.DecisionAction;
import java.time.Instant;

/**
 * Response DTO for an append-only decision record (ADR-0022 D-4).
 *
 * <p>{@code decidedByName} is a read-time enrichment — the decider's display name (falls back to
 * username), resolved by {@code ApprovalEngineImpl.toDto} against the IAM {@code AppUserRepository}
 * (the same seam that resolves {@code submittedByName}/{@code resolvedByName} on
 * {@code ApprovalRequestDto}) — null-safe: a null/missing user id yields {@code null}, never a
 * failed read.
 */
public record ApprovalDecisionDto(
        Long id,
        String uid,
        Long approvalRequestStepId,
        DecisionAction action,
        Long decidedBy,
        String decidedByName,
        Instant decidedAt,
        String comment
) {}

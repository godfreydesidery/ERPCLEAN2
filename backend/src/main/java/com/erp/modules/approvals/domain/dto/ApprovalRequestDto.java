package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for an approval request (ADR-0022 D-5, D-7).
 *
 * <p>This is the return type of both {@code ApprovalEngine.submitForApproval} and
 * {@code ApprovalEngine.getApprovalState}, and the body exposed by REST. Both id and uid are
 * included (id serialised as string by the global Jackson config).
 *
 * <p>{@code submittedByName}, {@code resolvedByName}, {@code branchName} and {@code branchCode}
 * are read-time enrichments (a top managers' complaint: the inbox showed only raw numeric user
 * ids and no branch name) resolved by {@code ApprovalEngineImpl.toDto} against the IAM
 * {@code AppUserRepository}/{@code BranchRepository} — null-safe, never fail the read.
 */
public record ApprovalRequestDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String branchName,
        String branchCode,
        String requestNumber,
        String documentType,
        String documentUid,
        BigDecimal amount,
        String currency,
        ApprovalRequestStatus status,
        Integer currentStepSequence,
        boolean autoApproved,
        Long sourcePolicyId,
        String sourcePolicyUid,
        String summary,
        Long submittedBy,
        String submittedByName,
        Instant submittedAt,
        Instant resolvedAt,
        Long resolvedBy,
        String resolvedByName,
        List<ApprovalRequestStepDto> steps
) {}

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
 */
public record ApprovalRequestDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
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
        Instant submittedAt,
        Instant resolvedAt,
        Long resolvedBy,
        List<ApprovalRequestStepDto> steps
) {}

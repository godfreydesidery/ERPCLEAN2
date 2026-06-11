package com.erp.modules.approvals.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbox payload for {@code APPROVAL.SUBMITTED} events (ADR-0022 D-10).
 * Emitted when a policy-matched PENDING request is created; for Notifications later.
 */
public record ApprovalSubmittedPayload(
        String requestUid,
        String documentType,
        String documentUid,
        Long companyId,
        Long branchId,
        BigDecimal amount,
        Long submittedByUserId,
        Instant submittedAt
) {}

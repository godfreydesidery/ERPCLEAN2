package com.erp.modules.approvals.domain.dto;

import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import java.time.Instant;

/**
 * Outbox payload for {@code APPROVAL.RESOLVED} events (ADR-0022 D-10).
 * Emitted whenever a request reaches a terminal state (APPROVED, REJECTED, RECALLED, CANCELLED).
 * Consumers (e.g. Procurement) subscribe to react asynchronously (e.g. auto-advance PO to ORDERED
 * when {@code finalStatus = APPROVED}).
 */
public record ApprovalResolvedPayload(
        String requestUid,
        String documentType,
        String documentUid,
        Long companyId,
        Long branchId,
        /** APPROVED | REJECTED | RECALLED | CANCELLED */
        ApprovalRequestStatus finalStatus,
        Long resolvedByUserId,
        Instant resolvedAt
) {}

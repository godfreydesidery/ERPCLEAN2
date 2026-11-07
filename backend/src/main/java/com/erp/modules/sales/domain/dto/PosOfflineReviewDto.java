package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.OfflineReviewKind;
import com.erp.modules.sales.domain.enums.OfflineReviewStatus;
import java.time.Instant;

/**
 * One item on the offline review list (ADR-0068 D-8), with the names a manager reads — never ids.
 * {@code heldRequest} is the sale exactly as the till rang it (JSON), only for a HELD item.
 */
public record PosOfflineReviewDto(
        String uid,
        OfflineReviewKind kind,
        OfflineReviewStatus status,
        String offlineNumber,
        String detail,
        String tillName,
        String invoiceUid,
        String invoiceNumber,
        String heldRequest,
        Instant createdAt,
        String acknowledgedByName,
        Instant acknowledgedAt,
        String acknowledgeNote
) {}

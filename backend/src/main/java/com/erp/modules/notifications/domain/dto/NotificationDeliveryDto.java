package com.erp.modules.notifications.domain.dto;

import java.time.Instant;

/**
 * Delivery log row DTO for the admin view (ADR-0024 D-10).
 */
public record NotificationDeliveryDto(
        Long id,
        String uid,
        Long notificationId,
        Long companyId,
        String typeKey,
        Long recipientUserId,
        String channel,
        String outcome,
        String suppressionReason,
        short attemptNo,
        String error,
        String triggerKey,
        Instant attemptedAt,
        Instant completedAt
) {}

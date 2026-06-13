package com.erp.modules.notifications.domain.dto;

import java.time.Instant;

/**
 * Full notification DTO for the in-app inbox (ADR-0024 D-10).
 */
public record NotificationDto(
        Long id,
        String uid,
        Long companyId,
        String typeKey,
        String channel,
        String severity,
        String title,
        String body,
        String sourceAggregateType,
        String sourceUid,
        String linkRoute,
        boolean read,
        Instant readAt,
        Instant createdAt
) {}

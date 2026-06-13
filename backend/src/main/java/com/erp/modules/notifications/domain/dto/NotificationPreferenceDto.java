package com.erp.modules.notifications.domain.dto;

/**
 * Per-user notification preference DTO (ADR-0024 D-10).
 */
public record NotificationPreferenceDto(
        Long id,
        String uid,
        Long companyId,
        Long userId,
        String typeKey,
        boolean muted,
        String channelsEnabled
) {}

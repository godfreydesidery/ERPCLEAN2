package com.erp.modules.notifications.domain.dto;

/**
 * Notification type catalogue entry DTO (ADR-0024 D-10).
 */
public record NotificationTypeDto(
        Long id,
        String uid,
        Long companyId,
        String typeKey,
        String displayName,
        String description,
        String audiencePermission,
        boolean branchScoped,
        String defaultChannels,
        String severity,
        String titleTemplate,
        String bodyTemplate,
        String linkRouteTemplate,
        boolean companyEnabled,
        String status
) {}

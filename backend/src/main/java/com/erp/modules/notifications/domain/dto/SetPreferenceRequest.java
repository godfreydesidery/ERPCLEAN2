package com.erp.modules.notifications.domain.dto;

/**
 * Request to set per-user notification preferences for a type (ADR-0024 D-10).
 */
public record SetPreferenceRequest(
        boolean muted,
        String channelsEnabled
) {}

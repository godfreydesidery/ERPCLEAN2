package com.erp.modules.notifications.domain.enums;

/**
 * Why a delivery was suppressed (ADR-0024 D-3).
 * Answers "why didn't I get it?" from the delivery log.
 */
public enum SuppressionReason {
    MUTED,
    CHANNEL_DISABLED,
    NO_EMAIL,
    COMPANY_TYPE_OFF,
    NO_AUDIENCE
}

package com.erp.modules.notifications.domain.enums;

/**
 * Delivery channel for a notification (ADR-0024 D-3).
 * v1 writes only IN_APP and EMAIL; the full set is reserved in the DB CHECK so adding a
 * sender is additive (NFR-NOTIF-07).
 */
public enum NotificationChannel {
    IN_APP,
    EMAIL,
    /** Reserved — not sent in v1. */
    SMS,
    /** Reserved — not sent in v1. */
    PUSH,
    /** Reserved — not sent in v1. */
    WEBHOOK
}

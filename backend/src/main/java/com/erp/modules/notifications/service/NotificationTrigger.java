package com.erp.modules.notifications.service;

import java.util.Map;

/**
 * Internal value object carrying everything the {@link NotificationRaiserImpl} needs to
 * fan out a notification (ADR-0024 D-7). Both the dispatcher (event-driven) and the scanner
 * (time-based) build this and hand it to {@link NotificationRaiser#raise}.
 *
 * @param companyId            the company the notification is about
 * @param branchId             source branch; null for company-level triggers
 * @param typeKey              notification type catalogue key (e.g. "LOW_STOCK")
 * @param audiencePermission   the permission whose holders are the audience;
 *                             overrides the type's catalogue value when non-null
 *                             (used by APPROVAL_PENDING whose audience is dynamic)
 * @param sourceAggregateType  diagnostic aggregate kind
 * @param sourceUid            the source entity uid (deep-link target)
 * @param templateValues       already-formatted strings for placeholder substitution (D-5)
 * @param triggerKey           dedup key: event.uid() for events; "scan:TYPE:key" for scans
 */
public record NotificationTrigger(
        Long companyId,
        Long branchId,
        String typeKey,
        String audiencePermission,
        String sourceAggregateType,
        String sourceUid,
        Map<String, String> templateValues,
        String triggerKey
) {}

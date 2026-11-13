package com.erp.modules.subscription.domain.dto;

import java.time.LocalDate;

/**
 * What a tenant's own users see of the subscription, in {@code /auth/me} (ADR-0069 D-11): enough
 * for the banner. Null for the platform operator and for tenants without a plan.
 */
public record SubscriptionSummaryDto(
        String planCode,
        String planName,
        String stage,
        LocalDate dueDate,
        LocalDate readOnlyFrom,
        LocalDate suspendedFrom,
        boolean addOnRestaurant,
        boolean addOnOrbixHq) {
}

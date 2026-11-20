package com.erp.modules.subscription.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One tenant's subscription as the platform console shows it (ADR-0069). {@code assigned=false}
 * means no plan yet: the tenant is unrestricted and every plan field is null.
 */
public record TenantSubscriptionDto(
        String organisationUid,
        String organisationName,
        boolean assigned,
        String planCode,
        String planName,
        boolean addOnRestaurant,
        boolean addOnOrbixHq,
        String billingCycle,
        LocalDate trialEndsOn,
        LocalDate paidUntil,
        int graceDays,
        int readOnlyDays,
        String notes,
        String stage,
        LocalDate dueDate,
        LocalDate readOnlyFrom,
        LocalDate suspendedFrom,
        List<CapUsage> caps,
        List<PlanCatalogueDto.ModuleDto> modules,
        List<Payment> payments) {

    /**
     * {@code planLimit}/{@code effectiveLimit} null = unlimited; {@code override} null = none.
     * {@code inUse} is an int on purpose: a long is written as a JSON string (64-bit ids), and the
     * console compares it with the limits as a number.
     */
    public record CapUsage(String resource, Integer planLimit, Integer override,
                           Integer effectiveLimit, int inUse) {
    }

    public record Payment(String uid, BigDecimal amount, String currencyCode, LocalDate paidOn,
                          LocalDate paidUntil, String reference, String note, Instant recordedAt,
                          String recordedBy) {
    }
}

package com.erp.modules.subscription.domain.dto;

import com.erp.modules.subscription.domain.enums.BillingCycle;
import com.erp.modules.subscription.domain.enums.PlanCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Assign or change a tenant's subscription (ADR-0069). The whole subscription is sent: a null
 * override means "the plan's cap", a null date means "none".
 */
public record UpdateTenantSubscriptionRequest(
        @NotNull PlanCode planCode,
        boolean addOnRestaurant,
        boolean addOnOrbixHq,
        @NotNull BillingCycle billingCycle,
        LocalDate trialEndsOn,
        LocalDate paidUntil,
        @Min(0) @Max(365) int graceDays,
        @Min(0) @Max(365) int readOnlyDays,
        @Min(0) Integer maxUsersOverride,
        @Min(0) Integer maxBranchesOverride,
        @Min(0) Integer maxTillsOverride,
        @Min(0) Integer maxCompaniesOverride,
        @Size(max = 500) String notes) {
}

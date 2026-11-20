package com.erp.modules.subscription.service;

import com.erp.modules.subscription.domain.entity.OrganisationSubscription;
import com.erp.modules.subscription.domain.enums.AddOn;
import com.erp.modules.subscription.domain.enums.CapResource;
import com.erp.modules.subscription.domain.enums.PlanCode;
import com.erp.modules.subscription.domain.enums.PlanModule;
import com.erp.modules.subscription.domain.enums.SubscriptionStage;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * What a tenant's subscription allows, as an immutable snapshot (ADR-0069). Pure: no database, no
 * clock — {@code today} is always passed in, so the ladder is unit-testable day by day.
 */
public record Entitlement(PlanCode plan, boolean restaurant, boolean orbixHq,
                          Integer maxUsersOverride, Integer maxBranchesOverride,
                          Integer maxTillsOverride, Integer maxCompaniesOverride,
                          LocalDate trialEndsOn, LocalDate paidUntil, int graceDays,
                          int readOnlyDays, ZoneId zone) {

    public static Entitlement of(OrganisationSubscription s, ZoneId zone) {
        return new Entitlement(s.getPlanCode(), s.isAddOnRestaurant(), s.isAddOnOrbixHq(),
                s.getMaxUsersOverride(), s.getMaxBranchesOverride(), s.getMaxTillsOverride(),
                s.getMaxCompaniesOverride(), s.getTrialEndsOn(), s.getPaidUntil(),
                s.getGraceDays(), s.getReadOnlyDays(), zone);
    }

    // -------------------------------------------------------------------------
    // Modules (D-1, D-2, D-4)
    // -------------------------------------------------------------------------

    public boolean has(AddOn addOn) {
        return switch (addOn) {
            case RESTAURANT -> restaurant;
            case ORBIXHQ -> orbixHq;
        };
    }

    public boolean includes(PlanModule module) {
        return module.addOn() != null ? has(module.addOn()) : plan.includes(module);
    }

    /** Whether a permission code's module is part of this subscription. */
    public boolean permits(String permissionCode) {
        return includes(PlanModule.of(permissionCode));
    }

    // -------------------------------------------------------------------------
    // Caps (D-3, D-5)
    // -------------------------------------------------------------------------

    public Integer override(CapResource resource) {
        return switch (resource) {
            case USERS -> maxUsersOverride;
            case BRANCHES -> maxBranchesOverride;
            case TILLS -> maxTillsOverride;
            case COMPANIES -> maxCompaniesOverride;
        };
    }

    /** The cap in force: the override if set, else the plan's; {@code null} = unlimited. */
    public Integer cap(CapResource resource) {
        Integer o = override(resource);
        return o != null ? o : plan.cap(resource);
    }

    // -------------------------------------------------------------------------
    // The ladder (D-6, D-7)
    // -------------------------------------------------------------------------

    /** {@code paid_until} once anything was paid, else the trial end; {@code null} = never lapses. */
    public LocalDate dueDate() {
        return paidUntil != null ? paidUntil : trialEndsOn;
    }

    /** The first read-only day, or {@code null} when the subscription never lapses. */
    public LocalDate readOnlyFrom() {
        LocalDate due = dueDate();
        return due == null ? null : due.plusDays(graceDays + 1L);
    }

    /** The first suspended day, or {@code null} when the subscription never lapses. */
    public LocalDate suspendedFrom() {
        LocalDate due = dueDate();
        return due == null ? null : due.plusDays((long) graceDays + readOnlyDays + 1L);
    }

    public SubscriptionStage stageOn(LocalDate today) {
        LocalDate due = dueDate();
        if (due == null) {
            return SubscriptionStage.ACTIVE;
        }
        long overdue = ChronoUnit.DAYS.between(due, today);
        if (overdue <= 0) {
            return paidUntil == null ? SubscriptionStage.TRIAL : SubscriptionStage.ACTIVE;
        }
        if (overdue <= graceDays) {
            return SubscriptionStage.GRACE;
        }
        if (overdue <= (long) graceDays + readOnlyDays) {
            return SubscriptionStage.READ_ONLY;
        }
        return SubscriptionStage.SUSPENDED;
    }

    /** "Today" in the tenant's own time zone. */
    public LocalDate today(java.time.Clock clock) {
        return LocalDate.now(clock.withZone(zone));
    }
}

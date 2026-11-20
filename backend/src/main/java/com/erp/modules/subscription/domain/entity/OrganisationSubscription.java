package com.erp.modules.subscription.domain.entity;

import com.erp.modules.subscription.domain.enums.BillingCycle;
import com.erp.modules.subscription.domain.enums.PlanCode;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;

/**
 * A tenant's subscription (ADR-0069): plan, add-ons, cap overrides, and the dates that place it on
 * the non-payment ladder. One row per organisation; no row means unrestricted (D-10).
 */
@Getter
@Entity
@Table(name = "organisation_subscriptions")
public class OrganisationSubscription extends UidEntity {

    @Column(name = "organisation_id", nullable = false, updatable = false)
    private Long organisationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, length = 20)
    private PlanCode planCode;

    @Column(name = "addon_restaurant", nullable = false)
    private boolean addOnRestaurant;

    @Column(name = "addon_orbixhq", nullable = false)
    private boolean addOnOrbixHq;

    @Column(name = "max_users_override")
    private Integer maxUsersOverride;

    @Column(name = "max_branches_override")
    private Integer maxBranchesOverride;

    @Column(name = "max_tills_override")
    private Integer maxTillsOverride;

    @Column(name = "max_companies_override")
    private Integer maxCompaniesOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 20)
    private BillingCycle billingCycle = BillingCycle.MONTHLY;

    @Column(name = "trial_ends_on")
    private LocalDate trialEndsOn;

    @Column(name = "paid_until")
    private LocalDate paidUntil;

    @Column(name = "grace_days", nullable = false)
    private int graceDays = 7;

    @Column(name = "read_only_days", nullable = false)
    private int readOnlyDays = 7;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected OrganisationSubscription() {
        // JPA
    }

    public OrganisationSubscription(Long organisationId, PlanCode planCode, Long createdBy) {
        this.organisationId = organisationId;
        this.planCode = planCode;
        this.createdBy = createdBy;
    }

    /** Replace every operator-editable term at once (the edit form sends the whole subscription). */
    public void changeTerms(PlanCode planCode, boolean addOnRestaurant, boolean addOnOrbixHq,
                            BillingCycle billingCycle, LocalDate trialEndsOn, LocalDate paidUntil,
                            int graceDays, int readOnlyDays, Integer maxUsersOverride,
                            Integer maxBranchesOverride, Integer maxTillsOverride,
                            Integer maxCompaniesOverride, String notes, Long actorId) {
        this.planCode = planCode;
        this.addOnRestaurant = addOnRestaurant;
        this.addOnOrbixHq = addOnOrbixHq;
        this.billingCycle = billingCycle;
        this.trialEndsOn = trialEndsOn;
        this.paidUntil = paidUntil;
        this.graceDays = graceDays;
        this.readOnlyDays = readOnlyDays;
        this.maxUsersOverride = maxUsersOverride;
        this.maxBranchesOverride = maxBranchesOverride;
        this.maxTillsOverride = maxTillsOverride;
        this.maxCompaniesOverride = maxCompaniesOverride;
        this.notes = notes;
        touch(actorId);
    }

    /** A new tenant's trial (D-7). */
    public void startTrial(LocalDate trialEndsOn, boolean addOnRestaurant, boolean addOnOrbixHq) {
        this.trialEndsOn = trialEndsOn;
        this.addOnRestaurant = addOnRestaurant;
        this.addOnOrbixHq = addOnOrbixHq;
    }

    /** A recorded payment moves the paid-until date (D-8). */
    public void paidUntil(LocalDate paidUntil, Long actorId) {
        this.paidUntil = paidUntil;
        touch(actorId);
    }

    private void touch(Long actorId) {
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }
}

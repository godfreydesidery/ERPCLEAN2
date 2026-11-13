package com.erp.modules.subscription.service;

import com.erp.modules.subscription.domain.enums.CapResource;
import com.erp.modules.subscription.domain.enums.SubscriptionStage;
import java.time.Instant;
import java.util.Optional;

/**
 * The runtime face of a tenant's subscription (ADR-0069): what the permission resolver, the
 * request guard, sign-in and the cap checks ask. Answers are cached per organisation and evicted
 * when the subscription changes. An organisation with no subscription (every tenant before a plan
 * is assigned, and the platform organisation) is unrestricted.
 */
public interface SubscriptionGate {

    /** The tenant's entitlement, or empty when it has no subscription (unrestricted). */
    Optional<Entitlement> entitlementOf(Long organisationId);

    /** Today's stage; {@link SubscriptionStage#UNRESTRICTED} when there is no subscription. */
    SubscriptionStage stageOf(Long organisationId);

    /** Whether the tenant's plan includes the module a permission code belongs to. */
    boolean permits(Long organisationId, String permissionCode);

    /**
     * The instant the tenant became read-only (start of the first read-only day in its time zone),
     * or {@code null} when it is not read-only or suspended today.
     */
    Instant readOnlySince(Long organisationId);

    /**
     * Refuse adding one more {@code resource} when the tenant is at its cap (D-5). Counts what is
     * already there; never removes anything. No-op without a subscription or for an unlimited cap.
     */
    void assertCanAdd(Long organisationId, CapResource resource);

    /** {@link #assertCanAdd} for the tenant that owns {@code companyId}. */
    void assertCanAddInCompany(Long companyId, CapResource resource);

    /** Drop the cached answer for one tenant (called when its subscription changes). */
    void evict(Long organisationId);
}

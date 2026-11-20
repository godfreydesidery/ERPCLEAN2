package com.erp.modules.subscription.service;

import com.erp.modules.subscription.domain.dto.PlanCatalogueDto;
import com.erp.modules.subscription.domain.dto.RecordSubscriptionPaymentRequest;
import com.erp.modules.subscription.domain.dto.SubscriptionSummaryDto;
import com.erp.modules.subscription.domain.dto.TenantSubscriptionDto;
import com.erp.modules.subscription.domain.dto.UpdateTenantSubscriptionRequest;
import com.erp.modules.subscription.domain.enums.PlanCode;
import java.util.Optional;

/** Tenant subscriptions (ADR-0069). Everything but {@link #summaryFor} is platform-operator only. */
public interface SubscriptionService {

    PlanCatalogueDto catalogue();

    TenantSubscriptionDto get(String organisationUid);

    /** Assign a plan (creating the subscription) or change its terms. */
    TenantSubscriptionDto update(String organisationUid, UpdateTenantSubscriptionRequest request);

    TenantSubscriptionDto recordPayment(String organisationUid,
                                        RecordSubscriptionPaymentRequest request);

    /**
     * Start a new tenant's 14-day trial on the chosen plan (D-7). Called by tenant provisioning, in
     * its transaction.
     */
    void startTrial(Long organisationId, PlanCode plan, boolean restaurant, boolean orbixHq);

    /** The banner summary for a tenant; empty when it has no plan (unrestricted). */
    Optional<SubscriptionSummaryDto> summaryFor(Long organisationId);
}

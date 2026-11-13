package com.erp.api;

import com.erp.modules.subscription.domain.dto.RecordSubscriptionPaymentRequest;
import com.erp.modules.subscription.domain.dto.TenantSubscriptionDto;
import com.erp.modules.subscription.domain.dto.UpdateTenantSubscriptionRequest;
import com.erp.modules.subscription.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant's subscription, for the platform console (ADR-0069). Platform codes only (D-11): reading
 * needs ORG.CREATE (as the tenant list does), changing it or recording a payment needs ORG.SUSPEND.
 * The service checks for the platform operator again.
 */
@RestController
@RequestMapping("/api/v1/organisations/uid/{uid}/subscription")
@Tag(name = "Tenant subscriptions")
public class TenantSubscriptionController {

    private final SubscriptionService subscriptions;

    public TenantSubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    @Operation(summary = "A tenant's plan, add-ons, caps in use, ladder dates and payments")
    @PreAuthorize("@perm.has('ORG.CREATE')")
    public TenantSubscriptionDto get(@PathVariable String uid) {
        return subscriptions.get(uid);
    }

    @PutMapping
    @Operation(summary = "Assign a plan to a tenant, or change its subscription terms")
    @PreAuthorize("@perm.has('ORG.SUSPEND')")
    public TenantSubscriptionDto update(@PathVariable String uid,
                                        @Valid @RequestBody UpdateTenantSubscriptionRequest request) {
        return subscriptions.update(uid, request);
    }

    @PostMapping("/payments")
    @Operation(summary = "Record a payment; its paid-until date becomes the subscription's")
    @PreAuthorize("@perm.has('ORG.SUSPEND')")
    public TenantSubscriptionDto recordPayment(
            @PathVariable String uid, @Valid @RequestBody RecordSubscriptionPaymentRequest request) {
        return subscriptions.recordPayment(uid, request);
    }
}

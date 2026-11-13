package com.erp.api;

import com.erp.modules.subscription.domain.dto.PlanCatalogueDto;
import com.erp.modules.subscription.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The plan catalogue for the platform console (ADR-0069 D-1). */
@RestController
@RequestMapping("/api/v1/subscription-plans")
@Tag(name = "Tenant subscriptions")
public class SubscriptionPlanController {

    private final SubscriptionService subscriptions;

    public SubscriptionPlanController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    @Operation(summary = "The four plans, their caps and modules, and the add-ons")
    @PreAuthorize("@perm.has('ORG.CREATE')")
    public PlanCatalogueDto catalogue() {
        return subscriptions.catalogue();
    }
}

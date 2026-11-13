package com.erp.modules.subscription.domain.enums;

/** How often a tenant is billed. Informational: the web pre-fills the next paid-until from it (D-8). */
public enum BillingCycle {
    MONTHLY,
    QUARTERLY,
    HALF_YEARLY,
    ANNUAL,
    CUSTOM
}

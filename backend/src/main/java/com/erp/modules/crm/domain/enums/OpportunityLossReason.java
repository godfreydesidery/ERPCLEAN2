package com.erp.modules.crm.domain.enums;

/**
 * Reason an opportunity was marked LOST (ADR-0031, P2 enum swap).
 * Previously free-text; constrained to this set via chk_opportunity_loss_reason.
 */
public enum OpportunityLossReason {
    PRICE,
    COMPETITOR,
    NO_BUDGET,
    NO_DECISION,
    TIMING,
    NO_REQUIREMENT,
    LOST_CONTACT,
    PRODUCT_FIT,
    OTHER
}

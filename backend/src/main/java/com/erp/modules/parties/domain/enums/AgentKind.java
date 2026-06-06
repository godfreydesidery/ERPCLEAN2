package com.erp.modules.parties.domain.enums;

/**
 * Agent kind (FR-PARTY-13, ADR-0006 D-5): internal (references an app_user) or external
 * (standalone party with no IAM link).
 */
public enum AgentKind {
    INTERNAL,
    EXTERNAL
}

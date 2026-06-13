package com.erp.modules.projects.domain.enums;

/**
 * Lifecycle states for a project (ADR-0033 D-2, FR-PROJ-02).
 *
 * <p>Transitions:
 * DRAFT ──activate──▶ ACTIVE ──hold──▶ ON_HOLD ──resume──▶ ACTIVE
 * ACTIVE | ON_HOLD ──complete──▶ COMPLETED (terminal)
 * any non-terminal ──cancel──▶ CANCELLED (terminal)
 *
 * <p>Tagging is allowed when ACTIVE or ON_HOLD (BR-PROJ-04).
 * COMPLETED / CANCELLED reject new tags (OQ-PROJ-06).
 */
public enum ProjectStatus {

    /** Initial state; not yet open for tagging. */
    DRAFT,

    /** Open for tagging, costs, and billing. */
    ACTIVE,

    /** Temporarily paused; tagging allowed by default (BR-PROJ-04). */
    ON_HOLD,

    /** Terminal — no new tags accepted. History visible. */
    COMPLETED,

    /** Terminal — no new tags accepted. History visible. */
    CANCELLED;

    /** True if this state allows a project tag to be attached (BR-PROJ-04). */
    public boolean allowsTagging() {
        return this == ACTIVE || this == ON_HOLD;
    }
}

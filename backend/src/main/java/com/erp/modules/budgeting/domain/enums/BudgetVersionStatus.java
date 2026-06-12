package com.erp.modules.budgeting.domain.enums;

/**
 * Self-contained budget-version lifecycle (ADR-0034 D-5, BR-BUD-13).
 *
 * <pre>
 * DRAFT ──submit──▶ SUBMITTED ──approve──▶ APPROVED  (active; supersedes prior)
 *   ▲                   │                      │
 *   │                   ├──reject──▶ REJECTED  (terminal; re-plan = new version)
 *   └──recall────────────┘                     │
 *                                          SUPERSEDED  (when newer version approved; terminal)
 * </pre>
 *
 * Status is NEVER free-set; every transition goes through BudgetVersionLifecycle (D-5).
 */
public enum BudgetVersionStatus {

    /** Editable; lines may be added/changed/removed. Initial state on version creation. */
    DRAFT,

    /** Submitted for approval; lines locked from edit. DRAFT→SUBMITTED via submit(). */
    SUBMITTED,

    /** The single active version for its (company, FY, cost-centre) scope. SUBMITTED→APPROVED. */
    APPROVED,

    /** Rejected by approver; terminal. Re-plan = create a new version. SUBMITTED→REJECTED. */
    REJECTED,

    /** A newer APPROVED version exists for the same scope; terminal; retained for history. */
    SUPERSEDED
}

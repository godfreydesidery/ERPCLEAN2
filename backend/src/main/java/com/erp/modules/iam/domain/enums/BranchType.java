package com.erp.modules.iam.domain.enums;

/**
 * Classification of a branch (P2 mechanical gap, GAP-FIX-WORKLIST iam/Branch).
 *
 * <p>Nullable on the entity — purely a descriptive classification with no behaviour in v1.
 * Mirrored by the {@code chk_branch_type} CHECK constraint in V1.
 */
public enum BranchType {
    HEAD_OFFICE,
    RETAIL,
    WAREHOUSE,
    SALES_OFFICE,
    FACTORY,
    OTHER
}

package com.erp.modules.approvals.domain.enums;

/**
 * Controls whether an approval policy applies to one specific branch or to all branches
 * company-wide (ADR-0022 D-2, D-3).
 *
 * <p>In the policy-match function (D-3) a BRANCH-scoped policy beats a COMPANY_WIDE one when
 * both would otherwise match (branch-specificity wins).
 */
public enum PolicyBranchScope {
    /** Policy applies to every branch in the company (branch_id = NULL on the policy). */
    COMPANY_WIDE,
    /** Policy applies only to one specific branch (branch_id set on the policy). */
    BRANCH
}

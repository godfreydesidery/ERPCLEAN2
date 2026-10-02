package com.erp.modules.reporting.service;

/**
 * Which slice of a company's ledger a financial statement reads.
 *
 * <ul>
 *   <li><b>Company-wide</b> ({@link #companyWide()}) — every journal line of the company. This is
 *       what every statement read before branch statements existed, and it stays the default.
 *   <li><b>One branch</b> — only the lines stamped with that {@code journal_lines.branch_id}.
 *   <li><b>Company-level entries</b> ({@code unassignedOnly}) — only the lines with NO branch:
 *       manual journals, AR opening balances, FX revaluation runs and the year-end close are
 *       posted that way. Offered so that the branch statements plus this one visibly add back to
 *       the company statement.
 * </ul>
 *
 * <p>Why a branch statement balances on its own: the GL posting engine stamps the draft's single
 * {@code branchId} on the entry AND on every one of its lines, so every journal entry is balanced
 * inside exactly one of these slices. A slice can only fail to balance if some entry's lines carry
 * different branches — which nothing in the application writes today. The statements still compute
 * that figure (the "inter-branch balance") rather than assume it, and show it as an explicit line.
 *
 * @param branchId       resolved branch id, or null
 * @param branchUid      the branch uid as supplied, or null
 * @param label          what the statement header prints: a branch name, {@link #ALL_BRANCHES},
 *                       or {@link #COMPANY_LEVEL}
 * @param unassignedOnly true for the "company-level entries" slice
 */
public record StatementScope(Long branchId, String branchUid, String label, boolean unassignedOnly) {

    public static final String ALL_BRANCHES  = "All branches";
    public static final String COMPANY_LEVEL = "Company-level entries (no branch)";

    private static final StatementScope COMPANY_WIDE =
            new StatementScope(null, null, ALL_BRANCHES, false);

    public static StatementScope companyWide() {
        return COMPANY_WIDE;
    }

    public static StatementScope branch(Long branchId, String branchUid, String branchName) {
        return new StatementScope(branchId, branchUid, branchName, false);
    }

    public static StatementScope unassigned() {
        return new StatementScope(null, null, COMPANY_LEVEL, true);
    }

    /** True for the whole company — no line filter applies. */
    public boolean isCompanyWide() {
        return branchId == null && !unassignedOnly;
    }
}

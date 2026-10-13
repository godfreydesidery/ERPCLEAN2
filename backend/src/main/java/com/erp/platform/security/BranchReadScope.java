package com.erp.platform.security;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Which branches a report read may cover, as decided by {@link BranchReadGuard#readScope}.
 *
 * <ul>
 *   <li><b>Every branch</b> ({@code branchIds == null}) — root, or a caller with a company-wide
 *       role grant, asking for no branch filter.
 *   <li><b>One branch</b> ({@code filtered == true}) — the caller asked for a branch and may read it.
 *   <li><b>My branches</b> — a branch-limited caller asked for no filter. "All branches" means the
 *       branches they are assigned to, never the whole company (owner ruling 2026-10-10). The list
 *       may be empty: a caller with no live assignment reads nothing.
 * </ul>
 *
 * @param branchIds   the branch ids the read is narrowed to, or {@code null} for every branch
 * @param branchNames the names of {@code branchIds}, in the same order (empty for every branch)
 * @param filtered    true when the caller asked for exactly one branch
 */
public record BranchReadScope(List<Long> branchIds, List<String> branchNames, boolean filtered) {

    /** What a report says about an unnarrowed read. */
    public static final String ALL_BRANCHES = "All branches";

    public BranchReadScope {
        branchIds   = branchIds == null ? null : List.copyOf(branchIds);
        branchNames = branchNames == null ? List.of() : List.copyOf(branchNames);
    }

    public static BranchReadScope everyBranch() {
        return new BranchReadScope(null, List.of(), false);
    }

    public static BranchReadScope single(Long branchId, String branchName) {
        return new BranchReadScope(List.of(branchId),
                branchName == null ? List.of() : List.of(branchName), true);
    }

    public static BranchReadScope assigned(List<Long> branchIds, List<String> branchNames) {
        return new BranchReadScope(branchIds, branchNames, false);
    }

    /** True when nothing narrows the read: every branch of the company. */
    public boolean everyBranchOfCompany() {
        return branchIds == null;
    }

    /** True for a branch-limited caller's unfiltered read ("all MY branches"). */
    public boolean limitedToAssigned() {
        return branchIds != null && !filtered;
    }

    /**
     * The SQL predicate that narrows {@code column} to this scope, ready to append to a WHERE
     * clause: empty for every branch, {@code AND <column> IN (...)} otherwise, and a predicate that
     * matches nothing for an empty assignment list.
     *
     * <p>The ids are inlined rather than bound so the predicate can be appended to any existing
     * query without shifting its positional parameters. That is safe: they are {@code Long}s read
     * back from {@code user_branch} / {@code branches}, never caller text, and are rendered with
     * {@link Long#toString}.
     *
     * @param column a trusted, code-supplied column reference such as {@code "i.branch_id"}
     */
    public String sql(String column) {
        if (branchIds == null) {
            return "";
        }
        if (branchIds.isEmpty()) {
            return " AND 1 = 0";
        }
        return " AND " + column + " IN ("
                + branchIds.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")";
    }

    /** Whether a row stamped with {@code branchId} falls inside this scope. */
    public boolean includes(Long branchId) {
        return branchIds == null || (branchId != null && branchIds.contains(branchId));
    }

    /**
     * What the read actually covers, for a report body or header: {@code "All branches"}, the one
     * branch's name, or {@code "Branches: A, B"} for a branch-limited caller's unfiltered read.
     */
    public String label() {
        if (branchIds == null) {
            return ALL_BRANCHES;
        }
        if (filtered) {
            return branchNames.isEmpty() ? "" : branchNames.get(0);
        }
        if (branchNames.isEmpty()) {
            return "Branches: none assigned";
        }
        return "Branches: " + String.join(", ", branchNames);
    }

    /** The scope as one printed header line: {@code "Branch: X"}, or the label when it is a list. */
    public String headerLine() {
        return limitedToAssigned() ? label() : "Branch: " + label();
    }
}

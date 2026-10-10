package com.erp.platform.security;

import com.erp.platform.common.api.ForbiddenException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * The one rule every report applies to its branch scope (owner ruling 2026-10-10, RPT-06 / LRB-06).
 *
 * <p><b>Who sees the whole company.</b> Root, and a caller holding a <em>company-wide role
 * grant</em> in the report's company: an active {@code user_role} row with {@code branch_id IS
 * NULL} (FR-IAM-13 — a grant made without choosing a branch). That is the existing IAM notion of
 * company-wide access; nothing new is invented here. Such a caller reads every branch when no
 * branch is chosen, and may filter to ANY branch of the company, including one they are not
 * assigned to (LRB-06: the drill-down used to be refused while the company total already showed
 * that branch).
 *
 * <p><b>Everyone else is branch-limited.</b> Their grants are all branch-scoped, so "All branches"
 * means the branches they hold a live {@code user_branch} assignment to — never the whole company.
 * Filtering to a branch outside those is refused with {@link #branchNotAssigned()}, the same bar as
 * the {@code X-Branch-Uid} session override in {@link JwtRequestContextFilter} (ADR-0003).
 *
 * <p>Callers pass the RESOLVED branch id (resolved together with the report's {@code company_id},
 * so a foreign uid is already "not found"), never the raw parameter, and apply the returned
 * {@link BranchReadScope} to every branch-dimensional read with {@link BranchReadScope#sql}.
 *
 * <p>This used to be a private method copied into each report query, and three reports (Sales,
 * Stock, Stock Movement) never got the copy — the hole the copies were written to close stayed open
 * in them. One component means a new report cannot forget it by omission.
 *
 * <p>Read with raw SQL rather than IAM's repositories: report queries are scalar-SQL throughout and
 * importing another module's repository would breach the module boundary.
 */
@Component
public class BranchReadGuard {

    private final JdbcTemplate jdbc;

    public BranchReadGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Decide which branches a report read covers. {@code ScopeGuard.assertCanActIn(principal,
     * companyId)} must already have passed.
     *
     * @param companyId the report's company
     * @param branchId  the resolved branch id of the caller's filter, or null when none was given
     * @return the scope to apply; never null
     * @throws ForbiddenException when a branch-limited caller filters to a branch they are not
     *         assigned to, or the session has no user behind it
     */
    public BranchReadScope readScope(RequestContext.Principal principal, Long companyId,
                                     Long branchId) {
        if (principal != null && principal.root()) {
            return branchId == null ? BranchReadScope.everyBranch() : single(branchId);
        }
        Long userId = principal != null ? principal.userId() : null;
        if (userId == null) {
            if (branchId == null && principal != null) {
                // A SYSTEM principal (outbox handler, scheduled job) has no user and no grants; it
                // was already admitted to the company by ScopeGuard and reads it whole, as before.
                return BranchReadScope.everyBranch();
            }
            // A session with no user behind it is a broken session, not a branch problem — the
            // generic wording is the honest one here.
            throw ForbiddenException.notPermitted();
        }
        boolean companyWide = hasCompanyWideGrant(userId, companyId);
        if (branchId != null) {
            if (!companyWide && !isAssigned(userId, branchId)) {
                throw branchNotAssigned();
            }
            return single(branchId);
        }
        return companyWide ? BranchReadScope.everyBranch() : assignedBranches(userId, companyId);
    }

    /**
     * The scope a report covered, as its printed header line — for PDF/CSV exports, which are
     * rendered after the read and must state the real scope (RPT-05): {@code "Branch: All
     * branches"}, {@code "Branch: Arusha"} or {@code "Branches: Arusha, Moshi"}.
     *
     * <p>Call it only after the report read itself succeeded: that read has already refused an
     * unknown or unassigned branch, so this only describes.
     */
    public String scopeHeaderLine(Long companyId, String branchUid) {
        Long branchId = null;
        if (branchUid != null && !branchUid.isBlank()) {
            List<Long> ids = jdbc.query(
                    "SELECT id FROM branches WHERE uid = ? AND company_id = ?",
                    (rs, n) -> rs.getLong(1), branchUid.trim(), companyId);
            if (!ids.isEmpty()) {
                branchId = ids.get(0);
            }
        }
        return readScope(RequestContext.get(), companyId, branchId).headerLine();
    }

    /**
     * True when the caller reads the whole company: root, or a company-wide role grant in
     * {@code companyId}. Panels that cannot be narrowed to branches (GL-wide finance figures) are
     * shown only to these callers.
     */
    public boolean seesWholeCompany(RequestContext.Principal principal, Long companyId) {
        if (principal == null) {
            return false;
        }
        if (principal.root()) {
            return true;
        }
        if (principal.userId() == null) {
            return true; // SYSTEM principal — see readScope
        }
        return hasCompanyWideGrant(principal.userId(), companyId);
    }

    /**
     * The same assignment rule for a WRITE that a caller stamps with a branch of their choosing — a
     * manual journal posted to a branch. Root is exempt; a non-root caller must hold a live
     * {@code user_branch} assignment. A {@code null} id means "no branch" (company level) and is
     * not narrowed. The refusal names the remedy for a poster, not for a report reader.
     *
     * @param branchId the resolved branch id the caller asked to post to, or null
     */
    public void assertMayPostTo(RequestContext.Principal principal, Long branchId) {
        if (branchId == null) {
            return;
        }
        if (principal != null && principal.root()) {
            return;
        }
        Long userId = principal != null ? principal.userId() : null;
        if (userId == null) {
            throw ForbiddenException.notPermitted();
        }
        if (!isAssigned(userId, branchId)) {
            throw new ForbiddenException(
                    "You are not assigned to that branch, so you cannot post to it. Choose a branch "
                            + "you work in, or leave the branch empty to post at company level.");
        }
    }

    private boolean isAssigned(Long userId, Long branchId) {
        Integer assigned = jdbc.query(
                """
                SELECT 1
                FROM user_branch
                WHERE user_id = ? AND branch_id = ? AND active = true AND revoked_at IS NULL
                LIMIT 1
                """,
                (ResultSetExtractor<Integer>) rs -> rs.next() ? 1 : null,
                userId, branchId);
        return assigned != null;
    }

    /**
     * A company-wide role grant: an active, unexpired {@code user_role} row in the company with no
     * branch, on an ACTIVE role. The same rows {@code UserRoleRepository.resolvePermissionCodes}
     * reads as "company-wide" ({@code ur.branchId IS NULL}).
     */
    private boolean hasCompanyWideGrant(Long userId, Long companyId) {
        if (companyId == null) {
            return false;
        }
        Integer found = jdbc.query(
                """
                SELECT 1
                FROM user_role ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                  AND ur.company_id = ?
                  AND ur.branch_id IS NULL
                  AND ur.revoked_at IS NULL
                  AND (ur.expires_at IS NULL OR ur.expires_at > now())
                  AND r.status = 'ACTIVE'
                LIMIT 1
                """,
                (ResultSetExtractor<Integer>) rs -> rs.next() ? 1 : null,
                userId, companyId);
        return found != null;
    }

    /** The caller's live branch assignments inside {@code companyId}, by name. */
    private BranchReadScope assignedBranches(Long userId, Long companyId) {
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        jdbc.query(
                """
                SELECT b.id, b.name
                FROM user_branch ub
                JOIN branches b ON b.id = ub.branch_id
                WHERE ub.user_id = ?
                  AND b.company_id = ?
                  AND ub.active = true
                  AND ub.revoked_at IS NULL
                ORDER BY b.name, b.id
                """,
                (RowCallbackHandler) rs -> {
                    ids.add(rs.getLong("id"));
                    names.add(rs.getString("name"));
                },
                userId, companyId);
        return BranchReadScope.assigned(ids, names);
    }

    private BranchReadScope single(Long branchId) {
        List<String> names = jdbc.query("SELECT name FROM branches WHERE id = ?",
                (rs, n) -> rs.getString(1), branchId);
        return BranchReadScope.single(branchId, names.isEmpty() ? null : names.get(0));
    }

    /**
     * What a branch-limited caller reads when they filter to a branch they are not assigned to.
     *
     * <p>Deliberately NOT the generic "you do not have permission" wording. The caller holds the
     * report permission — that is how they reached the screen — so a permission-shaped refusal sends
     * them to an administrator to ask for something they already have, and leaves them believing the
     * report is broken. The remedy is a branch assignment, or "All branches", which for them means
     * their own branches. It used to say "clear the branch filter to see the whole company"; that is
     * no longer true for a branch-limited caller and must not be promised.
     *
     * <p>Plain literal on purpose — the branch name and uid stay out of it (error-message hygiene).
     */
    public static ForbiddenException branchNotAssigned() {
        return new ForbiddenException(
                "You are not assigned to that branch. Choose one of your branches, or choose "
                        + "All branches to see every branch you work in.");
    }
}

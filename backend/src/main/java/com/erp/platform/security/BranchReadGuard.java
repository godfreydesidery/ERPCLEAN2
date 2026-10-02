package com.erp.platform.security;

import com.erp.platform.common.api.ForbiddenException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

/**
 * The one rule every report applies to a caller-supplied branch filter.
 *
 * <p>A branch uid on a report's query string is a filter the CALLER supplies, so it must clear the
 * same bar as the {@code X-Branch-Uid} session override in {@link JwtRequestContextFilter}
 * (ADR-0003): same company — which each report enforces by resolving the uid together with its
 * {@code company_id} — AND a live {@code user_branch} assignment. Without the second half a
 * storekeeper assigned only to Arusha could read Dodoma's quantities, costs and margins by editing
 * the URL, which the header path refuses.
 *
 * <p>Callers pass the RESOLVED branch id, never the raw parameter. A {@code null} id means "no
 * branch filter": the company-wide read is already authorised by {@code ScopeGuard.assertCanActIn}
 * and is not narrowed here.
 *
 * <p>Root is exempt (ScopeGuard already audits its cross-scope reads). Anything else fails closed.
 *
 * <p>This used to be a private method copied into each report query, and three reports (Sales,
 * Stock, Stock Movement) never got the copy — the hole the copies were written to close stayed open
 * in them. One component means a new report cannot forget it by omission.
 *
 * <p>Read with raw SQL rather than IAM's {@code UserBranchRepository}: report queries are
 * scalar-SQL throughout and importing another module's repository would breach the module boundary.
 */
@Component
public class BranchReadGuard {

    private final JdbcTemplate jdbc;

    public BranchReadGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param branchId the resolved branch id of the caller's filter, or null when none was given
     */
    public void assertMayRead(RequestContext.Principal principal, Long branchId) {
        if (branchId == null) {
            return; // no branch filter — the company-wide read is already authorised
        }
        if (principal != null && principal.root()) {
            return;
        }
        Long userId = principal != null ? principal.userId() : null;
        if (userId == null) {
            // A session with no user behind it is a broken session, not a branch problem — the
            // generic wording is the honest one here.
            throw ForbiddenException.notPermitted();
        }
        Integer assigned = jdbc.query(
                """
                SELECT 1
                FROM user_branch
                WHERE user_id = ? AND branch_id = ? AND active = true AND revoked_at IS NULL
                LIMIT 1
                """,
                (ResultSetExtractor<Integer>) rs -> rs.next() ? 1 : null,
                userId, branchId);
        if (assigned == null) {
            throw branchNotAssigned();
        }
    }

    /**
     * What the caller reads when they filter to a branch they are not assigned to.
     *
     * <p>Deliberately NOT the generic "you do not have permission" wording. The caller holds the
     * report permission — that is how they reached the screen — so a permission-shaped refusal sends
     * them to an administrator to ask for something they already have, and leaves them believing the
     * report is broken. The actual remedy is either a branch assignment or no branch filter, and both
     * are worth saying. Naming the constraint that was applied is not a leak: it discloses nothing
     * about the branch, its data, or anyone else's access.
     *
     * <p>Plain literal on purpose — the branch name and uid stay out of it (error-message hygiene).
     */
    public static ForbiddenException branchNotAssigned() {
        return new ForbiddenException(
                "You are not assigned to that branch. Choose a branch you work in, or clear the "
                        + "branch filter to see the whole company.");
    }
}

package com.erp.modules.reporting.service;

import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a statement request's optional branch filter into a {@link StatementScope}.
 *
 * <p>The branch uid is resolved TOGETHER with the company the caller was already scope-checked
 * for, so a uid from another company is "not found" — never a silent widening to the whole
 * company. A resolved branch then has to clear {@link BranchReadGuard}: a caller who is not
 * assigned to that branch is refused even though they may read the company-wide statement.
 *
 * <p>The "company-level entries" slice needs no branch assignment: it is a subset of the
 * company-wide read the caller is already authorised for.
 *
 * <p>Read with scalar SQL (not IAM's {@code BranchRepository}) to stay inside the module boundary.
 */
@Component
@Transactional(readOnly = true)
public class StatementScopeResolver {

    private final JdbcTemplate    jdbc;
    private final BranchReadGuard branchGuard;

    public StatementScopeResolver(JdbcTemplate jdbc, BranchReadGuard branchGuard) {
        this.jdbc        = jdbc;
        this.branchGuard = branchGuard;
    }

    /**
     * {@code assertCanActIn(principal, companyId)} must already have passed.
     *
     * @param branchUid  optional branch filter
     * @param unassigned true for the company-level (no branch) slice; cannot be combined with a
     *                   branch
     */
    public StatementScope resolve(Long companyId, String branchUid, boolean unassigned) {
        boolean hasBranch = branchUid != null && !branchUid.isBlank();
        if (hasBranch && unassigned) {
            throw new IllegalArgumentException(
                    "Choose either a branch or the company-level entries, not both.");
        }
        if (unassigned) {
            return StatementScope.unassigned();
        }
        if (!hasBranch) {
            return StatementScope.companyWide();
        }
        List<Object[]> rows = jdbc.query(
                "SELECT id, name FROM branches WHERE uid = ? AND company_id = ?",
                (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("name")},
                branchUid.trim(), companyId);
        if (rows.isEmpty()) {
            throw NotFoundException.of("Branch", branchUid);
        }
        Long   branchId   = (Long) rows.get(0)[0];
        String branchName = (String) rows.get(0)[1];
        branchGuard.assertMayRead(RequestContext.get(), branchId);
        return StatementScope.branch(branchId, branchUid.trim(), branchName);
    }
}

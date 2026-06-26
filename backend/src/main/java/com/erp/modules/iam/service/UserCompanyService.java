package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.AssignUserCompanyRequest;
import com.erp.modules.iam.domain.dto.UserCompanyDto;
import java.util.List;

/**
 * Explicit, <strong>authoritative</strong> user↔company membership (ADR-0046, supersedes the
 * non-authoritative phase of ADR-0045). Membership is a prerequisite: a branch or role can only be
 * assigned in a company the user already belongs to ({@link #isActiveMember}). Root users bypass
 * the gate entirely. The read/isolation oracle stays additive (role OR branch OR user_company) as a
 * defensive superset — see ADR-0046 §4.
 *
 * <p>Controllers depend on this interface (DIP). All public methods are transactional.
 */
public interface UserCompanyService {

    /**
     * Ensure an active membership row exists for (userId, companyId). IDEMPOTENT: if one already
     * exists this is a no-op. Used by the startup reconcile ({@code UserCompanyBackfill}) to
     * back-fill memberships for any pre-existing active grants/branches before the authoritative
     * gate enforces assign-company-first (ADR-0046 §5). It is NOT called from grant/assign anymore.
     *
     * <p>Runs in {@code REQUIRES_NEW} — the insert commits in its own transaction so that a
     * failure (e.g. a concurrent-insert race on {@code uq_user_company_active}) rolls back only
     * the inner TX. Callers <em>must</em> invoke this via the injected {@link UserCompanyService}
     * proxy (never a self-call) for {@code REQUIRES_NEW} to take effect.
     *
     * @param userId     internal id of the user
     * @param companyId  internal id of the company
     * @param assignedBy internal id of the acting user (may be {@code null} for system-generated rows)
     */
    void ensureMembership(Long userId, Long companyId, Long assignedBy);

    /**
     * {@code true} iff the user has an active explicit company membership (ADR-0046 authoritative
     * gate). Used by {@link UserRoleService#grant} and {@link UserBranchService#assign} to enforce
     * assign-company-first.
     */
    boolean isActiveMember(Long userId, Long companyId);

    /**
     * Explicitly assign a user to a company (USER.COMPANY.MANAGE). Uses {@link #ensureMembership}
     * internally; returns the resulting dto. Scope: caller must be acting in the target company
     * (enforced via {@link com.erp.platform.security.ScopeGuard}).
     */
    UserCompanyDto assign(AssignUserCompanyRequest request);

    /**
     * Soft-revoke an explicit membership by its uid (USER.COMPANY.MANAGE). ADR-0046: rejected with a
     * conflict while the user still holds any active role grant or branch assignment in that
     * company — clear those first (no silent cascade). A future re-assign creates a new active row
     * (the unique partial index is freed on revoke).
     */
    void remove(String userCompanyUid);

    /**
     * Active company memberships for the given user (USER.VIEW). A non-root caller sees only
     * memberships in their active company (tenant-isolation, consistent with
     * {@link UserBranchServiceImpl#listForUser} and {@link UserRoleServiceImpl#listForUser}).
     */
    List<UserCompanyDto> listForUser(String userUid);
}

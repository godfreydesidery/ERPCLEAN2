package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.AssignUserCompanyRequest;
import com.erp.modules.iam.domain.dto.UserCompanyDto;
import java.util.List;

/**
 * Explicit, non-authoritative user↔company membership (V77). The membership oracle is additive:
 * a user_company row only ADDS membership — it never blocks a role/branch grant, never strips
 * access, and never acts as a hard gate. Root users bypass the oracle entirely.
 *
 * <p>Controllers depend on this interface (DIP). All public methods are transactional.
 */
public interface UserCompanyService {

    /**
     * Ensure an active membership row exists for (userId, companyId). IDEMPOTENT: if one already
     * exists this is a no-op. Used internally by {@link UserRoleService#grant} and
     * {@link UserBranchService#assign} to auto-create the membership when a grant/branch-assign
     * implies it.
     *
     * <p>Runs in {@code REQUIRES_NEW} — the insert commits in its own transaction so that a
     * failure (e.g. a concurrent-insert race on {@code uq_user_company_active}) rolls back only
     * the inner TX without poisoning the caller's grant/branch-assign TX. Callers <em>must</em>
     * invoke this via the injected {@link UserCompanyService} proxy (never a self-call) for
     * {@code REQUIRES_NEW} to take effect. Both current call sites satisfy this requirement.
     *
     * @param userId     internal id of the user
     * @param companyId  internal id of the company
     * @param assignedBy internal id of the acting user (may be {@code null} for system-generated rows)
     */
    void ensureMembership(Long userId, Long companyId, Long assignedBy);

    /**
     * Explicitly assign a user to a company (USER.COMPANY.MANAGE). Uses {@link #ensureMembership}
     * internally; returns the resulting dto. Scope: caller must be acting in the target company
     * (enforced via {@link com.erp.platform.security.ScopeGuard}).
     */
    UserCompanyDto assign(AssignUserCompanyRequest request);

    /**
     * Soft-revoke an explicit membership by its uid (USER.COMPANY.MANAGE). Only the user_company
     * row is revoked — roles and branch assignments are unaffected (non-authoritative phase). A
     * future re-assign creates a new active row (the unique partial index is freed on revoke).
     */
    void remove(String userCompanyUid);

    /**
     * Active company memberships for the given user (USER.VIEW). A non-root caller sees only
     * memberships in their active company (tenant-isolation, consistent with
     * {@link UserBranchServiceImpl#listForUser} and {@link UserRoleServiceImpl#listForUser}).
     */
    List<UserCompanyDto> listForUser(String userUid);
}

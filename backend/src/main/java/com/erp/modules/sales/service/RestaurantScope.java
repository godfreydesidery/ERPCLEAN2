package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.springframework.stereotype.Component;

/**
 * The tenancy rule for everything restaurant (ADR-0065 D-10), in one place.
 *
 * <p>A restaurant's floor, stations and orders belong to a BRANCH. The caller must be in the
 * branch's company ({@link ScopeGuard}) and assigned to the branch itself
 * ({@link BranchReadGuard#assertMayPostTo}; root is exempt). For a loaded row the company and
 * branch come from the row, never from the request.
 */
@Component
class RestaurantScope {

    private final ScopeGuard scopeGuard;
    private final BranchReadGuard branchGuard;
    private final BranchRepository branches;

    RestaurantScope(ScopeGuard scopeGuard, BranchReadGuard branchGuard, BranchRepository branches) {
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
        this.branches    = branches;
    }

    /** The caller's active company. */
    Long companyId() {
        var p = RequestContext.get();
        if (p == null || p.companyId() == null) {
            throw ForbiddenException.notPermitted();
        }
        return p.companyId();
    }

    /** The caller's user id, or null for a system principal. */
    Long actorId() {
        var p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    /**
     * Resolves a branch for a branch-level request: {@code branchUid} inside the caller's company,
     * or the caller's active branch when it is null. Refuses a branch the caller is not assigned to.
     */
    Long branchId(String branchUid) {
        var p = RequestContext.get();
        Long companyId = companyId();
        Long branchId;
        if (branchUid == null || branchUid.isBlank()) {
            branchId = p.branchId();
            if (branchId == null) {
                throw new IllegalArgumentException("Choose a branch first.");
            }
        } else {
            branchId = branches.findByUidAndCompanyId(branchUid.trim(), companyId)
                    .orElseThrow(() -> NotFoundException.of("Branch", branchUid))
                    .getId();
        }
        assertAssigned(p, branchId);
        return branchId;
    }

    /** Proves the caller may act on a loaded restaurant row of {@code companyId}/{@code branchId}. */
    void assertCanActOn(Long companyId, Long branchId) {
        var p = RequestContext.get();
        scopeGuard.assertCanActIn(p, companyId);
        assertAssigned(p, branchId);
    }

    /**
     * The guard's own refusal speaks about posting journals; a waiter needs to hear about branches.
     * Same rule, restaurant wording.
     */
    private void assertAssigned(RequestContext.Principal p, Long branchId) {
        try {
            branchGuard.assertMayPostTo(p, branchId);
        } catch (ForbiddenException e) {
            throw new ForbiddenException(
                    "This belongs to a branch you are not assigned to. Switch to your own branch.");
        }
    }
}

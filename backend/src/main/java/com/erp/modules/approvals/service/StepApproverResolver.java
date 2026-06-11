package com.erp.modules.approvals.service;

import com.erp.modules.approvals.domain.entity.ApprovalRequest;
import com.erp.modules.approvals.domain.entity.ApprovalRequestStep;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.security.RequestContext;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Fine-grained routing check for step decisions (ADR-0022 D-8).
 *
 * <p>A user may decide a step iff:
 * <ol>
 *   <li>They hold the step's {@code approver_role_code} (role-membership query via UserRoleRepository).</li>
 *   <li>For BRANCH-scoped requests, they have access to the request's branch (UserBranchRepository).</li>
 *   <li>They are NOT the submitter (SoD — BR-APR-06, OQ-APR-03 default = enforce).</li>
 * </ol>
 *
 * <p>The coarse {@code @perm.has('APPROVALS.DECIDE')} gate is on the controller; this resolver
 * handles the fine routing on top. {@code ScopeGuard.assertCanActIn} is called by the service.
 */
@Component
public class StepApproverResolver {

    private final UserRoleRepository  userRoles;
    private final UserBranchRepository userBranches;

    public StepApproverResolver(UserRoleRepository userRoles,
                                UserBranchRepository userBranches) {
        this.userRoles    = userRoles;
        this.userBranches = userBranches;
    }

    /**
     * Returns {@code true} iff the principal may decide the given open step on the given request.
     * Does NOT throw — the service throws with an appropriate message when this returns false.
     */
    public boolean canDecide(RequestContext.Principal principal,
                             ApprovalRequest request,
                             ApprovalRequestStep openStep) {
        Long userId = principal.userId();

        // SoD: submitter cannot approve own request (BR-APR-06)
        if (userId.equals(request.getSubmittedBy())) {
            return false;
        }

        // Role membership check: does the user hold the step's role in this company?
        List<String> roleCodes = userRoles.resolvePermissionCodes(
                userId, request.getCompanyId(),
                request.getBranchId() != null ? request.getBranchId() : 0L);
        // resolvePermissionCodes returns permission codes, not role codes; we need role codes.
        // Use a separate role-code resolution query.
        boolean holdsRole = holdsRoleCode(userId, request.getCompanyId(), openStep.getApproverRoleCode());
        if (!holdsRole) {
            return false;
        }

        // Branch-scoped request: user must have access to the request's branch
        if (request.getBranchId() != null) {
            boolean hasBranchAccess = userBranches
                    .findByUserIdAndBranchId(userId, request.getBranchId())
                    .isPresent();
            if (!hasBranchAccess && !principal.root()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks whether the user holds an active role with the given role code in the company.
     * Uses the UserRoleRepository active-grant query (reading roles via the IAM spine — D-12 allowed).
     */
    private boolean holdsRoleCode(Long userId, Long companyId, String roleCode) {
        return userRoles.findByUserIdAndRevokedAtIsNull(userId).stream()
                .anyMatch(ur -> ur.getCompanyId() != null
                                && ur.getCompanyId().equals(companyId)
                                && ur.getRole() != null
                                && roleCode.equals(ur.getRole().getCode()));
    }

    /**
     * Returns the role codes held by the user in the given company (for inbox query).
     */
    public List<String> resolveRoleCodes(Long userId, Long companyId) {
        return userRoles.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(ur -> ur.getCompanyId() != null && ur.getCompanyId().equals(companyId))
                .filter(ur -> ur.getRole() != null)
                .map(ur -> ur.getRole().getCode())
                .distinct()
                .toList();
    }
}

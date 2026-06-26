package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.AssignBranchRequest;
import com.erp.modules.iam.domain.dto.UserBranchDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch assignment (US-IAM-006, DATA-MODEL §1.9). The one-default invariant (BR-1) and the
 * earliest-assigned auto-fallback (FR-IAM-19, ADR-0001 D-D) each live in a single {@code @Transactional}
 * method here so they can't be applied half-way — the partial unique index {@code uq_user_branch_default}
 * is the DB backstop. Scope is enforced per branch (a branch belongs to a company): a non-root admin
 * can only assign within their active company (ADR-0002, via {@link ScopeGuard}).
 */
@Service
@Transactional
public class UserBranchServiceImpl implements UserBranchService {

    private final UserBranchRepository userBranches;
    private final AppUserRepository users;
    private final BranchRepository branches;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final UserCompanyService userCompanyService;

    public UserBranchServiceImpl(UserBranchRepository userBranches,
                                 AppUserRepository users,
                                 BranchRepository branches,
                                 ScopeGuard scopeGuard,
                                 AuditService audit,
                                 UserCompanyService userCompanyService) {
        this.userBranches = userBranches;
        this.users = users;
        this.branches = branches;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.userCompanyService = userCompanyService;
    }

    @Override
    public UserBranchDto assign(AssignBranchRequest request) {
        AppUser user = Lookups.orNotFound(users.findByUid(request.userUid()), "User", request.userUid());
        Branch branch = Lookups.orNotFound(
                branches.findByUid(request.branchUid()), "Branch", request.branchUid());

        // Scope: the caller must be able to act in the branch's company (root bypasses).
        scopeGuard.assertCanActIn(RequestContext.get(), branch.getCompany().getId());

        // ADR-0046: authoritative membership — the user must already belong to the branch's company.
        if (!userCompanyService.isActiveMember(user.getId(), branch.getCompany().getId())) {
            throw new ConflictException("Assign this user to the company before assigning branches.");
        }

        if (userBranches.findByUserIdAndBranchId(user.getId(), branch.getId()).isPresent()) {
            throw new ConflictException("User is already assigned to that branch.");
        }

        UserBranch assignment = new UserBranch(user.getId(), branch, actorId());
        if (request.makeDefault()) {
            clearCurrentDefault(user.getId());
            assignment.markDefault();
        }
        UserBranch saved = userBranches.save(assignment);

        // ADR-0046: membership is a prerequisite (asserted above), no longer auto-created here.
        audit.record(AuditEvent.of(AuditActions.BRANCH_ASSIGN, "user_branch", saved.getId(), saved.getUid())
                .detail(Map.of("userUid", user.getUid(), "branchUid", branch.getUid(),
                        "madeDefault", request.makeDefault())));
        return UserBranchDto.from(saved, user.getUid());
    }

    @Override
    public UserBranchDto setDefault(String userBranchUid) {
        UserBranch target = requireByUid(userBranchUid);
        scopeGuard.assertCanActIn(RequestContext.get(), target.getBranch().getCompany().getId());

        if (!target.isDefault()) {
            // Clear the old default and flush BEFORE marking the new one, so the partial unique
            // index never sees two defaults for the user in the same statement batch.
            clearCurrentDefault(target.getUserId());
            target.markDefault();
        }
        audit.record(AuditEvent.of(AuditActions.BRANCH_SET_DEFAULT, "user_branch",
                        target.getId(), target.getUid())
                .detail(Map.of("userUid", userUidOf(target), "branchUid", target.getBranch().getUid())));
        return UserBranchDto.from(target, userUidOf(target));
    }

    @Override
    public void remove(String userBranchUid) {
        UserBranch target = requireByUid(userBranchUid);
        scopeGuard.assertCanActIn(RequestContext.get(), target.getBranch().getCompany().getId());

        Long userId = target.getUserId();
        boolean wasDefault = target.isDefault();
        // Capture identity BEFORE delete — the row (and its lazy branch) are gone afterward.
        Long removedId = target.getId();
        String removedUid = target.getUid();
        String removedBranchUid = target.getBranch().getUid();
        String userUid = userUidOf(target);

        userBranches.delete(target);

        String fallbackBranchUid = null;
        if (wasDefault) {
            // Auto-fallback: promote the earliest-assigned remaining branch (D-D). If none remain,
            // the user is left with no default → no active branch (read-only state, FR-IAM-19).
            userBranches.flush();
            UserBranch fallback = userBranches
                    .findFirstByUserIdAndIdNotOrderByAssignedAtAscIdAsc(userId, removedId)
                    .orElse(null);
            if (fallback != null) {
                fallback.markDefault();
                fallbackBranchUid = fallback.getBranch().getUid();
            }
        }

        Map<String, Object> detail = new HashMap<>();
        detail.put("userUid", userUid);
        detail.put("branchUid", removedBranchUid);
        detail.put("wasDefault", wasDefault);
        if (fallbackBranchUid != null) {
            detail.put("fallbackBranchUid", fallbackBranchUid);
        }
        audit.record(AuditEvent.of(AuditActions.BRANCH_UNASSIGN, "user_branch", removedId, removedUid)
                .detail(detail));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserBranchDto> listForUser(String userUid) {
        AppUser user = Lookups.orNotFound(users.findByUid(userUid), "User", userUid);

        // Tenant-isolation fix (security audit 2026-06-25): root sees all assignments; non-root
        // callers see only assignments whose branch belongs to their active company. Fail-closed
        // on a missing/null company — a non-root caller with no active company sees nothing.
        RequestContext.Principal principal = RequestContext.get();
        List<UserBranch> assignments;
        if (principal != null && !principal.root()) {
            Long activeCompany = principal.companyId();
            if (activeCompany == null) {
                return List.of();
            }
            assignments = userBranches.findByUserIdAndBranchCompanyIdOrderByAssignedAtAscIdAsc(
                    user.getId(), activeCompany);
        } else {
            assignments = userBranches.findByUserIdOrderByAssignedAtAscIdAsc(user.getId());
        }

        return assignments.stream()
                .map(ub -> UserBranchDto.from(ub, user.getUid()))
                .toList();
    }

    /** Clear the user's current default (if any) and flush so the partial unique index stays satisfied. */
    private void clearCurrentDefault(Long userId) {
        userBranches.findByUserIdAndIsDefaultTrue(userId).ifPresent(current -> {
            current.clearDefault();
            userBranches.saveAndFlush(current);
        });
    }

    private UserBranch requireByUid(String uid) {
        return Lookups.orNotFound(userBranches.findByUid(uid), "Branch assignment", uid);
    }

    private String userUidOf(UserBranch ub) {
        return users.findById(ub.getUserId()).map(AppUser::getUid).orElse(null);
    }

    private Long actorId() {
        RequestContext.Principal principal = RequestContext.get();
        return principal != null ? principal.userId() : null;
    }
}

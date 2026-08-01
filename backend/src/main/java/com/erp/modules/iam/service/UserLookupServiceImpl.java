package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link UserLookupService} — the narrow IAM surface exposed for cross-module
 * user-validity checks (ADR-0006 D-5). Delegates to {@link AppUserRepository} and
 * {@link UserBranchRepository} inside the IAM module, so repositories never cross the module
 * boundary.
 */
@Service
@Transactional(readOnly = true)
public class UserLookupServiceImpl implements UserLookupService {

    private final AppUserRepository users;
    private final UserBranchRepository userBranches;

    public UserLookupServiceImpl(AppUserRepository users, UserBranchRepository userBranches) {
        this.users = users;
        this.userBranches = userBranches;
    }

    @Override
    public boolean isActiveUser(Long userId) {
        if (userId == null) {
            return false;
        }
        return users.existsByIdAndStatus(userId, MasterStatus.ACTIVE);
    }

    @Override
    public boolean isActiveUserByUid(String userUid) {
        if (userUid == null || userUid.isBlank()) {
            return false;
        }
        return users.findByUid(userUid)
                .map(u -> u.getStatus() == MasterStatus.ACTIVE)
                .orElse(false);
    }

    @Override
    public boolean isActiveUserInCompany(Long userId, Long companyId) {
        if (userId == null || companyId == null) {
            return false;
        }
        // Must be ACTIVE and NOT the super-admin (root is a system user, never a sales agent —
        // BR-PARTY-10); only then test company membership. Cheap indexed PK+status+root check first.
        return users.existsByIdAndStatusAndRootFalse(userId, MasterStatus.ACTIVE)
                && userBranches.existsByUserIdAndBranchCompanyId(userId, companyId);
    }

    @Override
    public Map<Long, String> displayNamesByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // One IN-list read for the whole caller batch — never a query per id (see the interface).
        var ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream()
                .filter(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank())
                .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
    }
}

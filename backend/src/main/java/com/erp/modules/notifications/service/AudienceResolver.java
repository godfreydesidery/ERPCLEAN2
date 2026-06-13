package com.erp.modules.notifications.service;

import com.erp.modules.iam.repository.UserRoleRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the recipient user IDs for a notification trigger (ADR-0024 D-9).
 *
 * <p>Runs as SYSTEM (no {@code RequestContext} / no JWT). Uses the new additive IAM repository
 * reads {@code findUserIdsWithPermission} / {@code findUserIdsWithPermissionInCompany} that
 * return the inverse of {@code PermissionResolver} (users holding P in company C).
 */
@Component
public class AudienceResolver {

    private final UserRoleRepository userRoles;

    public AudienceResolver(UserRoleRepository userRoles) {
        this.userRoles = userRoles;
    }

    /**
     * Returns the set of {@code app_users.id} values that hold {@code permissionCode}
     * in the given company, optionally narrowed to {@code branchId} when
     * {@code branchScoped = true} (D-9).
     */
    @Transactional(readOnly = true)
    public List<Long> recipients(Long companyId, Long branchId,
                                  String permissionCode, boolean branchScoped) {
        if (branchScoped && branchId != null) {
            return userRoles.findUserIdsWithPermission(companyId, branchId, permissionCode);
        }
        return userRoles.findUserIdsWithPermissionInCompany(companyId, permissionCode);
    }
}

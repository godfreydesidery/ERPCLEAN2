package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.UserRoleDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.domain.entity.UserRole;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserRoleServiceImpl implements UserRoleService {

    private final UserRoleRepository userRoles;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final CompanyRepository companies;
    private final BranchRepository branches;
    private final ScopeGuard scopeGuard;
    private final PermissionResolver permissionResolver;

    public UserRoleServiceImpl(UserRoleRepository userRoles,
                               AppUserRepository users,
                               RoleRepository roles,
                               CompanyRepository companies,
                               BranchRepository branches,
                               ScopeGuard scopeGuard,
                               PermissionResolver permissionResolver) {
        this.userRoles = userRoles;
        this.users = users;
        this.roles = roles;
        this.companies = companies;
        this.branches = branches;
        this.scopeGuard = scopeGuard;
        this.permissionResolver = permissionResolver;
    }

    @Override
    public UserRoleDto grant(GrantRoleRequest request) {
        AppUser user = Lookups.orNotFound(users.findByUid(request.userUid()), "AppUser", request.userUid());
        Role role = Lookups.orNotFound(roles.findByUid(request.roleUid()), "Role", request.roleUid());
        Company company = Lookups.orNotFound(companies.findByUid(request.companyUid()), "Company", request.companyUid());

        // ADR-0002 D-3: scope the grant to the caller's active company.
        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());

        Branch branch = null;
        if (request.branchUid() != null && !request.branchUid().isBlank()) {
            branch = Lookups.orNotFound(branches.findByUid(request.branchUid()), "Branch", request.branchUid());
            // BR-5: branch must belong to the specified company.
            if (!branch.getCompany().getId().equals(company.getId())) {
                throw new ConflictException(
                        "Branch " + request.branchUid() + " does not belong to company " + request.companyUid());
            }
        }

        Long branchId = branch != null ? branch.getId() : null;

        if (userRoles.existsActiveGrant(user.getId(), role.getId(), company.getId(), branchId)) {
            throw new ConflictException("Active grant already exists for this user/role/company/branch combination.");
        }

        Long grantedBy = RequestContext.get().userId();
        UserRole ur = new UserRole(user.getId(), role, company.getId(), branchId, grantedBy);
        userRoles.save(ur);
        permissionResolver.invalidate();

        return toDto(ur, user.getUid(), role.getCode(), company.getUid(),
                branch != null ? branch.getUid() : null);
    }

    @Override
    public void revoke(String userRoleUid) {
        UserRole ur = Lookups.orNotFound(userRoles.findByUid(userRoleUid), "UserRole", userRoleUid);
        scopeGuard.assertCanActIn(RequestContext.get(), ur.getCompanyId());
        if (!ur.isActive()) {
            throw new ConflictException("Assignment already revoked: " + userRoleUid);
        }
        ur.revoke(Instant.now());
        permissionResolver.invalidate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRoleDto> listForUser(String userUid) {
        AppUser user = Lookups.orNotFound(users.findByUid(userUid), "AppUser", userUid);
        List<UserRole> assignments = userRoles.findByUserIdAndRevokedAtIsNull(user.getId());
        return assignments.stream()
                .map(ur -> buildDtoForAssignment(ur, user.getUid()))
                .toList();
    }

    // --- private helpers ---

    /**
     * Build a UserRoleDto for an assignment. Company and branch are resolved here where the
     * repositories are accessible — avoids a static from() that can't reach them.
     */
    private UserRoleDto buildDtoForAssignment(UserRole ur, String userUid) {
        String companyUid = companies.findById(ur.getCompanyId())
                .map(c -> c.getUid())
                .orElse(null);
        String branchUid = ur.getBranchId() != null
                ? branches.findById(ur.getBranchId()).map(b -> b.getUid()).orElse(null)
                : null;
        return toDto(ur, userUid, ur.getRole().getCode(), companyUid, branchUid);
    }

    private static UserRoleDto toDto(UserRole ur, String userUid, String roleCode,
                                     String companyUid, String branchUid) {
        return new UserRoleDto(
                ur.getId(),
                ur.getUid(),
                userUid,
                roleCode,
                companyUid,
                branchUid,
                ur.getGrantedAt().toString());
    }
}

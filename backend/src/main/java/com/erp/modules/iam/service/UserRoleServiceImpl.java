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
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final AuditService audit;
    private final UserCompanyService userCompanyService;

    public UserRoleServiceImpl(UserRoleRepository userRoles,
                               AppUserRepository users,
                               RoleRepository roles,
                               CompanyRepository companies,
                               BranchRepository branches,
                               ScopeGuard scopeGuard,
                               PermissionResolver permissionResolver,
                               AuditService audit,
                               UserCompanyService userCompanyService) {
        this.userRoles = userRoles;
        this.users = users;
        this.roles = roles;
        this.companies = companies;
        this.branches = branches;
        this.scopeGuard = scopeGuard;
        this.permissionResolver = permissionResolver;
        this.audit = audit;
        this.userCompanyService = userCompanyService;
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

        // Auto-create company membership (V77 additive oracle): ensure a user_company row exists
        // for the company this role grant targets. Idempotent + exception-free — must not roll back
        // the grant if the membership insert fails.
        userCompanyService.ensureMembership(user.getId(), company.getId(), grantedBy);

        permissionResolver.invalidate();

        String branchUid = branch != null ? branch.getUid() : null;

        Map<String, Object> detail = new HashMap<>();
        detail.put("userUid", user.getUid());
        detail.put("roleCode", role.getCode());
        detail.put("companyUid", company.getUid());
        if (branchUid != null) {
            detail.put("branchUid", branchUid);
        }
        audit.record(AuditEvent.of(AuditActions.ROLE_GRANT, "user_role", ur.getId(), ur.getUid())
                .detail(detail));

        return toDto(ur, user.getUid(), role.getCode(), company.getUid(), branchUid);
    }

    @Override
    public void revoke(String userRoleUid) {
        UserRole ur = Lookups.orNotFound(userRoles.findByUid(userRoleUid), "UserRole", userRoleUid);
        scopeGuard.assertCanActIn(RequestContext.get(), ur.getCompanyId());
        if (!ur.isActive()) {
            throw new ConflictException("Assignment already revoked: " + userRoleUid);
        }

        // Capture context before revoke mutates the row.
        AppUser user = users.findById(ur.getUserId()).orElse(null);
        String userUid = user != null ? user.getUid() : null;
        String roleCode = ur.getRole().getCode();

        ur.revoke(Instant.now());
        permissionResolver.invalidate();

        audit.record(AuditEvent.of(AuditActions.ROLE_REVOKE, "user_role", ur.getId(), ur.getUid())
                .detail(Map.of("userUid", userUid != null ? userUid : "",
                               "roleCode", roleCode)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRoleDto> listForUser(String userUid) {
        AppUser user = Lookups.orNotFound(users.findByUid(userUid), "AppUser", userUid);
        List<UserRole> assignments = userRoles.findByUserIdAndRevokedAtIsNull(user.getId());

        // Tenant-isolation fix (security audit 2026-06-25): root sees all grants; non-root callers
        // see only grants scoped to their active company. Fail-closed on a missing/null company.
        RequestContext.Principal principal = RequestContext.get();
        if (principal != null && !principal.root()) {
            Long activeCompany = principal.companyId();
            if (activeCompany == null) {
                return List.of();
            }
            assignments = assignments.stream()
                    .filter(ur -> activeCompany.equals(ur.getCompanyId()))
                    .toList();
        }

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

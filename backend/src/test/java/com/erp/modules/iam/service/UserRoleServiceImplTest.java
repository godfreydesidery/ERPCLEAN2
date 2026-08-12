package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.UserRoleDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.domain.entity.UserRole;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.AuthorityCeiling;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the tenant-isolation fix in
 * {@link UserRoleServiceImpl#listForUser(String)}.
 *
 * <p>Security audit 2026-06-25: a non-root ROLE.VIEW holder could see all of a target user's role
 * grants across every company they are provisioned into. The fix scopes the returned list to the
 * caller's active company for non-root principals; root still receives the full cross-company view.
 *
 * <p>Three assertions:
 * <ol>
 *   <li>Non-root admin active in company A sees only grants in company A (not company B).
 *   <li>Root principal sees grants in both A and B.
 *   <li>Non-root principal with null active company receives an empty list (fail-closed).
 * </ol>
 */
class UserRoleServiceImplTest {

    private static final Long USER_ID   = 55L;
    private static final String USER_UID = "user-uid-055";

    private static final Long COMPANY_A = 10L;
    private static final Long COMPANY_B = 20L;

    private static final String ROLE_UID    = "role-uid-001";
    private static final Long   ROLE_ID     = 5L;
    private static final String COMPANY_UID = "co-uid-a";

    private UserRoleRepository  userRoleRepo;
    private AppUserRepository   userRepo;
    private CompanyRepository   companyRepo;
    private BranchRepository    branchRepo;
    private RoleRepository      roleRepo;
    private UserCompanyService  userCompanyService;
    private PermissionResolver  permissionResolver;

    private UserRoleServiceImpl service;

    /** The company-A grant, kept for the revoke tests. */
    private UserRole grantA;

    @BeforeEach
    void setUp() {
        userRoleRepo       = mock(UserRoleRepository.class);
        userRepo           = mock(AppUserRepository.class);
        companyRepo        = mock(CompanyRepository.class);
        branchRepo         = mock(BranchRepository.class);
        roleRepo           = mock(RoleRepository.class);
        userCompanyService = mock(UserCompanyService.class);
        permissionResolver = mock(PermissionResolver.class);

        service = new UserRoleServiceImpl(
                userRoleRepo,
                userRepo,
                roleRepo,
                companyRepo,
                branchRepo,
                mock(ScopeGuard.class),
                mock(AuthorityCeiling.class),
                permissionResolver,
                mock(AuditService.class),
                userCompanyService);

        // Target user stub
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getUid()).thenReturn(USER_UID);
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));

        // Two active grants for the target user: one in company A, one in company B
        grantA = stubUserRole(COMPANY_A, "ur-uid-a");
        UserRole grantB = stubUserRole(COMPANY_B, "ur-uid-b");
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(USER_ID))
                .thenReturn(List.of(grantA, grantB));

        // companyRepo lookups used by buildDtoForAssignment — return empty to avoid NPE;
        // the companyUid in the DTO will be null which is fine for these isolation tests.
        when(companyRepo.findById(COMPANY_A)).thenReturn(Optional.empty());
        when(companyRepo.findById(COMPANY_B)).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UserRole stubUserRole(Long companyId, String uid) {
        Role role = mock(Role.class);
        when(role.getCode()).thenReturn("ROLE-" + companyId);

        UserRole ur = mock(UserRole.class);
        when(ur.getId()).thenReturn(companyId * 100);
        when(ur.getUid()).thenReturn(uid);
        when(ur.getUserId()).thenReturn(USER_ID);
        when(ur.getCompanyId()).thenReturn(companyId);
        when(ur.getBranchId()).thenReturn(null);
        when(ur.getRole()).thenReturn(role);
        when(ur.getGrantedAt()).thenReturn(Instant.EPOCH);
        when(ur.isActive()).thenReturn(true);
        return ur;
    }

    /** Stubs everything {@link UserRoleServiceImpl#grant} needs for a plain company-wide grant. */
    private void stubGrantPath() {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(ROLE_ID);
        when(role.getCode()).thenReturn("PROCUREMENT_OFFICER");
        when(role.isSystem()).thenReturn(false);
        when(role.getPermissions()).thenReturn(Set.of());
        when(roleRepo.findWithPermissionsByUid(ROLE_UID)).thenReturn(Optional.of(role));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_A);
        when(company.getUid()).thenReturn(COMPANY_UID);
        when(companyRepo.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));

        when(userCompanyService.isActiveMember(USER_ID, COMPANY_A)).thenReturn(true);
        when(userRoleRepo.existsActiveGrant(USER_ID, ROLE_ID, COMPANY_A, null)).thenReturn(false);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * (1) Non-root admin active in company A must NOT see the grant in company B.
     */
    @Test
    void nonRoot_activeInCompanyA_seesOnlyCompanyAGrants() {
        RequestContext.set(new RequestContext.Principal(99L, "admin@test.com", false, COMPANY_A, null, null));

        List<UserRoleDto> result = service.listForUser(USER_UID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("ur-uid-a");
    }

    /**
     * (2) Root principal sees grants in both company A and company B.
     */
    @Test
    void root_seesAllGrants() {
        RequestContext.set(new RequestContext.Principal(1L, "root@test.com", true, null, null, null));

        List<UserRoleDto> result = service.listForUser(USER_UID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(UserRoleDto::uid)
                .containsExactlyInAnyOrder("ur-uid-a", "ur-uid-b");
    }

    /**
     * (3) Non-root principal with null active company receives an empty list (fail-closed).
     */
    @Test
    void nonRoot_nullActiveCompany_returnsEmpty() {
        RequestContext.set(new RequestContext.Principal(99L, "admin@test.com", false, null, null, null));

        List<UserRoleDto> result = service.listForUser(USER_UID);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Permission-cache invalidation (UAT wave 1)
    //
    // A procurement officer was granted SUPPLIER.MANAGE and still got 403 for about two minutes.
    // The grant must evict the cached permission set of the user it affects — targeted, so one
    // grant does not send every other signed-in user back to the database.
    // -----------------------------------------------------------------------

    @Test
    void grant_invalidatesThePermissionCacheOfTheUserWhoWasGranted() {
        RequestContext.set(new RequestContext.Principal(99L, "admin@test.com", false, COMPANY_A, null, null));
        stubGrantPath();

        service.grant(new GrantRoleRequest(USER_UID, ROLE_UID, COMPANY_UID, null));

        verify(permissionResolver).invalidateUser(USER_ID);
        verify(permissionResolver, never()).invalidate();
    }

    @Test
    void revoke_invalidatesThePermissionCacheOfTheUserWhoLostTheRole() {
        RequestContext.set(new RequestContext.Principal(99L, "admin@test.com", false, COMPANY_A, null, null));
        when(userRoleRepo.findByUid("ur-uid-a")).thenReturn(Optional.of(grantA));

        service.revoke("ur-uid-a");

        verify(permissionResolver).invalidateUser(USER_ID);
        verify(permissionResolver, never()).invalidate();
    }
}

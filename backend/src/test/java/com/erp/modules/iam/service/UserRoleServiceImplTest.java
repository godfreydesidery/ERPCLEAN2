package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.UserRoleDto;
import com.erp.modules.iam.domain.entity.AppUser;
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

    private UserRoleRepository  userRoleRepo;
    private AppUserRepository   userRepo;
    private CompanyRepository   companyRepo;
    private BranchRepository    branchRepo;

    private UserRoleServiceImpl service;

    @BeforeEach
    void setUp() {
        userRoleRepo = mock(UserRoleRepository.class);
        userRepo     = mock(AppUserRepository.class);
        companyRepo  = mock(CompanyRepository.class);
        branchRepo   = mock(BranchRepository.class);

        service = new UserRoleServiceImpl(
                userRoleRepo,
                userRepo,
                mock(RoleRepository.class),
                companyRepo,
                branchRepo,
                mock(ScopeGuard.class),
                mock(AuthorityCeiling.class),
                mock(PermissionResolver.class),
                mock(AuditService.class),
                mock(UserCompanyService.class));

        // Target user stub
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getUid()).thenReturn(USER_UID);
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));

        // Two active grants for the target user: one in company A, one in company B
        UserRole grantA = stubUserRole(COMPANY_A, "ur-uid-a");
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
        when(ur.getCompanyId()).thenReturn(companyId);
        when(ur.getBranchId()).thenReturn(null);
        when(ur.getRole()).thenReturn(role);
        when(ur.getGrantedAt()).thenReturn(Instant.EPOCH);
        return ur;
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
}

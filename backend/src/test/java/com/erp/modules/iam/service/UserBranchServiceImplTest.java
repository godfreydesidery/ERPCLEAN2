package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.UserBranchDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.audit.AuditService;
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
 * {@link UserBranchServiceImpl#listForUser(String)}.
 *
 * <p>Security audit 2026-06-25: a non-root caller could list a target user's branch assignments
 * across ALL companies, revealing cross-company provisioning. The fix queries only assignments
 * whose branch belongs to the caller's active company; root still sees all assignments.
 *
 * <p>Three assertions:
 * <ol>
 *   <li>Non-root admin active in company A sees only branch-A assignment (not branch-B in company B).
 *   <li>Root principal sees all assignments (branch A and branch B).
 *   <li>Non-root principal with null active company receives an empty list (fail-closed).
 * </ol>
 */
class UserBranchServiceImplTest {

    private static final Long USER_ID    = 77L;
    private static final String USER_UID  = "user-uid-077";

    private static final Long COMPANY_A_ID = 10L;
    private static final Long COMPANY_B_ID = 20L;

    private UserBranchRepository userBranchRepo;
    private AppUserRepository    userRepo;

    private UserBranchServiceImpl service;

    // Stubs shared across tests
    private UserBranch ubA; // assignment in company A
    private UserBranch ubB; // assignment in company B

    @BeforeEach
    void setUp() {
        userBranchRepo = mock(UserBranchRepository.class);
        userRepo       = mock(AppUserRepository.class);

        service = new UserBranchServiceImpl(
                userBranchRepo,
                userRepo,
                mock(BranchRepository.class),
                mock(ScopeGuard.class),
                mock(AuditService.class),
                mock(UserCompanyService.class));

        // Target user stub
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getUid()).thenReturn(USER_UID);
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));

        // Branch + Company stubs for assignment A (company A)
        Company companyA = mock(Company.class);
        when(companyA.getId()).thenReturn(COMPANY_A_ID);
        when(companyA.getUid()).thenReturn("co-uid-a");

        Branch branchA = mock(Branch.class);
        when(branchA.getId()).thenReturn(100L);
        when(branchA.getUid()).thenReturn("br-uid-a");
        when(branchA.getCode()).thenReturn("A001");
        when(branchA.getName()).thenReturn("Branch A");
        when(branchA.getCompany()).thenReturn(companyA);

        ubA = mock(UserBranch.class);
        when(ubA.getId()).thenReturn(1001L);
        when(ubA.getUid()).thenReturn("ub-uid-a");
        when(ubA.getBranch()).thenReturn(branchA);
        when(ubA.isDefault()).thenReturn(true);
        when(ubA.getAssignedAt()).thenReturn(Instant.EPOCH);

        // Branch + Company stubs for assignment B (company B)
        Company companyB = mock(Company.class);
        when(companyB.getId()).thenReturn(COMPANY_B_ID);
        when(companyB.getUid()).thenReturn("co-uid-b");

        Branch branchB = mock(Branch.class);
        when(branchB.getId()).thenReturn(200L);
        when(branchB.getUid()).thenReturn("br-uid-b");
        when(branchB.getCode()).thenReturn("B001");
        when(branchB.getName()).thenReturn("Branch B");
        when(branchB.getCompany()).thenReturn(companyB);

        ubB = mock(UserBranch.class);
        when(ubB.getId()).thenReturn(2002L);
        when(ubB.getUid()).thenReturn("ub-uid-b");
        when(ubB.getBranch()).thenReturn(branchB);
        when(ubB.isDefault()).thenReturn(false);
        when(ubB.getAssignedAt()).thenReturn(Instant.EPOCH.plusSeconds(60));

        // Root path: all assignments
        when(userBranchRepo.findByUserIdOrderByAssignedAtAscIdAsc(USER_ID))
                .thenReturn(List.of(ubA, ubB));

        // Scoped path: company A only
        when(userBranchRepo.findByUserIdAndBranchCompanyIdOrderByAssignedAtAscIdAsc(
                USER_ID, COMPANY_A_ID))
                .thenReturn(List.of(ubA));

        // Scoped path: company B only (for completeness)
        when(userBranchRepo.findByUserIdAndBranchCompanyIdOrderByAssignedAtAscIdAsc(
                USER_ID, COMPANY_B_ID))
                .thenReturn(List.of(ubB));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * (1) Non-root admin active in company A sees only the assignment in company A.
     */
    @Test
    void nonRoot_activeInCompanyA_seesOnlyCompanyAAssignment() {
        RequestContext.set(new RequestContext.Principal(99L, "admin@test.com", false, COMPANY_A_ID, null, null));

        List<UserBranchDto> result = service.listForUser(USER_UID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("ub-uid-a");
        assertThat(result.get(0).branchUid()).isEqualTo("br-uid-a");
    }

    /**
     * (2) Root principal sees assignments in both company A and company B.
     */
    @Test
    void root_seesAllAssignments() {
        RequestContext.set(new RequestContext.Principal(1L, "root@test.com", true, null, null, null));

        List<UserBranchDto> result = service.listForUser(USER_UID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(UserBranchDto::uid)
                .containsExactlyInAnyOrder("ub-uid-a", "ub-uid-b");
    }

    /**
     * (3) Non-root principal with null active company receives an empty list (fail-closed).
     */
    @Test
    void nonRoot_nullActiveCompany_returnsEmpty() {
        RequestContext.set(new RequestContext.Principal(99L, "admin@test.com", false, null, null, null));

        List<UserBranchDto> result = service.listForUser(USER_UID);

        assertThat(result).isEmpty();
    }
}

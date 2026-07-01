package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.CreateUserRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
import com.erp.modules.iam.domain.dto.UpdateUserRequest;
import com.erp.modules.iam.domain.dto.UserDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.repository.UserCompanyRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression tests for the tenant-isolation fix on {@link UserServiceImpl}'s mutating endpoints
 * (security audit 2026-06-26, CVE: cross-tenant account takeover via USER.MANAGE).
 *
 * <p><b>What is being tested:</b> Before the fix, a non-root principal holding {@code USER.MANAGE}
 * in Company A could call {@code updateByUid}, {@code disableByUid}, {@code enableByUid},
 * {@code unlockByUid}, and {@code setPasswordByUid} against any user uid in the database,
 * including users who belong exclusively to Company B. The mutators used a bare
 * {@code requireByUid} with no company-membership check.
 *
 * <p><b>Fix:</b> All five mutators now call {@code requireInScope(uid)}, which mirrors the
 * {@code getByUid} check: non-root principals receive a {@code 404} (not {@code 403}) when the
 * target user is a root user or does not belong to the principal's active company
 * ({@code AppUserRepository.existsUserInCompany}).
 *
 * <p><b>Test topology:</b>
 * <ul>
 *   <li>One organisation, two companies (A and B), one branch each.
 *   <li>{@code aAdmin} — a non-root user whose {@code RequestContext} places them in Company A.
 *       Company-A membership is established by assigning them to Branch A1 (the
 *       {@code existsUserInCompany} oracle checks branch→company linkage).
 *   <li>{@code bVictim} — a non-root user assigned <em>only</em> to Branch B1 (Company B).
 *       A-Admin must never be able to mutate bVictim.
 *   <li>{@code abUser} — a non-root user assigned to both Branch A1 and Branch B1.
 *       A-Admin must be able to mutate abUser (multi-company membership control test).
 * </ul>
 *
 * <p><b>Manual repro (if the IT bootstrap is impractical):</b>
 * <ol>
 *   <li>Create two companies (A, B) with distinct users (A_ADMIN holds USER.MANAGE, B_VIEW
 *       belongs only to company B).
 *   <li>Log in as A_ADMIN and call {@code PUT /api/v1/users/uid/{B_VIEW_UID}/password} with a
 *       body {@code {"password":"NewHax9Pass!"}}.
 *   <li>Before the fix: HTTP 204 and the password is changed. After the fix: HTTP 404.
 * </ol>
 */
class UserMutatorTenantIsolationIT extends PostgresIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private AppUserRepository users;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private UserCompanyRepository userCompanies;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private static final String VALID_PASSWORD = "ValidPass1";

    private AppUser aAdmin;   // principal: Company A only
    private AppUser bVictim;  // belongs to Company B only — must be unreachable by aAdmin
    private AppUser abUser;   // belongs to both A and B — must be reachable by aAdmin
    private Company companyA;
    private Company companyB;
    private Branch branchA;
    private Branch branchB;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Isolation Corp"));
        companyA = companies.save(new Company(org, "CA", "Company A"));
        companyB = companies.save(new Company(org, "CB", "Company B"));
        branchA  = branches.save(new Branch(companyA, "A1", "Branch A1"));
        branchB  = branches.save(new Branch(companyB, "B1", "Branch B1"));

        // aAdmin: assigned to Branch A1 → Company A membership
        aAdmin = users.save(new AppUser("a_admin", passwordEncoder.encode(VALID_PASSWORD), "A Admin"));
        UserBranch ubA = new UserBranch(aAdmin.getId(), branchA, aAdmin.getId());
        ubA.markDefault();
        userBranches.save(ubA);

        // bVictim: assigned ONLY to Branch B1 → Company B membership only
        bVictim = users.save(new AppUser("b_victim", passwordEncoder.encode(VALID_PASSWORD), "B Victim"));
        UserBranch ubBv = new UserBranch(bVictim.getId(), branchB, bVictim.getId());
        ubBv.markDefault();
        userBranches.save(ubBv);

        // abUser: assigned to both branches → member of both companies
        abUser = users.save(new AppUser("ab_user", passwordEncoder.encode(VALID_PASSWORD), "AB User"));
        UserBranch ubAb1 = new UserBranch(abUser.getId(), branchA, aAdmin.getId());
        ubAb1.markDefault();
        userBranches.save(ubAb1);
        userBranches.save(new UserBranch(abUser.getId(), branchB, aAdmin.getId()));

        // Default context: aAdmin active in Company A (non-root)
        RequestContext.set(new RequestContext.Principal(
                aAdmin.getId(), "a_admin", false, companyA.getId(), branchA.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------
    // updateByUid — cross-tenant write must be rejected
    // ---------------------------------------------------------------

    @Test
    void updateByUid_crossTenant_throwsNotFound() {
        assertThatThrownBy(() ->
                userService.updateByUid(bVictim.getUid(),
                        new UpdateUserRequest("PWNED", "pwned@evil.test", null)))
                .isInstanceOf(NotFoundException.class);

        // Verify no data was tampered
        AppUser reloaded = users.findByUid(bVictim.getUid()).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("B Victim");
        assertThat(reloaded.getEmail()).isNull();
    }

    @Test
    void updateByUid_sameCompany_succeeds() {
        userService.updateByUid(abUser.getUid(),
                new UpdateUserRequest("AB Updated", "ab@test.com", null));

        AppUser reloaded = users.findByUid(abUser.getUid()).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("AB Updated");
    }

    // ---------------------------------------------------------------
    // disableByUid — cross-tenant DoS must be rejected
    // ---------------------------------------------------------------

    @Test
    void disableByUid_crossTenant_throwsNotFound() {
        assertThatThrownBy(() -> userService.disableByUid(bVictim.getUid()))
                .isInstanceOf(NotFoundException.class);

        // bVictim must still be ACTIVE
        AppUser reloaded = users.findByUid(bVictim.getUid()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MasterStatus.ACTIVE);
    }

    @Test
    void disableByUid_sameCompany_succeeds() {
        userService.disableByUid(abUser.getUid());

        AppUser reloaded = users.findByUid(abUser.getUid()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MasterStatus.INACTIVE);
    }

    // ---------------------------------------------------------------
    // enableByUid — cross-tenant lifecycle control must be rejected
    // ---------------------------------------------------------------

    @Test
    void enableByUid_crossTenant_throwsNotFound() {
        // First disable bVictim as B's own admin (root context) so the enable is meaningful
        RequestContext.set(new RequestContext.Principal(
                bVictim.getId(), "b_victim", false, companyB.getId(), branchB.getId(), null));
        userService.disableByUid(bVictim.getUid());

        // Switch back to aAdmin (Company A) and try to enable
        RequestContext.set(new RequestContext.Principal(
                aAdmin.getId(), "a_admin", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> userService.enableByUid(bVictim.getUid()))
                .isInstanceOf(NotFoundException.class);

        // bVictim must remain INACTIVE — the enable was not applied
        AppUser reloaded = users.findByUid(bVictim.getUid()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MasterStatus.INACTIVE);
    }

    @Test
    void enableByUid_sameCompany_succeeds() {
        userService.disableByUid(abUser.getUid());
        userService.enableByUid(abUser.getUid());

        AppUser reloaded = users.findByUid(abUser.getUid()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MasterStatus.ACTIVE);
    }

    // ---------------------------------------------------------------
    // unlockByUid — cross-tenant lockout-clearing must be rejected
    // ---------------------------------------------------------------

    @Test
    void unlockByUid_crossTenant_throwsNotFound() {
        // Lock bVictim by simulating failed logins
        AppUser victim = users.findByUid(bVictim.getUid()).orElseThrow();
        for (int i = 0; i < 5; i++) {
            victim.registerFailedLogin(5, 30, Instant.now());
        }
        users.saveAndFlush(victim);
        assertThat(victim.isLocked(Instant.now())).isTrue();

        // aAdmin (Company A) must not be able to unlock a Company B user
        assertThatThrownBy(() -> userService.unlockByUid(bVictim.getUid()))
                .isInstanceOf(NotFoundException.class);

        // Verify still locked
        AppUser reloaded = users.findByUid(bVictim.getUid()).orElseThrow();
        assertThat(reloaded.isLocked(Instant.now())).isTrue();
    }

    @Test
    void unlockByUid_sameCompany_succeeds() {
        AppUser ab = users.findByUid(abUser.getUid()).orElseThrow();
        for (int i = 0; i < 5; i++) {
            ab.registerFailedLogin(5, 30, Instant.now());
        }
        users.saveAndFlush(ab);

        userService.unlockByUid(abUser.getUid());

        AppUser reloaded = users.findByUid(abUser.getUid()).orElseThrow();
        assertThat(reloaded.isLocked(Instant.now())).isFalse();
    }

    // ---------------------------------------------------------------
    // setPasswordByUid — cross-tenant account takeover must be rejected
    // ---------------------------------------------------------------

    @Test
    void setPasswordByUid_crossTenant_throwsNotFound() {
        assertThatThrownBy(() ->
                userService.setPasswordByUid(bVictim.getUid(), new SetPasswordRequest("NewHax9Pass!")))
                .isInstanceOf(NotFoundException.class);

        // Verify original password hash is unchanged (old password still matches)
        AppUser reloaded = users.findByUid(bVictim.getUid()).orElseThrow();
        assertThat(passwordEncoder.matches(VALID_PASSWORD, reloaded.getPasswordHash()))
                .as("original password must still match after rejected cross-tenant attempt")
                .isTrue();
    }

    @Test
    void setPasswordByUid_sameCompany_succeeds() {
        userService.setPasswordByUid(abUser.getUid(), new SetPasswordRequest("NewSecure9"));

        AppUser reloaded = users.findByUid(abUser.getUid()).orElseThrow();
        assertThat(passwordEncoder.matches("NewSecure9", reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(VALID_PASSWORD, reloaded.getPasswordHash())).isFalse();
    }

    // ---------------------------------------------------------------
    // root principal bypasses the scope check (existsUserInCompany not called)
    // ---------------------------------------------------------------

    @Test
    void setPasswordByUid_rootPrincipal_succeedsOnAnyCompanyUser() {
        RequestContext.set(new RequestContext.Principal(
                aAdmin.getId(), "root", true, null, null, null));

        userService.setPasswordByUid(bVictim.getUid(), new SetPasswordRequest("RootCanDo9!"));

        AppUser reloaded = users.findByUid(bVictim.getUid()).orElseThrow();
        assertThat(passwordEncoder.matches("RootCanDo9!", reloaded.getPasswordHash())).isTrue();
    }

    @Test
    void updateByUid_rootPrincipal_succeedsOnAnyCompanyUser() {
        RequestContext.set(new RequestContext.Principal(
                aAdmin.getId(), "root", true, null, null, null));

        userService.updateByUid(bVictim.getUid(),
                new UpdateUserRequest("Root Updated", null, null));

        AppUser reloaded = users.findByUid(bVictim.getUid()).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("Root Updated");
    }

    // ---------------------------------------------------------------
    // create — F-bug fix (user-reported): a user created by a non-root admin must be
    // immediately visible to that admin (auto-membership in the creator's active company).
    // Before the fix the new user had no user_company row → invisible in the admin's
    // company-scoped list(), and re-creating tripped "Username already exists".
    // ---------------------------------------------------------------

    @Test
    void create_nonRootAdmin_userIsMemberOfCreatorCompany_andVisibleInList() {
        // aAdmin (Company A, non-root) creates a fresh user.
        UserDto created = userService.create(new CreateUserRequest(
                "brayton_made", "Brayton Made", "ValidPass1", null, null));

        // 1. An active user_company membership exists in the creator's company.
        AppUser newUser = users.findByUid(created.uid()).orElseThrow();
        assertThat(users.existsUserInCompany(newUser.getId(), companyA.getId()))
                .as("new user must be a member of the creator's active company")
                .isTrue();
        assertThat(userCompanies.existsByUserIdAndCompanyIdAndRevokedAtIsNull(
                newUser.getId(), companyA.getId())).isTrue();

        // 2. The new user now appears in aAdmin's company-scoped list (the reported symptom).
        assertThat(userService.list())
                .extracting(UserDto::uid)
                .contains(created.uid());
    }

    @Test
    void create_nonRootAdmin_userNotLeakedIntoAnotherCompany() {
        UserDto created = userService.create(new CreateUserRequest(
                "scoped_user", "Scoped User", "ValidPass1", null, null));

        AppUser newUser = users.findByUid(created.uid()).orElseThrow();
        // Membership is ONLY in Company A, never Company B (tenant-safe).
        assertThat(users.existsUserInCompany(newUser.getId(), companyB.getId())).isFalse();
    }

    @Test
    void create_rootPrincipal_leavesUserUnassigned() {
        RequestContext.set(new RequestContext.Principal(
                aAdmin.getId(), "root", true, null, null, null));

        UserDto created = userService.create(new CreateUserRequest(
                "root_made", "Root Made", "ValidPass1", null, null));

        AppUser newUser = users.findByUid(created.uid()).orElseThrow();
        // Root has no single company → no membership anywhere (unassigned exactly as before the fix).
        assertThat(userCompanies.findByUserIdAndRevokedAtIsNullOrderByAssignedAtAscIdAsc(newUser.getId()))
                .as("a root-created user stays unassigned")
                .isEmpty();
    }
}

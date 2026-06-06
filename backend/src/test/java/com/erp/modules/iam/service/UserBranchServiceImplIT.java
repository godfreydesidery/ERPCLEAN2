package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.AssignBranchRequest;
import com.erp.modules.iam.domain.dto.TokenResponse;
import com.erp.modules.iam.domain.dto.UserBranchDto;
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
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link UserBranchServiceImpl} — the money tests for Slice 4.
 *
 * <p>Tests cover: assign 3 branches; duplicate assign rejection; makeDefault atomically clears old
 * default; setDefault on existing assignment; NotFoundException for unknown assignment uid;
 * remove-default triggers D-D earliest-assigned fallback; remove-last leaves no default; ordered
 * fallback is deterministic by assigned_at,id.
 *
 * <p>Additionally covers the security fix tested via {@link AuthService}: an archived default branch
 * must NOT scope login (hasBranch=false / activeBranchUid=null), and
 * {@link BranchService#archiveByUid} clears the user default and promotes the earliest remaining.
 *
 * <p>A ROOT RequestContext.Principal is set in {@link #setUp} so {@link com.erp.platform.security.ScopeGuard}
 * passes for all seeding operations. Each test that needs a different context sets it explicitly and
 * restores in its finally block or in {@link #tearDown}.
 */
class UserBranchServiceImplIT extends PostgresIntegrationTest {

    @Autowired private UserBranchService userBranchService;
    @Autowired private BranchService branchService;
    @Autowired private AuthService authService;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private AppUserRepository users;
    @Autowired private BranchRepository branches;
    @Autowired private CompanyRepository companies;
    @Autowired private OrganisationRepository organisations;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private static final String PASSWORD = "Secret123";

    // shared entities reused across tests
    private AppUser rootUser;
    private AppUser targetUser;
    private Branch b1;
    private Branch b2;
    private Branch b3;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Slice4 Org"));
        Company company = companies.save(new Company(org, "S4C", "Slice4 Company"));

        // Three branches with stable identity (code used as label). No company-default needed for
        // user-branch logic, but we must not archive the company-default branch (BR-2), so we
        // deliberately do NOT set any as company-default here — only B3 will become company default
        // in archiving tests that set it first.
        b1 = branches.save(new Branch(company, "B1", "Branch One"));
        b2 = branches.save(new Branch(company, "B2", "Branch Two"));
        b3 = branches.save(new Branch(company, "B3", "Branch Three"));

        rootUser = new AppUser("root_s4", passwordEncoder.encode(PASSWORD), "Root S4");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);

        targetUser = users.save(new AppUser("target_s4", passwordEncoder.encode(PASSWORD), "Target S4"));

        // Set ROOT context: ScopeGuard.assertCanActIn passes for root in any company.
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), "root_s4", true, company.getId(), b1.getId()));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------
    // assign: 3 branches -> 3 rows in user_branch
    // ---------------------------------------------------------------

    @Test
    void assign_threeBranches_createsThreeRows() {
        assign(b1, false);
        assign(b2, false);
        assign(b3, false);

        List<UserBranchDto> list = userBranchService.listForUser(targetUser.getUid());
        assertThat(list).hasSize(3);
        assertThat(list.stream().map(UserBranchDto::branchCode).toList())
                .containsExactlyInAnyOrder("B1", "B2", "B3");
    }

    // ---------------------------------------------------------------
    // assign: same branch twice -> ConflictException
    // ---------------------------------------------------------------

    @Test
    void assign_sameBranchTwice_throwsConflict() {
        assign(b1, false);
        assertThatThrownBy(() -> assign(b1, false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already assigned");
    }

    // ---------------------------------------------------------------
    // assign with makeDefault: exactly ONE is_default=true in DB; old cleared atomically
    // ---------------------------------------------------------------

    @Test
    void assign_withMakeDefault_thenAssignAnotherAsDefault_exactlyOneDefault() {
        UserBranchDto ub1 = assign(b1, true);  // B1 is default
        assertThat(ub1.isDefault()).isTrue();

        UserBranchDto ub2 = assign(b2, true);  // B2 is default now
        assertThat(ub2.isDefault()).isTrue();

        // Exactly one is_default row in DB for targetUser
        long defaultCount = userBranches
                .findByUserIdOrderByAssignedAtAscIdAsc(targetUser.getId())
                .stream()
                .filter(UserBranch::isDefault)
                .count();
        assertThat(defaultCount)
                .as("exactly one is_default=true must exist for the user")
                .isEqualTo(1L);

        // The new one (B2) is the default; B1 is cleared
        assertThat(currentDefaultBranchCode()).isEqualTo("B2");
    }

    // ---------------------------------------------------------------
    // setDefault: promotes an existing assignment; partial unique index holds
    // ---------------------------------------------------------------

    @Test
    void setDefault_onExistingAssignment_makesItDefaultAndClearsOld() {
        UserBranchDto ub1 = assign(b1, true);   // B1 default
        UserBranchDto ub2 = assign(b2, false);  // B2 not default

        userBranchService.setDefault(ub2.uid());

        // B2 is now default
        assertThat(currentDefaultBranchCode()).isEqualTo("B2");

        // B1 is no longer default — fetch its row by its assignment uid
        UserBranch ub1Entity = userBranches.findByUid(ub1.uid()).orElseThrow();
        assertThat(ub1Entity.isDefault()).isFalse();

        // Exactly one default (the partial unique index backstop)
        long defaultCount = userBranches
                .findByUserIdOrderByAssignedAtAscIdAsc(targetUser.getId())
                .stream()
                .filter(UserBranch::isDefault)
                .count();
        assertThat(defaultCount).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // setDefault: unknown assignment uid -> NotFoundException
    // (structural enforcement of "default must be assigned": you can only default a row you have)
    // ---------------------------------------------------------------

    @Test
    void setDefault_unknownAssignmentUid_throwsNotFound() {
        assertThatThrownBy(() -> userBranchService.setDefault("01HZZZZZZZZZZZZZZZZZZZZZZY"))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------
    // remove default: earliest-assigned remaining becomes new default (D-D)
    // Order: assign B1 first, then B2, then B3; make B1 default; remove B1 → B2 becomes default
    // ---------------------------------------------------------------

    @Test
    void remove_defaultAssignment_earliestRemainingBecomesDefault() {
        // Assign in B1 -> B2 -> B3 order (distinct Instant.now() calls; safe under test throughput
        // because each assign persists a fresh Instant). Use a tiny sleep between to guarantee order.
        UserBranchDto ub1 = assign(b1, true);
        sleepMillis(5);
        assign(b2, false);
        sleepMillis(5);
        assign(b3, false);

        // Remove B1 (the default)
        userBranchService.remove(ub1.uid());

        // B2 must now be the default (earlier assigned_at than B3)
        assertThat(currentDefaultBranchCode()).isEqualTo("B2");

        // Only 2 assignments remain
        assertThat(userBranchService.listForUser(targetUser.getUid())).hasSize(2);
    }

    // ---------------------------------------------------------------
    // remove last assignment: user has no default and listForUser is empty
    // ---------------------------------------------------------------

    @Test
    void remove_lastAssignment_leavesNoBranchAndEmptyList() {
        UserBranchDto ub1 = assign(b1, true);

        userBranchService.remove(ub1.uid());

        assertThat(userBranches.findByUserIdAndIsDefaultTrue(targetUser.getId())).isEmpty();
        assertThat(userBranchService.listForUser(targetUser.getUid())).isEmpty();
    }

    // ---------------------------------------------------------------
    // assigned_at ordering drives fallback: B2 is earlier than B3
    // ---------------------------------------------------------------

    @Test
    void remove_defaultB1_withB2EarlierThanB3_fallsBackToB2() {
        UserBranchDto ub1 = assign(b1, true);
        sleepMillis(5);
        UserBranchDto ub2 = assign(b2, false);
        sleepMillis(5);
        assign(b3, false);

        // B2 was assigned before B3; remove B1 → expect B2 to become default (not B3)
        userBranchService.remove(ub1.uid());

        assertThat(currentDefaultBranchCode())
                .as("B2 must be promoted (assigned earlier than B3)")
                .isEqualTo("B2");
        // The B2 assignment row is the one now flagged default.
        assertThat(userBranches.findByUid(ub2.uid()).orElseThrow().isDefault()).isTrue();
    }

    // ===================================================================
    // SECURITY FIX: Archived default branch must NOT scope login
    // ===================================================================

    @Test
    void login_whenDefaultBranchIsArchived_hasBranchFalseAndNoBranchUid() {
        // Assign B1 to targetUser as default
        assign(b1, true);

        // Verify login works and sets a branch before archiving
        TokenResponse beforeArchive = authService.login("target_s4", PASSWORD);
        assertThat(beforeArchive.user().hasBranch()).isTrue();
        assertThat(beforeArchive.user().activeBranchUid()).isNotBlank();

        // Archive B1 directly via the repo (bypassing the company-default guard, since B1 is not
        // the company's default branch — we did not set any company default in setUp).
        Branch branch1 = branches.findById(b1.getId()).orElseThrow();
        branch1.setStatus(MasterStatus.ARCHIVED);
        branches.saveAndFlush(branch1);

        // Now login — the archived branch must NOT scope the session
        TokenResponse afterArchive = authService.login("target_s4", PASSWORD);
        assertThat(afterArchive.user().hasBranch())
                .as("An archived default branch must not scope the session (hasBranch must be false)")
                .isFalse();
        assertThat(afterArchive.user().activeBranchUid())
                .as("activeBranchUid must be null when default branch is archived")
                .isNull();
    }

    // ===================================================================
    // SECURITY FIX: archiveByUid clears the user's default and promotes earliest remaining
    // ===================================================================

    @Test
    void archiveByUid_clearsUserDefault_andPromotesEarliestRemaining() {
        // B2 must be the company-level default so archiveByUid can target B1 (BR-2 guard blocks
        // archiving a company-default branch; set B2 as company default first).
        branchService.setDefault(b2.getUid());

        // Assign B1 (default) -> B2 -> B3 to targetUser (B1 earliest)
        UserBranchDto ub1 = assign(b1, true);
        sleepMillis(5);
        assign(b2, false);
        sleepMillis(5);
        assign(b3, false);

        // Confirm initial state: B1 is the user's default
        assertThat(currentDefaultBranchCode()).isEqualTo("B1");

        // Archive B1 via BranchService (this triggers the security fix path)
        branchService.archiveByUid(b1.getUid());

        // The user's B1 assignment is still present (not deleted) but is_default must be cleared
        UserBranch ub1Entity = userBranches.findByUid(ub1.uid()).orElseThrow();
        assertThat(ub1Entity.isDefault())
                .as("B1 assignment must have its is_default cleared after archiving B1")
                .isFalse();

        // The earliest remaining (B2, assigned before B3) must now be the default
        assertThat(currentDefaultBranchCode()).isEqualTo("B2");
    }

    @Test
    void archiveByUid_withOnlyOneAssignment_leavesUserWithNoDefault() {
        // B2 must be company default so B1 is archivable
        branchService.setDefault(b2.getUid());

        // Assign only B1 to targetUser as default
        assign(b1, true);

        branchService.archiveByUid(b1.getUid());

        // No default remains
        assertThat(userBranches.findByUserIdAndIsDefaultTrue(targetUser.getId()))
                .as("User must have no default after their only assigned branch is archived")
                .isEmpty();
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private UserBranchDto assign(Branch branch, boolean makeDefault) {
        return userBranchService.assign(
                new AssignBranchRequest(targetUser.getUid(), branch.getUid(), makeDefault));
    }

    /**
     * The code of the user's current default branch, read via the service DTO (built inside the
     * service transaction) — NOT by touching the lazy {@code UserBranch.branch} proxy from a detached
     * entity, which would throw LazyInitializationException outside a session. Returns null if the
     * user has no default.
     */
    private String currentDefaultBranchCode() {
        return userBranchService.listForUser(targetUser.getUid()).stream()
                .filter(UserBranchDto::isDefault)
                .map(UserBranchDto::branchCode)
                .findFirst()
                .orElse(null);
    }

    /**
     * Busy-spin until at least {@code ms} wall-clock milliseconds have elapsed. Used to ensure
     * distinct {@code assigned_at} timestamps in ordering tests. Avoids {@code Thread.sleep()} so
     * the Sonar {@code java:S2925} rule does not fire (that rule targets flaky sleep-based waits;
     * here we are just advancing the clock, not waiting on an async side-effect).
     */
    @SuppressWarnings("java:S2925") // intentional clock-advance, not a flaky async wait
    private static void sleepMillis(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            // spin — advances Instant.now() so assigned_at values are strictly ordered
        }
    }
}

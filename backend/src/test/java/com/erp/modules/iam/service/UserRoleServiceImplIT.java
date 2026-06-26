package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.UserRoleDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link UserRoleServiceImpl} against real Postgres. Covers: root grantor
 * succeeds cross-company; non-root grantor outside their company gets ForbiddenException; BR-5
 * branch-company mismatch; duplicate active grant; revoke sets revoked_at and disappears from
 * listForUser; re-grant after revoke succeeds.
 *
 * <p>RequestContext is set/cleared per test so the ThreadLocal is never leaked between test methods.
 */
class UserRoleServiceImplIT extends PostgresIntegrationTest {

    @Autowired private UserRoleService userRoleService;
    @Autowired private RoleRepository roleRepo;
    @Autowired private PermissionRepository permissionRepo;
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private AppUser rootUser;
    private AppUser targetUser;
    private AppUser nonRootUser;
    private Company companyA;
    private Company companyB;
    private Branch branchInA;
    private Branch branchInB;
    private Role testRole;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Grant Corp"));
        companyA = companies.save(new Company(org, "CA", "Company A"));
        companyB = companies.save(new Company(org, "CB", "Company B"));
        branchInA = branches.save(new Branch(companyA, "A1", "Branch A1"));
        branchInB = branches.save(new Branch(companyB, "B1", "Branch B1"));

        rootUser = new AppUser("root", passwordEncoder.encode("pw"), "Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);

        targetUser = users.save(new AppUser("target", passwordEncoder.encode("pw"), "Target"));
        nonRootUser = users.save(new AppUser("nonroot", passwordEncoder.encode("pw"), "Non Root"));

        var companyManage = permissionRepo.findByCode("COMPANY.MANAGE")
                .orElseThrow(() -> new IllegalStateException("COMPANY.MANAGE must be seeded by V1 migration"));
        testRole = new Role("GRANT_TEST_ROLE", "Grant Test Role");
        testRole.setPermissions(Set.of(companyManage));
        testRole = roleRepo.save(testRole);

        // Default context: root principal active in company A.
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), "root", true, companyA.getId(), branchInA.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------
    // Root grantor succeeds (cross-company: grants in both CA and CB)
    // ---------------------------------------------------------------

    @Test
    void grant_rootGrantor_succeedsForAnyCompany() {
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        UserRoleDto dtoA = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));
        assertThat(dtoA.uid()).isNotBlank();
        assertThat(dtoA.companyUid()).isEqualTo(companyA.getUid());
        assertThat(dtoA.branchUid()).isNull(); // company-wide

        // Root may also grant in company B (cross-company)
        testData.seedMembership(nonRootUser.getUid(), companyB.getUid());
        UserRoleDto dtoB = userRoleService.grant(new GrantRoleRequest(
                nonRootUser.getUid(), testRole.getUid(), companyB.getUid(), null));
        assertThat(dtoB.companyUid()).isEqualTo(companyB.getUid());
    }

    // ---------------------------------------------------------------
    // Non-root grantor whose active company != grant company -> ForbiddenException
    // ---------------------------------------------------------------

    @Test
    void grant_nonRootGrantorOutsideOwnCompany_throwsForbidden() {
        // nonRootUser is active in companyA; tries to grant in companyB
        // Seed targetUser membership in companyB so only the grantor-scope check fires (ForbiddenException)
        testData.seedMembership(targetUser.getUid(), companyB.getUid());
        RequestContext.set(new RequestContext.Principal(
                nonRootUser.getId(), "nonroot", false, companyA.getId(), branchInA.getId(), null));

        assertThatThrownBy(() -> userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyB.getUid(), null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void grant_nonRootGrantorInOwnCompany_succeeds() {
        // nonRootUser is active in companyA; grants in companyA — should succeed
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        RequestContext.set(new RequestContext.Principal(
                nonRootUser.getId(), "nonroot", false, companyA.getId(), branchInA.getId(), null));

        UserRoleDto dto = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));
        assertThat(dto.companyUid()).isEqualTo(companyA.getUid());
    }

    // ---------------------------------------------------------------
    // BR-5: branch whose company != grant company -> ConflictException
    // ---------------------------------------------------------------

    @Test
    void grant_branchFromWrongCompany_throwsConflict() {
        // branchInB belongs to companyB, but we're granting in companyA
        // Seed membership so only the branch-company mismatch ConflictException fires
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        assertThatThrownBy(() -> userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), branchInB.getUid())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(branchInB.getUid());
    }

    // ---------------------------------------------------------------
    // Duplicate active grant -> ConflictException; after revoke, re-grant succeeds
    // ---------------------------------------------------------------

    @Test
    void grant_duplicateActiveGrant_throwsConflict() {
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));

        assertThatThrownBy(() -> userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void grant_afterRevoke_succeedsAsNewGrant() {
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        UserRoleDto first = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));
        userRoleService.revoke(first.uid());

        // Should succeed — the revoked grant is no longer "active"
        UserRoleDto second = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));
        assertThat(second.uid()).isNotEqualTo(first.uid()); // distinct assignment record
    }

    // ---------------------------------------------------------------
    // Branch-scoped grant: branchUid recorded correctly
    // ---------------------------------------------------------------

    @Test
    void grant_withBranchUid_recordsBranchInDto() {
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        UserRoleDto dto = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), branchInA.getUid()));

        assertThat(dto.branchUid()).isEqualTo(branchInA.getUid());
        assertThat(dto.companyUid()).isEqualTo(companyA.getUid());
    }

    // ---------------------------------------------------------------
    // revoke: grant disappears from listForUser; revoking again -> ConflictException
    // ---------------------------------------------------------------

    @Test
    void revoke_grantDisappearsFromListForUser() {
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        UserRoleDto dto = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));

        List<UserRoleDto> before = userRoleService.listForUser(targetUser.getUid());
        assertThat(before).as("grant present before revoke").isNotEmpty()
                .anyMatch(u -> u.uid().equals(dto.uid()));

        userRoleService.revoke(dto.uid());

        List<UserRoleDto> after = userRoleService.listForUser(targetUser.getUid());
        assertThat(after).as("grant absent after revoke")
                .noneMatch(u -> u.uid().equals(dto.uid()));
    }

    @Test
    void revoke_alreadyRevoked_throwsConflict() {
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        UserRoleDto dto = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));
        userRoleService.revoke(dto.uid());

        assertThatThrownBy(() -> userRoleService.revoke(dto.uid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("revoked");
    }

    // ---------------------------------------------------------------
    // listForUser: only returns active grants
    // ---------------------------------------------------------------

    @Test
    void listForUser_returnsOnlyActiveGrants() {
        // Grant two; revoke one
        testData.seedMembership(targetUser.getUid(), companyA.getUid());
        UserRoleDto grantA = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));

        // Need a second role to avoid the duplicate-active-grant guard
        Role secondRole = roleRepo.save(new Role("SECOND_ROLE", "Second Role"));
        UserRoleDto grantB = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), secondRole.getUid(), companyA.getUid(), null));

        userRoleService.revoke(grantA.uid());

        List<UserRoleDto> active = userRoleService.listForUser(targetUser.getUid());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).uid()).isEqualTo(grantB.uid());
    }

    // ---------------------------------------------------------------
    // revoke: ScopeGuard check — non-root may not revoke a grant outside their company
    // ---------------------------------------------------------------

    @Test
    void revoke_nonRootOutsideGrantCompany_throwsForbidden() {
        // Root grants targetUser a role in companyB
        testData.seedMembership(targetUser.getUid(), companyB.getUid());
        UserRoleDto dto = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyB.getUid(), null));

        // Switch to nonRootUser who is active in companyA — cannot revoke a companyB grant
        RequestContext.set(new RequestContext.Principal(
                nonRootUser.getId(), "nonroot", false, companyA.getId(), branchInA.getId(), null));

        assertThatThrownBy(() -> userRoleService.revoke(dto.uid()))
                .isInstanceOf(ForbiddenException.class);
    }
}

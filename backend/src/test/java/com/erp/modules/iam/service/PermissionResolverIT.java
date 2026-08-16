package com.erp.modules.iam.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Permission;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link PermissionResolver} against real Postgres (ADR-0001 D-E).
 *
 * <p>Covers: empty-before-grant; company-scoped DENY (cross-company); branch-scoped grant vs
 * company-wide grant; root short-circuit; cache bust on invalidate (grant then revoke,
 * revoke then re-grant); null company returns empty.
 *
 * <p>nowMillis is pinned to a fixed value throughout each test so TTL is never the variable — only
 * invalidate() controls cache-bust behaviour.
 */
class PermissionResolverIT extends PostgresIntegrationTest {

    @Autowired private PermissionResolver resolver;
    @Autowired private UserRoleService userRoleService;
    @Autowired private RoleRepository roleRepo;
    @Autowired private PermissionRepository permissionRepo;
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    /** Fixed "now" throughout each test — TTL (30 s) never fires; only invalidate() matters. */
    private static final long NOW = System.currentTimeMillis();

    private AppUser nonRootUser;
    private AppUser rootUser;
    private Company companyA;
    private Company companyB;
    private Branch branchA1;
    private Branch branchA2;
    private Branch branchB1;
    private Role roleWithCompanyManage;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        resolver.invalidate(); // clean cache slate for each test

        Organisation org = organisations.save(new Organisation("Acme Group"));
        companyA = companies.save(new Company(org, "CA", "Company A"));
        companyB = companies.save(new Company(org, "CB", "Company B"));
        branchA1 = branches.save(new Branch(companyA, "A1", "Branch A1"));
        branchA2 = branches.save(new Branch(companyA, "A2", "Branch A2"));
        branchB1 = branches.save(new Branch(companyB, "B1", "Branch B1"));

        nonRootUser = users.save(inOrganisation(
                new AppUser("nonroot", passwordEncoder.encode("pw"), "Non Root"), org.getId()));
        rootUser = new AppUser("root", passwordEncoder.encode("pw"), "Root");
        rootUser.setRoot(true);
        rootUser = users.save(inOrganisation(rootUser, org.getId()));

        Permission companyManage = permissionRepo.findByCode("COMPANY.MANAGE")
                .orElseThrow(() -> new IllegalStateException("COMPANY.MANAGE must be seeded by V1 migration"));

        roleWithCompanyManage = new Role("TEST_ROLE", "Test Role");
        roleWithCompanyManage.setPermissions(Set.of(companyManage));
        roleWithCompanyManage = roleRepo.save(roleWithCompanyManage);

        // Root principal so grant() passes ScopeGuard during @BeforeEach and test-body grants.
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), "root", true, companyA.getId(), branchA1.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        resolver.invalidate();
    }

    // ---------------------------------------------------------------
    // resolve() returns empty before any grant
    // ---------------------------------------------------------------

    @Test
    void resolve_beforeAnyGrant_returnsEmpty() {
        Set<String> codes = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        assertThat(codes).isEmpty();
    }

    // ---------------------------------------------------------------
    // After granting COMPANY.MANAGE in Company A, resolve at Company A CONTAINS the code
    // ---------------------------------------------------------------

    @Test
    void resolve_afterGrant_containsPermission() {
        testData.seedMembership(nonRootUser.getUid(), companyA.getUid());
        userRoleService.grant(new GrantRoleRequest(
                nonRootUser.getUid(), roleWithCompanyManage.getUid(),
                companyA.getUid(), null));

        Set<String> codes = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        assertThat(codes).isNotEmpty().contains("COMPANY.MANAGE");
    }

    // ---------------------------------------------------------------
    // Cross-company DENY: a grant in CA must NOT appear in CB
    // ---------------------------------------------------------------

    @Test
    void resolve_grantInCompanyA_doesNotAppearInCompanyB() {
        testData.seedMembership(nonRootUser.getUid(), companyA.getUid());
        userRoleService.grant(new GrantRoleRequest(
                nonRootUser.getUid(), roleWithCompanyManage.getUid(),
                companyA.getUid(), null));

        Set<String> codesInA = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        assertThat(codesInA).as("grant is present in company A").isNotEmpty().contains("COMPANY.MANAGE");

        Set<String> codesInB = resolver.resolve(
                nonRootUser.getId(), companyB.getId(), branchB1.getId(), NOW);
        assertThat(codesInB).as("cross-company DENY: grant must not bleed into company B").isEmpty();
    }

    // ---------------------------------------------------------------
    // Branch scoping: branch-scoped grant appears at that branch only;
    // company-wide grant (branchId null) appears at both branches
    // ---------------------------------------------------------------

    @Test
    void resolve_branchScopedGrant_appearsAtGrantedBranchOnly() {
        testData.seedMembership(nonRootUser.getUid(), companyA.getUid());
        userRoleService.grant(new GrantRoleRequest(
                nonRootUser.getUid(), roleWithCompanyManage.getUid(),
                companyA.getUid(), branchA1.getUid()));

        Set<String> atA1 = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        assertThat(atA1).as("branch-scoped grant is active at the granted branch")
                .isNotEmpty().contains("COMPANY.MANAGE");

        Set<String> atA2 = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA2.getId(), NOW);
        assertThat(atA2).as("branch-scoped grant must not appear at a sibling branch").isEmpty();
    }

    @Test
    void resolve_companyWideGrant_appearsAtAllBranchesInThatCompany() {
        testData.seedMembership(nonRootUser.getUid(), companyA.getUid());
        userRoleService.grant(new GrantRoleRequest(
                nonRootUser.getUid(), roleWithCompanyManage.getUid(),
                companyA.getUid(), null));

        Set<String> atA1 = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        Set<String> atA2 = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA2.getId(), NOW);

        assertThat(atA1).as("company-wide grant visible at branch A1").isNotEmpty().contains("COMPANY.MANAGE");
        assertThat(atA2).as("company-wide grant visible at branch A2").isNotEmpty().contains("COMPANY.MANAGE");
    }

    // ---------------------------------------------------------------
    // Root short-circuit: hasPermission with root=true is always true
    // ---------------------------------------------------------------

    @Test
    void hasPermission_rootPrincipal_alwaysTrueWithoutAnyGrant() {
        var rootPrincipal = new RequestContext.Principal(
                rootUser.getId(), "root", true, companyA.getId(), branchA1.getId(), null);
        assertThat(resolver.hasPermission(rootPrincipal, "COMPANY.MANAGE", NOW)).isTrue();
        assertThat(resolver.hasPermission(rootPrincipal, "ANYTHING.MADE_UP", NOW)).isTrue();
    }

    @Test
    void hasPermission_nullPrincipal_isFalse() {
        assertThat(resolver.hasPermission(null, "COMPANY.MANAGE", NOW)).isFalse();
    }

    // ---------------------------------------------------------------
    // Cache bust: grant -> resolve (caches) -> revoke -> resolve again = GONE immediately
    // ---------------------------------------------------------------

    @Test
    void cacheBust_revokeAfterGrant_permissionGoneImmediately() {
        testData.seedMembership(nonRootUser.getUid(), companyA.getUid());
        var dto = userRoleService.grant(new GrantRoleRequest(
                nonRootUser.getUid(), roleWithCompanyManage.getUid(),
                companyA.getUid(), null));

        Set<String> afterGrant = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        assertThat(afterGrant).as("permission present after grant").isNotEmpty().contains("COMPANY.MANAGE");

        // Revoke — UserRoleServiceImpl.revoke() calls invalidate()
        userRoleService.revoke(dto.uid());

        // Re-resolve with the SAME nowMillis — TTL has NOT expired, only invalidate() clears it
        Set<String> afterRevoke = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        assertThat(afterRevoke).as("permission gone immediately after revoke+invalidate").isEmpty();
    }

    @Test
    void cacheBust_grantAfterNegativeResolve_permissionPresentAfterInvalidate() {
        // Warm the cache with a negative (empty) result
        Set<String> noGrant = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        assertThat(noGrant).as("no grant yet").isEmpty();

        // Grant — UserRoleServiceImpl.grant() calls invalidate()
        testData.seedMembership(nonRootUser.getUid(), companyA.getUid());
        userRoleService.grant(new GrantRoleRequest(
                nonRootUser.getUid(), roleWithCompanyManage.getUid(),
                companyA.getUid(), null));

        // Re-resolve with the SAME nowMillis — cache was busted, DB is re-hit
        Set<String> afterGrant = resolver.resolve(
                nonRootUser.getId(), companyA.getId(), branchA1.getId(), NOW);
        assertThat(afterGrant).as("permission present after grant+invalidate").isNotEmpty().contains("COMPANY.MANAGE");
    }

    // ---------------------------------------------------------------
    // No active company / no userId (companyId null) -> resolve returns empty
    // ---------------------------------------------------------------

    @Test
    void resolve_nullCompanyId_returnsEmpty() {
        testData.seedMembership(nonRootUser.getUid(), companyA.getUid());
        userRoleService.grant(new GrantRoleRequest(
                nonRootUser.getUid(), roleWithCompanyManage.getUid(),
                companyA.getUid(), null));

        Set<String> codes = resolver.resolve(nonRootUser.getId(), null, branchA1.getId(), NOW);
        assertThat(codes).isEmpty();
    }

    @Test
    void resolve_nullUserId_returnsEmpty() {
        Set<String> codes = resolver.resolve(null, companyA.getId(), branchA1.getId(), NOW);
        assertThat(codes).isEmpty();
    }
}

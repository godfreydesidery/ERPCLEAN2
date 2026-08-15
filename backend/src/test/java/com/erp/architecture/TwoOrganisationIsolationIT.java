package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.service.CompanyService;
import com.erp.modules.iam.service.OrganisationService;
import com.erp.modules.iam.service.RoleService;
import com.erp.modules.iam.service.UserService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The first test in this codebase that puts <b>two organisations</b> in one database and asks
 * whether the boundary between them actually holds (MULTITENANCY-PLAN.md P7-1, P7-2; ADR-0062).
 *
 * <h2>Why this did not exist, and why that was dangerous</h2>
 *
 * Eight ITs are already named {@code *TenantIsolation*}. Every one of them creates a SINGLE
 * organisation and two companies inside it — for example
 * {@code organisations.save(new Organisation("Isolation Test Org"))} followed by {@code ISO-A} and
 * {@code ISO-B}. They are cross-<b>company</b> tests, which is a real and useful thing to check, but
 * it is not the boundary organisation-as-tenant introduces. Their names imply coverage that does not
 * exist, which is worse than having no tests at all: it is the reason nobody noticed that the entire
 * Phase 2 and Phase 3 security spine had never once been observed denying anything.
 *
 * <p>Every environment — local, QA, production — holds exactly one organisation. So until this
 * class, "tenant isolation works" rested on unit tests of the rules in isolation and a parity
 * harness proving the predicates do not over-filter. Nobody had ever seen a cross-tenant request
 * refused end-to-end through a real service, against a real database.
 *
 * <h2>What it asserts</h2>
 *
 * One probe per control that Phases 2 and 3 added, each phrased as the attack it prevents. The
 * caller is always a member of organisation A; the target always belongs to organisation B.
 *
 * <p><b>Root is included deliberately in the scope probes.</b> {@code is_root} is deployment-global,
 * so a root user is precisely the caller for whom a tenant boundary must still hold — D-2 re-bounds
 * root to "full authority inside my own organisation", and these are the assertions that make that
 * sentence true rather than aspirational.
 */
@DisplayName("two organisations in one database — the boundary must hold")
class TwoOrganisationIsolationIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    @Autowired private ScopeGuard scopeGuard;
    @Autowired private UserService userService;
    @Autowired private CompanyService companyService;
    @Autowired private OrganisationService organisationService;
    @Autowired private RoleService roleService;

    private Organisation orgA;
    private Organisation orgB;
    private Company companyA;
    private Company companyB;
    private Branch branchA;
    private Branch branchB;
    private AppUser adminA;      // non-root administrator of A — P7-2's probe identity
    private AppUser rootA;       // root user WHOSE HOME IS A
    private AppUser userB;
    private Role roleB;

    @BeforeEach
    void seedTwoTenants() {
        testData.clearAll();
        String tag = UUID.randomUUID().toString().substring(0, 8);

        orgA = organisations.save(new Organisation("Tenant A " + tag));
        companyA = companies.save(new Company(orgA, "TA-" + tag, "Tenant A Co"));
        branchA = branches.save(new Branch(companyA, "TAB-" + tag, "Tenant A Branch"));

        orgB = organisations.save(new Organisation("Tenant B " + tag));
        companyB = companies.save(new Company(orgB, "TB-" + tag, "Tenant B Co"));
        branchB = branches.save(new Branch(companyB, "TBB-" + tag, "Tenant B Branch"));

        adminA = newUser("admin-a-" + tag, "Admin A", orgA, false);
        rootA = newUser("root-a-" + tag, "Root A", orgA, true);
        userB = newUser("user-b-" + tag, "User B", orgB, false);

        roleB = new Role("TENANT_B_ROLE_" + tag, "Tenant B's own role");
        roleB.setOrganisationId(orgB.getId());
        roleB = roles.save(roleB);
    }

    private AppUser newUser(String username, String displayName, Organisation org, boolean root) {
        AppUser u = new AppUser(username, passwordEncoder.encode("Tenant!Pass123"), displayName);
        u.setOrganisationId(org.getId());
        u.setRoot(root);
        return users.save(u);
    }

    /** Act as a member of organisation A. */
    private void actingAsA(AppUser who) {
        RequestContext.set(new RequestContext.Principal(
                who.getId(), who.getUsername(), who.isRoot(),
                companyA.getId(), branchA.getId(), "127.0.0.1", orgA.getId()));
    }

    /** Act as a member of organisation B. */
    private void actingAsB(AppUser who) {
        RequestContext.set(new RequestContext.Principal(
                who.getId(), who.getUsername(), who.isRoot(),
                companyB.getId(), branchB.getId(), "127.0.0.1", orgB.getId()));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------------
    // P3-11 — the tenant check inside ScopeGuard, under all 698 call sites
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("P3-11 · a caller cannot act in another tenant's company — root included")
    void canActInRefusesAnotherTenantsCompany() {
        actingAsA(adminA);
        assertThat(scopeGuard.canActIn(RequestContext.get(), companyB.getId()))
                .as("89 controllers take companyId straight from the caller; this is the one method "
                        + "that stands between a query parameter and another tenant's ledger")
                .isFalse();

        actingAsA(rootA);
        assertThat(scopeGuard.canActIn(RequestContext.get(), companyB.getId()))
                .as("root is deployment-global, so it is exactly the caller this must still refuse "
                        + "(D-2: full authority INSIDE my own organisation)")
                .isFalse();

        // ...and the same root keeps everything it is entitled to at home.
        assertThat(scopeGuard.canActIn(RequestContext.get(), companyA.getId())).isTrue();
    }

    @Test
    @DisplayName("P3-8 · the uid-addressed path refuses too — root no longer short-circuits it")
    void canActOnRefusesAnotherTenantsCompany() {
        actingAsA(rootA);
        assertThat(scopeGuard.canActOn(RequestContext.get(), "company", companyB.getUid()))
                .as("canActOn used to return true for root BEFORE canActIn was reached, which would "
                        + "have made the tenant check dead code for every uid-addressed operation")
                .isFalse();
        assertThat(scopeGuard.canActOn(RequestContext.get(), "company", companyA.getUid())).isTrue();
    }

    // ---------------------------------------------------------------------
    // P3-8 — reads that used to drop their predicate for root
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("P3-8 · root's user list stops at its own tenant")
    void rootUserListExcludesTheOtherTenant() {
        actingAsA(rootA);
        var listed = userService.list().stream().map(u -> u.username()).toList();

        assertThat(listed)
                .as("root's branch was findAllByOrderByUsername() — every user in the database, of "
                        + "every customer, by name")
                .contains(adminA.getUsername())
                .doesNotContain(userB.getUsername());
    }

    @Test
    @DisplayName("P3-8 · another tenant's user is not found, rather than forbidden")
    void anotherTenantsUserIsNotFound() {
        actingAsA(rootA);
        // NotFound, not Forbidden: "it exists but is not yours" is an existence oracle across the
        // boundary, and a uid is the one thing an outsider might plausibly hold.
        assertThatThrownBy(() -> userService.getByUid(userB.getUid()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("P3-6/P3-8 · naming another tenant's organisation uid yields nothing")
    void anotherTenantsCompanyListIsNotReachable() {
        actingAsA(rootA);
        // The organisation uid arrives FROM THE CALLER on an endpoint that is only
        // isAuthenticated(); for root the predicate was dropped entirely.
        assertThatThrownBy(() -> companyService.listAccessibleByOrganisationUid(orgB.getUid()))
                .isInstanceOf(NotFoundException.class);

        assertThat(companyService.listAccessibleByOrganisationUid(orgA.getUid()))
                .as("its own organisation still resolves")
                .isNotEmpty();
    }

    @Test
    @DisplayName("P3-7 · the organisation list shows the caller's own tenant only")
    void organisationListIsScoped() {
        actingAsA(adminA);
        assertThat(organisationService.list().stream().map(o -> o.uid()))
                .containsExactly(orgA.getUid());
        assertThat(organisationService.current().uid()).isEqualTo(orgA.getUid());
    }

    @Test
    @DisplayName("P3-5 · roles are scoped, but the global bundles stay visible (I-2)")
    void roleListIsScopedYetKeepsGlobals() {
        actingAsA(adminA);
        var visible = roleService.list();

        assertThat(visible.stream().map(r -> r.code()))
                .as("tenant B's own role must not appear in tenant A's role screen")
                .doesNotContain(roleB.getCode());

        assertThat(visible.stream().anyMatch(r -> r.system()))
                .as("the shipped global bundles must still be visible — a plain equality predicate "
                        + "would hide ORG_ADMIN and every operational bundle from every tenant, "
                        + "which is invariant I-2")
                .isTrue();
    }

    @Test
    @DisplayName("P4-1 · a role authored by one tenant belongs to that tenant, not to everybody")
    void aCreatedRoleIsStampedWithTheAuthorsTenant() {
        actingAsA(adminA);
        var created = roleService.create(new com.erp.modules.iam.domain.dto.CreateRoleRequest(
                "A_OWN_ROLE_" + UUID.randomUUID().toString().substring(0, 6),
                "A's own role", "authored inside tenant A"));

        // organisation_id NULL is not "unset" - it is the marker for a GLOBAL role. Left unstamped,
        // every role a customer authored would have been published to every tenant through the
        // NULL-tolerant visibility predicate: visible in strangers' role lists, and grantable there.
        Role stored = roles.findById(created.id()).orElseThrow();
        assertThat(stored.getOrganisationId())
                .as("a role created by a member of tenant A must belong to tenant A")
                .isEqualTo(orgA.getId());
    }

    @Test
    @DisplayName("P4-1 · the role DETAIL read is scoped too, not just the list")
    void anotherTenantsRoleDetailIsNotReadable() {
        actingAsA(adminA);
        // The detail read returns the full permission list, which is the most revealing thing a role
        // has. P3-5 scoped list() and the by-uid lookup behind the write paths; getByUid reached
        // past both via findWithPermissionsByUid, which has no tenant predicate at all.
        assertThatThrownBy(() -> roleService.getByUid(roleB.getUid()))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------------
    // P4-1b / P4-1c / R-1 — role codes after V102
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("P4-1b · both tenants may hold the same role code — the point of V102")
    void bothTenantsMayHoldTheSameRoleCode() {
        String code = "SUPERVISOR_" + UUID.randomUUID().toString().substring(0, 6);

        actingAsA(adminA);
        var a = roleService.create(new com.erp.modules.iam.domain.dto.CreateRoleRequest(
                code, "A's supervisor", "authored in tenant A"));

        actingAsB(userB);
        var b = roleService.create(new com.erp.modules.iam.domain.dto.CreateRoleRequest(
                code, "B's supervisor", "authored in tenant B"));

        // Impossible before V102: uq_role_code was global, so the second create hit a 409 and
        // per-tenant roles were a fiction. create()'s uniqueness check had to become org-scoped in
        // the same change, or tenant B could still never use a code tenant A had taken.
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(roles.findById(a.id()).orElseThrow().getOrganisationId()).isEqualTo(orgA.getId());
        assertThat(roles.findById(b.id()).orElseThrow().getOrganisationId()).isEqualTo(orgB.getId());
    }

    @Test
    @DisplayName("P4-1b · the same code twice in ONE tenant is still refused")
    void oneTenantMayNotReuseItsOwnRoleCode() {
        String code = "DUPE_" + UUID.randomUUID().toString().substring(0, 6);
        actingAsA(adminA);
        roleService.create(new com.erp.modules.iam.domain.dto.CreateRoleRequest(code, "first", null));

        assertThatThrownBy(() -> roleService.create(
                new com.erp.modules.iam.domain.dto.CreateRoleRequest(code, "second", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("R-1 · a tenant may not reuse a GLOBAL role code")
    void aTenantMayNotReuseAGlobalRoleCode() {
        // The shipped bundles survive IamTestData.clearAll() (it deletes only non-system roles), so
        // this collides with something real rather than passing vacuously.
        Role global = roles.findAll().stream()
                .filter(r -> r.getOrganisationId() == null && r.isSystem())
                .findFirst().orElseThrow(() ->
                        new IllegalStateException("no global role seeded — this test would be vacuous"));

        actingAsA(adminA);
        assertThatThrownBy(() -> roleService.create(
                new com.erp.modules.iam.domain.dto.CreateRoleRequest(
                        global.getCode(), "A's own " + global.getCode(), null)))
                .as("V102's two partial indexes sit on different partitions, so the DATABASE would "
                        + "accept this; R-1 is what stops role-code resolution having to pick a "
                        + "winner instead of an answer")
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("R-1 · the trigger holds even when the service is bypassed")
    void theTriggerEnforcesR1WithoutTheService() {
        Role global = roles.findAll().stream()
                .filter(r -> r.getOrganisationId() == null && r.isSystem())
                .findFirst().orElseThrow();

        Role sneaky = new Role(global.getCode(), "bypassing the service");
        sneaky.setOrganisationId(orgA.getId());

        // Straight at the repository, past RoleServiceImpl entirely. This is the "a seeder cannot
        // bypass it" half of R-1, and the only reason V102 introduces the first trigger in this
        // schema: a service check cannot bind a migration, a direct SQL fix, or a future seeder.
        assertThatThrownBy(() -> roles.saveAndFlush(sneaky))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------
    // The control that guards the guard
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the fixture really does hold two distinct, populated organisations")
    void theFixtureIsGenuinelyTwoTenants() {
        // Without this, every assertion above could pass because the "other tenant" was empty or
        // because both sides resolved to the same organisation — the way the parity harness once
        // passed against two equally empty result sets.
        assertThat(orgA.getId()).isNotEqualTo(orgB.getId());
        assertThat(companyA.getId()).isNotEqualTo(companyB.getId());
        assertThat(users.findById(userB.getId())).isPresent();
        assertThat(roles.findById(roleB.getId())).isPresent();
        assertThat(userB.getOrganisationId()).isEqualTo(orgB.getId());
        assertThat(adminA.getOrganisationId()).isEqualTo(orgA.getId());
    }
}

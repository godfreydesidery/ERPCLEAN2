package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.AssignBranchRequest;
import com.erp.modules.iam.domain.dto.AssignUserCompanyRequest;
import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.UserCompanyDto;
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
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression tests for ADR-0046 — authoritative {@code user_company} membership (assign-company-first).
 *
 * <p>Verifies the three write-path gates added on top of ADR-0045's non-authoritative model:
 * <ol>
 *   <li>{@code UserRoleService.grant} rejects with {@link ConflictException} unless the user is
 *       already an explicit member of the target company; succeeds once assigned.</li>
 *   <li>{@code UserBranchService.assign} likewise requires prior membership in the branch's company.</li>
 *   <li>{@code UserCompanyService.remove} refuses while the user still holds an active role grant or
 *       branch assignment in that company (no silent cascade); succeeds once access is cleared.</li>
 * </ol>
 *
 * <p>Acts as a root principal so tenant scope ({@code ScopeGuard}) passes — the gate under test is
 * about the <em>target user's</em> membership, not the caller's authority.
 */
class AuthoritativeMembershipIT extends PostgresIntegrationTest {

    @Autowired private UserCompanyService userCompanyService;
    @Autowired private UserRoleService    userRoleService;
    @Autowired private UserBranchService  userBranchService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private RoleRepository         roles;
    @Autowired private UserRoleRepository     userRoles;
    @Autowired private UserBranchRepository   userBranches;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company company;
    private Branch  branch;
    private AppUser user;     // the user being administered (starts with NO membership)
    private Role    role;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Authoritative Corp"));
        company = companies.save(new Company(org, "AC", "Authoritative Co"));
        branch  = branches.save(new Branch(company, "AC1", "Branch AC1"));
        user    = users.save(new AppUser("member_user", passwordEncoder.encode("ValidPass1"), "Member User"));
        role    = roles.save(new Role("TESTROLE", "Test Role"));

        // Act as root: tenant scope is bypassed, but the membership gate still applies to `user`.
        RequestContext.set(new RequestContext.Principal(user.getId(), "root", true, null, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void assignCompany() {
        userCompanyService.assign(new AssignUserCompanyRequest(user.getUid(), company.getUid()));
    }

    // ── grant role ────────────────────────────────────────────────────────────

    @Test
    void grantRole_withoutMembership_isRejected() {
        assertThatThrownBy(() ->
                userRoleService.grant(new GrantRoleRequest(user.getUid(), role.getUid(), company.getUid(), null)))
                .isInstanceOf(ConflictException.class);
        assertThat(userRoles.findByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
    }

    @Test
    void grantRole_afterAssigningCompany_succeeds() {
        assignCompany();
        userRoleService.grant(new GrantRoleRequest(user.getUid(), role.getUid(), company.getUid(), null));
        assertThat(userRoles.findByUserIdAndRevokedAtIsNull(user.getId())).hasSize(1);
    }

    // ── assign branch ───────────────────────────────────────────────────────────

    @Test
    void assignBranch_withoutMembership_isRejected() {
        assertThatThrownBy(() ->
                userBranchService.assign(new AssignBranchRequest(user.getUid(), branch.getUid(), false)))
                .isInstanceOf(ConflictException.class);
        assertThat(userBranches.findByUserIdOrderByAssignedAtAscIdAsc(user.getId())).isEmpty();
    }

    @Test
    void assignBranch_afterAssigningCompany_succeeds() {
        assignCompany();
        userBranchService.assign(new AssignBranchRequest(user.getUid(), branch.getUid(), false));
        assertThat(userBranches.findByUserIdOrderByAssignedAtAscIdAsc(user.getId())).hasSize(1);
    }

    // ── remove company membership (guarded) ─────────────────────────────────────

    @Test
    void removeCompany_withActiveRole_isRejected() {
        assignCompany();
        userRoleService.grant(new GrantRoleRequest(user.getUid(), role.getUid(), company.getUid(), null));
        UserCompanyDto membership = userCompanyService.listForUser(user.getUid()).get(0);

        assertThatThrownBy(() -> userCompanyService.remove(membership.uid()))
                .isInstanceOf(ConflictException.class);
        assertThat(userCompanyService.isActiveMember(user.getId(), company.getId())).isTrue();
    }

    @Test
    void removeCompany_withActiveBranch_isRejected() {
        assignCompany();
        userBranchService.assign(new AssignBranchRequest(user.getUid(), branch.getUid(), false));
        UserCompanyDto membership = userCompanyService.listForUser(user.getUid()).get(0);

        assertThatThrownBy(() -> userCompanyService.remove(membership.uid()))
                .isInstanceOf(ConflictException.class);
        assertThat(userCompanyService.isActiveMember(user.getId(), company.getId())).isTrue();
    }

    @Test
    void removeCompany_withNoAccess_succeeds() {
        assignCompany();
        UserCompanyDto membership = userCompanyService.listForUser(user.getUid()).get(0);

        userCompanyService.remove(membership.uid());
        assertThat(userCompanyService.isActiveMember(user.getId(), company.getId())).isFalse();
    }
}

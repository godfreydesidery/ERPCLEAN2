package com.erp.modules.iam.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
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
import com.erp.platform.common.api.ForbiddenException;
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
 * Integration tests for the ADR-0059 authority ceiling on {@code UserServiceImpl.setPasswordByUid}:
 * a non-root caller may not reset the password of a user who outranks them (holds a permission the
 * caller lacks). Closes the same-company account-takeover path. Root is exempt.
 */
class SetPasswordAuthorityCeilingIT extends PostgresIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserRoleService userRoleService;
    @Autowired private RoleRepository roleRepo;
    @Autowired private PermissionRepository permissionRepo;
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private static final String VALID_PASSWORD = "ValidPass1";

    private AppUser rootUser;
    private AppUser caller;   // a non-root admin (holds USER.MANAGE in production; not needed here)
    private AppUser target;
    private Company company;
    private Branch branch;
    private Role glRole;      // holds GL.POST

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Pw Corp"));
        company = companies.save(new Company(org, "PC", "Pw Corp Co"));
        branch = branches.save(new Branch(company, "PB", "Pw Branch"));

        rootUser = new AppUser("pw_root", passwordEncoder.encode(VALID_PASSWORD), "Root");
        rootUser.setRoot(true);
        rootUser = users.save(inOrganisation(rootUser, org.getId()));

        caller = users.save(inOrganisation(
                new AppUser("pw_caller", passwordEncoder.encode(VALID_PASSWORD), "Caller"), org.getId()));
        target = users.save(inOrganisation(
                new AppUser("pw_target", passwordEncoder.encode(VALID_PASSWORD), "Target"), org.getId()));

        var glPost = permissionRepo.findByCode("GL.POST")
                .orElseThrow(() -> new IllegalStateException("GL.POST must be seeded"));
        glRole = new Role("GL_ROLE", "GL Role");
        glRole.setPermissions(Set.of(glPost));
        glRole = roleRepo.save(glRole);

        // Root context for the privileged setup (memberships + grants).
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), "pw_root", true, company.getId(), branch.getId(), null));
        testData.seedMembership(caller.getUid(), company.getUid());
        testData.seedMembership(target.getUid(), company.getUid());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void actAsCaller() {
        RequestContext.set(new RequestContext.Principal(
                caller.getId(), "pw_caller", false, company.getId(), branch.getId(), null));
    }

    @Test
    void setPassword_targetOutranksCaller_throwsForbidden() {
        // target holds GL.POST; caller holds nothing — caller cannot reset a more-privileged peer.
        userRoleService.grant(new GrantRoleRequest(target.getUid(), glRole.getUid(), company.getUid(), null));
        actAsCaller();

        assertThatThrownBy(() -> userService.setPasswordByUid(
                target.getUid(), new SetPasswordRequest(VALID_PASSWORD)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void setPassword_targetHoldsNothing_succeeds() {
        // target is a plain member with no roles — empty authority is a subset of anything.
        actAsCaller();

        assertThatCode(() -> userService.setPasswordByUid(
                target.getUid(), new SetPasswordRequest(VALID_PASSWORD)))
                .doesNotThrowAnyException();
    }

    @Test
    void setPassword_callerOutranksTarget_succeeds() {
        // Both hold GL.POST — the target's authority is a subset of the caller's, so the reset is allowed.
        userRoleService.grant(new GrantRoleRequest(target.getUid(), glRole.getUid(), company.getUid(), null));
        userRoleService.grant(new GrantRoleRequest(caller.getUid(), glRole.getUid(), company.getUid(), null));
        actAsCaller();

        assertThatCode(() -> userService.setPasswordByUid(
                target.getUid(), new SetPasswordRequest(VALID_PASSWORD)))
                .doesNotThrowAnyException();
    }

    @Test
    void setPassword_rootCaller_isExempt() {
        // Root (the default setUp context) may reset even a more-privileged target.
        userRoleService.grant(new GrantRoleRequest(target.getUid(), glRole.getUid(), company.getUid(), null));

        assertThatCode(() -> userService.setPasswordByUid(
                target.getUid(), new SetPasswordRequest(VALID_PASSWORD)))
                .doesNotThrowAnyException();
    }
}

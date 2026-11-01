package com.erp.modules.iam.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.AuthorityVerificationDto;
import com.erp.modules.iam.domain.dto.TokenResponse;
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
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.auth.AuthenticationException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Waiter POS PIN (V111) end to end against real Postgres. */
class PosPinIT extends PostgresIntegrationTest {

    @Autowired private AuthService auth;
    @Autowired private UserService userService;
    @Autowired private StepUpAuthService stepUp;
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IamTestData testData;

    private static final String PASSWORD = "W@iter1Pass";

    private Long orgId;
    private Company company;
    private Branch branch;
    private AppUser root;
    private AppUser waiter;
    private AppUser cashier;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Cafe Group"));
        orgId = org.getId();
        company = companies.save(new Company(org, "CAFE", "Cafe Co"));
        branch = new Branch(company, "CF1", "Cafe Branch");
        branch.setDefault(true);
        branch = branches.save(branch);

        AppUser r = new AppUser("pin_root", passwordEncoder.encode("R00t!Pass1"), "Manager");
        r.setRoot(true);
        root = users.save(inOrganisation(r, orgId));
        waiter = user("wanda", "Wanda Waiter", "WAITER");
        cashier = user("carl", "Carl Cashier", "WAITER", "CASHIER");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private AppUser user(String username, String name, String... roles) {
        AppUser u = users.save(inOrganisation(
                new AppUser(username, passwordEncoder.encode(PASSWORD), name), orgId));
        UserBranch ub = new UserBranch(u.getId(), branch, u.getId());
        ub.markDefault();
        userBranches.save(ub);
        for (String role : roles) {
            jdbc.update("""
                    INSERT INTO user_role (uid, user_id, role_id, company_id, granted_at, granted_by, version)
                    SELECT ?, ?, r.id, ?, now(), ?, 0 FROM roles r
                    WHERE r.code = ? AND r.organisation_id IS NULL""",
                    "UR-" + role + "-" + u.getId(), u.getId(), company.getId(), u.getId(), role);
        }
        return u;
    }

    private void as(AppUser u) {
        RequestContext.set(new RequestContext.Principal(u.getId(), u.getUsername(), u.isRoot(),
                company.getId(), branch.getId(), null, orgId));
    }

    private void managerSets(AppUser u, String pin) {
        as(root);
        userService.setPosPinByUid(u.getUid(), pin);
        RequestContext.clear();
    }

    @Test
    void aWaiterSignsInWithThePinAManagerSet() {
        managerSets(waiter, "2580");
        TokenResponse res = auth.loginWithPin("wanda", "2580", null);
        assertThat(res.accessToken()).isNotBlank();
        assertThat(res.user().username()).isEqualTo("wanda");
        assertThat(res.user().activeBranchUid()).isEqualTo(branch.getUid());
        as(root);
        assertThat(userService.getByUid(waiter.getUid()).posPinSet()).isTrue();
    }

    @Test
    void easyPinsAreRefused() {
        as(root);
        for (String pin : new String[] {"1234", "0000", "9876", "12a4", "123", "12345"}) {
            assertThatThrownBy(() -> userService.setPosPinByUid(waiter.getUid(), pin))
                    .as(pin).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void fiveWrongPinsLockTheWaiter_likePasswords() {
        managerSets(waiter, "2580");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.loginWithPin("wanda", "1357", null))
                    .isInstanceOf(AuthenticationException.class);
        }
        assertThatThrownBy(() -> auth.loginWithPin("wanda", "2580", null))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("locked");
    }

    @Test
    void aCashier_orAWaiterWithoutAPin_cannotUseOne() {
        managerSets(cashier, "2580");
        assertThatThrownBy(() -> auth.loginWithPin("carl", "2580", null))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("for waiters");
        assertThatThrownBy(() -> auth.loginWithPin("wanda", "2580", null))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("PIN is not correct");
    }

    @Test
    void aWaiterChangesTheirOwnPin_provingItWithTheOldOneOrTheirPassword() {
        managerSets(waiter, "2580");
        as(waiter);
        assertThatThrownBy(() -> auth.changeOwnPin("0000", "3691", null))
                .isInstanceOf(IllegalArgumentException.class);
        auth.changeOwnPin("2580", "3691", null);
        auth.changeOwnPin(PASSWORD, "4826", null);
        RequestContext.clear();
        assertThat(auth.loginWithPin("wanda", "4826", null).accessToken()).isNotBlank();
        assertThatThrownBy(() -> auth.loginWithPin("wanda", "3691", null))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void whosServing_acceptsAWaitersPin_butNeverACashiersPin() {
        managerSets(waiter, "2580");
        managerSets(cashier, "7319");
        as(cashier); // the till is signed in as the cashier
        AuthorityVerificationDto ok = stepUp.verifyWaiterPin("wanda", "2580");
        assertThat(ok.authorised()).isTrue();
        assertThat(ok.authoriserUid()).isEqualTo(waiter.getUid());
        assertThat(stepUp.verifyWaiterPin("wanda", "1357").authorised()).isFalse();

        as(waiter);
        assertThat(stepUp.verifyWaiterPin("carl", "7319").authorised())
                .as("a PIN never proves a till user").isFalse();
    }

    @Test
    void aRemovedPinSignsInNoLonger() {
        managerSets(waiter, "2580");
        as(root);
        userService.clearPosPinByUid(waiter.getUid());
        assertThat(userService.getByUid(waiter.getUid()).posPinSet()).isFalse();
        RequestContext.clear();
        assertThatThrownBy(() -> auth.loginWithPin("wanda", "2580", null))
                .isInstanceOf(AuthenticationException.class);
        assertThat(auth.login("wanda", PASSWORD, null).accessToken())
                .as("the password still works").isNotBlank();
    }
}

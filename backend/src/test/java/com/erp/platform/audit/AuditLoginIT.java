package com.erp.platform.audit;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.erp.modules.iam.service.AuthService;
import com.erp.platform.security.auth.AuthenticationException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for audit-trail correctness on the authentication path (ADR-0004 D-3).
 *
 * <p>Login events run in {@code REQUIRES_NEW} (via {@link com.erp.modules.iam.service.LoginAttemptService})
 * so they commit even when the outer login transaction rolls back on failure. These tests verify:
 * <ul>
 *   <li>{@code LOGIN.FAIL} is persisted on a wrong-password attempt (survives the login rollback).
 *   <li>{@code ACCOUNT.LOCKED} is also emitted on the attempt that crosses the lockout threshold.
 *   <li>{@code LOGIN.SUCCESS} is persisted on a correct-credentials login.
 *   <li>Unknown username emits {@code LOGIN.FAIL} with {@code actorUserId == null}.
 *   <li>The {@code ip} field is stored exactly as supplied to {@link AuthService#login}.
 * </ul>
 */
class AuditLoginIT extends PostgresIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private AuditRepository auditRepo;

    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private static final String PASSWORD = "LoginAudit1";
    private static final String CLIENT_IP = "192.168.1.42";

    private AppUser testUser;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Login Audit Org"));
        Company company = companies.save(new Company(org, "LAC", "Login Audit Co"));
        Branch branch = new Branch(company, "LA-B1", "Login Audit HQ");
        branch.setDefault(true);
        branch = branches.save(branch);

        testUser = users.save(inOrganisation(
                new AppUser("la_user", passwordEncoder.encode(PASSWORD), "Login Audit User"),
                org.getId()));
        UserBranch assign = new UserBranch(testUser.getId(), branch, testUser.getId());
        assign.markDefault();
        userBranches.save(assign);
    }

    // =========================================================================
    // LOGIN.SUCCESS
    // =========================================================================

    @Test
    void login_success_emitsLoginSuccessRow_withIp() {
        authService.login("la_user", PASSWORD, CLIENT_IP);

        List<AuditLog> rows = auditRepo.findAll().stream()
                .filter(r -> AuditActions.LOGIN_SUCCESS.equals(r.getAction()))
                .toList();
        assertThat(rows).as("LOGIN.SUCCESS row must be persisted").hasSize(1);

        AuditLog row = rows.get(0);
        assertThat(row.getActorUserId()).isEqualTo(testUser.getId());
        assertThat(row.getTargetType()).isEqualTo("app_users");
        assertThat(row.getTargetId()).isEqualTo(testUser.getId());
        assertThat(row.getIp()).isEqualTo(CLIENT_IP);
        assertThat(row.getAt()).isNotNull();
    }

    // =========================================================================
    // LOGIN.FAIL on wrong password — survives the login-tx rollback
    // =========================================================================

    @Test
    void login_wrongPassword_emitsLoginFailRow_withIp() {
        assertThatThrownBy(() -> authService.login("la_user", "wrongpassword", CLIENT_IP))
                .isInstanceOf(AuthenticationException.class);

        List<AuditLog> rows = auditRepo.findAll().stream()
                .filter(r -> AuditActions.LOGIN_FAIL.equals(r.getAction()))
                .toList();
        assertThat(rows)
                .as("LOGIN.FAIL must be committed even though the login tx rolled back")
                .hasSize(1);

        AuditLog row = rows.get(0);
        assertThat(row.getActorUserId()).isEqualTo(testUser.getId());
        assertThat(row.getIp()).isEqualTo(CLIENT_IP);
        assertThat(row.getAt()).isNotNull();
    }

    // =========================================================================
    // ACCOUNT.LOCKED emitted on the attempt that crosses the threshold
    // =========================================================================

    @Test
    void login_fiveFailures_emitsAccountLockedRow() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login("la_user", "wrong", CLIENT_IP))
                    .isInstanceOf(AuthenticationException.class);
        }

        List<AuditLog> lockedRows = auditRepo.findAll().stream()
                .filter(r -> AuditActions.ACCOUNT_LOCKED.equals(r.getAction()))
                .toList();
        assertThat(lockedRows)
                .as("ACCOUNT.LOCKED must be emitted when the lockout threshold is crossed")
                .hasSize(1);

        AuditLog locked = lockedRows.get(0);
        assertThat(locked.getActorUserId()).isEqualTo(testUser.getId());
        assertThat(locked.getIp()).isEqualTo(CLIENT_IP);
        assertThat(locked.getAt()).isNotNull();

        // LOGIN.FAIL rows: one per bad attempt (5 total).
        long failCount = auditRepo.findAll().stream()
                .filter(r -> AuditActions.LOGIN_FAIL.equals(r.getAction()))
                .count();
        assertThat(failCount).isEqualTo(5L);
    }

    // =========================================================================
    // Unknown username — LOGIN.FAIL with actorUserId == null
    // =========================================================================

    @Test
    void login_unknownUsername_emitsLoginFail_withNullActor_andIp() {
        assertThatThrownBy(() -> authService.login("nobody_at_all", "whatever", CLIENT_IP))
                .isInstanceOf(AuthenticationException.class);

        List<AuditLog> rows = auditRepo.findAll().stream()
                .filter(r -> AuditActions.LOGIN_FAIL.equals(r.getAction()))
                .toList();
        assertThat(rows).as("LOGIN.FAIL for unknown username must be stored").hasSize(1);

        AuditLog row = rows.get(0);
        assertThat(row.getActorUserId())
                .as("actorUserId must be null for an unknown-username failure")
                .isNull();
        assertThat(row.getIp()).isEqualTo(CLIENT_IP);
        assertThat(row.getAt()).isNotNull();
    }
}

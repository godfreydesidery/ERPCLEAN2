package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.erp.modules.iam.repository.RefreshTokenRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.security.auth.AuthenticationException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for the auth path against real Postgres — the security backbone. Covers: login
 * success lands in the default branch; bad password increments and locks after 5; lockout blocks;
 * refresh rotates and a reused refresh token revokes the chain.
 */
class AuthServiceImplIT extends PostgresIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private AppUserRepository users;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private static final String PASSWORD = "Secret123";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Acme Group"));
        Company company = companies.save(new Company(org, "C1", "Acme Tanzania"));
        Branch branch = new Branch(company, "BR-01", "Kariakoo");
        branch.setDefault(true);
        branches.save(branch);

        AppUser user = users.save(new AppUser("alice", passwordEncoder.encode(PASSWORD), "Alice"));

        UserBranch assignment = new UserBranch(user.getId(), branch, user.getId());
        assignment.markDefault();
        userBranches.save(assignment);
    }

    @Test
    void login_withValidCredentials_returnsTokensAndLandsInDefaultBranch() {
        TokenResponse res = authService.login("alice", PASSWORD, null);
        assertThat(res.accessToken()).isNotBlank();
        assertThat(res.refreshToken()).isNotBlank();
        assertThat(res.user().username()).isEqualTo("alice");
        assertThat(res.user().hasBranch()).isTrue();
        assertThat(res.user().activeBranchUid()).isNotBlank();
    }

    @Test
    void login_usernameIsCaseInsensitive() {
        TokenResponse res = authService.login("ALICE", PASSWORD, null);
        assertThat(res.user().username()).isEqualTo("alice");
    }

    @Test
    void login_wrongPassword_throwsGenericAndIncrementsCounter() {
        assertThatThrownBy(() -> authService.login("alice", "wrong", null))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Invalid username or password");
        assertThat(users.findByUsername("alice").orElseThrow().getFailedLoginCount()).isEqualTo(1);
    }

    @Test
    void login_fiveFailures_locksAccount() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login("alice", "wrong", null))
                    .isInstanceOf(AuthenticationException.class);
        }
        // 6th attempt — even with the CORRECT password — is refused because the account is locked.
        assertThatThrownBy(() -> authService.login("alice", PASSWORD, null))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("locked");
        assertThat(users.findByUsername("alice").orElseThrow().getLockedUntil()).isNotNull();
    }

    @Test
    void login_unknownUser_throwsGeneric() {
        assertThatThrownBy(() -> authService.login("nobody", "whatever", null))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Invalid username or password");
    }

    @Test
    void refresh_rotatesToken_andSuccessorWorks() {
        TokenResponse first = authService.login("alice", PASSWORD, null);
        TokenResponse second = authService.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        // The rotated successor still works (chaining forward is fine as long as no token is reused).
        TokenResponse third = authService.refresh(second.refreshToken());
        assertThat(third.accessToken()).isNotBlank();
        assertThat(third.refreshToken()).isNotEqualTo(second.refreshToken());
    }

    @Test
    void refresh_reuseOfConsumedToken_revokesWholeChain() {
        TokenResponse first = authService.login("alice", PASSWORD, null);
        TokenResponse second = authService.refresh(first.refreshToken()); // first now consumed

        // Reuse the consumed first token → theft response: revoke all the user's tokens.
        assertThatThrownBy(() -> authService.refresh(first.refreshToken()))
                .isInstanceOf(AuthenticationException.class);

        // The previously-valid second token is now revoked too.
        assertThatThrownBy(() -> authService.refresh(second.refreshToken()))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void logout_revokesRefreshToken() {
        TokenResponse res = authService.login("alice", PASSWORD, null);
        authService.logout(res.refreshToken());
        assertThatThrownBy(() -> authService.refresh(res.refreshToken()))
                .isInstanceOf(AuthenticationException.class);
    }
}

package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.TokenResponse;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RefreshTokenRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.config.JwtProperties;
import com.erp.platform.security.jwt.JwtService;
import com.erp.platform.security.password.PasswordPolicy;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** ADM-02 / PAR-14: self-service password change keeps every existing password control. */
class AuthChangeOwnPasswordTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final UserBranchRepository userBranches = mock(UserBranchRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final JwtService jwt = mock(JwtService.class);
    private final JwtProperties jwtProps = mock(JwtProperties.class);
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final PasswordPolicy policy = mock(PasswordPolicy.class);
    private final AuditService audit = mock(AuditService.class);

    private AppUser user;
    private AuthServiceImpl auth;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("NEW-HASH");
        auth = new AuthServiceImpl(users, refreshTokens, mock(BranchRepository.class),
                mock(CompanyRepository.class), userBranches, encoder, jwt, jwtProps, attempts,
                mock(PermissionResolver.class), mock(OrganisationRepository.class), policy, audit);
        user = new AppUser("asha@duka", "OLD-HASH", "Asha");
        ReflectionTestUtils.setField(user, "id", 41L);
        user.setMustChangePassword(true);
        when(users.findScopedById(41L)).thenReturn(Optional.of(user));
        when(userBranches.findByUserIdAndIsDefaultTrue(41L)).thenReturn(Optional.empty());
        when(jwt.issueAccessToken(any(), any(), any()))
                .thenReturn(new JwtService.IssuedToken("ACCESS", Instant.now().plusSeconds(900)));
        when(jwtProps.refreshTokenTtlDays()).thenReturn(7);
        RequestContext.set(new RequestContext.Principal(41L, "asha@duka", false, null, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void correctCurrentPassword_changesHash_clearsFlag_revokesSessions_andAudits() {
        when(encoder.matches("Current123", "OLD-HASH")).thenReturn(true);

        TokenResponse res = auth.changeOwnPassword("Current123", "Brand-new-123", "10.0.0.1");

        verify(policy).validate("Brand-new-123");
        assertThat(user.getPasswordHash()).isEqualTo("NEW-HASH");
        assertThat(user.isMustChangePassword()).isFalse();
        verify(refreshTokens).revokeAllForUser(eq(41L), any());
        verify(audit).record(any());
        assertThat(res.accessToken()).isEqualTo("ACCESS");
        assertThat(res.user().mustChangePassword()).isFalse();
    }

    @Test
    void wrongCurrentPassword_countsTowardsLockout_andChangesNothing() {
        when(encoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> auth.changeOwnPassword("guess", "Brand-new-123", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current password is not correct");

        verify(attempts).recordFailure(eq(41L), eq("10.0.0.1"), any());
        assertThat(user.getPasswordHash()).isEqualTo("OLD-HASH");
        verify(refreshTokens, never()).revokeAllForUser(anyLong(), any());
    }

    @Test
    void weakNewPassword_isRejectedByThePolicy() {
        when(encoder.matches("Current123", "OLD-HASH")).thenReturn(true);
        doThrow(new IllegalArgumentException("Password is too common; choose a stronger one."))
                .when(policy).validate("password1");

        assertThatThrownBy(() -> auth.changeOwnPassword("Current123", "password1", "10.0.0.1"))
                .hasMessageContaining("too common");
        assertThat(user.getPasswordHash()).isEqualTo("OLD-HASH");
    }

    @Test
    void sameAsCurrent_isRejected() {
        when(encoder.matches("Current123", "OLD-HASH")).thenReturn(true);

        assertThatThrownBy(() -> auth.changeOwnPassword("Current123", "Current123", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");
    }
}

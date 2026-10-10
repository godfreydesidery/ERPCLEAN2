package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RefreshTokenRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.auth.AuthenticationException;
import com.erp.platform.security.config.JwtProperties;
import com.erp.platform.security.jwt.JwtService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LUI-16: a bare sign-in name resolves to {@code name@alias} only on a single-customer
 * installation; a full name or a legacy bare name always matches exactly. Driven through the
 * wrong-password path so the test pins WHICH account the password was checked against without
 * standing up token issuance.
 */
class AuthLoginNameResolutionTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final OrganisationRepository organisations = mock(OrganisationRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private AuthServiceImpl auth;

    private final AppUser cashier = withId(new AppUser("asha@duka", "HASH-ASHA", "Asha"), 41L);

    @BeforeEach
    void setUp() {
        auth = new AuthServiceImpl(users, mock(RefreshTokenRepository.class),
                mock(BranchRepository.class), mock(CompanyRepository.class),
                mock(UserBranchRepository.class), encoder, mock(JwtService.class),
                mock(JwtProperties.class), attempts, mock(PermissionResolver.class), organisations,
                mock(com.erp.platform.security.password.PasswordPolicy.class),
                mock(com.erp.platform.audit.AuditService.class));
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        when(users.findByUsername("asha@duka")).thenReturn(Optional.of(cashier));
        when(encoder.matches(anyString(), any())).thenReturn(false);
    }

    @Test
    void bareNameOnASingleCustomerInstallationResolvesToTheSuffixedAccount() {
        when(organisations.findCustomerAliases()).thenReturn(List.of("duka"));

        assertThatThrownBy(() -> auth.login("Asha", "wrong", "127.0.0.1"))
                .isInstanceOf(AuthenticationException.class);

        // The password was checked against Asha's account, and the failure counted against it.
        verify(encoder).matches("wrong", "HASH-ASHA");
        verify(attempts).recordFailure(eq(41L), any(), any());
        verify(attempts, never()).recordUnknownUserFailure(any(), any(), any());
    }

    @Test
    void bareNameOnASharedInstallationIsNotResolved() {
        when(organisations.findCustomerAliases()).thenReturn(List.of("duka", "acme"));

        assertThatThrownBy(() -> auth.login("asha", "wrong", "127.0.0.1"))
                .isInstanceOf(AuthenticationException.class);

        verify(encoder, never()).matches("wrong", "HASH-ASHA");
        verify(attempts).recordUnknownUserFailure(eq("asha"), any(), any());
    }

    @Test
    void aNameWithAnAtSignIsNeverRewritten() {
        when(organisations.findCustomerAliases()).thenReturn(List.of("duka"));

        assertThatThrownBy(() -> auth.login("asha@other", "wrong", "127.0.0.1"))
                .isInstanceOf(AuthenticationException.class);

        verify(encoder, never()).matches("wrong", "HASH-ASHA");
    }

    @Test
    void anExactBareAccountStillWinsOverTheSuffixedOne() {
        AppUser operator = withId(new AppUser("asha", "HASH-OPERATOR", "Asha (platform)"), 7L);
        when(users.findByUsername("asha")).thenReturn(Optional.of(operator));
        when(organisations.findCustomerAliases()).thenReturn(List.of("duka"));

        assertThatThrownBy(() -> auth.login("asha", "wrong", "127.0.0.1"))
                .isInstanceOf(AuthenticationException.class);

        verify(encoder).matches("wrong", "HASH-OPERATOR");
        verify(encoder, never()).matches("wrong", "HASH-ASHA");
    }

    private static AppUser withId(AppUser user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}

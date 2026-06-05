package com.erp.modules.iam.service;

import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.RefreshTokenRepository;
import com.erp.platform.security.config.SecurityProperties;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Security bookkeeping that must COMMIT even when the surrounding login/refresh throws. A failed
 * login or a detected token-reuse throws {@code AuthenticationException}, which would roll back the
 * caller's transaction and discard the lockout increment / chain-revoke — so the protection would
 * never take effect. These {@code REQUIRES_NEW} methods persist independently of that rollback.
 */
@Service
public class LoginAttemptService {

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final SecurityProperties props;

    public LoginAttemptService(AppUserRepository users,
                               RefreshTokenRepository refreshTokens,
                               SecurityProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.props = props;
    }

    /** Revoke every active refresh token for a user (token-reuse / theft response). Commits. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllTokens(Long userId, Instant now) {
        refreshTokens.revokeAllForUser(userId, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long userId, Instant now) {
        users.findById(userId).ifPresent(user -> {
            user.registerFailedLogin(
                    props.lockout().maxFailedAttempts(), props.lockout().lockMinutes(), now);
            users.save(user);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId, Instant now) {
        users.findById(userId).ifPresent(user -> {
            user.registerSuccessfulLogin(now);
            users.save(user);
        });
    }
}

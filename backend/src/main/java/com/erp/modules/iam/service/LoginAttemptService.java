package com.erp.modules.iam.service;

import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.RefreshTokenRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.config.SecurityProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Security bookkeeping that must COMMIT even when the surrounding login/refresh throws. A failed
 * login or a detected token-reuse throws {@code AuthenticationException}, which would roll back the
 * caller's transaction and discard the lockout increment / chain-revoke — so the protection would
 * never take effect. These {@code REQUIRES_NEW} methods persist independently of that rollback.
 *
 * <p>Audit emits join the REQUIRES_NEW tx via {@code AuditService.record(event, actorId, ip)}
 * (propagation MANDATORY joins whatever is active — ADR-0004 D-3).
 */
@Service
public class LoginAttemptService {

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final SecurityProperties props;
    private final AuditService audit;

    public LoginAttemptService(AppUserRepository users,
                               RefreshTokenRepository refreshTokens,
                               SecurityProperties props,
                               AuditService audit) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.props = props;
        this.audit = audit;
    }

    /** Revoke every active refresh token for a user (token-reuse / theft response). Commits. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllTokens(Long userId, Instant now) {
        refreshTokens.revokeAllForUser(userId, now);
    }

    /**
     * Record a failed login attempt for a known user. Emits LOGIN.FAIL and, when the lockout
     * threshold is reached, also ACCOUNT.LOCKED. All in this REQUIRES_NEW tx.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long userId, String ip, Instant now) {
        users.findById(userId).ifPresent(user -> {
            user.registerFailedLogin(
                    props.lockout().maxFailedAttempts(), props.lockout().lockMinutes(), now);
            users.save(user);

            audit.record(
                    AuditEvent.of(AuditActions.LOGIN_FAIL, "app_user", userId, user.getUid()),
                    userId, ip);

            if (user.isLocked(now)) {
                audit.record(
                        AuditEvent.of(AuditActions.ACCOUNT_LOCKED, "app_user", userId, user.getUid())
                                  .detail(Map.of(
                                          "failedCount", user.getFailedLoginCount(),
                                          "lockedUntil", user.getLockedUntil().toString())),
                        userId, ip);
            }
        });
    }

    /**
     * Record a successful login for a known user. Emits LOGIN.SUCCESS in this REQUIRES_NEW tx.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId, String ip, Instant now) {
        users.findById(userId).ifPresent(user -> {
            user.registerSuccessfulLogin(now);
            users.save(user);

            audit.record(
                    AuditEvent.of(AuditActions.LOGIN_SUCCESS, "app_user", userId, user.getUid()),
                    userId, ip);
        });
    }

    /**
     * Audit-only path for unknown-username login failures (ADR-0004 D-3). There is no user to lock
     * out; actor is NULL; the attempted username goes into detail (never as a column). Runs in its
     * own REQUIRES_NEW tx so AuditService.record(MANDATORY) has a tx to join.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnknownUserFailure(String usernameAttempted, String ip, Instant now) {
        audit.record(
                AuditEvent.of(AuditActions.LOGIN_FAIL, "app_user")
                          .detail(Map.of("usernameAttempted",
                                  usernameAttempted != null ? usernameAttempted : "")),
                null, ip);
    }
}

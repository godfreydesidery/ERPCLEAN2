package com.erp.modules.iam.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AppUser} lockout transitions (persona UAT I8).
 *
 * <p>No {@code AuthServiceImplTest} unit test exists in this codebase (only the Testcontainers
 * {@code AuthServiceImplIT}) — this class is the direct, DB-free unit test for the fixed entity
 * behaviour and is what should be run in its place for a fast PR gate.
 */
class AppUserTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;

    @Test
    void registerFailedLogin_belowThreshold_incrementsCountOnly() {
        AppUser user = new AppUser("bob", "hash", "Bob");
        Instant now = Instant.now();

        user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, now);
        user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, now);

        assertThat(user.getFailedLoginCount()).isEqualTo(2);
        assertThat(user.isLocked(now)).isFalse();
    }

    @Test
    void registerFailedLogin_reachesThreshold_locksAccount() {
        AppUser user = new AppUser("bob", "hash", "Bob");
        Instant now = Instant.now();

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, now);
        }

        assertThat(user.getFailedLoginCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(user.isLocked(now)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(now.plusSeconds(LOCK_MINUTES * 60L));
    }

    @Test
    void registerFailedLogin_afterLockWindowLapses_startsAFreshCountOfOne() {
        // Before the fix: failedLoginCount was only ever reset by a SUCCESSFUL login or an explicit
        // unlock() — never when the lock window itself expired. So the very first failed attempt
        // after expiry immediately re-locked the account, because the count was still pinned at (or
        // above) maxAttempts from before the lock.
        AppUser user = new AppUser("bob", "hash", "Bob");
        Instant t0 = Instant.now();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, t0);
        }
        assertThat(user.isLocked(t0)).isTrue();

        Instant afterLockExpiry = user.getLockedUntil().plusSeconds(1);

        user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, afterLockExpiry);

        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        assertThat(user.isLocked(afterLockExpiry)).isFalse();
    }

    @Test
    void registerFailedLogin_afterLockWindowLapses_stillLocksAgainOnceThresholdReReached() {
        // The reset must not turn off the lockout control entirely — enough fresh failures after
        // expiry must still lock the account again.
        AppUser user = new AppUser("bob", "hash", "Bob");
        Instant t0 = Instant.now();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, t0);
        }
        Instant afterLockExpiry = user.getLockedUntil().plusSeconds(1);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, afterLockExpiry);
        }

        assertThat(user.getFailedLoginCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(user.isLocked(afterLockExpiry)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(afterLockExpiry.plusSeconds(LOCK_MINUTES * 60L));
    }

    @Test
    void registerSuccessfulLogin_resetsCounterAndLock() {
        AppUser user = new AppUser("bob", "hash", "Bob");
        Instant now = Instant.now();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, now);
        }

        user.registerSuccessfulLogin(now);

        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.isLocked(now)).isFalse();
        assertThat(user.getLastLoginAt()).isEqualTo(now);
    }

    @Test
    void unlock_resetsCounterAndLockRegardlessOfWindow() {
        AppUser user = new AppUser("bob", "hash", "Bob");
        Instant now = Instant.now();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.registerFailedLogin(MAX_ATTEMPTS, LOCK_MINUTES, now);
        }

        user.unlock();

        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.isLocked(now)).isFalse();
    }
}

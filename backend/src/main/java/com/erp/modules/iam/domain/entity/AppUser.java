package com.erp.modules.iam.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A login identity (DATA-MODEL §1.4). Org-wide; {@code username} is org-unique and stored
 * lowercased. {@code isRoot} marks the super-admin that transcends company/branch scoping.
 *
 * <p>Lockout state lives on the entity ({@code failedLoginCount}, {@code lockedUntil}) with the
 * transition logic here so it can't be applied inconsistently across call sites.
 */
@Getter
@Entity
@Table(name = "app_user")
public class AppUser extends UidEntity {

    @Column(name = "username", nullable = false, length = 80)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 160)
    @Setter
    private String displayName;

    @Column(name = "email", length = 160)
    @Setter
    private String email;

    @Column(name = "phone", length = 40)
    @Setter
    private String phone;

    @Column(name = "is_root", nullable = false)
    @Setter
    private boolean root = false;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    protected AppUser() {
        // JPA
    }

    public AppUser(String username, String passwordHash, String displayName) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.passwordChangedAt = Instant.now();
    }

    // --- lockout behaviour (one home for the transitions) ---

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Record a failed attempt; lock for {@code lockMinutes} once {@code maxAttempts} is reached. */
    public void registerFailedLogin(int maxAttempts, int lockMinutes, Instant now) {
        this.failedLoginCount += 1;
        if (this.failedLoginCount >= maxAttempts) {
            this.lockedUntil = now.plusSeconds(lockMinutes * 60L);
        }
    }

    /** Successful login resets the counter and lock and stamps last-login. */
    public void registerSuccessfulLogin(Instant now) {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
    }

    public void unlock() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isActive() {
        return status == MasterStatus.ACTIVE;
    }

    public void changePassword(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.passwordChangedAt = now;
    }
}

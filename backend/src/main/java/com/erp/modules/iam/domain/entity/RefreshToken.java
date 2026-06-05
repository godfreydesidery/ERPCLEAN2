package com.erp.modules.iam.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A hashed, single-use, rotated refresh token (DATA-MODEL §1.10). The raw token is never stored —
 * only its SHA-256 hash. Not a {@code UidEntity}: it is looked up by {@code tokenHash}, never
 * addressed externally by uid.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "client_company_id")
    private Long clientCompanyId;

    @Column(name = "client_branch_id")
    private Long clientBranchId;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** Usable = not rotated, not revoked, not expired. */
    public boolean isActive(Instant now) {
        return rotatedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    /** Already used (rotated) or revoked — presenting it again signals reuse. */
    public boolean isConsumed() {
        return rotatedAt != null || revokedAt != null;
    }

    public void markRotated(Long replacedById, Instant now) {
        this.rotatedAt = now;
        this.replacedById = replacedById;
    }

    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setClientScope(Long companyId, Long branchId) {
        this.clientCompanyId = companyId;
        this.clientBranchId = branchId;
    }
}

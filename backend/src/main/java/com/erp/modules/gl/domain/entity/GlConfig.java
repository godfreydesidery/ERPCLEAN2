package com.erp.modules.gl.domain.entity;

import com.erp.modules.gl.domain.enums.GlConfigKey;
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
 * Per-company account-mapping entry: one posting role → one CoA account (ADR-0013 D-2d/D-5).
 * Key/value shape; extensible by adding new GlConfigKey values additively.
 */
@Getter
@Entity
@Table(name = "gl_configs")
public class GlConfig extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "config_key", nullable = false, length = 40)
    private GlConfigKey configKey;

    /** FK → chart_of_accounts.id; must be active when the auto-poster reads it (BR-GL-10). */
    @Column(name = "account_id", nullable = false)
    @Setter
    private Long accountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected GlConfig() {
        // JPA
    }

    public GlConfig(Long companyId, GlConfigKey configKey, Long accountId, Long createdBy) {
        this.companyId = companyId;
        this.configKey = configKey;
        this.accountId = accountId;
        this.createdBy = createdBy;
    }
}

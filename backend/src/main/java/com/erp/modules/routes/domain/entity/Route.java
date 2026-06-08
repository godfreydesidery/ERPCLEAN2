package com.erp.modules.routes.domain.entity;

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
 * Route master — a per-company named physical sales area / zone (ADR-0012 D-2, FR-ROUTE-01).
 *
 * <p>Extends {@link UidEntity} (id + uid + @Version optimistic lock).
 * company_id is scalar + immutable — tenant scope (BR-ROUTE-01).
 * code is system-assigned ({@code ROUTE-####}), immutable after creation (BR-ROUTE-06).
 * Geography is free-text only in v1 (BR-ROUTE-08): one {@code location_identifier} column.
 */
@Getter
@Entity
@Table(name = "routes")
public class Route extends UidEntity {

    /** FK → companies.id; tenant scope; never updated (BR-ROUTE-01). */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** System-assigned code, e.g. {@code ROUTE-0001}. Never user-editable (BR-ROUTE-06). */
    @Column(name = "code", nullable = false, updatable = false, length = 20)
    private String code;

    /** Human-visible route name (FR-ROUTE-01). */
    @Column(name = "name", nullable = false, length = 200)
    @Setter
    private String name;

    /**
     * Free-text area label / description of the area the route covers (FR-ROUTE-03, BR-ROUTE-08).
     * Not bound to any geo-hierarchy in v1.
     */
    @Column(name = "location_identifier", length = 500)
    @Setter
    private String locationIdentifier;

    /** Lifecycle status; ACTIVE | INACTIVE | ARCHIVED (FR-ROUTE-02). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

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

    protected Route() {
        // JPA
    }

    public Route(Long companyId, String code, String name, Long createdBy) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.createdBy = createdBy;
    }
}

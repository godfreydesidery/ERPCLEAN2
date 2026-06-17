package com.erp.modules.parties.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Common columns for all four party-contact child tables (ADR-0040 D-3).
 *
 * <p>Extends {@link UidEntity} (id + uid + @Version). Each concrete subclass adds only the
 * owner FK column (customer_id / supplier_id / agent_id / other_party_id).
 *
 * <p>No Lombok on the entity itself beyond @Getter/@Setter per PROJECT-CONVENTIONS.
 */
@Getter
@MappedSuperclass
public abstract class PartyContactBase extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "contact_person", length = 160)
    @Setter
    private String contactPerson;

    @Column(name = "phone", length = 40)
    @Setter
    private String phone;

    @Column(name = "email", length = 160)
    @Setter
    private String email;

    @Column(name = "is_primary", nullable = false)
    @Setter
    private boolean isPrimary = false;

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

    protected PartyContactBase() {
        // JPA
    }

    protected PartyContactBase(Long companyId, Long createdBy) {
        this.companyId = companyId;
        this.createdBy = createdBy;
    }
}

package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.AddressRole;
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
 * Common columns for all four party-address child tables (ADR-0040 D-3).
 *
 * <p>Extends {@link UidEntity} (id + uid + @Version). Each concrete subclass adds only the
 * owner FK column (customer_id / supplier_id / agent_id / other_party_id).
 */
@Getter
@MappedSuperclass
public abstract class PartyAddressBase extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "address_role", nullable = false, length = 20)
    @Setter
    private AddressRole addressRole;

    @Column(name = "is_default", nullable = false)
    @Setter
    private boolean isDefault = false;

    @Column(name = "country", length = 2)
    @Setter
    private String country;

    @Column(name = "address_line1", length = 200)
    @Setter
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    @Setter
    private String addressLine2;

    @Column(name = "city", length = 100)
    @Setter
    private String city;

    @Column(name = "region", length = 80)
    @Setter
    private String region;

    @Column(name = "postal_code", length = 20)
    @Setter
    private String postalCode;

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

    protected PartyAddressBase() {
        // JPA
    }

    protected PartyAddressBase(Long companyId, AddressRole addressRole, Long createdBy) {
        this.companyId = companyId;
        this.addressRole = addressRole;
        this.createdBy = createdBy;
    }
}

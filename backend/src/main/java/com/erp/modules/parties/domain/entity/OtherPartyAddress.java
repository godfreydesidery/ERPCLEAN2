package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.AddressRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Address sub-record for an other-party (ADR-0040 D-3).
 * At most one row per (other_party, address_role) may have {@code is_default = true}
 * (partial-unique index on other_party_id, address_role WHERE is_default).
 */
@Getter
@Entity
@Table(name = "other_party_addresses")
public class OtherPartyAddress extends PartyAddressBase {

    @Column(name = "other_party_id", nullable = false, updatable = false)
    private Long otherPartyId;

    protected OtherPartyAddress() {
        // JPA
    }

    public OtherPartyAddress(Long companyId, Long otherPartyId, AddressRole addressRole, Long createdBy) {
        super(companyId, addressRole, createdBy);
        this.otherPartyId = otherPartyId;
    }
}

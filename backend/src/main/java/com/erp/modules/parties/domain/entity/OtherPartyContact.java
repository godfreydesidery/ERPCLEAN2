package com.erp.modules.parties.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Contact sub-record for an other-party (ADR-0040 D-3).
 * At most one row per other_party may have {@code is_primary = true} (partial-unique index).
 */
@Getter
@Entity
@Table(name = "other_party_contacts")
public class OtherPartyContact extends PartyContactBase {

    @Column(name = "other_party_id", nullable = false, updatable = false)
    private Long otherPartyId;

    protected OtherPartyContact() {
        // JPA
    }

    public OtherPartyContact(Long companyId, Long otherPartyId, Long createdBy) {
        super(companyId, createdBy);
        this.otherPartyId = otherPartyId;
    }
}

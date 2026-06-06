package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.PartyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Generic/misc party record (FR-PARTY-04, ADR-0006 D-2).
 * {@code otherKind} is a free-text label (no enum) so operators are never blocked.
 */
@Getter
@Entity
@Table(name = "other_parties")
public class OtherParty extends PartyBase {

    /** Free-text kind label, e.g. {@code "LANDLORD"}, {@code "TRANSPORTER"}. */
    @Column(name = "other_kind", length = 40)
    @Setter
    private String otherKind;

    protected OtherParty() {
        // JPA
    }

    public OtherParty(Long companyId, String code, PartyType partyType, String displayName,
                      Long createdBy) {
        super(companyId, code, partyType, displayName, createdBy);
    }
}

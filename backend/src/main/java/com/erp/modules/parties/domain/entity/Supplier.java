package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Supplier master (FR-PARTY-02, ADR-0006 D-2).
 * Sub-kind: {@link SupplierKind#GOODS} or {@link SupplierKind#SERVICE}.
 */
@Getter
@Entity
@Table(name = "suppliers")
public class Supplier extends PartyBase {

    @Enumerated(EnumType.STRING)
    @Column(name = "supplier_kind", nullable = false, length = 20)
    @Setter
    private SupplierKind supplierKind;

    protected Supplier() {
        // JPA
    }

    public Supplier(Long companyId, String code, PartyType partyType, String displayName,
                    SupplierKind supplierKind, Long createdBy) {
        super(companyId, code, partyType, displayName, createdBy);
        this.supplierKind = supplierKind;
    }
}

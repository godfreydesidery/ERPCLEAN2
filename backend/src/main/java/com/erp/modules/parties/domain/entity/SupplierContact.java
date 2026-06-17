package com.erp.modules.parties.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Contact sub-record for a supplier (ADR-0040 D-3).
 * At most one row per supplier may have {@code is_primary = true} (partial-unique index).
 */
@Getter
@Entity
@Table(name = "supplier_contacts")
public class SupplierContact extends PartyContactBase {

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    protected SupplierContact() {
        // JPA
    }

    public SupplierContact(Long companyId, Long supplierId, Long createdBy) {
        super(companyId, createdBy);
        this.supplierId = supplierId;
    }
}

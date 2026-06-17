package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.AddressRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Address sub-record for a supplier (ADR-0040 D-3).
 * At most one row per (supplier, address_role) may have {@code is_default = true}
 * (partial-unique index on supplier_id, address_role WHERE is_default).
 */
@Getter
@Entity
@Table(name = "supplier_addresses")
public class SupplierAddress extends PartyAddressBase {

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    protected SupplierAddress() {
        // JPA
    }

    public SupplierAddress(Long companyId, Long supplierId, AddressRole addressRole, Long createdBy) {
        super(companyId, addressRole, createdBy);
        this.supplierId = supplierId;
    }
}

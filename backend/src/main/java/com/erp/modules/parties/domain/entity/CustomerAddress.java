package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.AddressRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Address sub-record for a customer (ADR-0040 D-3).
 * At most one row per (customer, address_role) may have {@code is_default = true}
 * (partial-unique index on customer_id, address_role WHERE is_default).
 */
@Getter
@Entity
@Table(name = "customer_addresses")
public class CustomerAddress extends PartyAddressBase {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    protected CustomerAddress() {
        // JPA
    }

    public CustomerAddress(Long companyId, Long customerId, AddressRole addressRole, Long createdBy) {
        super(companyId, addressRole, createdBy);
        this.customerId = customerId;
    }
}

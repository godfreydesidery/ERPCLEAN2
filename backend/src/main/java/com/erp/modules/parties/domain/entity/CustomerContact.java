package com.erp.modules.parties.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Contact sub-record for a customer (ADR-0040 D-3).
 * At most one row per customer may have {@code is_primary = true} (partial-unique index).
 */
@Getter
@Entity
@Table(name = "customer_contacts")
public class CustomerContact extends PartyContactBase {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    protected CustomerContact() {
        // JPA
    }

    public CustomerContact(Long companyId, Long customerId, Long createdBy) {
        super(companyId, createdBy);
        this.customerId = customerId;
    }
}

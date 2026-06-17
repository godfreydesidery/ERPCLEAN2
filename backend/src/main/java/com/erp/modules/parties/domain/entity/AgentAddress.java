package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.AddressRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Address sub-record for an agent (ADR-0040 D-3).
 * At most one row per (agent, address_role) may have {@code is_default = true}
 * (partial-unique index on agent_id, address_role WHERE is_default).
 */
@Getter
@Entity
@Table(name = "agent_addresses")
public class AgentAddress extends PartyAddressBase {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private Long agentId;

    protected AgentAddress() {
        // JPA
    }

    public AgentAddress(Long companyId, Long agentId, AddressRole addressRole, Long createdBy) {
        super(companyId, addressRole, createdBy);
        this.agentId = agentId;
    }
}

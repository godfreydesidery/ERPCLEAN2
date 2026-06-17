package com.erp.modules.parties.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Contact sub-record for an agent (ADR-0040 D-3).
 * At most one row per agent may have {@code is_primary = true} (partial-unique index).
 */
@Getter
@Entity
@Table(name = "agent_contacts")
public class AgentContact extends PartyContactBase {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private Long agentId;

    protected AgentContact() {
        // JPA
    }

    public AgentContact(Long companyId, Long agentId, Long createdBy) {
        super(companyId, createdBy);
        this.agentId = agentId;
    }
}

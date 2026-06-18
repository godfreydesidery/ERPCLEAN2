package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * Sales agent master (FR-PARTY-03, ADR-0006 D-2/D-5).
 *
 * <p>{@code agentKind} is the discriminator: {@link AgentKind#INTERNAL} references an active
 * {@code app_user} via the scalar {@code appUserId} FK; {@link AgentKind#EXTERNAL} is a standalone
 * party with {@code appUserId = null}. The DB CHECK enforces the coupling; the service validates
 * that the referenced user is ACTIVE on create/update (BR-PARTY-10).
 *
 * <p>{@code appUserId} is a <em>scalar Long</em> (not a {@code @ManyToOne} association into IAM)
 * — the same convention as {@code UserBranch.userId} and {@code audit_log.actor_user_id}. This
 * keeps the module boundary clean: the parties module does not import IAM entities.
 */
@Getter
@Entity
@Table(name = "agents")
public class Agent extends PartyBase {

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_kind", nullable = false, length = 20)
    @Setter
    private AgentKind agentKind;

    /**
     * FK → {@code app_users.id}. Non-null iff {@code agentKind = INTERNAL} (enforced by DB CHECK
     * and service guard). Stored as a scalar — no JPA association across the module boundary.
     */
    @Column(name = "app_user_id")
    @Setter
    private Long appUserId;

    // -------------------------------------------------------------------------
    // P2 D5 — performance targets (ADR-0041 D5 Tier-1)
    // -------------------------------------------------------------------------

    /** P2 D5: periodic sales target value (currency implied by company base). */
    @Column(name = "sales_target", precision = 19, scale = 4)
    @Setter
    private BigDecimal salesTarget;

    /** P2 D5: assigned quota value. */
    @Column(name = "quota", precision = 19, scale = 4)
    @Setter
    private BigDecimal quota;

    protected Agent() {
        // JPA
    }

    public Agent(Long companyId, String code, PartyType partyType, String displayName,
                 AgentKind agentKind, Long createdBy) {
        super(companyId, code, partyType, displayName, createdBy);
        this.agentKind = agentKind;
    }
}

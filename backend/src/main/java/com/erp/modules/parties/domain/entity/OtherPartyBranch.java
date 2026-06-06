package com.erp.modules.parties.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Junction: other_party ↔ branch association (ADR-0006 D-4).
 */
@Getter
@Entity
@Table(name = "other_party_branch")
public class OtherPartyBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "other_party_id", nullable = false)
    private OtherParty otherParty;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    protected OtherPartyBranch() {
        // JPA
    }

    public OtherPartyBranch(OtherParty otherParty, Long branchId, Long assignedBy) {
        this.otherParty = otherParty;
        this.branchId = branchId;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }
}

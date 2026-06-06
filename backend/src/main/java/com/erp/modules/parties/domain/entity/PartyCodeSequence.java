package com.erp.modules.parties.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * Per-(company, kind) counter table for application-assigned party codes (ADR-0006 D-7).
 * Allocated under {@code SELECT ... FOR UPDATE} (pessimistic write lock) within the same
 * transaction as the party INSERT — concurrency-safe, gap-free, per-company.
 */
@Getter
@Entity
@Table(name = "party_code_sequence")
public class PartyCodeSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** {@code CUSTOMER} | {@code SUPPLIER} | {@code AGENT} | {@code OTHER} */
    @Column(name = "party_kind", nullable = false, length = 20)
    private String partyKind;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1L;

    @Version
    private Long version;

    protected PartyCodeSequence() {
        // JPA
    }

    public PartyCodeSequence(Long companyId, String partyKind) {
        this.companyId = companyId;
        this.partyKind = partyKind;
    }

    /**
     * Reads the current counter, increments it in-place, and returns the value that was consumed.
     * Must be called inside a {@code SELECT ... FOR UPDATE} lock (pessimistic write).
     */
    public long consumeNext() {
        long consumed = this.nextValue;
        this.nextValue = consumed + 1;
        return consumed;
    }
}

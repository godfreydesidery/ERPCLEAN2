package com.erp.modules.products.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * Generic per-(company, entity_kind) counter table for application-assigned codes (ADR-0007 D-6).
 * Allocated under {@code SELECT ... FOR UPDATE} within the same transaction as the insert —
 * concurrency-safe, gap-free, per-company.
 *
 * <p>Unlike {@code party_code_sequence}, this table has NO CHECK on entity_kind — it is
 * open-ended so every future module can reuse it (PRODUCT, PRICELIST, etc.).
 */
@Getter
@Entity
@Table(name = "code_sequence")
public class CodeSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** e.g. {@code PRODUCT}; no CHECK — intentionally open-ended. */
    @Column(name = "entity_kind", nullable = false, length = 30)
    private String entityKind;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1L;

    @Version
    private Long version;

    protected CodeSequence() {
        // JPA
    }

    public CodeSequence(Long companyId, String entityKind) {
        this.companyId = companyId;
        this.entityKind = entityKind;
    }

    /**
     * Reads the current counter, increments it in-place, and returns the value consumed.
     * Must be called inside a {@code SELECT ... FOR UPDATE} lock (pessimistic write).
     */
    public long consumeNext() {
        long consumed = this.nextValue;
        this.nextValue = consumed + 1;
        return consumed;
    }
}

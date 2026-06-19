package com.erp.modules.sales.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * POS-sale idempotency marker (ADR-0042 D-1).
 *
 * <p>One row per {@code (company_id, idem_key)} where {@code idem_key} is the client's
 * {@code Idempotency-Key} header. The unique constraint {@code uq_pos_sale_idem} is the dedup key.
 * Rows are reserved (insert) BEFORE the invoice is created and stamped with {@code invoice_uid}
 * afterwards, all in the sale's transaction — so a retry returns the original invoice rather than
 * creating a second sale. Read-only from JPA's side; rows are written via a native
 * {@code INSERT ... ON CONFLICT DO NOTHING} reserve + a JPQL stamp (see the repository).
 */
@Getter
@Entity
@Table(name = "pos_sale_idempotency")
public class PosSaleIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "idem_key", nullable = false, length = 80)
    private String idemKey;

    /** The finalised invoice's uid; NULL only in the brief window before the winner stamps it. */
    @Column(name = "invoice_uid", length = 26)
    private String invoiceUid;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PosSaleIdempotency() {
        // JPA
    }
}

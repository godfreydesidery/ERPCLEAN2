package com.erp.modules.purchases.domain.entity;

import com.erp.modules.purchases.domain.enums.RfqResponseStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * RFQ-supplier junction: which suppliers this RFQ targets (ADR-0027 D-4).
 * Singular table name per db-naming-convention (junction tables are singular).
 */
@Getter
@Entity
@Table(name = "rfq_supplier")
public class RfqSupplier extends UidEntity {

    @Column(name = "rfq_id", nullable = false, updatable = false)
    private Long rfqId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "sent_at")
    @Setter
    private Instant sentAt;

    /** P2: when this supplier responded. Nullable. */
    @Column(name = "responded_at")
    @Setter
    private Instant respondedAt;

    /** P2: response tracking status. Nullable. */
    @Enumerated(EnumType.STRING)
    @Column(name = "response_status", length = 20)
    @Setter
    private RfqResponseStatus responseStatus;

    /** P2: scalar link to the supplier_quotes uid this supplier submitted. Nullable. */
    @Column(name = "supplier_quote_uid", length = 26)
    @Setter
    private String supplierQuoteUid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected RfqSupplier() {
        // JPA
    }

    public RfqSupplier(Long rfqId, Long supplierId, Long companyId, Long branchId, Long createdBy) {
        this.rfqId      = rfqId;
        this.supplierId = supplierId;
        this.companyId  = companyId;
        this.branchId   = branchId;
        this.createdBy  = createdBy;
    }
}

package com.erp.modules.purchases.domain.entity;

import com.erp.modules.purchases.domain.enums.RfqStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * Request for Quotation header (ADR-0027 D-4).
 * rfq_number assigned at create (D-10). Awards once (BR-PROC-04).
 */
@Getter
@Entity
@Table(name = "rfqs")
public class Rfq extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "rfq_number", nullable = false, length = 30)
    private String rfqNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private RfqStatus status = RfqStatus.DRAFT;

    @Column(name = "source_requisition_uid", length = 26)
    private String sourceRequisitionUid;

    @Column(name = "response_due_date")
    @Setter
    private LocalDate responseDueDate;

    /** Scalar uid of the winning supplier_quote — set on award (BR-PROC-04). */
    @Column(name = "awarded_quote_uid", length = 26)
    @Setter
    private String awardedQuoteUid;

    /** Scalar uid of the PO generated on award. */
    @Column(name = "awarded_po_uid", length = 26)
    @Setter
    private String awardedPoUid;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "sent_at")
    @Setter
    private Instant sentAt;

    @Column(name = "awarded_at")
    @Setter
    private Instant awardedAt;

    @Column(name = "cancelled_at")
    @Setter
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected Rfq() {
        // JPA
    }

    public Rfq(Long companyId, Long branchId, String rfqNumber,
               String sourceRequisitionUid, LocalDate responseDueDate,
               String notes, Long createdBy) {
        this.companyId            = companyId;
        this.branchId             = branchId;
        this.rfqNumber            = rfqNumber;
        this.sourceRequisitionUid = sourceRequisitionUid;
        this.responseDueDate      = responseDueDate;
        this.notes                = notes;
        this.createdBy            = createdBy;
    }
}

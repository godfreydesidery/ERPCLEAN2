package com.erp.modules.purchases.domain.entity;

import com.erp.modules.purchases.domain.enums.RequisitionPriority;
import com.erp.modules.purchases.domain.enums.RequisitionStatus;
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
 * Purchase Requisition header (ADR-0027 D-3).
 *
 * <p>Lifecycle: DRAFT → SUBMITTED → APPROVED → CONVERTED (+ REJECTED, CANCELLED).
 * requisition_number is NULL while DRAFT, assigned at submit (D-10).
 * approval_request_uid / approval_status are the thin engine-seam scalars (D-6).
 */
@Getter
@Entity
@Table(name = "purchase_requisitions")
public class PurchaseRequisition extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "requisition_number", length = 30)
    @Setter
    private String requisitionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private RequisitionStatus status = RequisitionStatus.DRAFT;

    /** P2: priority/urgency. Nullable. */
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 10)
    @Setter
    private RequisitionPriority priority;

    /** P2: soft-FK suppliers.id — requester's preferred source. Nullable. */
    @Column(name = "preferred_supplier_id")
    @Setter
    private Long preferredSupplierId;

    @Column(name = "required_by_date")
    @Setter
    private LocalDate requiredByDate;

    @Column(name = "cost_centre_code", length = 40)
    @Setter
    private String costCentreCode;

    /** Scalar uid of the approvals-engine request — no FK (D-6). */
    @Column(name = "approval_request_uid", length = 26)
    @Setter
    private String approvalRequestUid;

    /** Cached approval outcome; diagnostic mirror (D-6). */
    @Column(name = "approval_status", length = 20)
    @Setter
    private String approvalStatus;

    /** PURCHASE_ORDER | RFQ — what convert produced (D-3). */
    @Column(name = "converted_to_type", length = 20)
    @Setter
    private String convertedToType;

    @Column(name = "converted_to_uid", length = 26)
    @Setter
    private String convertedToUid;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "submitted_at")
    @Setter
    private Instant submittedAt;

    @Column(name = "approved_at")
    @Setter
    private Instant approvedAt;

    @Column(name = "rejected_at")
    @Setter
    private Instant rejectedAt;

    @Column(name = "converted_at")
    @Setter
    private Instant convertedAt;

    @Column(name = "cancelled_at")
    @Setter
    private Instant cancelledAt;

    @Column(name = "submitted_by")
    @Setter
    private Long submittedBy;

    @Column(name = "approved_by")
    @Setter
    private Long approvedBy;

    @Column(name = "rejected_by")
    @Setter
    private Long rejectedBy;

    @Column(name = "reject_reason", length = 255)
    @Setter
    private String rejectReason;

    @Column(name = "cancel_reason", length = 255)
    @Setter
    private String cancelReason;

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

    protected PurchaseRequisition() {
        // JPA
    }

    public PurchaseRequisition(Long companyId, Long branchId, LocalDate requiredByDate,
                                String costCentreCode, String notes, Long createdBy) {
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.requiredByDate  = requiredByDate;
        this.costCentreCode  = costCentreCode;
        this.notes           = notes;
        this.createdBy       = createdBy;
    }
}

package com.erp.modules.approvals.domain.entity;

import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Runtime approval request — the governance instance for one submitted document (ADR-0022 D-5).
 *
 * <p>Created by {@code ApprovalEngineImpl.submitForApproval}. The request pins everything needed
 * to be self-contained: document reference (opaque type+uid, no FK — D-12), matched policy trace,
 * frozen step snapshot (in {@code approval_request_steps}), submitter, status, and timestamps.
 *
 * <p>{@code document_type} + {@code document_uid} are opaque scalars — the engine never FKs into
 * a consumer table. The {@code uq_approval_request_document} UNIQUE (company_id, document_type,
 * document_uid) provides the idempotency backstop (BR-APR-08).
 *
 * <p>{@code @Version} guards the optimistic-lock step-advance concurrency (NFR-APR-05).
 */
@Getter
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "request_number", nullable = false, length = 30)
    @Setter
    private String requestNumber;

    /** Consumer-owned routing key: {@code PURCHASE_ORDER}, {@code PAYMENT_RUN}, etc. */
    @Column(name = "document_type", nullable = false, length = 60, updatable = false)
    private String documentType;

    /** Opaque reference to the consumer's document — NO FK (D-12). */
    @Column(name = "document_uid", nullable = false, length = 26, updatable = false)
    private String documentUid;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private ApprovalRequestStatus status = ApprovalRequestStatus.PENDING;

    /** P2: denormalised sequence of the currently-open step (derived listing convenience). */
    @Column(name = "current_step_sequence")
    @Setter
    private Integer currentStepSequence;

    /** True iff no policy matched — terminal APPROVED with no steps (BR-APR-09). */
    @Column(name = "auto_approved", nullable = false, updatable = false)
    private boolean autoApproved = false;

    /** Trace back to the matched policy id (nullable when auto_approved). */
    @Column(name = "source_policy_id")
    private Long sourcePolicyId;

    /** Scalar uid trace — survives a policy hard-delete (nullable when auto_approved). */
    @Column(name = "source_policy_uid", length = 26)
    private String sourcePolicyUid;

    /** Human label passed by the consumer for inbox display ("PO to Acme"). */
    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "submitted_by", nullable = false, updatable = false)
    private Long submittedBy;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "resolved_at")
    @Setter
    private Instant resolvedAt;

    /** App_user id of the last decider / recaller / canceller. */
    @Column(name = "resolved_by")
    @Setter
    private Long resolvedBy;

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

    protected ApprovalRequest() {
        // JPA
    }

    /** Constructor for a policy-matched (PENDING) request. */
    public ApprovalRequest(Long companyId,
                           Long branchId,
                           String documentType,
                           String documentUid,
                           BigDecimal amount,
                           String currency,
                           Long sourcePolicyId,
                           String sourcePolicyUid,
                           String summary,
                           Long submittedBy,
                           Long createdBy) {
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.documentType    = documentType;
        this.documentUid     = documentUid;
        this.amount          = amount;
        this.currency        = CurrencyCode.of(currency);
        this.sourcePolicyId  = sourcePolicyId;
        this.sourcePolicyUid = sourcePolicyUid;
        this.summary         = summary;
        this.submittedBy     = submittedBy;
        this.createdBy       = createdBy;
    }

    /** Constructor for an auto-approved (no policy matched) request. Terminal on creation. */
    public ApprovalRequest(Long companyId,
                           Long branchId,
                           String documentType,
                           String documentUid,
                           BigDecimal amount,
                           String currency,
                           String summary,
                           Long submittedBy,
                           Long createdBy,
                           boolean autoApproved) {
        this.companyId    = companyId;
        this.branchId     = branchId;
        this.documentType = documentType;
        this.documentUid  = documentUid;
        this.amount       = amount;
        this.currency     = CurrencyCode.of(currency);
        this.summary      = summary;
        this.submittedBy  = submittedBy;
        this.createdBy    = createdBy;
        this.autoApproved = autoApproved;
        if (autoApproved) {
            this.status     = ApprovalRequestStatus.APPROVED;
            this.resolvedAt = Instant.now();
            this.resolvedBy = submittedBy;
        }
    }
}

package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.OfflineReviewKind;
import com.erp.modules.sales.domain.enums.OfflineReviewStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * One item on the offline review list (ADR-0068 D-8, V114): a posted offline sale that broke a rule
 * the online path would have enforced, or a HELD sale that was not posted at all. Nothing here blocks
 * the books; a manager acknowledges each item.
 */
@Getter
@Entity
@Table(name = "pos_offline_reviews")
public class PosOfflineReview extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "pos_till_id", nullable = false, updatable = false)
    private Long posTillId;

    @Column(name = "pos_session_id", updatable = false)
    private Long posSessionId;

    @Column(name = "sales_invoice_id", updatable = false)
    private Long salesInvoiceId;

    @Column(name = "offline_number", updatable = false, length = 40)
    private String offlineNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 24)
    private OfflineReviewKind kind;

    @Column(name = "detail", length = 500, updatable = false)
    private String detail;

    @Column(name = "held_request", updatable = false, columnDefinition = "TEXT")
    private String heldRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OfflineReviewStatus status = OfflineReviewStatus.OPEN;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledge_note", length = 500)
    private String acknowledgeNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected PosOfflineReview() {
        // JPA
    }

    private PosOfflineReview(Long companyId, Long branchId, Long posTillId, Long posSessionId,
                             Long salesInvoiceId, String offlineNumber, OfflineReviewKind kind,
                             String detail, String heldRequest, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.posTillId = posTillId;
        this.posSessionId = posSessionId;
        this.salesInvoiceId = salesInvoiceId;
        this.offlineNumber = offlineNumber;
        this.kind = kind;
        this.detail = truncate(detail);
        this.heldRequest = heldRequest;
        this.createdBy = createdBy;
    }

    /** A posted offline sale that broke a rule the online path enforces (never HELD). */
    public static PosOfflineReview flagged(Long companyId, Long branchId, Long posTillId,
                                           Long posSessionId, Long salesInvoiceId,
                                           String offlineNumber, OfflineReviewKind kind,
                                           String detail, Long createdBy) {
        if (kind == OfflineReviewKind.HELD || salesInvoiceId == null) {
            throw new IllegalArgumentException("A flagged review item needs the posted invoice.");
        }
        return new PosOfflineReview(companyId, branchId, posTillId, posSessionId, salesInvoiceId,
                offlineNumber, kind, detail, null, createdBy);
    }

    /** An offline sale that was NOT posted, kept exactly as the till rang it. */
    public static PosOfflineReview held(Long companyId, Long branchId, Long posTillId,
                                        Long posSessionId, String offlineNumber, String detail,
                                        String heldRequest, Long createdBy) {
        if (heldRequest == null || heldRequest.isBlank()) {
            throw new IllegalArgumentException("A held offline sale must keep the sale as rung.");
        }
        return new PosOfflineReview(companyId, branchId, posTillId, posSessionId, null,
                offlineNumber, OfflineReviewKind.HELD, detail, heldRequest, createdBy);
    }

    /** A manager has looked at this item. Acknowledging twice is refused, not silently repeated. */
    public void acknowledge(Long managerId, String note) {
        if (status == OfflineReviewStatus.ACKNOWLEDGED) {
            throw new IllegalStateException("This item has already been acknowledged.");
        }
        this.status = OfflineReviewStatus.ACKNOWLEDGED;
        this.acknowledgedBy = managerId;
        this.acknowledgedAt = Instant.now();
        this.acknowledgeNote = truncate(note);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}

package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.OfflinePeriodReason;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * One stretch of time a till sold offline (ADR-0068 D-1/D-8, V114): who switched it, why, and how
 * many sales it took. Uploaded by the till at sync, idempotently on its own {@code clientKey}.
 */
@Getter
@Entity
@Table(name = "pos_offline_periods")
public class PosOfflinePeriod extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "pos_till_id", nullable = false, updatable = false)
    private Long posTillId;

    @Column(name = "pos_session_id")
    private Long posSessionId;

    @Column(name = "client_key", nullable = false, updatable = false, length = 64)
    private String clientKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 16)
    private OfflinePeriodReason reason;

    @Column(name = "switched_by", nullable = false, updatable = false)
    private Long switchedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "sales_count", nullable = false)
    private int salesCount;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected PosOfflinePeriod() {
        // JPA
    }

    public PosOfflinePeriod(Long companyId, Long branchId, Long posTillId, Long posSessionId,
                            String clientKey, OfflinePeriodReason reason, Long switchedBy,
                            Instant startedAt, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.posTillId = posTillId;
        this.posSessionId = posSessionId;
        this.clientKey = clientKey;
        this.reason = reason;
        this.switchedBy = switchedBy;
        this.startedAt = startedAt;
        this.createdBy = createdBy;
    }

    /**
     * Takes the till's latest view of the period: a re-upload may close it or count more sales.
     * The end is set once and never before the start; the count never goes down.
     */
    public void applyUpdate(Instant endedAt, int salesCount, Long actorId) {
        if (salesCount < 0) {
            throw new IllegalArgumentException("The number of offline sales cannot be negative.");
        }
        if (endedAt != null && this.endedAt == null) {
            if (endedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("An offline period cannot end before it started.");
            }
            this.endedAt = endedAt;
        }
        this.salesCount = Math.max(this.salesCount, salesCount);
        this.syncedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }
}

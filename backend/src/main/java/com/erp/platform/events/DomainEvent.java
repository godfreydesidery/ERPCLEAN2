package com.erp.platform.events;

import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Outbox row: a PENDING event written atomically with the producing business change (ADR-0009 D-2).
 *
 * <p>Does NOT extend {@link com.erp.platform.common.domain.UidEntity}: the outbox has no optimistic
 * lock via UidEntity (the {@code @Version} is declared here directly — the field name matches the
 * column and serves as the multi-instance {@code SKIP LOCKED} upgrade seam, D-4/D-scaling).
 * Audit columns ({@code created_by}, {@code updated_by}) are intentionally absent — the outbox is
 * plumbing; {@code occurred_at} + {@code dispatched_at} + {@code attempt_count} are its lifecycle
 * (ADR-0009 D-9).
 *
 * <p>company_id / branch_id carry the originating tenant scope so consumers can establish context
 * from the event alone without any JWT / RequestContext (D-5).
 */
@Getter
@Entity
@Table(name = "domain_events")
public class DomainEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ULID — the stable external event identifier; consumer dedup key. */
    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    /** Canonical type string (MODULE.EVENT form), e.g. {@code SALE.FINALISED}. Routing key. */
    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    /** Producing aggregate kind, e.g. {@code SALES_INVOICE}. For diagnostics / replay grouping. */
    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    /** Internal id of the producing aggregate (no FK — cross-module scalar, ADR-0009 D-2). */
    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    /** Stable uid of the producing aggregate. Echoed in the payload. */
    @Column(name = "aggregate_uid", nullable = false, length = 26)
    private String aggregateUid;

    /** Originating company scope; consumers post effects under this company. */
    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** Originating branch scope; nullable for future company-level events. */
    @Column(name = "branch_id")
    private Long branchId;

    /** Event body — JSONB; serialised from the producer's payload record by {@link OutboxPublisherImpl}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DomainEventStatus status = DomainEventStatus.PENDING;

    /** When the business event occurred (= producing TX time). Poller ordering key. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Set when status transitions to DISPATCHED. */
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    /** Incremented on each failed delivery attempt (ADR-0009 D-4). */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /** Last failure message (truncated, user-safe). Null until a failure occurs. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** Optimistic lock — the seam for the multi-instance SKIP LOCKED upgrade (D-4/D-scaling). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected DomainEvent() {
        // JPA
    }

    /** Constructor used by {@link OutboxPublisherImpl}. */
    public DomainEvent(String eventType,
                       String aggregateType,
                       Long aggregateId,
                       String aggregateUid,
                       Long companyId,
                       Long branchId,
                       String payloadJson) {
        this.eventType     = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId   = aggregateId;
        this.aggregateUid  = aggregateUid;
        this.companyId     = companyId;
        this.branchId      = branchId;
        this.payload       = payloadJson;
        this.occurredAt    = Instant.now();
    }

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    // Mutators used only by the dispatcher — no public setters on the entity fields.

    void markDispatched(Instant at) {
        this.status       = DomainEventStatus.DISPATCHED;
        this.dispatchedAt = at;
    }

    void recordFailure(String error, int maxAttempts) {
        this.attemptCount++;
        this.lastError = truncate(error, 1000);
        if (this.attemptCount >= maxAttempts) {
            this.status = DomainEventStatus.FAILED;
        }
        // else stays PENDING — retried next poll
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

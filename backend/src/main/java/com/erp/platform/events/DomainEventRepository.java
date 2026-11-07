package com.erp.platform.events;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence port for {@link DomainEvent} (ADR-0009 D-2/D-4).
 *
 * <p>The claim query reads PENDING rows oldest-first bounded by {@code attempt_count < maxAttempts}
 * so exhausted rows are never re-fetched by the poller. The partial index
 * {@code ix_domain_events_pending} (status='PENDING') makes this an index-only scan regardless of
 * how many DISPATCHED rows have accumulated.
 *
 * <p><strong>Multi-instance safety (ADR-0009 D-4/D-scaling — now built):</strong> the poller reads a
 * batch un-locked, then {@link DomainEventDispatcher#dispatchOne(Long)} re-claims each row with
 * {@link #claimPendingForUpdate(Long)} — a {@code FOR UPDATE SKIP LOCKED} native query — so two
 * pollers running on two containers never dispatch the same event (the second instance's claim skips
 * the row another instance has locked). This is the same row-lock discipline {@code code_sequence}
 * uses (ADR-0007 D-6).
 */
public interface DomainEventRepository extends JpaRepository<DomainEvent, Long> {

    /**
     * Claim the next batch of dispatchable PENDING events, oldest-first.
     * {@code attemptCap} = the configured {@code erp.outbox.max-attempts} value.
     */
    @Query("""
            SELECT e FROM DomainEvent e
            WHERE e.status = 'PENDING'
              AND e.attemptCount < :attemptCap
            ORDER BY e.occurredAt ASC
            """)
    List<DomainEvent> findPendingBatch(@Param("attemptCap") int attemptCap, Pageable pageable);

    /**
     * Ids of the next batch of dispatchable PENDING events, interleaved ACROSS companies
     * (MULTITENANCY-PLAN §14, outbox fairness).
     *
     * <p>{@link #findPendingBatch} is strictly oldest-first, so on a shared instance one tenant's
     * burst — a bulk import, a month-end posting run — queues every other tenant's sales behind it
     * for as long as the burst lasts. This takes each company's oldest event first, then each
     * company's second, and so on: a quiet tenant's next sale is never more than one round behind.
     *
     * <p>Order WITHIN a company is unchanged (occurred_at, then id), which is the only order any
     * handler relies on — the stock and GL handlers read their own company's state. On an install
     * with a single company the result is identical to {@link #findPendingBatch}.
     *
     * <p>Cost: the window runs over the PENDING set only, which the partial
     * {@code ix_domain_events_pending} index bounds; that set is near-empty in steady state.
     *
     * <p>Unscoped by design: the dispatcher is the platform's own worker and serves every tenant.
     */
    @Query(value = """
            SELECT id FROM (
                SELECT id, occurred_at,
                       ROW_NUMBER() OVER (PARTITION BY company_id ORDER BY occurred_at, id) AS rn
                FROM domain_events
                WHERE status = 'PENDING' AND attempt_count < :attemptCap
            ) pending
            ORDER BY rn, occurred_at, id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findFairPendingBatchIds(@Param("attemptCap") int attemptCap,
                                       @Param("batchSize") int batchSize);

    /**
     * Deletes up to {@code batchSize} DISPATCHED events older than {@code cutoff}
     * ({@link OutboxRetention}). Never touches PENDING or FAILED rows: those are work still owed
     * and the evidence of work that failed. Oldest ids first, so repeated calls walk forward.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
            DELETE FROM domain_events
            WHERE id IN (
                SELECT id FROM domain_events
                WHERE status = 'DISPATCHED' AND dispatched_at < :cutoff
                ORDER BY id
                LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteDispatchedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Multi-instance-safe single-row claim (ADR-0009 D-4/D-scaling). Locks the row {@code FOR UPDATE
     * SKIP LOCKED} so a concurrent poller on another instance skips it (returns empty) rather than
     * blocking or double-dispatching. Returns empty when the row is no longer PENDING or is held by
     * another instance. Under one container the lock is uncontended — correct and cheap either way.
     */
    @Query(value = """
            SELECT * FROM domain_events
            WHERE id = :id AND status = 'PENDING'
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<DomainEvent> claimPendingForUpdate(@Param("id") Long id);

    /**
     * Whether an aggregate still has an event of this type in one of the given statuses — e.g. "has
     * this goods receipt's STOCK.RECEIVED not been applied yet?". Served by
     * {@code ix_domain_events_aggregate (aggregate_type, aggregate_id)}.
     */
    boolean existsByAggregateTypeAndAggregateIdAndEventTypeAndStatusIn(
            String aggregateType, Long aggregateId, String eventType,
            java.util.Collection<DomainEventStatus> statuses);

    /** Count of events in a given status — backs the {@code erp.outbox.failed} gauge (D-8 / #2). */
    long countByStatus(DomainEventStatus status);

    /**
     * {@code occurred_at} of the oldest PENDING event (the dispatch backlog head), or {@code null}
     * when nothing is pending — backs the {@code erp.outbox.pending.oldest.age.seconds} gauge.
     * Uses the partial {@code ix_domain_events_pending} index.
     */
    @Query("SELECT MIN(e.occurredAt) FROM DomainEvent e WHERE e.status = 'PENDING'")
    Instant oldestPendingOccurredAt();

    /**
     * Failed events grouped by company — backs the per-tenant gauge (ADR-0062 P8-4).
     *
     * <p>Grouped by COMPANY rather than organisation because {@code domain_events} carries
     * {@code company_id} and not {@code organisation_id}; the caller maps company to tenant through
     * {@code CompanyTenantIndex}, which is a cached in-memory lookup. That avoids a schema change
     * for a metrics concern.
     *
     * <p>Returns {@code [companyId, count]} pairs. A null company (system-originated events) is
     * included and the caller tags it explicitly rather than dropping it — an event nobody owns is
     * exactly the kind that goes unnoticed.
     */
    @Query("SELECT e.companyId, COUNT(e) FROM DomainEvent e WHERE e.status = :status "
            + "GROUP BY e.companyId")
    java.util.List<Object[]> countByStatusGroupedByCompany(
            @Param("status") DomainEventStatus status);
}

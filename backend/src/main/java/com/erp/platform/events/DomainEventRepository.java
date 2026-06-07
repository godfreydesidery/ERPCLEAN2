package com.erp.platform.events;

import java.util.List;
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
 * <p>The multi-instance upgrade (D-4/D-scaling) swaps this JPQL finder for a
 * {@code SELECT … FOR UPDATE SKIP LOCKED} native query — a one-method additive change, not built yet.
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
}

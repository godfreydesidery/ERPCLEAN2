package com.erp.platform.events;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence port for {@link ProcessedEvent} (ADR-0009 D-6a).
 * The {@code uq_processed_event (consumer, event_uid)} DB constraint is the dedup backstop;
 * {@link IdempotencyGuard} uses {@link #existsByConsumerAndEventUid} for a cheap read-before-write
 * check.
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByConsumerAndEventUid(String consumer, String eventUid);
}

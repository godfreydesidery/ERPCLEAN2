package com.erp.platform.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Consumer-side idempotency dedup marker (ADR-0009 D-6a).
 *
 * <p>Written by {@link IdempotencyGuard} in the <em>same transaction</em> as the consumer's effect.
 * The unique constraint {@code uq_processed_event (consumer, event_uid)} is the dedup key — a
 * duplicate insert (redelivery) triggers a constraint violation that the guard catches and turns into
 * a "skip" signal.
 *
 * <p>{@code consumer} is the handler's logical name (e.g. {@code STOCK.SALE_ISSUE}) so two
 * consumers of the same event each maintain independent progress markers.
 */
@Getter
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The handler's logical name — scopes dedup independently per consumer. */
    @Column(name = "consumer", nullable = false, length = 80)
    private String consumer;

    /** The {@code domain_events.uid} that was processed. */
    @Column(name = "event_uid", nullable = false, length = 26)
    private String eventUid;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedEvent() {
        // JPA
    }

    public ProcessedEvent(String consumer, String eventUid) {
        this.consumer    = consumer;
        this.eventUid    = eventUid;
        this.processedAt = Instant.now();
    }
}

package com.erp.platform.events;

/**
 * Consumer contract for the transactional outbox (ADR-0009 D-5).
 *
 * <p>Implement this interface as a Spring bean in the consuming module (e.g.
 * {@code com.erp.modules.stock}) and it will be automatically discovered and invoked by
 * {@link DomainEventDispatcher} for every PENDING event whose {@code event_type} matches
 * {@link #eventType()}.
 *
 * <p><strong>Idempotency obligation (ADR-0009 D-5/D-6):</strong> at-least-once delivery means
 * {@code handle} may be called more than once for the same event. The implementation MUST dedupe —
 * either via {@link IdempotencyGuard} (the generic platform mechanism) or via its own natural key.
 * A redelivered event that has already been processed must no-op without error.
 *
 * <p>{@code handle} is invoked inside the dispatcher's per-event transaction. The consumer's effect
 * (e.g. a stock movement write) and the dedup marker commit together — either both happen or neither
 * does. Throw to signal a transient failure; the dispatcher will retry up to the configured cap.
 */
public interface DomainEventHandler {

    /**
     * The {@code event_type} this handler consumes.
     * Return a {@link DomainEventType} constant, e.g. {@code DomainEventType.SALE_FINALISED}.
     */
    String eventType();

    /**
     * Apply the event's effect. Must be idempotent. Invoked inside the dispatcher's per-event TX.
     * Throw any unchecked exception to signal failure; the dispatcher handles retry/cap/FAILED.
     */
    void handle(DomainEvent event);
}

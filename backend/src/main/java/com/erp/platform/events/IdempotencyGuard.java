package com.erp.platform.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform helper for consumer-side idempotency (ADR-0009 D-6a).
 *
 * <p>A {@link DomainEventHandler} calls {@link #alreadyProcessed} at the start of its
 * {@code handle} method; if it returns {@code true} the handler must return without applying any
 * effect (redelivery no-op). If it returns {@code false}, the handler applies its effect and then
 * calls {@link #markProcessed} in the <em>same transaction</em>.
 *
 * <p>Both methods use {@code Propagation.MANDATORY} — they must join the dispatcher's per-event
 * transaction so that the marker and the effect commit or roll back together (the atomicity that
 * makes at-least-once delivery safe, ADR-0009 D-6a).
 */
@Component
public class IdempotencyGuard {

    private final ProcessedEventRepository repository;

    public IdempotencyGuard(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns {@code true} if this (consumer, eventUid) pair has already been recorded,
     * meaning the event was already processed by this consumer — the handler must no-op.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean alreadyProcessed(String consumer, String eventUid) {
        return repository.existsByConsumerAndEventUid(consumer, eventUid);
    }

    /**
     * Records that this (consumer, eventUid) has been processed. Must be called in the same TX
     * as the consumer's effect. A duplicate insert (racing redelivery) will propagate as a
     * constraint violation — the dispatcher catches it and treats the event as already handled.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markProcessed(String consumer, String eventUid) {
        repository.save(new ProcessedEvent(consumer, eventUid));
    }
}

package com.erp.platform.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-process {@code @Scheduled} poller that claims PENDING {@link DomainEvent} rows and dispatches
 * them to registered {@link DomainEventHandler} beans (ADR-0009 D-4).
 *
 * <p><strong>Delivery semantics:</strong> at-least-once. A crash between "handler succeeded" and
 * "row marked DISPATCHED" causes redelivery on the next poll; every handler must be idempotent
 * (ADR-0009 D-5/D-6; use {@link IdempotencyGuard}).
 *
 * <p><strong>Failure handling (D-4/D-8):</strong> a throwing handler increments
 * {@code attempt_count}; at {@code max-attempts} the row is parked {@code FAILED} and skipped by
 * future polls. A poison event never blocks events behind it (D-7).
 *
 * <p><strong>No-handler policy (D-4 flagged item — recommended default):</strong> if no handler is
 * registered for an event type the event is marked {@code DISPATCHED} immediately and logged at
 * DEBUG. This avoids piling up PENDING rows for event types whose consumers are absent in a partial
 * deployment; it surfaces a misconfiguration via the DEBUG log.
 *
 * <p><strong>Single-instance safety:</strong> correct under one container — there is only one
 * poller, so the read→process→update loop cannot double-dispatch. The {@code @Version} on
 * {@link DomainEvent} is the seam for the multi-instance {@code SELECT … FOR UPDATE SKIP LOCKED}
 * upgrade (not built yet — QA runs one container, D-4/D-scaling).
 *
 * <p>{@code fixedDelay} ensures a slow batch never overlaps itself.
 */
@Component
public class DomainEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventDispatcher.class);

    /** Default batch size per poll — bounded to avoid long-running transaction locks. */
    private static final int BATCH_SIZE = 100;

    private final DomainEventRepository repository;
    private final Map<String, List<DomainEventHandler>> handlersByType;

    @Value("${erp.outbox.max-attempts:5}")
    private int maxAttempts;

    DomainEventDispatcher(DomainEventRepository repository,
                          List<DomainEventHandler> handlers) {
        this.repository      = repository;
        // Group handlers by their declared event type at construction time (eager — no per-poll map rebuild).
        this.handlersByType  = handlers.stream()
                .collect(Collectors.groupingBy(DomainEventHandler::eventType));
    }

    /**
     * Poll PENDING events oldest-first and dispatch each in its own per-event transaction.
     * fixedDelayString → delay between the end of one poll and the start of the next (no overlap).
     */
    @Scheduled(fixedDelayString = "${erp.outbox.poll-interval-ms:1000}")
    void poll() {
        List<DomainEvent> batch = repository.findPendingBatch(
                maxAttempts, PageRequest.of(0, BATCH_SIZE));
        for (DomainEvent event : batch) {
            dispatchOne(event.getId());
        }
    }

    /**
     * Dispatch a single event in its own transaction so each event's success/failure commits
     * independently — a later poison event does not roll back already-applied ones (D-4).
     * The event is re-fetched by id inside this transaction to avoid stale-state from the batch read.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchOne(Long eventId) {
        DomainEvent event = repository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != DomainEventStatus.PENDING) {
            return; // already dispatched by a concurrent runner (future SKIP LOCKED scenario)
        }

        List<DomainEventHandler> handlers = handlersByType.getOrDefault(event.getEventType(), List.of());

        if (handlers.isEmpty()) {
            // No registered handler — mark DISPATCHED and log (D-4 recommended default).
            log.debug("No handler registered for event_type='{}', uid='{}' — marking DISPATCHED",
                    event.getEventType(), event.getUid());
            event.markDispatched(Instant.now());
            repository.save(event);
            return;
        }

        try {
            for (DomainEventHandler handler : handlers) {
                handler.handle(event);
            }
            event.markDispatched(Instant.now());
            repository.save(event);
            log.debug("Dispatched event uid='{}' type='{}'", event.getUid(), event.getEventType());
        } catch (Exception ex) {
            event.recordFailure(ex.getMessage(), maxAttempts);
            repository.save(event);
            log.warn("Failed to dispatch event uid='{}' type='{}' attempt={}: {}",
                    event.getUid(), event.getEventType(), event.getAttemptCount(), ex.getMessage());
        }
    }
}

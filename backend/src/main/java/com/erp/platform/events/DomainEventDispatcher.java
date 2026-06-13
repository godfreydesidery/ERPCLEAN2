package com.erp.platform.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    /**
     * Self-reference (via provider to break the construction cycle) so {@link #poll()} invokes
     * {@link #dispatchOne(Long)} THROUGH the Spring proxy. A plain {@code this.dispatchOne(...)}
     * self-invocation bypasses the proxy, so its {@code @Transactional(REQUIRES_NEW)} never engages —
     * which left handlers' {@code MANDATORY} posting service with "no existing transaction" and the
     * whole sale→stock / receipt→stock loop silently failing at runtime (unit tests masked it by
     * calling dispatchOne directly on the proxy bean).
     */
    private final ObjectProvider<DomainEventDispatcher> self;

    // D-5 (ADR-0038): Micrometer metrics — dispatch timer + failure/retry counter.
    // The dispatch timer surfaces end-to-end latency per event type; the poison counter makes
    // the retry loop that was invisible (#20d) observable before it exhausts max-attempts.
    private final Timer dispatchTimer;
    private final Counter failureCounter;

    @Value("${erp.outbox.max-attempts:5}")
    private int maxAttempts;

    DomainEventDispatcher(DomainEventRepository repository,
                          List<DomainEventHandler> handlers,
                          ObjectProvider<DomainEventDispatcher> self,
                          MeterRegistry meterRegistry) {
        this.repository      = repository;
        this.self            = self;
        // Group handlers by their declared event type at construction time (eager — no per-poll map rebuild).
        this.handlersByType  = handlers.stream()
                .collect(Collectors.groupingBy(DomainEventHandler::eventType));
        // D-5: register metrics once at construction — cheap and thread-safe.
        this.dispatchTimer   = Timer.builder("erp.outbox.dispatch")
                .description("Time taken to dispatch a single domain event to all its handlers")
                .register(meterRegistry);
        this.failureCounter  = Counter.builder("erp.outbox.dispatch.failures")
                .description("Number of domain event dispatch failures / retries (poison-event signal)")
                .register(meterRegistry);
    }

    /**
     * Poll PENDING events oldest-first and dispatch each in its own per-event transaction.
     * fixedDelayString → delay between the end of one poll and the start of the next (no overlap).
     * Dispatch goes through the proxy ({@link #self}) so the per-event {@code REQUIRES_NEW} TX opens.
     */
    @Scheduled(fixedDelayString = "${erp.outbox.poll-interval-ms:1000}")
    void poll() {
        List<DomainEvent> batch = repository.findPendingBatch(
                maxAttempts, PageRequest.of(0, BATCH_SIZE));
        DomainEventDispatcher proxy = self.getObject();
        for (DomainEvent event : batch) {
            proxy.dispatchOne(event.getId());
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

        // D-5: time the full handler chain for this event. Tags by event type for per-type breakdowns.
        long startNs = System.nanoTime();
        try {
            for (DomainEventHandler handler : handlers) {
                handler.handle(event);
            }
            event.markDispatched(Instant.now());
            repository.save(event);
            dispatchTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
            log.debug("Dispatched event uid='{}' type='{}'", event.getUid(), event.getEventType());
        } catch (Exception ex) {
            event.recordFailure(ex.getMessage(), maxAttempts);
            repository.save(event);
            // D-5: increment failure counter — makes the poison-event retry loop (#20d) visible in metrics.
            failureCounter.increment();
            log.warn("Failed to dispatch event uid='{}' type='{}' attempt={}: {}",
                    event.getUid(), event.getEventType(), event.getAttemptCount(), ex.getMessage());
        }
    }
}

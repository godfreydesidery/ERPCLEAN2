package com.erp.platform.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Operational gauges for the transactional outbox (ADR-0009 flagged item #2 — FAILED-event
 * visibility). Without these a poison event that has exhausted its retries and parked in
 * {@code FAILED} (e.g. a stock deduction that never applied) sits unnoticed.
 *
 * <ul>
 *   <li>{@code erp.outbox.failed} — events parked in FAILED (require manual replay). This is the
 *       actionable alert signal; the existing {@code erp.outbox.dispatch.failures} <em>counter</em>
 *       counts failed <em>attempts</em> (a flapping event inflates it), which is not the same as
 *       "how many events are stuck".</li>
 *   <li>{@code erp.outbox.pending.oldest.age.seconds} — age of the oldest PENDING event; rises when
 *       the dispatcher falls behind or is wedged.</li>
 * </ul>
 *
 * <p>A scheduled refresh updates two atomics the gauges read, so each Prometheus scrape is a cheap
 * memory read rather than a DB query, and DB load is bounded by the refresh interval (both counts
 * are served by the partial {@code ix_domain_events_*} indexes). Gated by the same
 * {@code erp.outbox.scheduling-enabled} flag as the poller — the refresh does not run in ITs.
 *
 * <p>Alerting (e.g. {@code erp_outbox_failed > 0 for 5m}) is wired in the monitoring stack, not here.
 */
@Component
class OutboxMetrics {

    private final DomainEventRepository repository;
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0);

    OutboxMetrics(DomainEventRepository repository, MeterRegistry registry) {
        this.repository = repository;
        Gauge.builder("erp.outbox.failed", failedCount, AtomicLong::get)
                .description("Domain events parked in FAILED status — require manual replay")
                .register(registry);
        Gauge.builder("erp.outbox.pending.oldest.age.seconds", oldestPendingAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("Age of the oldest PENDING domain event — dispatch backlog signal")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${erp.outbox.metrics-refresh-ms:30000}")
    void refresh() {
        failedCount.set(repository.countByStatus(DomainEventStatus.FAILED));
        Instant oldest = repository.oldestPendingOccurredAt();
        oldestPendingAgeSeconds.set(
                oldest == null ? 0L : Math.max(0L, Duration.between(oldest, Instant.now()).getSeconds()));
    }
}

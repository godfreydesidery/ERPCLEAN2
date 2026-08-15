package com.erp.platform.events;

import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import com.erp.platform.security.CompanyTenantIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
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
    private final CompanyTenantIndex companyTenants;
    private final AtomicLong failedCount = new AtomicLong(0);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0);

    /**
     * Per-tenant failed counts (ADR-0062 P8-4).
     *
     * <p>The two gauges above are estate-wide totals, which answer "is anything stuck" but not
     * "whose". At one organisation those are the same question; with several they are not, and an
     * outbox backlog belonging to one customer would be invisible inside a global number that looks
     * unremarkable.
     *
     * <p>A {@link MultiGauge} rather than a fixed gauge because the tag set is not known at startup —
     * tenants are provisioned at runtime. Cardinality is bounded by the number of organisations,
     * which is small by construction.
     */
    private final MultiGauge failedByTenant;

    OutboxMetrics(DomainEventRepository repository, CompanyTenantIndex companyTenants,
                  MeterRegistry registry) {
        this.repository = repository;
        this.companyTenants = companyTenants;
        this.failedByTenant = MultiGauge.builder("erp.outbox.failed.by.tenant")
                .description("Domain events parked in FAILED status, per owning organisation")
                .register(registry);
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
        refreshPerTenantFailures();
        Instant oldest = repository.oldestPendingOccurredAt();
        oldestPendingAgeSeconds.set(
                oldest == null ? 0L : Math.max(0L, Duration.between(oldest, Instant.now()).getSeconds()));
    }

    /**
     * Rolls the per-company failed counts up to the owning organisation.
     *
     * <p>An event with no company is tagged {@code unattributed} rather than dropped: an event
     * nobody owns is precisely the kind that goes unnoticed, and a silently discarded row would make
     * the per-tenant totals disagree with {@code erp.outbox.failed} for no visible reason.
     */
    private void refreshPerTenantFailures() {
        Map<String, Long> byTenant = new HashMap<>();
        for (Object[] row : repository.countByStatusGroupedByCompany(DomainEventStatus.FAILED)) {
            Long companyId = (Long) row[0];
            long count = ((Number) row[1]).longValue();
            Long organisationId = companyId == null ? null : companyTenants.organisationOf(companyId);
            String tag = organisationId == null ? "unattributed" : String.valueOf(organisationId);
            byTenant.merge(tag, count, Long::sum);
        }
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        byTenant.forEach((tenant, count) ->
                rows.add(MultiGauge.Row.of(Tags.of("organisation", tenant), count)));
        failedByTenant.register(rows, true);
    }
}

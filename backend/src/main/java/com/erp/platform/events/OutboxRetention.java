package com.erp.platform.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes old DISPATCHED outbox events (MULTITENANCY-PLAN §14, outbox purge).
 *
 * <h2>Off unless configured</h2>
 *
 * {@code erp.outbox.retention-days} defaults to {@code 0}, which disables the job entirely, so every
 * existing installation keeps every event exactly as it does today. The shared multi-tenant server
 * sets it (env {@code ERP_OUTBOX_RETENTION_DAYS}), where one {@code domain_events} table carries
 * every tenant's history and would otherwise grow without bound.
 *
 * <h2>What it never deletes</h2>
 *
 * PENDING rows (work still owed) and FAILED rows (the evidence an operator needs to repair a posting
 * that did not happen). Only DISPATCHED rows whose {@code dispatched_at} is older than the retention
 * window. Nothing holds a foreign key to {@code domain_events}: {@code stock_movements.source_event_uid}
 * is a scalar reference by design (V7), and {@code ReceiptVoidStockGuard} only asks about PENDING and
 * FAILED events. What a purge does lose is the payload of an old event — which is why the floor below
 * exists.
 *
 * <h2>How it deletes</h2>
 *
 * In small batches, each in its own short transaction with a lock and statement timeout, so a purge
 * can never hold the table against the dispatcher or a till. A run stops after a bounded number of
 * batches and continues on the next run.
 */
@Component
class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

    /** The shortest window accepted. A smaller configured value is raised to this, with a warning. */
    static final int MINIMUM_RETENTION_DAYS = 30;
    static final int BATCH_SIZE = 1_000;
    static final int MAX_BATCHES_PER_RUN = 50;

    private final DomainEventRepository repository;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter purged;
    private final int retentionDays;

    @org.springframework.beans.factory.annotation.Autowired
    OutboxRetention(DomainEventRepository repository,
                    TransactionTemplate tx,
                    JdbcTemplate jdbc,
                    MeterRegistry meterRegistry,
                    @Value("${erp.outbox.retention-days:0}") int retentionDays) {
        this(repository, tx, jdbc, meterRegistry, retentionDays, Clock.systemUTC());
    }

    OutboxRetention(DomainEventRepository repository, TransactionTemplate tx, JdbcTemplate jdbc,
                    MeterRegistry meterRegistry, int retentionDays, Clock clock) {
        this.repository = repository;
        this.tx = tx;
        this.jdbc = jdbc;
        this.clock = clock;
        this.purged = Counter.builder("erp.outbox.purged")
                .description("DISPATCHED outbox events deleted by the retention job")
                .register(meterRegistry);
        if (retentionDays > 0 && retentionDays < MINIMUM_RETENTION_DAYS) {
            log.warn("erp.outbox.retention-days={} is below the minimum; using {} days.",
                    retentionDays, MINIMUM_RETENTION_DAYS);
            this.retentionDays = MINIMUM_RETENTION_DAYS;
        } else {
            this.retentionDays = Math.max(0, retentionDays);
        }
        if (this.retentionDays > 0) {
            log.info("Outbox retention on: DISPATCHED events older than {} days are deleted.",
                    this.retentionDays);
        }
    }

    /** Hourly by default; the first run waits ten minutes so it never competes with start-up. */
    @Scheduled(initialDelayString = "${erp.outbox.purge-initial-delay-ms:600000}",
            fixedDelayString = "${erp.outbox.purge-interval-ms:3600000}")
    void purge() {
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                Integer deleted = tx.execute(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '5s'");
                    jdbc.execute("SET LOCAL statement_timeout = '60s'");
                    return repository.deleteDispatchedBefore(cutoff, BATCH_SIZE);
                });
                int n = deleted == null ? 0 : deleted;
                total += n;
                if (n < BATCH_SIZE) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            // A held lock or a slow statement: stop this run, keep what was deleted, try next hour.
            log.warn("Outbox retention run stopped after {} deletions: {}", total, e.getMessage());
        }
        if (total > 0) {
            purged.increment(total);
            log.info("Outbox retention: deleted {} DISPATCHED event(s) dispatched before {}.",
                    total, cutoff);
        }
    }

    int retentionDays() {
        return retentionDays;
    }
}

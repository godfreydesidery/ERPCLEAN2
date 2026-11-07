package com.erp.platform.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox fairness across tenants and the retention purge (MULTITENANCY-PLAN §14), against real
 * Postgres — both are native SQL that no mock can check.
 */
class OutboxFairnessAndRetentionIT extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T08:00:00Z");

    @Autowired private DomainEventRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private IamTestData testData;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;

    private Long busy;
    private Long quiet;
    private int seq;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        jdbc.update("DELETE FROM domain_events");
        Organisation orgA = organisations.save(new Organisation("Fairness Busy Org"));
        Organisation orgB = organisations.save(new Organisation("Fairness Quiet Org"));
        busy = companies.save(new Company(orgA, "FBUSY", "Busy Co")).getId();
        quiet = companies.save(new Company(orgB, "FQUIET", "Quiet Co")).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM domain_events");
        testData.clearAll();
    }

    @Test
    void aBusyTenantsBacklogDoesNotHoldBackAQuietTenant() {
        // 150 events from the busy tenant, all OLDER than the quiet tenant's one event. Oldest-first
        // with a batch of 100 would never reach the quiet one on this poll.
        for (int i = 0; i < 150; i++) {
            insert(busy, "PENDING", T0.plusSeconds(i), null);
        }
        Long quietEvent = insert(quiet, "PENDING", T0.plusSeconds(1_000), null);

        List<Long> batch = repository.findFairPendingBatchIds(5, 100);

        assertThat(batch).hasSize(100);
        assertThat(batch.subList(0, 2)).contains(quietEvent);
    }

    @Test
    void orderWithinOneCompanyIsUnchanged() {
        Long first = insert(busy, "PENDING", T0, null);
        Long second = insert(busy, "PENDING", T0.plusSeconds(1), null);
        Long third = insert(busy, "PENDING", T0.plusSeconds(2), null);

        assertThat(repository.findFairPendingBatchIds(5, 100))
                .containsExactly(first, second, third);
    }

    @Test
    void exhaustedAndNonPendingEventsAreNotPicked() {
        insert(busy, "DISPATCHED", T0, T0);
        insert(busy, "FAILED", T0, null);
        Long exhausted = insert(busy, "PENDING", T0, null);
        jdbc.update("UPDATE domain_events SET attempt_count = 5 WHERE id = ?", exhausted);

        assertThat(repository.findFairPendingBatchIds(5, 100)).isEmpty();
    }

    @Test
    void purgeDeletesOnlyOldDispatchedEvents() {
        Instant now = T0.plus(Duration.ofDays(200));
        Long oldDispatched = insert(busy, "DISPATCHED", T0, T0);
        Long recentDispatched = insert(quiet, "DISPATCHED", now.minus(Duration.ofDays(10)),
                now.minus(Duration.ofDays(10)));
        Long oldPending = insert(busy, "PENDING", T0, null);
        Long oldFailed = insert(quiet, "FAILED", T0, null);

        retention(90, now).purge();

        assertThat(ids()).doesNotContain(oldDispatched)
                .contains(recentDispatched, oldPending, oldFailed);
    }

    @Test
    void purgeIsOffByDefault() {
        Long oldDispatched = insert(busy, "DISPATCHED", T0, T0);

        retention(0, T0.plus(Duration.ofDays(1_000))).purge();

        assertThat(ids()).contains(oldDispatched);
    }

    @Test
    void purgeWalksThroughMoreThanOneBatch() {
        for (int i = 0; i < OutboxRetention.BATCH_SIZE + 5; i++) {
            insert(busy, "DISPATCHED", T0, T0);
        }

        retention(90, T0.plus(Duration.ofDays(200))).purge();

        assertThat(ids()).isEmpty();
    }

    private OutboxRetention retention(int days, Instant now) {
        return new OutboxRetention(repository, tx, jdbc, new SimpleMeterRegistry(), days,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private List<Long> ids() {
        return jdbc.queryForList("SELECT id FROM domain_events", Long.class);
    }

    private Long insert(Long companyId, String status, Instant occurredAt, Instant dispatchedAt) {
        seq++;
        String uid = String.format("OUTBOXFAIR%016d", seq);
        return jdbc.queryForObject("""
                INSERT INTO domain_events (uid, event_type, aggregate_type, aggregate_id,
                    aggregate_uid, company_id, payload, status, occurred_at, dispatched_at)
                VALUES (?, 'TEST.FAIR', 'TEST_AGG', ?, ?, ?, '{}'::jsonb, ?, ?, ?)
                RETURNING id
                """, Long.class, uid, (long) seq, uid, companyId, status,
                java.sql.Timestamp.from(occurredAt),
                dispatchedAt == null ? null : java.sql.Timestamp.from(dispatchedAt));
    }
}

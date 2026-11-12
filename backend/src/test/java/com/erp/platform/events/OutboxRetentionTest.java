package com.erp.platform.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The configuration rules of {@link OutboxRetention}; the SQL is covered by its IT. */
class OutboxRetentionTest {

    private final DomainEventRepository repository = mock(DomainEventRepository.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private OutboxRetention withDays(int days) {
        return new OutboxRetention(repository, tx, mock(JdbcTemplate.class),
                new SimpleMeterRegistry(), days);
    }

    @Test
    void zeroMeansOffAndTouchesNothing() {
        OutboxRetention off = withDays(0);
        off.purge();
        assertThat(off.retentionDays()).isZero();
        verifyNoInteractions(repository, tx);
    }

    @Test
    void negativeIsTreatedAsOff() {
        assertThat(withDays(-5).retentionDays()).isZero();
    }

    @Test
    void aWindowBelowTheFloorIsRaisedToIt() {
        assertThat(withDays(3).retentionDays()).isEqualTo(OutboxRetention.MINIMUM_RETENTION_DAYS);
    }

    @Test
    void aWindowAboveTheFloorIsKept() {
        assertThat(withDays(120).retentionDays()).isEqualTo(120);
    }
}

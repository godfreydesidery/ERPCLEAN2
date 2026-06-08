package com.erp.platform.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's {@code @Scheduled} machinery for the outbox poller (ADR-0009 D-4).
 * Placed here rather than on {@code ErpApplication} so scheduling is owned by the platform
 * infrastructure that needs it, not the root bootstrap class.
 *
 * <p>Gated by {@code erp.outbox.scheduling-enabled} (default true). Integration tests set it to
 * {@code false} so the background poller does not race the test's manual {@code dispatchOne(...)}
 * calls for the same event row (which caused intermittent optimistic-lock failures). Prod/dev keep
 * the poller on.
 */
@Configuration
@ConditionalOnProperty(name = "erp.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
class OutboxSchedulingConfig {
}

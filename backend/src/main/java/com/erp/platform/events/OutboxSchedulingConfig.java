package com.erp.platform.events;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's {@code @Scheduled} machinery for the outbox poller (ADR-0009 D-4).
 * Placed here rather than on {@code ErpApplication} so scheduling is owned by the platform
 * infrastructure that needs it, not the root bootstrap class.
 */
@Configuration
@EnableScheduling
class OutboxSchedulingConfig {
}

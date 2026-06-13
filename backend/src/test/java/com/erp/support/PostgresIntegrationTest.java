package com.erp.support;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.InputStream;

/**
 * Base for integration tests that exercise the real schema (Flyway) and real queries against
 * PostgreSQL 15 — never a mock or embedded DB (qa-engineer's standing rule).
 *
 * <p><b>Singleton-container pattern.</b> One container is started once (in the static initializer)
 * and SHARED across every IT class for the whole JVM. We deliberately do NOT use {@code @Container}
 * /{@code @Testcontainers} (which manage a per-class lifecycle): on this Docker Desktop/Windows
 * setup, starting a fresh container per IT class races and yields "Connection to localhost:&lt;port&gt;
 * refused". A single long-lived container is reliable, and faster. It is never stopped explicitly —
 * the JVM exit and Docker reclaim it (Ryuk is disabled via testcontainers.properties for the same
 * port-mapping reason).
 *
 * <p>Reuse is intentionally OFF: while the schema is pre-stable we EDIT the V1 baseline (project
 * rule), which changes its Flyway checksum. A fresh container each run re-applies the current
 * baseline; a reused container would keep the old schema and fail Flyway validation.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("erp_test")
                .withUsername("erp")
                .withPassword("erp");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // Disable the background outbox poller in ITs — tests drive dispatch deterministically via
        // DomainEventDispatcher.dispatchOne(...); the @Scheduled poller would otherwise race the
        // test for the same event row (intermittent optimistic-lock failures). ADR-0009 D-4.
        registry.add("erp.outbox.scheduling-enabled", () -> "false");
    }

    /**
     * No-op JavaMailSender stub. The notifications module requires a JavaMailSender bean but SMTP
     * is not configured in the IT environment. EmailSender degrades gracefully at runtime (ADR-0024
     * D-6); this stub satisfies the dependency graph so the context loads without an SMTP server.
     */
    @TestConfiguration
    static class MailStubConfig {

        @Bean
        JavaMailSender noOpMailSender() {
            return new JavaMailSender() {
                @Override public MimeMessage createMimeMessage() {
                    return new MimeMessage((Session) null);
                }
                @Override public MimeMessage createMimeMessage(InputStream is) {
                    return createMimeMessage();
                }
                @Override public void send(MimeMessage... mimeMessages) throws MailException { }
                @Override public void send(MimeMessagePreparator... preparators) throws MailException { }
                @Override public void send(SimpleMailMessage... simpleMessages) throws MailException { }
            };
        }
    }
}

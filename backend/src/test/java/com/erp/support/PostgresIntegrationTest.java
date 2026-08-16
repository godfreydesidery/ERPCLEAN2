package com.erp.support;

import com.erp.platform.security.CompanyTenantIndex;
import com.erp.platform.security.PermissionResolver;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
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

    // -------------------------------------------------------------------------
    // Process-lifetime caches keyed on database ids
    // -------------------------------------------------------------------------
    //
    // Both of these memoise an answer under a numeric primary key, and both are singletons in the
    // ONE Spring context every IT class shares (failsafe runs a single reused fork). The fixtures,
    // meanwhile, reset the database with `TRUNCATE ... RESTART IDENTITY` (IamTestData.clearAll, and
    // NotificationsIT / PurchaseRequisitionTenantIsolationIT do their own), which RE-ISSUES ids from
    // 1 in every test method. So `company 2` in one method and `company 2` in the next are different
    // rows, frequently under different organisations and different users.
    //
    // Neither cache is wrong in production — identity sequences there are monotonic and never
    // re-issue an id, and companies.organisation_id is write-once. The staleness is manufactured
    // purely by the harness, and it produced two CI-only failures that looked like security
    // regressions and were not:
    //
    //   * TwoOrganisationIsolationIT :156 / :169 — CompanyTenantIndex served a previous class's
    //     {companyId → organisationId}, so ScopeGuard's tenant check saw "same tenant" and root was
    //     allowed into another tenant's company. The guard itself is correct (ScopeGuard.java:671
    //     runs the tenant check BEFORE the `root ||` short-circuit, and :696-698 applies it inside
    //     canActOn's root branch too).
    //   * SetPasswordAuthorityCeilingIT :101 — PermissionResolver served the caller's permission set
    //     from a PREVIOUS method (30 s TTL, key "userId:companyId:branchId", all recycled), so the
    //     ADR-0059 ceiling compared against authority the caller no longer had. Only reproducible on
    //     a machine fast enough to run two methods inside the TTL, which is why CI failed and local
    //     runs passed.
    //
    // Whatever resets the ids must also drop the memos keyed on them. Running here rather than in
    // IamTestData covers ITs that truncate without it, and a superclass @BeforeEach is guaranteed to
    // run before the subclass's fixture is built.
    @Autowired private PermissionResolver  permissionResolverCacheSeam;
    @Autowired private CompanyTenantIndex  companyTenantIndexCacheSeam;

    @BeforeEach
    protected void dropIdKeyedCaches() {
        permissionResolverCacheSeam.invalidate();
        companyTenantIndexCacheSeam.clear();
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

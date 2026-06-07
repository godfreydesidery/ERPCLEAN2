package com.erp.platform.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for {@link OutboxPublisher} / {@link OutboxPublisherImpl} against real Postgres
 * (ADR-0009 D-3).
 *
 * <p>Covers:
 * <ol>
 *   <li>publish() writes a PENDING row with correct event_type, aggregate_type, aggregate_id,
 *       aggregate_uid, company_id, branch_id, non-null payload, occurred_at set, attempt_count=0.
 *   <li>Same-TX atomicity: surrounding TX rollback → no domain_events row persists.
 *   <li>MANDATORY propagation: publish() outside a TX throws immediately.
 * </ol>
 */
class OutboxPublisherIT extends PostgresIntegrationTest {

    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private IamTestData testData;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;

    /** Simple Jackson-serialisable payload. */
    record TestPayload(String message, int count) {}

    private Long companyId;
    private Long branchId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("OutboxPublisher IT Org"));
        Company company  = companies.save(new Company(org, "OBP", "Outbox Publisher Co"));
        Branch branch    = branches.save(new Branch(company, "OBP-B1", "Outbox Publisher Branch"));

        companyId = company.getId();
        branchId  = branch.getId();
    }

    @AfterEach
    void tearDown() {
        testData.clearAll();
    }

    // -------------------------------------------------------------------------
    // publish() writes a PENDING row with correct fields
    // -------------------------------------------------------------------------

    @Test
    void publish_writesRowWithStatusPendingAndCorrectFields() {
        txTemplate.execute(status -> {
            outboxPublisher.publish(
                    DomainEventType.SALE_FINALISED,
                    DomainEventType.AGG_SALES_INVOICE,
                    42L,
                    "AGGREGATE-UID-001",
                    companyId,
                    branchId,
                    new TestPayload("hello outbox", 1));
            return null;
        });

        List<DomainEvent> rows = domainEventRepository.findAll();
        assertThat(rows).hasSize(1);

        DomainEvent evt = rows.get(0);
        assertThat(evt.getStatus()).isEqualTo(DomainEventStatus.PENDING);
        assertThat(evt.getEventType()).isEqualTo(DomainEventType.SALE_FINALISED);
        assertThat(evt.getAggregateType()).isEqualTo(DomainEventType.AGG_SALES_INVOICE);
        assertThat(evt.getAggregateId()).isEqualTo(42L);
        assertThat(evt.getAggregateUid()).isEqualTo("AGGREGATE-UID-001");
        assertThat(evt.getCompanyId()).isEqualTo(companyId);
        assertThat(evt.getBranchId()).isEqualTo(branchId);
        assertThat(evt.getPayload()).isNotNull().contains("hello outbox");
        assertThat(evt.getOccurredAt()).isNotNull();
        assertThat(evt.getAttemptCount()).isZero();
        assertThat(evt.getDispatchedAt()).isNull();
        assertThat(evt.getUid()).isNotBlank();
    }

    @Test
    void publish_withNullBranchId_isAllowed() {
        txTemplate.execute(status -> {
            outboxPublisher.publish(
                    DomainEventType.SALE_FINALISED,
                    DomainEventType.AGG_SALES_INVOICE,
                    7L,
                    "NO-BRANCH-UID",
                    companyId,
                    null,            // nullable branch
                    new TestPayload("no branch", 1));
            return null;
        });

        List<DomainEvent> rows = domainEventRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBranchId()).isNull();
    }

    @Test
    void publish_returnsNonBlankEventUid() {
        String uid = txTemplate.execute(status ->
                outboxPublisher.publish(
                        DomainEventType.SALE_VOIDED,
                        DomainEventType.AGG_SALES_INVOICE,
                        99L,
                        "VOIDED-UID-001",
                        companyId,
                        branchId,
                        new TestPayload("voided", 0)));

        assertThat(uid).isNotBlank();
        DomainEvent stored = domainEventRepository.findAll().get(0);
        assertThat(stored.getUid()).isEqualTo(uid);
    }

    // -------------------------------------------------------------------------
    // Same-TX atomicity: rollback → no row
    // -------------------------------------------------------------------------

    @Test
    void publish_surroundingTxRollsBack_noDomainEventsRowPersists() {
        assertThatThrownBy(() ->
                txTemplate.execute(status -> {
                    outboxPublisher.publish(
                            DomainEventType.SALE_FINALISED,
                            DomainEventType.AGG_SALES_INVOICE,
                            1L,
                            "WILL-ROLLBACK",
                            companyId,
                            branchId,
                            new TestPayload("rollback me", 0));
                    // Force the surrounding TX to roll back
                    throw new RuntimeException("forced rollback — atomicity check");
                })
        ).isInstanceOf(RuntimeException.class);

        // The event row must have been rolled back with the surrounding transaction.
        assertThat(domainEventRepository.findAll())
                .as("domain_events must be empty after TX rollback")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // MANDATORY propagation: calling publish() outside a TX must throw
    // -------------------------------------------------------------------------

    @Test
    void publish_outsideTransaction_throwsImmediately() {
        // There is no active Spring transaction on this thread — MANDATORY propagation
        // must throw without silently writing a row (ADR-0009 D-3, same discipline as AuditServiceImpl).
        assertThatThrownBy(() ->
                outboxPublisher.publish(
                        DomainEventType.SALE_FINALISED,
                        DomainEventType.AGG_SALES_INVOICE,
                        1L,
                        "OUTSIDE-TX",
                        companyId,
                        branchId,
                        new TestPayload("no tx", 0))
        ).isInstanceOf(Exception.class); // IllegalTransactionStateException or TransactionSystemException

        assertThat(domainEventRepository.findAll()).isEmpty();
    }
}

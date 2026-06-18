package com.erp.modules.crm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.erp.modules.crm.domain.dto.ForecastDto;
import com.erp.modules.crm.domain.dto.PipelineStageSummaryRowDto;
import com.erp.modules.crm.domain.dto.PipelineSummaryDto;
import com.erp.modules.crm.domain.entity.Opportunity;
import com.erp.modules.crm.domain.entity.PipelineStage;
import com.erp.modules.crm.repository.OpportunityRepository;
import com.erp.modules.crm.repository.PipelineStageRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression IT for ClassCastException in PipelineQuery when o.currency (a CurrencyCode
 * AttributeConverter value type) is projected by a JPQL SELECT.
 *
 * <p>Pre-fix behaviour: {@code pipeline()} and {@code forecast()} cast {@code row[6]} / {@code r[2]}
 * directly to {@code (String)}.  Because Hibernate's JPQL projection runs the AttributeConverter
 * and returns a {@code CurrencyCode} instance — not a raw String — every call with OPEN opportunities
 * threw {@code ClassCastException → HTTP 500}.
 *
 * <p>Fix: {@link PipelineQuery#toCurrency(Object)} coerces CurrencyCode/String/null safely.
 *
 * <p>This test seeds a real row via Hibernate (real Flyway schema, Testcontainers Postgres) so the
 * converter actually fires. A Mockito unit test would bypass the converter and never expose the cast.
 *
 * <p>Assertions that fail pre-fix (ClassCastException) and pass post-fix:
 * <ol>
 *   <li>{@code pipeline()} returns without throwing and the stage row's currency == "TZS".</li>
 *   <li>{@code forecast()} returns without throwing and the forecast currency == "TZS".</li>
 * </ol>
 */
class PipelineQueryCurrencyConverterIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private CustomerRepository      customers;
    @Autowired private PipelineStageRepository stages;
    @Autowired private OpportunityRepository   opportunities;
    @Autowired private IamTestData             testData;
    @Autowired private PipelineQuery           pipelineQuery;

    private Long   companyId;
    private Long   branchId;
    private Long   actorId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = new Organisation("PipelineCCIT Org");
        organisations.save(org);

        Company company = new Company(org, "PCCIT", "Pipeline CC IT Co");
        companies.save(company);
        companyId = company.getId();

        Branch branch = new Branch(company, "PCCIT-HQ", "Pipeline CC IT Branch");
        branch.setDefault(true);
        branches.save(branch);
        branchId = branch.getId();

        AppUser actor = new AppUser("pcc.actor", passwordEncoder.encode("Password1!"), "PCC Actor");
        users.save(actor);
        actorId = actor.getId();

        RequestContext.set(new RequestContext.Principal(
                actorId, actor.getUsername(), false, companyId, branchId, "127.0.0.1"));

        // Pipeline stage — required by the GROUP BY in pipelineSummaryRaw
        PipelineStage stage = new PipelineStage(
                companyId, "Prospecting", (short) 1, new BigDecimal("50.00"), actorId);
        stages.save(stage);

        // Customer — required by the Opportunity FK
        Customer customer = new Customer(
                companyId, "CUST-PCC-01", PartyType.BUSINESS,
                "PCC Corp", CustomerKind.CREDIT_ACCOUNT, actorId);
        customers.save(customer);

        // Opportunity: OPEN, currency = TZS, expected close in the near future
        // Construction goes through Opportunity(companyId, branchId, title, customerId, customerUid,
        // currency, pipelineStageId, winProbability, createdBy), which calls
        // CurrencyCode.ofNullable("TZS") — the AttributeConverter will fire on the SELECT.
        Opportunity opp = new Opportunity(
                companyId, branchId,
                "Regression Deal — currency cast",
                customer.getId(), customer.getUid(),
                "TZS",
                stage.getId(),
                new BigDecimal("50.00"),
                actorId);
        opp.setOpportunityNumber("OPP-PCC-001");
        opp.setEstimatedValueAmount(new BigDecimal("100000.00"));
        opp.setExpectedCloseDate(LocalDate.now().plusDays(14));
        opportunities.save(opp);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
    }

    // -------------------------------------------------------------------------
    // Regression: PipelineQuery.pipeline() — JPQL projection of CurrencyCode
    // -------------------------------------------------------------------------

    /**
     * Pre-fix: (String) row[6] threw ClassCastException because Hibernate returned CurrencyCode.
     * Post-fix: toCurrency() coerces CurrencyCode → String without throwing.
     *
     * <p>Asserts:
     * <ul>
     *   <li>No exception is thrown (ClassCastException regression guard).</li>
     *   <li>The stage row's currency is the 3-letter String "TZS", not a CurrencyCode instance.</li>
     * </ul>
     */
    @Test
    void pipeline_jpqlCurrencyProjection_doesNotThrowClassCastException() {
        assertThatCode(() -> {
            PipelineSummaryDto dto = pipelineQuery.pipeline(companyId, branchId);

            assertThat(dto).isNotNull();
            assertThat(dto.stages())
                    .as("At least one OPEN opportunity must appear in the pipeline summary")
                    .isNotEmpty();

            PipelineStageSummaryRowDto row = dto.stages().get(0);
            assertThat(row.currency())
                    .as("pipeline currency must be the plain String 'TZS', not a CurrencyCode instance "
                            + "(ClassCastException regression: JPQL SELECT o.currency returns CurrencyCode)")
                    .isInstanceOf(String.class)
                    .isEqualTo("TZS");
        }).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Regression: PipelineQuery.forecast() — JPQL projection of CurrencyCode
    // -------------------------------------------------------------------------

    /**
     * Pre-fix: (String) r[2] threw ClassCastException because Hibernate returned CurrencyCode.
     * Post-fix: toCurrency() coerces CurrencyCode → String without throwing.
     *
     * <p>Asserts:
     * <ul>
     *   <li>No exception is thrown (ClassCastException regression guard).</li>
     *   <li>The forecast currency is the 3-letter String "TZS".</li>
     * </ul>
     */
    @Test
    void forecast_jpqlCurrencyProjection_doesNotThrowClassCastException() {
        // Window includes the opportunity's expectedCloseDate (now + 14 days)
        LocalDate from = LocalDate.now();
        LocalDate to   = LocalDate.now().plusDays(30);

        assertThatCode(() -> {
            ForecastDto dto = pipelineQuery.forecast(companyId, branchId, from, to);

            assertThat(dto).isNotNull();
            assertThat(dto.currency())
                    .as("forecast currency must be the plain String 'TZS', not a CurrencyCode instance "
                            + "(ClassCastException regression: JPQL SELECT o.currency returns CurrencyCode)")
                    .isInstanceOf(String.class)
                    .isEqualTo("TZS");
            assertThat(dto.openCount())
                    .as("forecast must count the seeded OPEN opportunity")
                    .isGreaterThan(0L);
        }).doesNotThrowAnyException();
    }
}

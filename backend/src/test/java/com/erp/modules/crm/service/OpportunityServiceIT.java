package com.erp.modules.crm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.crm.domain.dto.CreateOpportunityRequest;
import com.erp.modules.crm.domain.dto.OpportunityDto;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression IT for Bug: opportunity_number ordering (commit 9798a66).
 *
 * <p>Pre-fix behaviour: {@code create()} called {@code opportunities.save(opp)} BEFORE
 * {@code opp.setOpportunityNumber(number)}.  Because {@code opportunity_number} is NOT NULL and
 * the subsequent audit-service query triggers a Hibernate autoflush, the INSERT fired with a null
 * {@code opportunity_number} → DataIntegrityViolationException → HTTP 500.
 *
 * <p>Fix: {@code numberGen.nextOpportunity()} is called and assigned BEFORE {@code save()}.
 *
 * <p>This test creates an opportunity via the service and asserts:
 * <ol>
 *   <li>The returned DTO has a non-null, non-blank {@code opportunityNumber}.</li>
 *   <li>The row persists and is re-fetchable by uid with the same number.</li>
 * </ol>
 * A test run against the pre-fix code throws a constraint violation and fails here.
 */
class OpportunityServiceIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private CustomerRepository      customers;
    @Autowired private PipelineStageRepository stages;
    @Autowired private OpportunityRepository   opportunities;
    @Autowired private IamTestData             testData;
    @Autowired private OpportunityService      opportunityService;

    private Long   companyId;
    private Long   branchId;
    private String customerUid;
    private String stageUid;

    @BeforeEach
    void setUp() {
        Organisation org = new Organisation("OppIT Org");
        organisations.save(org);

        Company company = new Company(org, "OPPCO", "Opp IT Company");
        companies.save(company);
        companyId = company.getId();

        Branch branch = new Branch(company, "OPP-HQ", "Opp HQ Branch");
        branch.setDefault(true);
        branches.save(branch);
        branchId = branch.getId();

        AppUser actor = new AppUser("opp.actor", passwordEncoder.encode("Password1!"), "Opp Actor");
        actor.setOrganisationId(org.getId());
        users.save(actor);

        RequestContext.set(new RequestContext.Principal(
                actor.getId(), actor.getUsername(), false, companyId, branchId, "127.0.0.1"));

        // Seed a customer — required by OpportunityServiceImpl.resolveCustomer()
        Customer customer = new Customer(
                companyId, "CUST-0001", PartyType.BUSINESS,
                "Regression Corp", CustomerKind.CREDIT_ACCOUNT, actor.getId());
        customers.save(customer);
        customerUid = customer.getUid();

        // Seed a pipeline stage — required by OpportunityServiceImpl.resolveStage()
        PipelineStage stage = new PipelineStage(
                companyId, "Prospecting", (short) 1, new BigDecimal("20.00"), actor.getId());
        stages.save(stage);
        stageUid = stage.getUid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
    }

    // -------------------------------------------------------------------------
    // Regression: opportunity_number must be set BEFORE save to avoid NOT NULL violation
    // -------------------------------------------------------------------------

    /**
     * Creates an opportunity and asserts the returned DTO carries a non-null, non-blank
     * opportunityNumber.  Pre-fix: DataIntegrityViolationException (null value in column
     * "opportunity_number" violates not-null constraint).  Post-fix: passes cleanly.
     */
    @Test
    void createOpportunity_opportunityNumberAssignedBeforeSave_neverNull() {
        CreateOpportunityRequest req = new CreateOpportunityRequest(
                companyId,
                customerUid,
                "Regression Deal — number ordering",
                stageUid,
                "TZS",
                new BigDecimal("50000.00"),
                null,    // winProbability — falls back to stage default
                null,    // expectedCloseDate
                null,    // agentUid
                null,    // ownerUserId
                null,    // sourceLeadUid — optional path, no QUALIFIED lead needed
                null     // notes
        );

        OpportunityDto dto = opportunityService.create(req);

        // Primary regression assertion: number must be present in the returned DTO
        assertThat(dto.opportunityNumber())
                .as("opportunityNumber must be set before save (NOT NULL constraint)")
                .isNotNull()
                .isNotBlank()
                .startsWith("OPP-");

        // Secondary: uid and companyId must be populated
        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.companyId()).isEqualTo(companyId);
        assertThat(dto.branchId()).isEqualTo(branchId);
    }

    /**
     * Re-fetches the persisted row by uid to prove the number was actually written to the DB
     * (not just returned in-memory from the service).
     */
    @Test
    void createOpportunity_numberPersistedToDB_refetchReturnsNonNullNumber() {
        CreateOpportunityRequest req = new CreateOpportunityRequest(
                companyId,
                customerUid,
                "Regression Deal — persistence check",
                stageUid,
                "TZS",
                new BigDecimal("10000.00"),
                null, null, null, null, null, null
        );

        OpportunityDto dto = opportunityService.create(req);

        // Reload directly from the repository — bypasses any in-memory/first-level cache state
        var persisted = opportunities.findByUid(dto.uid())
                .orElseThrow(() -> new AssertionError("Opportunity not found in DB: " + dto.uid()));

        assertThat(persisted.getOpportunityNumber())
                .as("opportunityNumber must be persisted in the DB row")
                .isNotNull()
                .isNotBlank()
                .isEqualTo(dto.opportunityNumber());
    }
}

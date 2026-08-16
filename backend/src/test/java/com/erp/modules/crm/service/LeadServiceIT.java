package com.erp.modules.crm.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.crm.domain.dto.CreateLeadRequest;
import com.erp.modules.crm.domain.dto.DisqualifyLeadRequest;
import com.erp.modules.crm.domain.dto.LeadDto;
import com.erp.modules.crm.domain.enums.LeadSource;
import com.erp.modules.crm.domain.enums.LeadStatus;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Core CRM Lead lifecycle integration tests (ADR-0031).
 *
 * <p>Covers:
 * <ol>
 *   <li>Create lead — persists with status NEW, auto-generated lead number.</li>
 *   <li>Contact lead — transitions status to CONTACTED.</li>
 *   <li>Disqualify lead — transitions to DISQUALIFIED; reason stored.</li>
 *   <li>Disqualify already-disqualified lead — guard rejects.</li>
 * </ol>
 */
class LeadServiceIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;
    @Autowired private LeadService             leadService;

    private Long companyId;
    private Long branchId;

    @BeforeEach
    void setUp() {
        Organisation org = new Organisation("CRM Test Org");
        organisations.save(org);

        Company company = new Company(org, "CRM1", "CRM Test Company");
        companies.save(company);
        companyId = company.getId();

        Branch branch = new Branch(company, "CRM-HQ", "CRM HQ Branch");
        branch.setDefault(true);
        branches.save(branch);
        branchId = branch.getId();

        AppUser actor = new AppUser("crm.actor", passwordEncoder.encode("Password1!"), "CRM Actor");
        users.save(inOrganisation(actor, org.getId()));

        // branchId drives Lead entity; userId drives audit
        RequestContext.set(new RequestContext.Principal(
                actor.getId(), actor.getUsername(), false, companyId, branchId, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
    }

    // -------------------------------------------------------------------------
    // 1 · Create
    // -------------------------------------------------------------------------

    @Test
    void createLead_persistsAndReturnsDto() {
        CreateLeadRequest req = new CreateLeadRequest(
                companyId,
                "Acme Corp",
                LeadSource.WEBSITE,
                null,   // companyName
                "John Smith",
                "+255712345678",
                "john@acme.com",
                null,   // ownerUserId
                null    // notes
        );

        LeadDto dto = leadService.create(req);

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.leadNumber()).startsWith("LEAD-");
        assertThat(dto.leadStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(dto.displayName()).isEqualTo("Acme Corp");
        assertThat(dto.companyId()).isEqualTo(companyId);
        assertThat(dto.branchId()).isEqualTo(branchId);
    }

    // -------------------------------------------------------------------------
    // 2 · Contact
    // -------------------------------------------------------------------------

    @Test
    void contactLead_transitionsToContacted() {
        LeadDto created = leadService.create(new CreateLeadRequest(
                companyId, "Beta Ltd", LeadSource.REFERRAL,
                null, null, null, null, null, null));

        LeadDto contacted = leadService.contact(created.uid());

        assertThat(contacted.leadStatus()).isEqualTo(LeadStatus.CONTACTED);
    }

    // -------------------------------------------------------------------------
    // 3 · Disqualify
    // -------------------------------------------------------------------------

    @Test
    void disqualifyLead_storesReasonAndStatus() {
        LeadDto created = leadService.create(new CreateLeadRequest(
                companyId, "Gamma Inc", LeadSource.COLD_CALL,
                null, null, null, null, null, null));

        LeadDto dq = leadService.disqualify(created.uid(),
                new DisqualifyLeadRequest("Not the right fit"));

        assertThat(dq.leadStatus()).isEqualTo(LeadStatus.DISQUALIFIED);
        assertThat(dq.disqualifyReason()).isEqualTo("Not the right fit");
    }

    // -------------------------------------------------------------------------
    // 4 · Guard — disqualify twice
    // -------------------------------------------------------------------------

    @Test
    void disqualifyAlreadyDisqualified_throwsIllegalState() {
        LeadDto created = leadService.create(new CreateLeadRequest(
                companyId, "Delta SA", LeadSource.WALK_IN,
                null, null, null, null, null, null));
        leadService.disqualify(created.uid(), new DisqualifyLeadRequest("First reason"));

        assertThatThrownBy(() ->
                leadService.disqualify(created.uid(), new DisqualifyLeadRequest("Second reason")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }
}

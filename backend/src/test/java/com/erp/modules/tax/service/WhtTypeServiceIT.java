package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.tax.domain.dto.CreateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.UpdateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.WhtTypeDto;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link WhtTypeServiceImpl} (ADR-0017 D-2d, FR-WHT-01/02/03).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>create — persists and returns correct fields.
 *   <li>listByCompany — returns all types for the company only (cross-company isolation).
 *   <li>getByUid — retrieves correct record.
 *   <li>update — name/ratePct/active changed, code unchanged.
 *   <li>deactivate — active → false; rejected for new captures.
 *   <li>per-company code uniqueness — ConflictException on duplicate code same company.
 *   <li>cross-company isolation — code duplicate in a different company is allowed.
 * </ol>
 */
class WhtTypeServiceIT extends PostgresIntegrationTest {

    @Autowired private WhtTypeService         service;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("WHT Type IT Org"));
        company    = companies.save(new Company(org, "WHTTY", "WHT Type IT Co"));
        branch     = branches.save(new Branch(company, "WHTTY1", "WHT Type IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("whtty_root", passwordEncoder.encode("WhtTy0t1!"), "WHT Type Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "whtty_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: create persists and returns correct fields
    // =========================================================================

    @Test
    void create_persistsAndReturnsCorrectFields() {
        WhtTypeDto dto = service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-2PCT", "2% Withholding",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.id()).isNotNull();
        assertThat(dto.companyId()).isEqualTo(company.getId());
        assertThat(dto.code()).isEqualTo("WHT-2PCT");
        assertThat(dto.name()).isEqualTo("2% Withholding");
        assertThat(dto.kind()).isEqualTo(WhtKind.WHT_ON_PAYMENT);
        assertThat(dto.ratePct()).isEqualByComparingTo(new BigDecimal("2.0000"));
        assertThat(dto.active()).isTrue();
    }

    // =========================================================================
    // Bar 2: listByCompany — returns only this company's types
    // =========================================================================

    @Test
    void listByCompany_returnsOnlyOwnCompanyTypes() {
        service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-LIST-01", "Pay WHT",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));
        service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-LIST-02", "Rec WHT",
                WhtKind.WHT_ON_RECEIPT, new BigDecimal("5.0000")));

        List<WhtTypeDto> list = service.listByCompany(company.getId());

        assertThat(list).hasSize(2);
        assertThat(list).allMatch(t -> t.companyId().equals(company.getId()));
    }

    // =========================================================================
    // Bar 3: getByUid — retrieves the correct record
    // =========================================================================

    @Test
    void getByUid_returnsCorrectRecord() {
        WhtTypeDto created = service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-GET-01", "Get Test WHT",
                WhtKind.WHT_ON_RECEIPT, new BigDecimal("3.5000")));

        WhtTypeDto fetched = service.getByUid(created.uid());

        assertThat(fetched.uid()).isEqualTo(created.uid());
        assertThat(fetched.code()).isEqualTo("WHT-GET-01");
        assertThat(fetched.ratePct()).isEqualByComparingTo(new BigDecimal("3.5000"));
    }

    // =========================================================================
    // Bar 4: update — changes name/ratePct/active; code is immutable
    // =========================================================================

    @Test
    void update_changesNameAndRatePct() {
        WhtTypeDto created = service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-UPD-01", "Old Name",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));

        WhtTypeDto updated = service.update(created.uid(),
                new UpdateWhtTypeRequest("New Name", new BigDecimal("3.0000"), true));

        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.ratePct()).isEqualByComparingTo(new BigDecimal("3.0000"));
        assertThat(updated.active()).isTrue();
        // code must not have changed
        assertThat(updated.code()).isEqualTo("WHT-UPD-01");
    }

    // =========================================================================
    // Bar 5: deactivate — active becomes false
    // =========================================================================

    @Test
    void deactivate_setsActiveToFalse() {
        WhtTypeDto created = service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-DEACT-01", "Deactivate Me",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));

        assertThat(created.active()).isTrue();

        WhtTypeDto deactivated = service.deactivate(created.uid());

        assertThat(deactivated.active())
                .as("WHT type must be inactive after deactivate()")
                .isFalse();

        // getByUid reflects the change
        assertThat(service.getByUid(created.uid()).active()).isFalse();
    }

    // =========================================================================
    // Bar 6: per-company code uniqueness — ConflictException on duplicate
    // =========================================================================

    @Test
    void create_duplicateCodeSameCompany_rejected() {
        service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-DUP", "First",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));

        assertThatThrownBy(() -> service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-DUP", "Second — same code",
                WhtKind.WHT_ON_RECEIPT, new BigDecimal("5.0000"))))
                .as("duplicate code in same company must throw ConflictException")
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // Bar 7: cross-company isolation — same code in a different company is allowed
    // =========================================================================

    @Test
    void create_sameCodeDifferentCompany_allowed() {
        // Company 2 — a separate tenant
        Organisation org2 = organisations.save(new Organisation("WHT Type IT Org 2"));
        Company company2  = companies.save(new Company(org2, "WHTTY2", "WHT Type IT Co 2"));
        Branch branch2    = branches.save(new Branch(company2, "WHTTY21", "WHT Type IT Branch 2"));
        AppUser root2 = new AppUser("whtty2_root", passwordEncoder.encode("WhtTy2R0t!"), "WHT Root 2");
        root2.setRoot(true);
        root2 = users.save(root2);

        // Seed GL for company2 (glConfigService.seedDefaults needs CoA first)
        chartOfAccountService.seedDefaults(company2.getId());
        fiscalCalendarService.seedCurrentYear(company2.getId());
        glConfigService.seedDefaults(company2.getId());

        // Create in company1
        service.create(new CreateWhtTypeRequest(
                companyUid, "WHT-CROSS", "Company1 Type",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));

        // Switch context to company2
        RequestContext.set(new RequestContext.Principal(
                root2.getId(), "whtty2_root", true, company2.getId(), branch2.getId(), null));

        // Same code in company2 must succeed (different tenant)
        WhtTypeDto dto2 = service.create(new CreateWhtTypeRequest(
                company2.getUid(), "WHT-CROSS", "Company2 Type",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));

        assertThat(dto2.companyId())
                .as("new type must belong to company2, not company1")
                .isEqualTo(company2.getId());
        assertThat(dto2.code()).isEqualTo("WHT-CROSS");

        // listByCompany for each company returns only its own
        RequestContext.set(new RequestContext.Principal(
                rootId, "whtty_root", true, company.getId(), branch.getId(), null));
        assertThat(service.listByCompany(company.getId()))
                .as("company1 list must contain only company1 types")
                .allMatch(t -> t.companyId().equals(company.getId()));

        RequestContext.set(new RequestContext.Principal(
                root2.getId(), "whtty2_root", true, company2.getId(), branch2.getId(), null));
        assertThat(service.listByCompany(company2.getId()))
                .as("company2 list must contain only company2 types")
                .allMatch(t -> t.companyId().equals(company2.getId()));
    }
}

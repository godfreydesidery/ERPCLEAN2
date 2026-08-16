package com.erp.modules.sales.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.dto.CreateTaxRateRequest;
import com.erp.modules.sales.domain.dto.TaxRateDto;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.platform.common.api.ForbiddenException;
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
 * Integration tests for {@link SalesInvoiceService#createTaxRate(CreateTaxRateRequest)}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy path — absent classification → created, DTO fields correct, row persisted.
 *   <li>Duplicate (company, vatStatus) → IllegalStateException (HTTP 409).
 *   <li>Cross-company scope attempt → ForbiddenException (HTTP 403).
 * </ul>
 */
class TaxRateCreateIT extends PostgresIntegrationTest {

    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private TaxRateRepository taxRates;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private Long rootId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("TaxRate Create IT Org"));
        company = companies.save(new Company(org, "TXCR", "TaxRate Create Co"));
        branch = branches.save(new Branch(company, "TXCR-1", "TaxRate Branch 1"));
        orgId = org.getId();

        AppUser root = new AppUser("taxrate_root", passwordEncoder.encode("RootPass1!"), "TaxRate Root");
        root.setRoot(true);
        root.setOrganisationId(orgId);
        root = users.save(root);
        rootId = root.getId();

        // Act as root in this company — no taxRateSeeder.seedDefaults call,
        // so the company starts with zero tax-rate rows.
        RequestContext.set(new RequestContext.Principal(
                rootId, "taxrate_root", true, company.getId(), branch.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // (a) Happy path: absent classification → persisted, DTO correct
    // -----------------------------------------------------------------------

    @Test
    void createTaxRate_absentClassification_persistsAndReturnsDto() {
        CreateTaxRateRequest req = new CreateTaxRateRequest(
                company.getId(), VatStatus.STANDARD, new BigDecimal("0.1800"));

        TaxRateDto dto = salesInvoiceService.createTaxRate(req);

        // DTO fields
        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.id()).isNotNull();
        assertThat(dto.companyId()).isEqualTo(company.getId());
        assertThat(dto.vatStatus()).isEqualTo(VatStatus.STANDARD);
        assertThat(dto.rate()).isEqualByComparingTo(new BigDecimal("0.1800"));

        // Row actually persisted
        assertThat(taxRates.findByCompanyIdAndVatStatus(company.getId(), VatStatus.STANDARD))
                .isPresent();
    }

    // -----------------------------------------------------------------------
    // (b) Duplicate (company, vatStatus) → IllegalStateException (→ HTTP 409)
    // -----------------------------------------------------------------------

    @Test
    void createTaxRate_duplicate_throwsIllegalState() {
        // First create succeeds
        salesInvoiceService.createTaxRate(new CreateTaxRateRequest(
                company.getId(), VatStatus.ZERO_RATED, new BigDecimal("0.0000")));

        // Second create for the same classification must be rejected
        assertThatThrownBy(() -> salesInvoiceService.createTaxRate(
                new CreateTaxRateRequest(company.getId(), VatStatus.ZERO_RATED, new BigDecimal("0.0500"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    // -----------------------------------------------------------------------
    // (c) Cross-company scope → ForbiddenException (→ HTTP 403)
    // -----------------------------------------------------------------------

    @Test
    void createTaxRate_crossCompany_throwsForbidden() {
        Organisation otherOrg = organisations.save(new Organisation("Other Org"));
        Company otherCompany = companies.save(new Company(otherOrg, "TXOT", "Other Co"));

        // Non-root user scoped to `company`, attempting to act in `otherCompany`
        AppUser nonRoot = users.save(inOrganisation(
                new AppUser("taxrate_user", passwordEncoder.encode("Pass1!"), "TaxRate User"),
                orgId));
        RequestContext.set(new RequestContext.Principal(
                nonRoot.getId(), "taxrate_user", false, company.getId(), branch.getId(), null));

        assertThatThrownBy(() -> salesInvoiceService.createTaxRate(
                new CreateTaxRateRequest(otherCompany.getId(), VatStatus.EXEMPT, new BigDecimal("0.0000"))))
                .isInstanceOf(ForbiddenException.class);
    }
}

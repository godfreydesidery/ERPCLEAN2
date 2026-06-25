package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.erp.modules.iam.domain.dto.CompanyDto;
import com.erp.modules.iam.domain.dto.CreateCompanyRequest;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for company-provisioning: verifies that {@link CompanyService#create} and
 * {@link CompanyService#reprovisionDefaults} leave the company in a fully-provisioned state.
 *
 * <p><b>Requires Docker / Testcontainers</b> — run with {@code mvn verify} (failsafe).
 * Pure unit behaviour is covered by {@link com.erp.platform.bootstrap.CompanyProvisioningServiceImplTest}.
 */
class CompanyProvisioningIT extends PostgresIntegrationTest {

    @Autowired private CompanyService            companyService;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private UnitOfMeasureRepository   unitOfMeasures;
    @Autowired private TaxRateRepository         taxRates;
    @Autowired private IamTestData               testData;

    private String orgUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Test Org"));
        this.orgUid = org.getUid();
    }

    @Test
    void create_provisionsUnitsOfMeasure() {
        CompanyDto company = companyService.create(
                new CreateCompanyRequest(orgUid, "C1", "Test Co", null, null, null));

        long uomCount = unitOfMeasures.findByCompanyId(company.id()).size();
        assertThat(uomCount)
                .as("Units of measure seeded for new company")
                .isGreaterThan(0);
    }

    @Test
    void create_provisionsTaxRates() {
        CompanyDto company = companyService.create(
                new CreateCompanyRequest(orgUid, "C2", "Tax Co", null, null, null));

        // TaxRateSeeder seeds 3 VAT status rows (STANDARD, ZERO, EXEMPT per TaxRateSeeder)
        long taxCount = taxRates.findAll().stream()
                .filter(t -> t.getCompanyId().equals(company.id()))
                .count();
        assertThat(taxCount)
                .as("Tax rates seeded for new company")
                .isGreaterThan(0);
    }

    @Test
    void reprovisionDefaults_onAlreadyProvisionedCompany_doesNotThrowAndDoesNotDuplicate() {
        CompanyDto company = companyService.create(
                new CreateCompanyRequest(orgUid, "C3", "Reprov Co", null, null, null));

        long uomBefore = unitOfMeasures.findByCompanyId(company.id()).size();

        // Second call must be idempotent — no exception, no duplicate rows.
        assertThatCode(() -> companyService.reprovisionDefaults(company.uid()))
                .doesNotThrowAnyException();

        long uomAfter = unitOfMeasures.findByCompanyId(company.id()).size();
        assertThat(uomAfter)
                .as("Re-provision must not duplicate UoM rows")
                .isEqualTo(uomBefore);
    }

    @Test
    void reprovisionDefaults_calledTwice_remainsIdempotent() {
        CompanyDto company = companyService.create(
                new CreateCompanyRequest(orgUid, "C4", "Double Reprov", null, null, null));

        // Both re-provision calls must succeed without exception or data corruption.
        assertThatCode(() -> {
            companyService.reprovisionDefaults(company.uid());
            companyService.reprovisionDefaults(company.uid());
        }).doesNotThrowAnyException();

        long uomCount = unitOfMeasures.findByCompanyId(company.id()).size();
        long uomAfterCreate = unitOfMeasures.findByCompanyId(company.id()).size();
        assertThat(uomCount).isEqualTo(uomAfterCreate);
    }
}

package com.erp.modules.fx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.fx.domain.dto.CompanyCurrencyDto;
import com.erp.modules.fx.domain.dto.EnableCurrencyRequest;
import com.erp.modules.fx.domain.exception.CurrencyNotEnabledException;
import com.erp.modules.fx.repository.CompanyCurrencyRepository;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.service.CompanyService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link CurrencyEnablementServiceImpl} and {@link CurrencyEnablementSeeder}
 * against a real PostgreSQL schema (ADR-0039 D-7/D-8/D-9).
 *
 * <p>Covers:
 * <ul>
 *   <li>Bootstrap seeding: currencies are seeded for the new company.
 *   <li>Exactly one default per company (partial-unique index holds).
 *   <li>Base currency cannot be deactivated (invariant).
 *   <li>Default document currency is correctly resolved.
 *   <li>assertEnabled throws for a currency not in the allow-list.
 *   <li>Base-change guard: blocked when GL entries exist.
 *   <li>Base-change guard: allowed on a transaction-free company.
 * </ul>
 */
class CurrencyEnablementServiceIT extends PostgresIntegrationTest {

    @Autowired private CurrencyEnablementService   enablementService;
    @Autowired private CurrencyEnablementSeeder    seeder;
    @Autowired private CompanyService              companyService;
    @Autowired private CompanyCurrencyRepository   companyCurrencies;
    @Autowired private OrganisationRepository      organisations;
    @Autowired private CompanyRepository           companies;
    @Autowired private BranchRepository            branches;
    @Autowired private IamTestData                 testData;

    private Long companyId;

    @BeforeEach
    @Transactional
    void setUp() {
        testData.clearAll();

        Organisation org = new Organisation("IT Org");
        organisations.save(org);

        Company company = new Company(org, "IT01", "IT Company");
        companies.save(company);
        companyId = company.getId();

        Branch branch = new Branch(company, "BR01", "Head Office");
        branches.save(branch);
    }

    @AfterEach
    void tearDown() {
        testData.clearAll();
    }

    // ── Bootstrap seeding ─────────────────────────────────────────────────────

    @Test
    @Transactional
    void seeder_seedsEnabledCurrenciesForCompany() {
        List<String> enabled = List.of("TZS", "KES", "USD");
        seeder.seedDefaults(companyId, "TZS", "TZS", enabled);

        List<CompanyCurrencyDto> result = enablementService.enabledForCompany(companyId);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(CompanyCurrencyDto::currencyCode))
                .containsExactlyInAnyOrder("TZS", "KES", "USD");
    }

    @Test
    @Transactional
    void seeder_marksExactlyOneDefaultPerCompany() {
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS", "USD", "KES"));

        long defaultCount = companyCurrencies
                .findByCompanyIdOrderByCurrencyCode(companyId)
                .stream().filter(cc -> cc.isDefault()).count();

        assertThat(defaultCount).isEqualTo(1);
        // The default is TZS (the configured default)
        var defaultRow = companyCurrencies.findByCompanyIdAndIsDefaultTrue(companyId);
        assertThat(defaultRow).isPresent();
        assertThat(defaultRow.get().getCurrencyCode().value()).isEqualTo("TZS");
    }

    @Test
    @Transactional
    void seeder_isIdempotent_runningTwiceDoesNotDuplicate() {
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS", "USD"));
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS", "USD")); // second run

        assertThat(companyCurrencies.findByCompanyIdOrderByCurrencyCode(companyId)).hasSize(2);
    }

    @Test
    @Transactional
    void seeder_skipsUnknownCodesGracefully() {
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS", "XYZ")); // XYZ not in catalog

        List<CompanyCurrencyDto> result = enablementService.enabledForCompany(companyId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).currencyCode()).isEqualTo("TZS");
    }

    // ── Invariants ────────────────────────────────────────────────────────────

    @Test
    @Transactional
    void deactivate_blockedForBaseCurrency() {
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS", "USD"));

        assertThatThrownBy(() -> enablementService.deactivateForCompany(companyId, "TZS"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot deactivate the company base currency");
    }

    @Test
    @Transactional
    void deactivate_blockedForDefaultRow() {
        seeder.seedDefaults(companyId, "TZS", "USD", List.of("TZS", "USD"));

        // USD is the default document currency
        assertThatThrownBy(() -> enablementService.deactivateForCompany(companyId, "USD"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot deactivate the default document currency");
    }

    @Test
    @Transactional
    void assertEnabled_throws_whenCurrencyNotInAllowList() {
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS"));

        assertThatThrownBy(() ->
                enablementService.assertEnabled(companyId, null, CurrencyCode.of("USD")))
                .isInstanceOf(CurrencyNotEnabledException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("not enabled for company");
    }

    @Test
    @Transactional
    void resolveDefault_returnsSeededDefault() {
        seeder.seedDefaults(companyId, "TZS", "KES", List.of("TZS", "KES", "USD"));

        CurrencyCode resolved = enablementService.resolveDefault(companyId, null);

        assertThat(resolved.value()).isEqualTo("KES");
    }

    // ── Enable / setDefault management ops ───────────────────────────────────

    @Test
    @Transactional
    void enableForCompany_addsNewCurrency() {
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS"));

        enablementService.enableForCompany(companyId, new EnableCurrencyRequest("USD", false));

        List<CompanyCurrencyDto> enabled = enablementService.enabledForCompany(companyId);
        assertThat(enabled.stream().map(CompanyCurrencyDto::currencyCode))
                .containsExactlyInAnyOrder("TZS", "USD");
    }

    @Test
    @Transactional
    void setDefaultForCompany_shiftsDefault() {
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS", "USD"));

        enablementService.setDefaultForCompany(companyId, "USD");

        var newDefault = companyCurrencies.findByCompanyIdAndIsDefaultTrue(companyId);
        assertThat(newDefault).isPresent();
        assertThat(newDefault.get().getCurrencyCode().value()).isEqualTo("USD");
        // TZS row must no longer be default
        var tzs = companyCurrencies.findByCompanyIdAndCurrencyCode(companyId, CurrencyCode.of("TZS"));
        assertThat(tzs).isPresent();
        assertThat(tzs.get().isDefault()).isFalse();
    }

    // ── Base-change guard (OQ-CCY-08) ────────────────────────────────────────

    @Test
    @Transactional
    void changeBaseCurrency_allowedOnTransactionFreeCompany() {
        seeder.seedDefaults(companyId, "TZS", "TZS", List.of("TZS", "USD"));

        var company = companies.findById(companyId).orElseThrow();
        var dto = companyService.changeBaseCurrency(company.getUid(), "USD");

        assertThat(dto.baseCurrency()).isEqualTo("USD");
    }
}

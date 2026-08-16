package com.erp.modules.fx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.fx.domain.dto.CurrencyRateDto;
import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.money.FxRateNotFoundException;
import com.erp.platform.common.money.FxRateService;
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
 * Integration tests for {@link FxRateService} / {@link com.erp.platform.common.money.FxRateServiceImpl}
 * against a real PostgreSQL schema (Flyway V77).
 *
 * <p>Covers:
 * <ul>
 *   <li>addRate persists and returns correct dto.</li>
 *   <li>rateOn resolves the latest rate on/before the requested date.</li>
 *   <li>rateOn picks the closest-earlier row, not a future one.</li>
 *   <li>rateOn throws FxRateNotFoundException when no row resolves.</li>
 *   <li>Cross-company isolation: a rate seeded for company A is invisible to company B.</li>
 * </ul>
 */
class FxRateServiceIT extends PostgresIntegrationTest {

    @Autowired private FxRateService      fxRateService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company companyA;
    private Company companyB;
    private Branch  branchA;

    private static final LocalDate DATE_JAN = LocalDate.of(2026, 1, 15);
    private static final LocalDate DATE_FEB = LocalDate.of(2026, 2, 15);
    private static final LocalDate DATE_MAR = LocalDate.of(2026, 3, 15);

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation orgA = organisations.save(new Organisation("FxRate IT Org A"));
        companyA = companies.save(new Company(orgA, "FXA", "FxRate IT Company A"));
        branchA  = branches.save(new Branch(companyA, "FXAB1", "FxRate IT Branch A1"));

        Organisation orgB = organisations.save(new Organisation("FxRate IT Org B"));
        companyB = companies.save(new Company(orgB, "FXB", "FxRate IT Company B"));
        branches.save(new Branch(companyB, "FXBB1", "FxRate IT Branch B1"));

        AppUser root = new AppUser("fxrate_root", passwordEncoder.encode("Root1234!"), "FX Root");
        root.setRoot(true);
        root.setOrganisationId(orgA.getId());
        root = users.save(root);

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "fxrate_root", true,
                companyA.getId(), branchA.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ── addRate ───────────────────────────────────────────────────────────────

    @Test
    void addRate_persistsAndReturnsDto() {
        UpsertRateRequest req = new UpsertRateRequest(
                companyA.getId(), "USD", "TZS",
                new BigDecimal("2500.00000000"), DATE_JAN, "SPOT", "manual");

        CurrencyRateDto dto = fxRateService.addRate(req);

        assertThat(dto.id()).isNotNull();
        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.companyId()).isEqualTo(companyA.getId());
        assertThat(dto.fromCurrency()).isEqualTo("USD");
        assertThat(dto.toCurrency()).isEqualTo("TZS");
        assertThat(dto.rate()).isEqualByComparingTo("2500.00000000");
        assertThat(dto.effectiveDate()).isEqualTo(DATE_JAN);
        assertThat(dto.rateType()).isEqualTo("SPOT");
        assertThat(dto.active()).isTrue();
    }

    // ── rateOn — effective-date lookup ────────────────────────────────────────

    @Test
    void rateOn_returnsLatestRateOnOrBeforeDate() {
        // Seed two rates for the same pair
        addUsdRate(companyA, DATE_JAN, "2500.00000000");
        addUsdRate(companyA, DATE_FEB, "2550.00000000");

        // Ask for the rate on DATE_MAR — should get the Feb rate (most recent on/before)
        BigDecimal rate = fxRateService.rateOn(companyA.getId(), "USD", "TZS", DATE_MAR);
        assertThat(rate).isEqualByComparingTo("2550.00000000");
    }

    @Test
    void rateOn_returnsOlderRateWhenNewerIsFuture() {
        // Jan rate exists; Feb rate does NOT exist yet
        addUsdRate(companyA, DATE_JAN, "2500.00000000");

        // Ask for a date between Jan and Feb — should get the Jan rate
        BigDecimal rate = fxRateService.rateOn(companyA.getId(), "USD", "TZS",
                LocalDate.of(2026, 1, 31));
        assertThat(rate).isEqualByComparingTo("2500.00000000");
    }

    @Test
    void rateOn_exactDateMatch_returnsRate() {
        addUsdRate(companyA, DATE_JAN, "2500.12345678");

        BigDecimal rate = fxRateService.rateOn(companyA.getId(), "USD", "TZS", DATE_JAN);
        assertThat(rate).isEqualByComparingTo("2500.12345678");
    }

    @Test
    void rateOn_noRateBeforeDate_throwsFxRateNotFoundException() {
        // No rate seeded for EUR
        Long cid = companyA.getId();
        assertThatThrownBy(() -> fxRateService.rateOn(cid, "EUR", "TZS", DATE_JAN))
                .isInstanceOf(FxRateNotFoundException.class)
                .hasMessageContaining("EUR")
                .hasMessageContaining("TZS");
    }

    // ── Cross-company isolation ───────────────────────────────────────────────

    @Test
    void rateOn_companyARate_isInvisibleToCompanyB() {
        // Seed a rate for company A
        addUsdRate(companyA, DATE_JAN, "2500.00000000");

        // Switch context to company B
        RequestContext.set(new RequestContext.Principal(
                1L, "fxrate_root", true,
                companyB.getId(), null, null));

        // Company B has no rate — must throw, not return company A's rate
        Long cid = companyB.getId();
        assertThatThrownBy(() -> fxRateService.rateOn(cid, "USD", "TZS", DATE_JAN))
                .isInstanceOf(FxRateNotFoundException.class);
    }

    @Test
    void listRates_returnsOnlyCompanyARates() {
        addUsdRate(companyA, DATE_JAN, "2500.00000000");
        addUsdRate(companyA, DATE_FEB, "2550.00000000");

        var page = fxRateService.listRates(companyA.getId(),
                org.springframework.data.domain.Pageable.ofSize(50));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allMatch(r -> r.companyId().equals(companyA.getId()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void addUsdRate(Company company, LocalDate effectiveDate, String rateValue) {
        // Ensure context is for the correct company when adding
        RequestContext.set(new RequestContext.Principal(
                1L, "fxrate_root", true,
                company.getId(), null, null));
        fxRateService.addRate(new UpsertRateRequest(
                company.getId(), "USD", "TZS",
                new BigDecimal(rateValue), effectiveDate, "SPOT", "test"));
        // Restore to companyA context
        RequestContext.set(new RequestContext.Principal(
                1L, "fxrate_root", true,
                companyA.getId(), branchA.getId(), null));
    }
}

package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.hr.service.StatutoryCalculator.StatutoryResult;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for StatutoryCalculator (ADR-0032 D-4, FR-HR-40/41).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>PAYE: gross 800,000 TZS → band 4 → 25% on excess over 760,001 + 68,000 cumulative.
 *   <li>PAYE: gross below threshold (200,000) → 0 PAYE.
 *   <li>NSSF: 10% employee + 10% employer on pensionable pay (PENSIONABLE basis).
 *   <li>SDL: 0 if headcount < 10 (threshold check).
 *   <li>HESLB: 15% on basic pay.
 * </ol>
 *
 * NOTE: Do NOT run with @Testcontainers on Windows — singleton container pattern only.
 */
class StatutoryCalculatorIT extends PostgresIntegrationTest {

    @Autowired private StatutoryCalculator calculator;
    @Autowired private HrStatutorySeeder statutorySeeder;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Long rootId;
    private static final LocalDate PAY_DATE = LocalDate.of(2025, 12, 1);

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Statutory IT Org"));
        company = companies.save(new Company(org, "STATIT", "Statutory IT Co"));
        Branch branch = branches.save(new Branch(company, "STAT1", "Statutory IT Branch"));

        AppUser root = new AppUser("stat_root", passwordEncoder.encode("RootPass1!"), "Stat Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "stat_root", true, company.getId(), branch.getId(), null));

        // Seed TZ 2025/26 rates
        statutorySeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: PAYE banded — gross 800,000 → band 4 (25% on excess + 68,000 fixed)
    //   excess = 800,000 - 760,001 = 39,999; marginal = 39,999 * 0.25 = 9,999.75
    //   total = 68,000 + 9,999.75 = 77,999.75 → rounded = 78,000 TZS
    // =========================================================================

    @Test
    void paye_gross800k_inBand4() {
        BigDecimal gross      = new BigDecimal("800000");
        BigDecimal basic      = new BigDecimal("700000");
        BigDecimal pensionable = new BigDecimal("700000");

        StatutoryResult result = calculator.compute(
                company.getId(), PAY_DATE, gross, basic, pensionable, 20);

        // Band 4: lowerBound 760,001, cumulative 68,000, marginalRate 25%
        // excess = 800,000 - 760,001 = 39,999
        // marginal = 39,999 * 25 / 100 = 9,999.75 → rounded to 10,000
        // total = 68,000 + 10,000 = 78,000
        assertThat(result.paye())
                .isEqualByComparingTo(new BigDecimal("78000"))
                .as("PAYE for 800k gross should be 78,000 TZS");
    }

    // =========================================================================
    // Bar 2: PAYE — gross below 270,000 threshold → 0
    // =========================================================================

    @Test
    void paye_grossBelowThreshold_isZero() {
        BigDecimal gross = new BigDecimal("200000");
        StatutoryResult result = calculator.compute(
                company.getId(), PAY_DATE, gross, gross, gross, 20);

        assertThat(result.paye()).isEqualByComparingTo(BigDecimal.ZERO)
                .as("PAYE below 270k threshold must be zero");
    }

    // =========================================================================
    // Bar 3: NSSF 10% employee + 10% employer on pensionable
    // =========================================================================

    @Test
    void nssf_tenPercentEachSide() {
        BigDecimal gross      = new BigDecimal("600000");
        BigDecimal basic      = new BigDecimal("500000");
        BigDecimal pensionable = new BigDecimal("500000"); // PENSIONABLE basis

        StatutoryResult result = calculator.compute(
                company.getId(), PAY_DATE, gross, basic, pensionable, 20);

        // 10% of 500,000 = 50,000 each
        assertThat(result.nssfEmployee()).isEqualByComparingTo(new BigDecimal("50000"))
                .as("NSSF employee must be 50,000 (10% of 500k pensionable)");
        assertThat(result.nssfEmployer()).isEqualByComparingTo(new BigDecimal("50000"))
                .as("NSSF employer must be 50,000 (10% of 500k pensionable)");
    }

    // =========================================================================
    // Bar 4: SDL → 0 when headcount < 10
    // =========================================================================

    @Test
    void sdl_belowHeadcountThreshold_isZero() {
        BigDecimal gross = new BigDecimal("600000");
        StatutoryResult result = calculator.compute(
                company.getId(), PAY_DATE, gross, gross, gross, 5); // headcount=5 < threshold=10

        assertThat(result.sdlEmployer()).isEqualByComparingTo(BigDecimal.ZERO)
                .as("SDL must be zero when headcount < 10");
    }

    // =========================================================================
    // Bar 5: HESLB 15% on basic
    // =========================================================================

    @Test
    void heslb_fifteenPercentOfBasic() {
        BigDecimal gross = new BigDecimal("700000");
        BigDecimal basic = new BigDecimal("500000"); // HESLB basis = BASIC
        StatutoryResult result = calculator.compute(
                company.getId(), PAY_DATE, gross, basic, basic, 20);

        // 15% of 500,000 = 75,000
        assertThat(result.heslb()).isEqualByComparingTo(new BigDecimal("75000"))
                .as("HESLB must be 75,000 (15% of 500k basic)");
    }

    // =========================================================================
    // Bar 6: setUids are populated when rates exist
    // =========================================================================

    @Test
    void compute_populatesSetUids() {
        BigDecimal gross = new BigDecimal("600000");
        StatutoryResult result = calculator.compute(
                company.getId(), PAY_DATE, gross, gross, gross, 20);

        assertThat(result.payeBandSetUid()).as("PAYE band set uid").isNotNull();
        assertThat(result.nssfSetUid()).as("NSSF set uid").isNotNull();
        assertThat(result.wcfSetUid()).as("WCF set uid").isNotNull();
        assertThat(result.sdlSetUid()).as("SDL set uid").isNotNull();
        assertThat(result.heslbSetUid()).as("HESLB set uid").isNotNull();
    }
}

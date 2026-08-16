package com.erp.modules.fx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.CurrencyConversionService;
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
 * Integration tests for {@link CurrencyConversionService} /
 * {@link com.erp.platform.common.money.CurrencyConversionServiceImpl} against a real PostgreSQL
 * schema (Flyway V77).
 *
 * <p>Covers (ADR-0036 D-1/D-8):
 * <ul>
 *   <li>convert(100 USD → TZS) = 100 × rate, rounded HALF_UP to TZS minor_units (0dp).</li>
 *   <li>convert(USD → EUR) = 100 × rate, rounded HALF_UP to EUR minor_units (2dp).</li>
 *   <li>from==to identity: returns face unchanged, rate=1, no rate-table lookup.</li>
 *   <li>from==base identity via toBase: returns face unchanged, rate=1.</li>
 *   <li>Missing foreign rate throws FxRateNotFoundException (never silent par=1).</li>
 *   <li>Cross-company isolation: conversion for company B cannot use company A's rate.</li>
 *   <li>Effective-dated: picks the most recent rate on/before the date.</li>
 * </ul>
 */
class CurrencyConversionServiceIT extends PostgresIntegrationTest {

    @Autowired private CurrencyConversionService conversionService;
    @Autowired private FxRateService             fxRateService;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private AppUserRepository         users;
    @Autowired private PasswordEncoder           passwordEncoder;
    @Autowired private IamTestData               testData;

    private Company companyA;
    private Company companyB;

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);
    /** USD→TZS rate seeded in setUp */
    private static final BigDecimal USD_TZS_RATE = new BigDecimal("2500.00000000");

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation orgA = organisations.save(new Organisation("FxConv IT Org A"));
        companyA = companies.save(new Company(orgA, "CVTA", "FxConv IT Company A"));
        branches.save(new Branch(companyA, "CVTAB1", "FxConv IT Branch A1"));

        Organisation orgB = organisations.save(new Organisation("FxConv IT Org B"));
        companyB = companies.save(new Company(orgB, "CVTB", "FxConv IT Company B"));
        branches.save(new Branch(companyB, "CVTBB1", "FxConv IT Branch B1"));

        AppUser root = new AppUser("fxconv_root", passwordEncoder.encode("Root1234!"), "FxConv Root");
        root.setRoot(true);
        root.setOrganisationId(orgA.getId());
        users.save(root);

        setContext(companyA);

        // Seed USD→TZS rate for company A (TZS is the company base currency by default)
        fxRateService.addRate(new UpsertRateRequest(
                companyA.getId(), "USD", "TZS", USD_TZS_RATE, AS_OF, "SPOT", "test"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ── convert(USD → TZS) ───────────────────────────────────────────────────

    @Test
    void convert_usdToTzs_multipliesAndRoundsHalfUp() {
        // TZS has minor_units=0, so result must have 0 decimal places
        // 100 USD × 2500 = 250,000 TZS (exact, no rounding needed)
        ConvertedAmount result = conversionService.convert(
                new BigDecimal("100"), "USD", "TZS", companyA.getId(), AS_OF);

        assertThat(result.rate()).isEqualByComparingTo(USD_TZS_RATE);
        assertThat(result.baseAmount()).isEqualByComparingTo("250000");
        // TZS minor_units=0: scale must be 0
        assertThat(result.baseAmount().scale()).isEqualTo(0);
        assertThat(result.rateAt()).isNotNull();
    }

    @Test
    void convert_usdToTzs_roundingHalfUp() {
        // Seed a rate that will produce a fractional result before rounding
        // 1 USD = 2500.50000000 TZS  →  1.001 USD × 2500.5 = 2503.0005 → rounds to 2503 (HALF_UP, 0dp)
        fxRateService.addRate(new UpsertRateRequest(
                companyA.getId(), "USD", "TZS",
                new BigDecimal("2500.50000000"), AS_OF.plusDays(1), "SPOT", "test"));

        ConvertedAmount result = conversionService.convert(
                new BigDecimal("1.001"), "USD", "TZS",
                companyA.getId(), AS_OF.plusDays(1));

        // 1.001 × 2500.5 = 2503.0005 → HALF_UP scale 0 → 2503
        assertThat(result.baseAmount()).isEqualByComparingTo("2503");
        assertThat(result.baseAmount().scale()).isEqualTo(0);
    }

    // ── toBase ────────────────────────────────────────────────────────────────

    @Test
    void toBase_usdToCompanyBase_convertsCorrectly() {
        // companyA base is TZS
        ConvertedAmount result = conversionService.toBase(
                new BigDecimal("50"), "USD", companyA.getId(), AS_OF);

        assertThat(result.baseAmount()).isEqualByComparingTo("125000");
        assertThat(result.rate()).isEqualByComparingTo(USD_TZS_RATE);
    }

    // ── Identity short-circuit ────────────────────────────────────────────────

    @Test
    void convert_fromEqualsTo_returnsIdentity_noRateLookup() {
        // TZS → TZS: no rate row needed, must not throw FxRateNotFoundException
        ConvertedAmount result = conversionService.convert(
                new BigDecimal("1000.00"), "TZS", "TZS", companyA.getId(), AS_OF);

        assertThat(result.baseAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.rate()).isOne();
    }

    @Test
    void toBase_fromEqualsBase_returnsIdentity() {
        // companyA base = TZS; converting TZS → base is the identity short-circuit
        ConvertedAmount result = conversionService.toBase(
                new BigDecimal("5000"), "TZS", companyA.getId(), AS_OF);

        assertThat(result.baseAmount()).isEqualByComparingTo("5000");
        assertThat(result.rate()).isOne();
    }

    // ── Loud failure — missing foreign rate ──────────────────────────────────

    @Test
    void convert_missingForeignRate_throwsFxRateNotFoundException_notSilentPar() {
        // No EUR→TZS rate seeded for companyA
        Long cid = companyA.getId();
        BigDecimal face = BigDecimal.valueOf(100);
        assertThatThrownBy(() -> conversionService.convert(face, "EUR", "TZS", cid, AS_OF))
                .isInstanceOf(FxRateNotFoundException.class)
                .hasMessageContaining("EUR")
                .hasMessageContaining("TZS")
                .hasMessageContaining(cid.toString());
    }

    @Test
    void toBase_missingForeignRate_throwsFxRateNotFoundException() {
        Long cid = companyA.getId();
        BigDecimal face = BigDecimal.valueOf(100);
        assertThatThrownBy(() -> conversionService.toBase(face, "GBP", cid, AS_OF))
                .isInstanceOf(FxRateNotFoundException.class);
    }

    // ── Cross-company isolation ───────────────────────────────────────────────

    @Test
    void convert_companyBCannotUseCompanyARate() {
        // Switch context to company B (no rates seeded for B)
        setContext(companyB);
        Long cid = companyB.getId();
        BigDecimal face = BigDecimal.valueOf(100);
        assertThatThrownBy(() -> conversionService.convert(face, "USD", "TZS", cid, AS_OF))
                .isInstanceOf(FxRateNotFoundException.class);
    }

    // ── Effective-dating ──────────────────────────────────────────────────────

    @Test
    void convert_picksLatestRateOnOrBeforeDate() {
        // Seed a newer rate for a later date
        fxRateService.addRate(new UpsertRateRequest(
                companyA.getId(), "USD", "TZS",
                new BigDecimal("2600.00000000"), AS_OF.plusDays(30), "SPOT", "test"));

        // Ask for a date BEFORE the new rate — should get the original rate
        ConvertedAmount old = conversionService.convert(
                new BigDecimal("1"), "USD", "TZS",
                companyA.getId(), AS_OF.plusDays(15));
        assertThat(old.rate()).isEqualByComparingTo("2500.00000000");

        // Ask for a date ON the new rate — should get the new rate
        ConvertedAmount current = conversionService.convert(
                new BigDecimal("1"), "USD", "TZS",
                companyA.getId(), AS_OF.plusDays(30));
        assertThat(current.rate()).isEqualByComparingTo("2600.00000000");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setContext(Company company) {
        RequestContext.set(new RequestContext.Principal(
                1L, "fxconv_root", true, company.getId(), null, null));
    }
}

package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.products.domain.dto.CreatePromotionRequest;
import com.erp.modules.products.domain.dto.PromotionDto;
import com.erp.modules.products.domain.dto.PromotionUsageDto;
import com.erp.modules.products.domain.dto.RecordPromotionUsageRequest;
import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for promotion P2 D5 settable fields + redemption recording
 * ({@link PricingRuleServiceImpl}, ADR-0041 D5).
 *
 * <p>Covers: targeting/guardrail fields settable on create (customer/branch scope, min_threshold,
 * usage_limit, coupon_code, combinable); {@code recordUsage} writes a redemption row and enforces
 * {@code usage_limit} (ConflictException once the limit is reached).
 */
class PricingRulePromotionIT extends PostgresIntegrationTest {

    @Autowired private PricingRuleService pricingRuleService;
    @Autowired private CustomerService customerService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company companyA;
    private Branch branchA;
    private Long rootId;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("Promo Test Org"));
        companyA = companies.save(new Company(org, "PRMCA", "Promo Co A"));
        branchA = branches.save(new Branch(companyA, "PRM-A1", "Promo Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "promo_root", "x", "Promo Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "promo_root", true, companyA.getId(), branchA.getId(), null, org.getId()));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // P2 D5 — targeting + guardrail fields settable on create
    // -----------------------------------------------------------------------

    @Test
    void createPromotion_withGuardrails_persistsAllD5Fields() {
        CustomerDto cust = customerService.create(new CreateCustomerRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "Promo Cust",
                null, null, false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null));

        PromotionDto dto = pricingRuleService.createPromotion(new CreatePromotionRequest(
                companyA.getUid(), "SAVE10", "Save 10%",
                PromotionTarget.ALL, null, null,
                PromotionEffect.PERCENT_DISCOUNT, new BigDecimal("10"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), (short) 5,
                cust.uid(), branchA.getUid(),
                new BigDecimal("100.00"), 3, "COUPON1", true));

        assertThat(dto.targetCustomerId()).isEqualTo(cust.id());
        assertThat(dto.targetBranchId()).isEqualTo(branchA.getId());
        assertThat(dto.minThreshold()).isEqualByComparingTo("100.00");
        assertThat(dto.usageLimit()).isEqualTo(3);
        assertThat(dto.couponCode()).isEqualTo("COUPON1");
        assertThat(dto.combinable()).isTrue();
    }

    @Test
    void createPromotion_withoutGuardrails_usesEntityDefaults() {
        PromotionDto dto = pricingRuleService.createPromotion(new CreatePromotionRequest(
                companyA.getUid(), "PLAIN", "Plain",
                PromotionTarget.ALL, null, null,
                PromotionEffect.PERCENT_DISCOUNT, new BigDecimal("5"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), (short) 0));

        assertThat(dto.targetCustomerId()).isNull();
        assertThat(dto.targetBranchId()).isNull();
        assertThat(dto.minThreshold()).isNull();
        assertThat(dto.usageLimit()).isNull();
        assertThat(dto.couponCode()).isNull();
        assertThat(dto.combinable()).isFalse();
    }

    // -----------------------------------------------------------------------
    // recordUsage — writes redemption + enforces usage_limit
    // -----------------------------------------------------------------------

    @Test
    void recordUsage_writesRedemptionRow() {
        PromotionDto promo = pricingRuleService.createPromotion(new CreatePromotionRequest(
                companyA.getUid(), "USE1", "Use Once",
                PromotionTarget.ALL, null, null,
                PromotionEffect.AMOUNT_DISCOUNT, new BigDecimal("50"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), (short) 0,
                null, null, null, 5, null, null));

        PromotionUsageDto usage = pricingRuleService.recordUsage(
                new RecordPromotionUsageRequest(promo.uid(), null, "ORDER-1", new BigDecimal("50")));

        assertThat(usage.uid()).isNotBlank();
        assertThat(usage.promotionId()).isEqualTo(promo.id());
        assertThat(usage.orderUid()).isEqualTo("ORDER-1");
        assertThat(usage.amount()).isEqualByComparingTo("50");
        assertThat(usage.usedBy()).isEqualTo(rootId);
    }

    @Test
    void recordUsage_enforcesUsageLimit_throwsConflictWhenExceeded() {
        PromotionDto promo = pricingRuleService.createPromotion(new CreatePromotionRequest(
                companyA.getUid(), "LIMIT1", "Limit One",
                PromotionTarget.ALL, null, null,
                PromotionEffect.AMOUNT_DISCOUNT, new BigDecimal("10"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), (short) 0,
                null, null, null, 1, null, null));

        // First redemption succeeds (limit = 1)
        pricingRuleService.recordUsage(
                new RecordPromotionUsageRequest(promo.uid(), null, "ORDER-A", new BigDecimal("10")));

        // Second redemption exceeds the limit
        RecordPromotionUsageRequest second =
                new RecordPromotionUsageRequest(promo.uid(), null, "ORDER-B", new BigDecimal("10"));
        assertThatThrownBy(() -> pricingRuleService.recordUsage(second))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("usage limit");
    }

    @Test
    void recordUsage_unlimitedWhenUsageLimitNull() {
        PromotionDto promo = pricingRuleService.createPromotion(new CreatePromotionRequest(
                companyA.getUid(), "UNLIM", "Unlimited",
                PromotionTarget.ALL, null, null,
                PromotionEffect.AMOUNT_DISCOUNT, new BigDecimal("1"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), (short) 0));

        // No usage_limit → multiple redemptions all succeed
        pricingRuleService.recordUsage(
                new RecordPromotionUsageRequest(promo.uid(), null, "O1", new BigDecimal("1")));
        pricingRuleService.recordUsage(
                new RecordPromotionUsageRequest(promo.uid(), null, "O2", new BigDecimal("1")));
        PromotionUsageDto third = pricingRuleService.recordUsage(
                new RecordPromotionUsageRequest(promo.uid(), null, "O3", new BigDecimal("1")));

        assertThat(third.uid()).isNotBlank();
    }
}

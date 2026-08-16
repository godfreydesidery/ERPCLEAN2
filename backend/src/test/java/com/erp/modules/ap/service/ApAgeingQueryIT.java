package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.ApAgeingRowDto;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ar.domain.enums.AgeingBucket;
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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link ApAgeingQuery} (ADR-0015 D-7, FR-AP-AGE).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>No open bills → all five buckets present, all zero.
 *   <li>A bill due 45 days ago appears in D31_60 bucket.
 *   <li>A bill not yet due appears in CURRENT.
 *   <li>Two bills in different buckets → amounts isolated correctly.
 * </ol>
 */
class ApAgeingQueryIT extends PostgresIntegrationTest {

    @Autowired private ApAgeingQuery          ageingQuery;
    @Autowired private ApOpeningBalanceService openingBalanceService;
    @Autowired private ApGlSeeder             apGlSeeder;
    @Autowired private SupplierService        supplierService;
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
    private String  supplierUid;
    private Long    supplierId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AP Age IT Org"));
        company    = companies.save(new Company(org, "APAGE", "AP Age IT Co"));
        branch     = branches.save(new Branch(company, "APAGE1", "AP Age IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("ap_age_root", passwordEncoder.encode("ApAg3R00t!Xx"), "AP Age Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_age_root", true, company.getId(), branch.getId(), null));

        var sup = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "Ageing Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
        supplierUid = sup.uid();
        supplierId  = sup.id();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: No open bills → all five buckets present, all zero
    // =========================================================================

    @Test
    void ageing_noBills_allBucketsZero() {
        List<ApAgeingRowDto> rows = ageingQuery.ageing(
                company.getId(), supplierId, LocalDate.now());

        assertThat(rows).hasSize(5);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.amount()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(rows.stream().map(ApAgeingRowDto::bucket).toList())
                .containsExactly(AgeingBucket.CURRENT, AgeingBucket.D1_30,
                        AgeingBucket.D31_60, AgeingBucket.D61_90, AgeingBucket.D90_PLUS);
    }

    // =========================================================================
    // Bar 2: Bill due 45 days ago → D31_60 bucket
    // =========================================================================

    @Test
    void ageing_billDue45DaysAgo_appearsInD31_60() {
        BigDecimal amount = new BigDecimal("3000.00");
        openingBalance("OB-AGE-D45", amount, LocalDate.now().minusDays(45));

        List<ApAgeingRowDto> rows = ageingQuery.ageing(
                company.getId(), supplierId, LocalDate.now());

        BigDecimal d31_60 = bucketAmount(rows, AgeingBucket.D31_60);
        assertThat(d31_60).isEqualByComparingTo(amount);

        BigDecimal current = bucketAmount(rows, AgeingBucket.CURRENT);
        assertThat(current).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 3: Bill due tomorrow → CURRENT
    // =========================================================================

    @Test
    void ageing_billDueTomorrow_appearsInCurrent() {
        BigDecimal amount = new BigDecimal("1500.00");
        openingBalance("OB-AGE-CURR", amount, LocalDate.now().plusDays(1));

        List<ApAgeingRowDto> rows = ageingQuery.ageing(
                company.getId(), supplierId, LocalDate.now());

        BigDecimal current = bucketAmount(rows, AgeingBucket.CURRENT);
        assertThat(current).isEqualByComparingTo(amount);
    }

    // =========================================================================
    // Bar 4: Two bills in different buckets → amounts isolated
    // =========================================================================

    @Test
    void ageing_twoBillsInDifferentBuckets_amountsIsolated() {
        BigDecimal amt1 = new BigDecimal("1000.00"); // D31_60
        BigDecimal amt2 = new BigDecimal("2000.00"); // CURRENT
        openingBalance("OB-AGE-TWO-A", amt1, LocalDate.now().minusDays(40));
        openingBalance("OB-AGE-TWO-B", amt2, LocalDate.now().plusDays(10));

        List<ApAgeingRowDto> rows = ageingQuery.ageing(
                company.getId(), supplierId, LocalDate.now());

        assertThat(bucketAmount(rows, AgeingBucket.D31_60)).isEqualByComparingTo(amt1);
        assertThat(bucketAmount(rows, AgeingBucket.CURRENT)).isEqualByComparingTo(amt2);
        assertThat(bucketAmount(rows, AgeingBucket.D1_30)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void openingBalance(String ref, BigDecimal amount, LocalDate dueDate) {
        // billDate must be <= dueDate (chk_supplier_bill_dates); use the earlier of today or dueDate
        LocalDate billDate = dueDate.isBefore(LocalDate.now()) ? dueDate : LocalDate.now();
        openingBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, supplierUid, amount, "TZS",
                billDate, dueDate, ref));
    }

    private static BigDecimal bucketAmount(List<ApAgeingRowDto> rows, AgeingBucket bucket) {
        return rows.stream()
                .filter(r -> r.bucket() == bucket)
                .map(ApAgeingRowDto::amount)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }
}

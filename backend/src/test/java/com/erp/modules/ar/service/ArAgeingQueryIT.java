package com.erp.modules.ar.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.dto.ArAgeingRowDto;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
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
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.platform.common.api.ForbiddenException;
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
 * Integration tests for {@link ArAgeingQuery} (ADR-0014 D-7, FR-AR-08).
 *
 * <p>Strategy: all due-dates are expressed relative to {@code LocalDate.now()} so that the
 * classify() buckets are stable regardless of when the suite runs.
 *
 * <p>Bucket rules (from ArAgeingQuery.classify):
 * <pre>
 *   daysOverdue = ChronoUnit.DAYS.between(dueDate, asAt)
 *   &lt;= 0        → CURRENT
 *   1..30       → D1_30
 *   31..60      → D31_60
 *   61..90      → D61_90
 *   &gt; 90        → D90_PLUS
 * </pre>
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Five invoices spanning all buckets → each lands in the correct bucket; total matches.
 *   <li>Customer with no open items → all buckets zero.
 *   <li>Cross-tenant isolation: company A principal cannot age company B's customer.
 *   <li>Boundary: due == asAt (daysOverdue == 0) → CURRENT.
 *   <li>Boundary: daysOverdue == 30 → D1_30 (still ≤ 30).
 *   <li>Boundary: daysOverdue == 31 → D31_60.
 * </ol>
 */
class ArAgeingQueryIT extends PostgresIntegrationTest {

    @Autowired private ArAgeingQuery ageingQuery;
    @Autowired private ArOpeningBalanceService openingBalanceService;
    @Autowired private ArGlSeeder arGlSeeder;
    @Autowired private CustomerService customerService;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private Long rootId;

    private String companyUid;
    private Long companyId;
    private Long orgId;
    private Long customerId;
    private String customerUidStr; // captured from create() — used by openItem() helper

    private static final String TZS = "TZS";

    /** Fixed reference date for all ageing computations in this suite. */
    private static final LocalDate AS_AT = LocalDate.now();

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AR Ageing IT Org"));
        company    = companies.save(new Company(org, "ARAG", "AR Ageing IT Co"));
        branch     = branches.save(new Branch(company, "ARAG1", "AR Ageing IT Branch"));
        companyUid = company.getUid();
        companyId  = company.getId();
        orgId      = org.getId();

        AppUser root = new AppUser("arag_root", passwordEncoder.encode("RootPass1!"), "ARAG Root");
        root.setRoot(true);
        root.setOrganisationId(orgId);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "arag_root", true, companyId, branch.getId(), null));

        var cust = customerService.create(new CreateCustomerRequest(
                companyId, PartyType.INDIVIDUAL, "Ageing Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));
        customerId    = cust.id();
        customerUidStr = cust.uid();

        chartOfAccountService.seedDefaults(companyId);
        fiscalCalendarService.seedCurrentYear(companyId);
        glConfigService.seedDefaults(companyId);
        arGlSeeder.seedDefaults(companyId);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: Five invoices spanning all five buckets → correct bucket placement
    // =========================================================================

    @Test
    void ageing_fiveInvoicesSpanningAllBuckets_eachLandsInCorrectBucket() {
        /*
         * Create one invoice per bucket. Due-date relative to AS_AT:
         *   CURRENT  : due today  → daysOverdue = 0  → AS_AT
         *   D1_30    : overdue 15 → due = AS_AT - 15
         *   D31_60   : overdue 45 → due = AS_AT - 45
         *   D61_90   : overdue 75 → due = AS_AT - 75
         *   D90_PLUS : overdue 120 → due = AS_AT - 120
         */
        BigDecimal amtCurrent = new BigDecimal("1000");
        BigDecimal amt1to30   = new BigDecimal("2000");
        BigDecimal amt31to60  = new BigDecimal("3000");
        BigDecimal amt61to90  = new BigDecimal("4000");
        BigDecimal amt90plus  = new BigDecimal("5000");

        openItem(amtCurrent, AS_AT);
        openItem(amt1to30,   AS_AT.minusDays(15));
        openItem(amt31to60,  AS_AT.minusDays(45));
        openItem(amt61to90,  AS_AT.minusDays(75));
        openItem(amt90plus,  AS_AT.minusDays(120));

        List<ArAgeingRowDto> rows = ageingQuery.ageing(companyId, customerId, AS_AT);

        assertThat(rows).hasSize(5);

        assertBucket(rows, AgeingBucket.CURRENT,  amtCurrent);
        assertBucket(rows, AgeingBucket.D1_30,    amt1to30);
        assertBucket(rows, AgeingBucket.D31_60,   amt31to60);
        assertBucket(rows, AgeingBucket.D61_90,   amt61to90);
        assertBucket(rows, AgeingBucket.D90_PLUS, amt90plus);

        BigDecimal expectedTotal = amtCurrent.add(amt1to30).add(amt31to60)
                .add(amt61to90).add(amt90plus);
        BigDecimal actualTotal = rows.stream()
                .map(ArAgeingRowDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actualTotal)
                .as("total across all ageing buckets must equal sum of all outstanding amounts")
                .isEqualByComparingTo(expectedTotal);
    }

    // =========================================================================
    // Bar 2: Customer with no open items → all buckets zero
    // =========================================================================

    @Test
    void ageing_noOpenItems_allBucketsAreZero() {
        List<ArAgeingRowDto> rows = ageingQuery.ageing(companyId, customerId, AS_AT);

        assertThat(rows).hasSize(5);
        for (ArAgeingRowDto row : rows) {
            assertThat(row.amount())
                    .as("bucket %s must be zero when customer has no open items", row.bucket())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // Bar 3: Cross-tenant isolation — company A principal cannot age company B's customer
    // =========================================================================

    @Test
    void ageing_crossTenant_blocked() {
        Organisation org2  = organisations.save(new Organisation("Ageing Cross Org"));
        Company company2   = companies.save(new Company(org2, "ARAG2", "Ageing Cross Co"));
        Branch branch2     = branches.save(new Branch(company2, "ARAG2B", "Ageing Cross Branch"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "arag_root", true, company2.getId(), branch2.getId(), null));
        Long customer2Id = customerService.create(new CreateCustomerRequest(
                company2.getId(), PartyType.INDIVIDUAL, "Cross Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).id();

        // Cross-tenant DENIAL must be asserted with a NON-ROOT principal: root legitimately
        // bypasses tenant scope (ScopeGuard line ~197), so it would NOT be denied. A non-root user
        // scoped to company A is denied cleanly (ForbiddenException) when reaching for company B.
        AppUser nonRoot = users.save(inOrganisation(new AppUser(
                "arag_nonroot", passwordEncoder.encode("Pass1!"), "ARAG NonRoot"), orgId));
        RequestContext.set(new RequestContext.Principal(
                nonRoot.getId(), "arag_nonroot", false, companyId, branch.getId(), null));

        // Pre-build args outside the lambda (S5778: only one throwing call inside assertThatThrownBy)
        Long company2Id = company2.getId();
        assertThatThrownBy(() -> ageingQuery.ageing(company2Id, customer2Id, AS_AT))
                .as("non-root company A principal must not age company B's customer (ScopeGuard)")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void ageing_rootCrossCompany_onReadOnlyPath_succeeds_notReadOnlyTxError() {
        // ISSUES-REGISTER #11 regression: ROOT acting OUTSIDE its active company triggers a
        // ROOT_BYPASS audit write inside ageing()'s @Transactional(readOnly=true). Before the fix
        // that MANDATORY INSERT threw "cannot execute INSERT in a read-only transaction" (a 500 for
        // root viewing another company's ageing). With recordIndependent (REQUIRES_NEW) it must now
        // return the data. (Root legitimately bypasses tenant scope — it is NOT denied.)
        openItem(new BigDecimal("500"), AS_AT); // open item for company A's customer

        Organisation org2 = organisations.save(new Organisation("Root Bypass Org"));
        Company company2  = companies.save(new Company(org2, "ARRB2", "Root Bypass Co"));
        Branch branch2    = branches.save(new Branch(company2, "ARRB2B", "Root Bypass Branch"));

        // root scoped to company2, reaching into company A's read-only ageing
        RequestContext.set(new RequestContext.Principal(
                rootId, "arag_root", true, company2.getId(), branch2.getId(), null));

        List<ArAgeingRowDto> rows = ageingQuery.ageing(companyId, customerId, AS_AT);

        assertBucket(rows, AgeingBucket.CURRENT, new BigDecimal("500"));
    }

    // =========================================================================
    // Bar 4: Boundary — due == asAt → CURRENT (daysOverdue == 0)
    // =========================================================================

    @Test
    void ageing_dueDateEqualsAsAt_classifiedCurrent() {
        openItem(new BigDecimal("999"), AS_AT);

        List<ArAgeingRowDto> rows = ageingQuery.ageing(companyId, customerId, AS_AT);

        assertBucket(rows, AgeingBucket.CURRENT, new BigDecimal("999"));
        assertAllOtherBucketsZero(rows, AgeingBucket.CURRENT);
    }

    // =========================================================================
    // Bar 5: Boundary — daysOverdue == 30 → D1_30 (≤ 30, not yet D31_60)
    // =========================================================================

    @Test
    void ageing_daysOverdue30_classifiedD1_30() {
        openItem(new BigDecimal("888"), AS_AT.minusDays(30));

        List<ArAgeingRowDto> rows = ageingQuery.ageing(companyId, customerId, AS_AT);

        assertBucket(rows, AgeingBucket.D1_30, new BigDecimal("888"));
        assertAllOtherBucketsZero(rows, AgeingBucket.D1_30);
    }

    // =========================================================================
    // Bar 6: Boundary — daysOverdue == 31 → D31_60
    // =========================================================================

    @Test
    void ageing_daysOverdue31_classifiedD31_60() {
        openItem(new BigDecimal("777"), AS_AT.minusDays(31));

        List<ArAgeingRowDto> rows = ageingQuery.ageing(companyId, customerId, AS_AT);

        assertBucket(rows, AgeingBucket.D31_60, new BigDecimal("777"));
        assertAllOtherBucketsZero(rows, AgeingBucket.D31_60);
    }

    // =========================================================================
    // Bar 7: Company-wide ageing (Bug #5 fix) — customerId=null returns ALL customers' buckets
    // =========================================================================

    @Test
    void ageing_nullCustomerId_returnsCompanyWideAgeing() {
        // Two customers; create one open item each in different buckets.
        // customerId / customerUidStr = customer 1 (set in @BeforeEach)
        openItem(new BigDecimal("1000"), AS_AT);               // CURRENT for customer 1

        // Customer 2 — scoped to same company
        var cust2 = customerService.create(new CreateCustomerRequest(
                companyId, PartyType.INDIVIDUAL, "Ageing Customer 2",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));
        Long cust2Id   = cust2.id();
        String cust2Uid = cust2.uid();
        // Open item for customer 2 (overdue by 40 days → D31_60)
        String savedCustUid = customerUidStr;
        customerUidStr = cust2Uid;
        openItem(new BigDecimal("2000"), AS_AT.minusDays(40));
        customerUidStr = savedCustUid; // restore for other helpers

        // Null customerId → company-wide ageing aggregates both customers
        List<ArAgeingRowDto> rows = ageingQuery.ageing(companyId, null, AS_AT);

        assertThat(rows).hasSize(5);
        assertBucket(rows, AgeingBucket.CURRENT, new BigDecimal("1000"));
        assertBucket(rows, AgeingBucket.D31_60,  new BigDecimal("2000"));
    }

    @Test
    void ageing_nullCustomerId_noOpenItems_allBucketsZero() {
        // No invoices at all — company-wide ageing must return all zeros (not throw NPE / 400)
        List<ArAgeingRowDto> rows = ageingQuery.ageing(companyId, null, AS_AT);

        assertThat(rows).hasSize(5);
        for (ArAgeingRowDto row : rows) {
            assertThat(row.amount())
                    .as("bucket %s must be zero when company has no open items", row.bucket())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Creates an OPEN ar_invoice via opening balance with the given amount and due date. */
    private void openItem(BigDecimal amount, LocalDate dueDate) {
        openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUidStr, amount, TZS,
                dueDate,  // invoiceDate == dueDate; bucket classifies by dueDate
                dueDate,
                null));
    }

    private static void assertBucket(
            List<ArAgeingRowDto> rows, AgeingBucket bucket, BigDecimal expected) {
        BigDecimal actual = rows.stream()
                .filter(r -> bucket.equals(r.bucket()))
                .map(ArAgeingRowDto::amount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for bucket " + bucket));
        assertThat(actual)
                .as("bucket %s should contain %s", bucket, expected)
                .isEqualByComparingTo(expected);
    }

    private static void assertAllOtherBucketsZero(
            List<ArAgeingRowDto> rows, AgeingBucket nonZero) {
        rows.stream()
                .filter(r -> !nonZero.equals(r.bucket()))
                .forEach(r -> assertThat(r.amount())
                        .as("bucket %s must be zero when only %s has an invoice",
                                r.bucket(), nonZero)
                        .isEqualByComparingTo(BigDecimal.ZERO));
    }
}

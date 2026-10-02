package com.erp.modules.fixedassets.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.fixedassets.domain.dto.AssetCategoryDto;
import com.erp.modules.fixedassets.domain.dto.AssetDisposalDto;
import com.erp.modules.fixedassets.domain.dto.AssetRevaluationDto;
import com.erp.modules.fixedassets.domain.dto.CreateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunDto;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunLineDto;
import com.erp.modules.fixedassets.domain.dto.DisposeAssetRequest;
import com.erp.modules.fixedassets.domain.dto.FixedAssetDto;
import com.erp.modules.fixedassets.domain.dto.PlaceInServiceRequest;
import com.erp.modules.fixedassets.domain.dto.RegisterAssetRequest;
import com.erp.modules.fixedassets.domain.dto.RevalueAssetRequest;
import com.erp.modules.fixedassets.domain.dto.RunDepreciationRequest;
import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.modules.fixedassets.domain.enums.RevaluationDirection;
import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.reporting.service.AccountMovementQuery;
import com.erp.modules.reporting.service.StatementScope;
import com.erp.platform.bootstrap.CompanyProvisioningService;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixed-asset postings land in the ASSET's branch (live-test defects 1 and 4).
 *
 * <p>Defect 1: the depreciation run posted ONE journal stamped with the first eligible asset's
 * branch, so a van in BR-02 was charged to BR-01's P&amp;L (BR-01: expense 12,200,000 vs register
 * 200,000; BR-02: 0 vs 12,000,000). The run now posts one journal per asset branch.
 *
 * <p>Defect 4: a freshly provisioned company mapped DEPRECIATION_EXPENSE onto 5500 "Bad Debt
 * Expense" (AR seeds 5500 before FA's find-or-create adopted it). The company here goes through the
 * real provisioning chain, so the mapping under test is the one a new tenant actually gets.
 */
class DepreciationBranchPostingIT extends PostgresIntegrationTest {

    @Autowired private CompanyProvisioningService provisioning;
    @Autowired private AssetCategoryService       categoryService;
    @Autowired private FixedAssetService          assetService;
    @Autowired private DepreciationRunService     runService;
    @Autowired private AssetDisposalService       disposalService;
    @Autowired private AssetRevaluationService    revaluationService;
    @Autowired private FiscalCalendarService      fiscalCalendarService;
    @Autowired private TransactionTemplate        tx;
    @Autowired private GLConfigResolver           glConfig;
    @Autowired private AccountMovementQuery       movements;
    @Autowired private ChartOfAccountRepository   coaRepo;
    @Autowired private JournalEntryRepository     journalEntries;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            iamTestData;

    private Long   companyId;
    private Branch br1;
    private Branch br2;
    private FiscalPeriodDto period;

    private Long assetAccountId;
    private Long accumDepAccountId;
    private Long depExpenseAccountId;

    @BeforeEach
    void setUp() {
        iamTestData.clearAll();

        Organisation org = organisations.save(new Organisation("FA-Branch-Org"));
        Company co = companies.save(new Company(org, "FABR", "FA Branch Co"));
        companyId = co.getId();
        br1 = new Branch(co, "BR-01", "Dar Branch");
        br1.setDefault(true);
        br1 = branches.save(br1);
        br2 = branches.save(new Branch(co, "BR-02", "Arusha Branch"));

        AppUser root = new AppUser("fabr_root", passwordEncoder.encode("RootPass12345"), "FA Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "fabr_root", true, companyId, br1.getId(), null));

        // The real new-company provisioning chain — not a hand-picked subset of seeders.
        provisioning.provisionDefaults(companyId, "TZS", "TZS", List.of("TZS"));

        period = fiscalCalendarService.listPeriods(companyId).stream().findFirst().orElseThrow();

        assetAccountId      = resolve(companyId, GlConfigKey.FIXED_ASSETS).getId();
        accumDepAccountId   = resolve(companyId, GlConfigKey.ACCUMULATED_DEPRECIATION).getId();
        depExpenseAccountId = resolve(companyId, GlConfigKey.DEPRECIATION_EXPENSE).getId();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        iamTestData.clearAll();
    }

    @Test
    void newCompany_mapsDepreciationExpenseToADepreciationAccount_notBadDebts() {
        var depExpense = resolve(companyId, GlConfigKey.DEPRECIATION_EXPENSE);
        assertThat(depExpense.getName()).isEqualTo("Depreciation Expense");
        assertThat(depExpense.getAccountCode()).isEqualTo(FixedAssetGlSeeder.DEPRECIATION_EXPENSE_CODE);

        var badDebt = resolve(companyId, GlConfigKey.BAD_DEBT_EXPENSE);
        assertThat(badDebt.getAccountCode()).isEqualTo("5500");
        assertThat(badDebt.getName()).isEqualTo("Bad Debt Expense");
        assertThat(depExpense.getId()).isNotEqualTo(badDebt.getId());

        // Re-provisioning is idempotent: it neither duplicates nor re-points anything.
        provisioning.provisionDefaults(companyId, "TZS", "TZS", List.of("TZS"));
        assertThat(resolve(companyId, GlConfigKey.DEPRECIATION_EXPENSE).getId())
                .isEqualTo(depExpense.getId());
    }

    @Test
    void depreciationRun_postsOneJournalPerAssetBranch_andEachBranchPnlEqualsItsRegisterCharge() {
        AssetCategoryDto cat = categoryService.create(new CreateAssetCategoryRequest(
                companyId, "VEH", "Motor Vehicles",
                DepreciationMethod.STRAIGHT_LINE, 12, null,
                assetAccountId, accumDepAccountId, depExpenseAccountId));

        // BR-01: 2,400,000 straight-line over 12 periods -> 200,000 this period.
        FixedAssetDto laptop = register(cat.id(), br1.getId(), "Laptop", "2400000.00",
                DepreciationMethod.STRAIGHT_LINE, 12, null);
        // BR-02: the live van — 48,000,000 reducing balance at 25 (per period) -> 12,000,000.
        FixedAssetDto van = register(cat.id(), br2.getId(), "Delivery Van", "48000000.00",
                DepreciationMethod.REDUCING_BALANCE, 60, new BigDecimal("25"));

        FixedAssetDto laptopIn = assetService.placeInService(laptop.uid(),
                new PlaceInServiceRequest(period.startDate()));
        FixedAssetDto vanIn = assetService.placeInService(van.uid(),
                new PlaceInServiceRequest(period.startDate()));

        // Capitalisation carries the asset's branch.
        assertThat(branchOf(laptopIn.capitalisedGlEntryUid())).isEqualTo(br1.getId());
        assertThat(branchOf(vanIn.capitalisedGlEntryUid())).isEqualTo(br2.getId());

        DepreciationRunDto run = runService.post(
                new RunDepreciationRequest(companyId, period.uid(), period.startDate()));

        // One journal per branch, each stamped with its branch and carrying the run uid.
        assertThat(run.glEntryUids()).hasSize(2);
        assertThat(run.glEntryUid()).isEqualTo(run.glEntryUids().get(0));
        assertThat(run.glEntryUids().stream().map(this::branchOf).toList())
                .containsExactlyInAnyOrder(br1.getId(), br2.getId());
        for (String uid : run.glEntryUids()) {
            JournalEntry je = journalEntries.findByUid(uid).orElseThrow();
            assertThat(je.getSourceRef()).isEqualTo(run.uid());
        }
        assertThat(runService.getByUid(run.uid()).glEntryUids())
                .containsExactlyElementsOf(run.glEntryUids());

        // The register's charge per branch (from the run lines + each asset's branch)...
        Map<Long, Long> branchByAssetId = Map.of(laptop.id(), br1.getId(), van.id(), br2.getId());
        Map<Long, BigDecimal> registerByBranch = run.lines().stream().collect(Collectors.groupingBy(
                l -> branchByAssetId.get(l.fixedAssetId()),
                Collectors.reducing(BigDecimal.ZERO, DepreciationRunLineDto::chargeAmount,
                        BigDecimal::add)));
        assertThat(registerByBranch.get(br1.getId())).isEqualByComparingTo("200000");
        assertThat(registerByBranch.get(br2.getId())).isEqualByComparingTo("12000000");

        // ...equals the depreciation expense in that branch's P&L slice.
        assertThat(depExpenseIn(StatementScope.branch(br1.getId(), br1.getUid(), br1.getName())))
                .isEqualByComparingTo(registerByBranch.get(br1.getId()));
        assertThat(depExpenseIn(StatementScope.branch(br2.getId(), br2.getUid(), br2.getName())))
                .isEqualByComparingTo(registerByBranch.get(br2.getId()));
        // Nothing lands at company level, and the company total is unchanged.
        assertThat(depExpenseIn(StatementScope.unassigned())).isEqualByComparingTo("0");
        assertThat(depExpenseIn(StatementScope.companyWide())).isEqualByComparingTo("12200000");
        assertThat(run.totalChargeAmount()).isEqualByComparingTo("12200000");

        // Revaluation and disposal also carry the asset's branch.
        AssetRevaluationDto reval = revaluationService.revalue(van.uid(), new RevalueAssetRequest(
                RevaluationDirection.UP, new BigDecimal("1000000.00"), period.startDate(), "market"));
        assertThat(branchOf(reval.glEntryUid())).isEqualTo(br2.getId());

        AssetDisposalDto disposal = disposalService.dispose(laptop.uid(), new DisposeAssetRequest(
                period.startDate(), new BigDecimal("2000000.00"), "sold"));
        assertThat(branchOf(disposal.glEntryUid())).isEqualTo(br1.getId());
    }

    // -------------------------------------------------------------------------

    private FixedAssetDto register(Long categoryId, Long branchId, String name, String cost,
                                   DepreciationMethod method, int life, BigDecimal rate) {
        return assetService.register(new RegisterAssetRequest(
                companyId, branchId, categoryId, name,
                new BigDecimal(cost), BigDecimal.ZERO, method, life, rate,
                period.startDate(), period.startDate(),
                null, null, null));
    }

    private Long branchOf(String journalUid) {
        return journalEntries.findByUid(journalUid).orElseThrow().getBranchId();
    }

    /** Net debit on the Depreciation Expense account inside one P&amp;L slice, this period. */
    private BigDecimal depExpenseIn(StatementScope scope) {
        BigDecimal[] dc = movements.periodMovementByAccount(
                        companyId, scope, period.startDate(), period.endDate(), true)
                .get(depExpenseAccountId);
        return dc == null ? BigDecimal.ZERO : dc[0].subtract(dc[1]);
    }

    /** GLConfigResolver is MANDATORY-propagation (it runs inside a posting) — give it a TX here. */
    private com.erp.modules.gl.domain.entity.ChartOfAccount resolve(Long companyId, GlConfigKey key) {
        return tx.execute(st -> glConfig.resolve(companyId, key));
    }
}

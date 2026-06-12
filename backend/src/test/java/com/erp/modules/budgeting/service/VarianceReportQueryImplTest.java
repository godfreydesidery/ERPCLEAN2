package com.erp.modules.budgeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.budgeting.domain.dto.VarianceQuery;
import com.erp.modules.budgeting.domain.dto.VarianceReportDto;
import com.erp.modules.budgeting.domain.dto.VarianceRowDto;
import com.erp.modules.budgeting.domain.entity.BudgetLine;
import com.erp.modules.budgeting.domain.entity.BudgetVersion;
import com.erp.modules.budgeting.repository.BudgetLineRepository;
import com.erp.modules.budgeting.repository.BudgetVersionRepository;
import com.erp.modules.costing.domain.dto.DimensionActualsRowDto;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.service.DimensionService;
import com.erp.modules.costing.service.DimensionSlicedReportQuery;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for VarianceReportQueryImpl — focused on the company-wide unallocated-actuals
 * inclusion fix (adversarial-review finding #3).
 *
 * <p>Scenario: GL has journal_lines with cost_centre_value_id IS NULL (unallocated postings).
 * For a company-wide variance query (costCentreValueUid == null), those amounts must appear
 * in the actual totals and drive a correct variance.  Before the fix they were silently
 * dropped, making actual = 0 for any account with only unallocated postings.
 */
class VarianceReportQueryImplTest {

    private BudgetVersionRepository   versions;
    private BudgetLineRepository      lines;
    private FiscalYearRepository      fiscalYears;
    private FiscalPeriodRepository    fiscalPeriods;
    private ChartOfAccountRepository  accounts;
    private DimensionService          dimensionService;
    private DimensionSlicedReportQuery dimensionActuals;
    private ScopeGuard                scopeGuard;
    private VarianceReportQueryImpl   service;

    @BeforeEach
    void setUp() {
        versions         = mock(BudgetVersionRepository.class);
        lines            = mock(BudgetLineRepository.class);
        fiscalYears      = mock(FiscalYearRepository.class);
        fiscalPeriods    = mock(FiscalPeriodRepository.class);
        accounts         = mock(ChartOfAccountRepository.class);
        dimensionService = mock(DimensionService.class);
        dimensionActuals = mock(DimensionSlicedReportQuery.class);
        scopeGuard       = mock(ScopeGuard.class);
        service = new VarianceReportQueryImpl(
                versions, lines, fiscalYears, fiscalPeriods,
                accounts, dimensionService, dimensionActuals, scopeGuard);

        RequestContext.set(new RequestContext.Principal(1L, "tester@test.com", false, 10L, null, null));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Fix #3: company-wide variance includes unallocated (NULL cost_centre_value_id) actuals
    // -------------------------------------------------------------------------

    /**
     * Company-wide variance (no costCentreValueUid) where ALL GL postings are unallocated
     * (cost_centre_value_id IS NULL).  Before fix: actual = 0, variance = -budget.
     * After fix: actual = unallocated amount, variance = actual − budget.
     */
    @Test
    void run_companyWide_unallocatedActualsIncludedInActual() {
        Long companyId = 10L;
        FiscalYear fy  = mockFiscalYear(1L, "FY-UID", companyId, "FY2026",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(fiscalYears.findByUid("FY-UID")).thenReturn(Optional.of(fy));

        // One approved version
        BudgetVersion approvedVer = mockApprovedVersion(99L, companyId, 1L);
        when(versions.findApproved(companyId, 1L, null)).thenReturn(Optional.of(approvedVer));

        // 12 periods
        List<FiscalPeriod> twelve = buildTwelvePeriods(1L, companyId);
        when(fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(1L)).thenReturn(twelve);

        // Budget line: account 20L, period 1 (period_no=1), amount 1000
        BudgetLine budgetLine = mockBudgetLine(20L, getId(twelve.get(0)), new BigDecimal("1000.0000"));
        when(lines.findByVersionAndPeriods(eq(99L), any())).thenReturn(List.of(budgetLine));

        // Account
        ChartOfAccount acct = mockAccount(20L, "5300", "Salaries", AccountType.EXPENSE, NormalBalance.DEBIT);
        when(accounts.findAllById(any())).thenReturn(List.of(acct));

        // ADR-0025 actuals: only unallocated rows (valueId == null)
        DimensionActualsRowDto unallocRow = new DimensionActualsRowDto(
                20L, null, 1, new BigDecimal("800.0000"), BigDecimal.ZERO, new BigDecimal("800.0000"));
        when(dimensionActuals.actualsByAccountValuePeriod(
                eq(companyId), eq(DimensionSlot.COST_CENTRE), any(), any()))
                .thenReturn(List.of(unallocRow));

        VarianceQuery query = new VarianceQuery(companyId, "FY-UID", 1, 12, null, null);
        VarianceReportDto report = service.run(query);

        // There should be one row for account 20L
        assertThat(report.rows()).hasSize(1);
        VarianceRowDto row = report.rows().get(0);

        // actual must include the unallocated 800 (the fix)
        assertThat(row.actualAmount()).isEqualByComparingTo("800.0000");

        // budget is 1000 from the approved version
        assertThat(row.budgetAmount()).isEqualByComparingTo("1000.0000");

        // variance = actual − budget = 800 − 1000 = −200
        assertThat(row.varianceAmount()).isEqualByComparingTo("-200.0000");
    }

    /**
     * Company-wide variance where BOTH allocated and unallocated actuals exist for the same
     * account.  The allocated amounts (from centre rows) and unallocated amounts must ALL be
     * summed into the single company-wide actual — not just the allocated amounts.
     */
    @Test
    void run_companyWide_allocatedPlusUnallocatedSummedCorrectly() {
        Long companyId = 10L;
        FiscalYear fy = mockFiscalYear(1L, "FY-UID", companyId, "FY2026",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(fiscalYears.findByUid("FY-UID")).thenReturn(Optional.of(fy));

        BudgetVersion approvedVer = mockApprovedVersion(99L, companyId, 1L);
        when(versions.findApproved(companyId, 1L, null)).thenReturn(Optional.of(approvedVer));

        List<FiscalPeriod> twelve = buildTwelvePeriods(1L, companyId);
        when(fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(1L)).thenReturn(twelve);

        BudgetLine budgetLine = mockBudgetLine(20L, getId(twelve.get(0)), new BigDecimal("5000.0000"));
        when(lines.findByVersionAndPeriods(eq(99L), any())).thenReturn(List.of(budgetLine));

        ChartOfAccount acct = mockAccount(20L, "5300", "Salaries", AccountType.EXPENSE, NormalBalance.DEBIT);
        when(accounts.findAllById(any())).thenReturn(List.of(acct));

        // Allocated: centre A (valueId=77L) contributed 2000; unallocated: 500
        DimensionActualsRowDto centreRow = new DimensionActualsRowDto(
                20L, 77L, 1, new BigDecimal("2000.0000"), BigDecimal.ZERO, new BigDecimal("2000.0000"));
        DimensionActualsRowDto unallocRow = new DimensionActualsRowDto(
                20L, null, 1, new BigDecimal("500.0000"), BigDecimal.ZERO, new BigDecimal("500.0000"));
        when(dimensionActuals.actualsByAccountValuePeriod(
                eq(companyId), eq(DimensionSlot.COST_CENTRE), any(), any()))
                .thenReturn(List.of(centreRow, unallocRow));

        VarianceQuery query = new VarianceQuery(companyId, "FY-UID", 1, 12, null, null);
        VarianceReportDto report = service.run(query);

        assertThat(report.rows()).hasSize(1);
        VarianceRowDto row = report.rows().get(0);

        // actual = centre 2000 + unalloc 500 = 2500
        assertThat(row.actualAmount()).isEqualByComparingTo("2500.0000");
        assertThat(row.budgetAmount()).isEqualByComparingTo("5000.0000");
        // variance = 2500 − 5000 = −2500
        assertThat(row.varianceAmount()).isEqualByComparingTo("-2500.0000");
    }

    /**
     * Centre-specific variance: the Unallocated bucket must appear as a SEPARATE row (OQ-BUD-07),
     * not merged into the centre row.  This is the pre-existing correct behaviour that the fix
     * must preserve.
     */
    @Test
    void run_centreSpecific_unallocatedAppearsAsSeparateRow() {
        Long companyId = 10L;
        FiscalYear fy = mockFiscalYear(1L, "FY-UID", companyId, "FY2026",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(fiscalYears.findByUid("FY-UID")).thenReturn(Optional.of(fy));

        // Centre-specific: ccValueId = 77L
        com.erp.modules.costing.domain.dto.DimensionValueDto ccDto =
                new com.erp.modules.costing.domain.dto.DimensionValueDto(
                        77L, "CC-UID", companyId, 1L, "DIM-UID",
                        "COST_CENTRE", "CC-01", "Dept A",
                        null, null, true,
                        com.erp.platform.common.domain.MasterStatus.ACTIVE);
        when(dimensionService.resolveValue("CC-UID")).thenReturn(Optional.of(ccDto));

        BudgetVersion approvedVer = mockApprovedVersion(99L, companyId, 1L);
        when(versions.findApproved(companyId, 1L, 77L)).thenReturn(Optional.of(approvedVer));

        List<FiscalPeriod> twelve = buildTwelvePeriods(1L, companyId);
        when(fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(1L)).thenReturn(twelve);

        BudgetLine budgetLine = mockBudgetLine(20L, getId(twelve.get(0)), new BigDecimal("3000.0000"));
        when(lines.findByVersionAndPeriods(eq(99L), any())).thenReturn(List.of(budgetLine));

        ChartOfAccount acct = mockAccount(20L, "5300", "Salaries", AccountType.EXPENSE, NormalBalance.DEBIT);
        when(accounts.findAllById(any())).thenReturn(List.of(acct));

        DimensionActualsRowDto centreRow = new DimensionActualsRowDto(
                20L, 77L, 1, new BigDecimal("1500.0000"), BigDecimal.ZERO, new BigDecimal("1500.0000"));
        DimensionActualsRowDto unallocRow = new DimensionActualsRowDto(
                20L, null, 1, new BigDecimal("200.0000"), BigDecimal.ZERO, new BigDecimal("200.0000"));
        when(dimensionActuals.actualsByAccountValuePeriod(
                eq(companyId), eq(DimensionSlot.COST_CENTRE), any(), any()))
                .thenReturn(List.of(centreRow, unallocRow));

        VarianceQuery query = new VarianceQuery(companyId, "FY-UID", 1, 12, "CC-UID", null);
        VarianceReportDto report = service.run(query);

        // Two rows for account 20L: one for the centre, one Unallocated
        List<VarianceRowDto> acct20Rows = report.rows().stream()
                .filter(r -> r.accountId().equals(20L))
                .toList();
        assertThat(acct20Rows).hasSize(2);

        VarianceRowDto centreResult = acct20Rows.stream()
                .filter(r -> r.costCentreValueId() != null && r.costCentreValueId().equals(77L))
                .findFirst().orElseThrow();
        assertThat(centreResult.actualAmount()).isEqualByComparingTo("1500.0000");
        assertThat(centreResult.budgetAmount()).isEqualByComparingTo("3000.0000");

        VarianceRowDto unallocResult = acct20Rows.stream()
                .filter(r -> r.costCentreValueId() == null && "Unallocated".equals(r.costCentreValueName()))
                .findFirst().orElseThrow();
        assertThat(unallocResult.actualAmount()).isEqualByComparingTo("200.0000");
        // Unallocated row has budget=0 (no budget line assigned to Unallocated)
        assertThat(unallocResult.budgetAmount()).isEqualByComparingTo("0");
    }

    /**
     * Company-wide variance with no approved budget version — noApprovedBudget flag must be
     * set to true and actual (unallocated) amounts still appear in the report.
     */
    @Test
    void run_companyWide_noApprovedBudget_stillShowsUnallocatedActuals() {
        Long companyId = 10L;
        FiscalYear fy = mockFiscalYear(1L, "FY-UID", companyId, "FY2026",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        when(fiscalYears.findByUid("FY-UID")).thenReturn(Optional.of(fy));

        when(versions.findApproved(companyId, 1L, null)).thenReturn(Optional.empty());

        List<FiscalPeriod> twelve = buildTwelvePeriods(1L, companyId);
        when(fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(1L)).thenReturn(twelve);

        // No budget lines (no approved version)
        when(lines.findByVersionAndPeriods(any(), any())).thenReturn(List.of());

        ChartOfAccount acct = mockAccount(20L, "5300", "Salaries", AccountType.EXPENSE, NormalBalance.DEBIT);
        when(accounts.findAllById(any())).thenReturn(List.of(acct));

        DimensionActualsRowDto unallocRow = new DimensionActualsRowDto(
                20L, null, 3, new BigDecimal("600.0000"), BigDecimal.ZERO, new BigDecimal("600.0000"));
        when(dimensionActuals.actualsByAccountValuePeriod(
                eq(companyId), eq(DimensionSlot.COST_CENTRE), any(), any()))
                .thenReturn(List.of(unallocRow));

        VarianceQuery query = new VarianceQuery(companyId, "FY-UID", 1, 12, null, null);
        VarianceReportDto report = service.run(query);

        assertThat(report.header().noApprovedBudget()).isTrue();
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0).actualAmount()).isEqualByComparingTo("600.0000");
        assertThat(report.rows().get(0).budgetAmount()).isEqualByComparingTo("0");
        assertThat(report.rows().get(0).varianceAmount()).isEqualByComparingTo("600.0000");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static FiscalYear mockFiscalYear(Long id, String uid, Long companyId, String yearCode,
                                              LocalDate start, LocalDate end) {
        FiscalYear fy = mock(FiscalYear.class);
        when(fy.getId()).thenReturn(id);
        when(fy.getUid()).thenReturn(uid);
        when(fy.getCompanyId()).thenReturn(companyId);
        when(fy.getYearCode()).thenReturn(yearCode);
        when(fy.getStartDate()).thenReturn(start);
        when(fy.getEndDate()).thenReturn(end);
        return fy;
    }

    private static BudgetVersion mockApprovedVersion(Long id, Long companyId, Long fiscalYearId) {
        BudgetVersion v = mock(BudgetVersion.class);
        when(v.getId()).thenReturn(id);
        when(v.getCompanyId()).thenReturn(companyId);
        when(v.getFiscalYearId()).thenReturn(fiscalYearId);
        when(v.getStatus()).thenReturn(
                com.erp.modules.budgeting.domain.enums.BudgetVersionStatus.APPROVED);
        return v;
    }

    private static List<FiscalPeriod> buildTwelvePeriods(Long fiscalYearId, Long companyId) {
        return java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(i -> {
            FiscalPeriod p = mock(FiscalPeriod.class);
            when(p.getId()).thenReturn((long) (100 + i));
            when(p.getFiscalYearId()).thenReturn(fiscalYearId);
            when(p.getCompanyId()).thenReturn(companyId);
            when(p.getPeriodNo()).thenReturn(i);
            return p;
        }).toList();
    }

    private static Long getId(FiscalPeriod p) {
        return p.getId();
    }

    private static ChartOfAccount mockAccount(Long id, String code, String name,
                                               AccountType type, NormalBalance nb) {
        ChartOfAccount a = mock(ChartOfAccount.class);
        when(a.getId()).thenReturn(id);
        when(a.getUid()).thenReturn("ACCT-" + id);
        when(a.getAccountCode()).thenReturn(code);
        when(a.getName()).thenReturn(name);
        when(a.getAccountType()).thenReturn(type);
        when(a.getNormalBalance()).thenReturn(nb);
        when(a.getCompanyId()).thenReturn(10L);
        when(a.isActive()).thenReturn(true);
        return a;
    }

    private static BudgetLine mockBudgetLine(Long accountId, Long periodId, BigDecimal amount) {
        BudgetLine l = mock(BudgetLine.class);
        when(l.getAccountId()).thenReturn(accountId);
        when(l.getFiscalPeriodId()).thenReturn(periodId);
        when(l.getAmount()).thenReturn(amount);
        return l;
    }
}

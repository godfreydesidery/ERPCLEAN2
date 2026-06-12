package com.erp.modules.budgeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.budgeting.domain.dto.UpsertBudgetLineRequest;
import com.erp.modules.budgeting.domain.dto.UpsertBudgetLineRequest.EntryMode;
import com.erp.modules.budgeting.domain.entity.BudgetLine;
import com.erp.modules.budgeting.domain.entity.BudgetVersion;
import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
import com.erp.modules.budgeting.repository.BudgetLineRepository;
import com.erp.modules.budgeting.repository.BudgetRepository;
import com.erp.modules.budgeting.repository.BudgetVersionRepository;
import com.erp.modules.costing.service.DimensionService;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for BudgetServiceImpl — focused on the upsert-line persistence fixes
 * (adversarial-review findings #1 and #2).
 *
 * <p>Both upsertDirectLines and upsertAnnualSpread must call lines.save() when updating
 * an existing BudgetLine, not rely on implicit Hibernate dirty-checking (which can be
 * flush-mode-dependent and produces no explicit save evidence).
 */
class BudgetServiceImplTest {

    private BudgetRepository          budgets;
    private BudgetVersionRepository   versions;
    private BudgetLineRepository      lines;
    private FiscalYearRepository      fiscalYears;
    private FiscalPeriodRepository    fiscalPeriods;
    private ChartOfAccountRepository  accounts;
    private DimensionService          dimensionService;
    private BudgetingNumberGenerator  numberGenerator;
    private BudgetSpreadCalculator    spreadCalculator;
    private ScopeGuard                scopeGuard;
    private AuditService              audit;
    private BudgetServiceImpl         service;

    @BeforeEach
    void setUp() {
        budgets          = mock(BudgetRepository.class);
        versions         = mock(BudgetVersionRepository.class);
        lines            = mock(BudgetLineRepository.class);
        fiscalYears      = mock(FiscalYearRepository.class);
        fiscalPeriods    = mock(FiscalPeriodRepository.class);
        accounts         = mock(ChartOfAccountRepository.class);
        dimensionService = mock(DimensionService.class);
        numberGenerator  = mock(BudgetingNumberGenerator.class);
        spreadCalculator = mock(BudgetSpreadCalculator.class);
        scopeGuard       = mock(ScopeGuard.class);
        audit            = mock(AuditService.class);
        service = new BudgetServiceImpl(
                budgets, versions, lines, fiscalYears, fiscalPeriods,
                accounts, dimensionService, numberGenerator, spreadCalculator,
                scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(1L, "tester@test.com", false, 10L, null, null));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Fix #1: upsertDirectLines — existing line must be saved explicitly
    // -------------------------------------------------------------------------

    /**
     * When a (version, account, period) line already exists in DIRECT mode,
     * the service must call lines.save(line) after mutating it — not rely on
     * implicit dirty-checking.  Verifies the save is called with the same
     * instance that was retrieved, carrying the updated amount.
     */
    @Test
    void upsertDirectLines_existingLine_callsSaveWithUpdatedAmount() {
        BudgetVersion ver = draftVersion(10L, 1L, 5L);
        when(versions.findByUid("VER-UID")).thenReturn(Optional.of(ver));

        ChartOfAccount acct = mockAccount(20L, "5300", "Salaries", AccountType.EXPENSE, NormalBalance.DEBIT);
        when(accounts.findByUid("ACCT-UID")).thenReturn(Optional.of(acct));

        FiscalPeriod period = mockPeriod(30L, 5L, 1);
        when(fiscalPeriods.findByUid("PER-UID")).thenReturn(Optional.of(period));
        when(fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(5L)).thenReturn(List.of(period));

        // Existing line — mocked so we can capture the save call
        BudgetLine existingLine = mock(BudgetLine.class);
        when(lines.findByBudgetVersionIdAndAccountIdAndFiscalPeriodId(1L, 20L, 30L))
                .thenReturn(Optional.of(existingLine));
        // After mutation, the re-fetch for the response DTO
        when(lines.findByBudgetVersionIdOrderByFiscalPeriodId(1L)).thenReturn(List.of(existingLine));
        when(existingLine.getId()).thenReturn(100L);
        when(existingLine.getUid()).thenReturn("LINE-UID");
        when(existingLine.getBudgetVersionId()).thenReturn(1L);
        when(existingLine.getAccountId()).thenReturn(20L);
        when(existingLine.getFiscalPeriodId()).thenReturn(30L);
        when(existingLine.getAmount()).thenReturn(new BigDecimal("500.0000"));
        when(existingLine.getCurrency()).thenReturn("TZS");
        when(lines.save(existingLine)).thenReturn(existingLine);

        UpsertBudgetLineRequest request = new UpsertBudgetLineRequest(
                EntryMode.DIRECT,
                List.of(new UpsertBudgetLineRequest.LineInputDto(
                        "ACCT-UID", "PER-UID", new BigDecimal("500.00"), "updated memo")),
                null, null, null);

        service.upsertLines("VER-UID", request);

        // The critical assertion: save() must be called on the existing managed entity
        verify(lines, times(1)).save(existingLine);
        // Amount and memo setters must have been called
        verify(existingLine).setAmount(new BigDecimal("500.0000"));
        verify(existingLine).setLineMemo("updated memo");
    }

    /**
     * When no existing line is found (insert path), save() is called with a new BudgetLine —
     * the insert path was never broken, this confirms it still works.
     */
    @Test
    void upsertDirectLines_newLine_callsSaveWithNewLine() {
        BudgetVersion ver = draftVersion(10L, 1L, 5L);
        when(versions.findByUid("VER-UID")).thenReturn(Optional.of(ver));

        ChartOfAccount acct = mockAccount(20L, "5300", "Salaries", AccountType.EXPENSE, NormalBalance.DEBIT);
        when(accounts.findByUid("ACCT-UID")).thenReturn(Optional.of(acct));

        FiscalPeriod period = mockPeriod(30L, 5L, 1);
        when(fiscalPeriods.findByUid("PER-UID")).thenReturn(Optional.of(period));
        when(fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(5L)).thenReturn(List.of(period));

        when(lines.findByBudgetVersionIdAndAccountIdAndFiscalPeriodId(1L, 20L, 30L))
                .thenReturn(Optional.empty());

        ArgumentCaptor<BudgetLine> lineCaptor = ArgumentCaptor.forClass(BudgetLine.class);
        when(lines.save(lineCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(lines.findByBudgetVersionIdOrderByFiscalPeriodId(1L)).thenReturn(List.of());

        UpsertBudgetLineRequest request = new UpsertBudgetLineRequest(
                EntryMode.DIRECT,
                List.of(new UpsertBudgetLineRequest.LineInputDto(
                        "ACCT-UID", "PER-UID", new BigDecimal("300.00"), null)),
                null, null, null);

        service.upsertLines("VER-UID", request);

        assertThat(lineCaptor.getValue().getAmount())
                .isEqualByComparingTo("300.0000");
        assertThat(lineCaptor.getValue().getAccountId()).isEqualTo(20L);
        assertThat(lineCaptor.getValue().getFiscalPeriodId()).isEqualTo(30L);
    }

    /**
     * A DIRECT upsert on a non-DRAFT version must throw ConflictException (BR-BUD-06).
     */
    @Test
    void upsertLines_nonDraftVersion_throwsConflict() {
        BudgetVersion submitted = draftVersion(10L, 1L, 5L);
        submitted.setStatus(BudgetVersionStatus.SUBMITTED);
        when(versions.findByUid("VER-UID")).thenReturn(Optional.of(submitted));

        UpsertBudgetLineRequest request = new UpsertBudgetLineRequest(
                EntryMode.DIRECT, List.of(), null, null, null);

        assertThatThrownBy(() -> service.upsertLines("VER-UID", request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DRAFT");

        verify(lines, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Fix #2: upsertAnnualSpread — existing line must be saved explicitly
    // -------------------------------------------------------------------------

    /**
     * When annual-spread encounters an existing (account, period) line, it must call
     * lines.save(line) after setting the new amount — not rely on dirty-checking.
     */
    @Test
    void upsertAnnualSpread_existingLine_callsSaveWithUpdatedAmount() {
        BudgetVersion ver = draftVersion(10L, 1L, 5L);
        when(versions.findByUid("VER-UID")).thenReturn(Optional.of(ver));

        ChartOfAccount acct = mockAccount(20L, "5400", "Utilities", AccountType.EXPENSE, NormalBalance.DEBIT);
        when(accounts.findByUid("ACCT-UID")).thenReturn(Optional.of(acct));

        // 12 periods required for annual spread
        List<FiscalPeriod> twelve = buildTwelvePeriods(5L);
        when(fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(5L)).thenReturn(twelve);

        BigDecimal monthlyAmt = new BigDecimal("1000.0000");
        List<BigDecimal> spread = twelve.stream().map(p -> monthlyAmt).toList();
        when(spreadCalculator.spread(new BigDecimal("12000.00"), 12)).thenReturn(spread);

        // Every (account, period) pair returns an existing line
        BudgetLine existingLine = mock(BudgetLine.class);
        when(existingLine.getId()).thenReturn(200L);
        when(existingLine.getUid()).thenReturn("LINE-UID-2");
        when(existingLine.getBudgetVersionId()).thenReturn(1L);
        when(existingLine.getAmount()).thenReturn(monthlyAmt);
        when(existingLine.getCurrency()).thenReturn("TZS");
        for (FiscalPeriod p : twelve) {
            when(lines.findByBudgetVersionIdAndAccountIdAndFiscalPeriodId(1L, 20L, p.getId()))
                    .thenReturn(Optional.of(existingLine));
        }
        when(lines.save(existingLine)).thenReturn(existingLine);
        when(lines.findByBudgetVersionIdOrderByFiscalPeriodId(1L)).thenReturn(List.of(existingLine));

        UpsertBudgetLineRequest request = new UpsertBudgetLineRequest(
                EntryMode.ANNUAL_SPREAD,
                null,
                new BigDecimal("12000.00"),
                "ACCT-UID",
                null);

        service.upsertLines("VER-UID", request);

        // save() must be called once per period (12 times) for the existing line
        verify(lines, times(12)).save(existingLine);
        verify(existingLine, times(12)).setAmount(monthlyAmt);
    }

    /**
     * When annual-spread finds no existing line for a period, it inserts a new one.
     */
    @Test
    void upsertAnnualSpread_newLines_savesNewBudgetLines() {
        BudgetVersion ver = draftVersion(10L, 1L, 5L);
        when(versions.findByUid("VER-UID")).thenReturn(Optional.of(ver));

        ChartOfAccount acct = mockAccount(20L, "5400", "Utilities", AccountType.EXPENSE, NormalBalance.DEBIT);
        when(accounts.findByUid("ACCT-UID")).thenReturn(Optional.of(acct));

        List<FiscalPeriod> twelve = buildTwelvePeriods(5L);
        when(fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(5L)).thenReturn(twelve);

        BigDecimal monthlyAmt = new BigDecimal("1000.0000");
        when(spreadCalculator.spread(new BigDecimal("12000.00"), 12))
                .thenReturn(twelve.stream().map(p -> monthlyAmt).toList());

        for (FiscalPeriod p : twelve) {
            when(lines.findByBudgetVersionIdAndAccountIdAndFiscalPeriodId(1L, 20L, p.getId()))
                    .thenReturn(Optional.empty());
        }

        ArgumentCaptor<BudgetLine> captor = ArgumentCaptor.forClass(BudgetLine.class);
        when(lines.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(lines.findByBudgetVersionIdOrderByFiscalPeriodId(1L)).thenReturn(List.of());

        UpsertBudgetLineRequest request = new UpsertBudgetLineRequest(
                EntryMode.ANNUAL_SPREAD, null, new BigDecimal("12000.00"), "ACCT-UID", null);

        service.upsertLines("VER-UID", request);

        // 12 new lines inserted
        assertThat(captor.getAllValues()).hasSize(12);
        captor.getAllValues().forEach(l -> assertThat(l.getAmount()).isEqualByComparingTo("1000.0000"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a BudgetVersion in DRAFT status using its public constructor. */
    private static BudgetVersion draftVersion(Long companyId, Long versionDbId, Long fiscalYearId) {
        BudgetVersion ver = new BudgetVersion(
                42L, companyId, fiscalYearId, null, 1, "v1", null, 1L);
        // id is private/inherited — reflect it in for the repo mock key
        try {
            var idField = com.erp.platform.common.domain.UidEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(ver, versionDbId);
        } catch (Exception e) {
            throw new RuntimeException("Could not set id on BudgetVersion", e);
        }
        return ver;
    }

    private static ChartOfAccount mockAccount(Long id, String code, String name,
                                               AccountType type, NormalBalance nb) {
        ChartOfAccount a = mock(ChartOfAccount.class);
        when(a.getId()).thenReturn(id);
        when(a.getAccountCode()).thenReturn(code);
        when(a.getName()).thenReturn(name);
        when(a.getAccountType()).thenReturn(type);
        when(a.getNormalBalance()).thenReturn(nb);
        when(a.getCompanyId()).thenReturn(10L);
        when(a.isActive()).thenReturn(true);
        return a;
    }

    private static FiscalPeriod mockPeriod(Long id, Long fiscalYearId, int periodNo) {
        FiscalPeriod p = mock(FiscalPeriod.class);
        when(p.getId()).thenReturn(id);
        when(p.getFiscalYearId()).thenReturn(fiscalYearId);
        when(p.getPeriodNo()).thenReturn(periodNo);
        return p;
    }

    /** Build 12 mocked FiscalPeriod objects for the given fiscal year. */
    private static List<FiscalPeriod> buildTwelvePeriods(Long fiscalYearId) {
        return java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> mockPeriod((long) (100 + i), fiscalYearId, i))
                .toList();
    }
}

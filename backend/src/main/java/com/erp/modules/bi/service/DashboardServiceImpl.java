package com.erp.modules.bi.service;

import com.erp.modules.ar.domain.dto.ArReconciliationDto;
import com.erp.modules.ar.service.ArReconciliationQuery;
import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.service.ApReconciliationQuery;
import com.erp.modules.bi.domain.dto.BiHeaderDto;
import com.erp.modules.bi.domain.dto.CashPositionDto;
import com.erp.modules.bi.domain.dto.CrmSnapshotDto;
import com.erp.modules.bi.domain.dto.DashboardDto;
import com.erp.modules.bi.domain.dto.FinanceSummaryDto;
import com.erp.modules.bi.domain.dto.HealthIndicatorDto;
import com.erp.modules.bi.domain.dto.InventorySummaryDto;
import com.erp.modules.bi.domain.dto.TrendDto;
import com.erp.modules.bi.domain.dto.TrendPointDto;
import com.erp.modules.bi.domain.dto.WorkingCapitalDto;
import com.erp.modules.cashbank.domain.dto.CashAccountBalanceDto;
import com.erp.modules.cashbank.domain.dto.CashGlReconciliationDto;
import com.erp.modules.cashbank.service.CashAccountStatementQuery;
import com.erp.modules.cashbank.service.CashGlReconciliationQuery;
import com.erp.modules.crm.domain.dto.CrmKpiDto;
import com.erp.modules.crm.domain.dto.ForecastDto;
import com.erp.modules.crm.domain.dto.PipelineSummaryDto;
import com.erp.modules.crm.service.PipelineQuery;
import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.service.TrialBalanceQuery;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.reporting.service.AccountMovementQuery;
import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.modules.stock.service.StockValuationQuery;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pure-composition BI dashboard service (ADR-0037 D-1/D-2/D-3).
 *
 * <p><b>Key constraints honoured:</b>
 * <ul>
 *   <li>assertCanActIn FIRST in every method before any bean call (D-5, defense in depth).
 *   <li>Holds NO EntityManager, NO repository (except the read-only FiscalPeriodRepository for the
 *       period axis — OQ-BI-01 default accepted).
 *   <li>AR/AP company-wide totals via recon subLedger ONLY — NEVER ArAgeingQuery.ageing(id,null,…)
 *       (the null-party-id silently returns zero rows — D-2 W-AR/W-AP fix).
 *   <li>Trend: iterate FiscalPeriodRepository × AccountMovementQuery.periodMovementByAccountType —
 *       aggregate in SQL (GROUP BY), never sum raw lines in Java (NFR-REP-02 / D-4).
 *   <li>Each panel is independently nullable — a failing/forbidden upstream leaves that panel null,
 *       not the whole dashboard (D-6 graceful degrade).
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardServiceImpl.class);

    /** Maximum periods to include in the trend (≤12 round-trips, D-4). */
    private static final int MAX_TREND_PERIODS = 12;

    private final ScopeGuard               scopeGuard;
    private final CompanyRepository        companyRepo;
    private final AccountMovementQuery     accountMovement;
    private final TrialBalanceQuery        trialBalance;
    private final ArReconciliationQuery    arRecon;
    private final ApReconciliationQuery    apRecon;
    private final CashAccountStatementQuery cashStatement;
    private final CashGlReconciliationQuery cashGlRecon;
    private final StockValuationQuery      stockValuation;
    private final PipelineQuery            pipeline;
    private final FiscalPeriodRepository   fiscalPeriods;

    public DashboardServiceImpl(ScopeGuard scopeGuard,
                                 CompanyRepository companyRepo,
                                 AccountMovementQuery accountMovement,
                                 TrialBalanceQuery trialBalance,
                                 ArReconciliationQuery arRecon,
                                 ApReconciliationQuery apRecon,
                                 CashAccountStatementQuery cashStatement,
                                 CashGlReconciliationQuery cashGlRecon,
                                 StockValuationQuery stockValuation,
                                 PipelineQuery pipeline,
                                 FiscalPeriodRepository fiscalPeriods) {
        this.scopeGuard     = scopeGuard;
        this.companyRepo    = companyRepo;
        this.accountMovement = accountMovement;
        this.trialBalance   = trialBalance;
        this.arRecon        = arRecon;
        this.apRecon        = apRecon;
        this.cashStatement  = cashStatement;
        this.cashGlRecon    = cashGlRecon;
        this.stockValuation = stockValuation;
        this.pipeline       = pipeline;
        this.fiscalPeriods  = fiscalPeriods;
    }

    // =========================================================================
    // Composite dashboard
    // =========================================================================

    @Override
    public DashboardDto dashboard(Long companyId, LocalDate from, LocalDate to, Long branchId) {
        // D-5: assertCanActIn FIRST — before any panel call
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String companyName = resolveCompanyName(companyId);
        String currency    = resolveBaseCurrency(companyId);

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        String    periodLabel   = effectiveFrom + " – " + effectiveTo;

        List<HealthIndicatorDto> health = new ArrayList<>();

        // Each panel is wrapped in a try/catch so a failing upstream never kills the whole dashboard.
        FinanceSummaryDto   financePanel   = safeFinance(companyId, effectiveFrom, effectiveTo, health);
        WorkingCapitalDto   wcPanel        = safeWorkingCapital(companyId, health);
        InventorySummaryDto inventoryPanel = safeInventory(companyId, health);
        CrmSnapshotDto      crmPanel       = safeCrm(companyId, branchId, effectiveFrom, effectiveTo);
        TrendDto            revTrend       = safeRevenueTrend(companyId, currency);
        TrendDto            netTrend       = safeNetProfitTrend(companyId, currency);

        BiHeaderDto header = new BiHeaderDto(
                companyId, companyName, currency, periodLabel,
                effectiveFrom, effectiveTo, LocalDate.now(), Instant.now());

        return new DashboardDto(header, financePanel, wcPanel, inventoryPanel, crmPanel,
                revTrend, netTrend, health);
    }

    // =========================================================================
    // Finance panel (F-1..F-4)
    // =========================================================================

    @Override
    public FinanceSummaryDto financeSummary(Long companyId, LocalDate from, LocalDate to) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return buildFinance(companyId, from, to);
    }

    private FinanceSummaryDto buildFinance(Long companyId, LocalDate from, LocalDate to) {
        // F-1 / F-2: P&L summary via AccountMovementQuery.periodMovementByAccountType (one GROUP BY)
        Map<AccountType, BigDecimal[]> byType =
                accountMovement.periodMovementByAccountType(companyId, from, to);

        BigDecimal[] incomeArr  = byType.getOrDefault(AccountType.INCOME,  zero2());
        BigDecimal[] expenseArr = byType.getOrDefault(AccountType.EXPENSE, zero2());

        // Revenue = INCOME net (CREDIT-normal: credit − debit)
        BigDecimal revenue = incomeArr[1].subtract(incomeArr[0]);
        // COGS: no separate AccountType for COGS in the five-type model — zero in v1 (not classifiable)
        BigDecimal cogs = BigDecimal.ZERO;
        BigDecimal grossProfit = revenue.subtract(cogs);
        // Opex = EXPENSE net (DEBIT-normal: debit − credit)
        BigDecimal opex = expenseArr[0].subtract(expenseArr[1]);
        // Net profit = INCOME net − EXPENSE net (matches AccountMovementQuery.netIncomeForPeriod)
        BigDecimal netProfit = revenue.subtract(opex);
        // Gross margin %
        BigDecimal grossMarginPct = revenue.compareTo(BigDecimal.ZERO) != 0
                ? grossProfit.divide(revenue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // F-3: Trial balance health (TrialBalanceQuery.compute)
        TrialBalanceDto tb = trialBalance.compute(companyId);
        boolean tbTies = tb.totalDebits().compareTo(tb.totalCredits()) == 0;

        // F-4: Cash position (CashAccountStatementQuery.listBalances + CashGlReconciliationQuery.reconcileAll)
        CashPositionDto cashPos = buildCashPosition(companyId);

        return new FinanceSummaryDto(netProfit, revenue, cogs, grossProfit, grossMarginPct,
                opex, netProfit, tbTies, tb.totalDebits(), tb.totalCredits(), cashPos);
    }

    private CashPositionDto buildCashPosition(Long companyId) {
        List<CashAccountBalanceDto> accounts = cashStatement.listBalances(companyId);
        BigDecimal total = accounts.stream()
                .map(CashAccountBalanceDto::bookBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CashGlReconciliationDto> glReconRows = cashGlRecon.reconcileAll(companyId);
        // Aggregate all cash GL differences — all ties iff all rows tie
        boolean   allTie  = glReconRows.stream().allMatch(r -> r.difference().compareTo(BigDecimal.ZERO) == 0);
        BigDecimal sumDiff = glReconRows.stream()
                .map(CashGlReconciliationDto::difference)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CashPositionDto(total, accounts, allTie, sumDiff);
    }

    // =========================================================================
    // Working capital panel (W-AR, W-AP)
    // =========================================================================

    @Override
    public WorkingCapitalDto workingCapital(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return buildWorkingCapital(companyId);
    }

    private WorkingCapitalDto buildWorkingCapital(Long companyId) {
        // W-AR: company-wide AR from ArReconciliationQuery.reconcile — NOT ArAgeingQuery.ageing(id,null,…)
        ArReconciliationDto ar = arRecon.reconcile(companyId);
        boolean arTies = ar.difference().compareTo(BigDecimal.ZERO) == 0;

        // W-AP: company-wide AP from ApReconciliationQuery.reconcile
        ApReconciliationDto ap = apRecon.reconcile(companyId);
        boolean apTies = ap.difference().compareTo(BigDecimal.ZERO) == 0;

        return new WorkingCapitalDto(
                ar.subLedgerTotal(), arTies, ar.difference(),
                ap.subLedgerTotal(), apTies, ap.difference());
    }

    // =========================================================================
    // Inventory panel (O-1)
    // =========================================================================

    @Override
    public InventorySummaryDto inventorySummary(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return buildInventory(companyId);
    }

    private InventorySummaryDto buildInventory(Long companyId) {
        // O-1: StockValuationQuery.report — stat-card only (totalValue + recon), NOT the per-product table
        StockValuationReportDto sv = stockValuation.report(companyId);
        boolean ties = sv.recon().ties();
        return new InventorySummaryDto(sv.totalValue(), ties, sv.recon().difference());
    }

    // =========================================================================
    // CRM panel (C-1, C-2, C-3)
    // =========================================================================

    @Override
    public CrmSnapshotDto crmSnapshot(Long companyId, Long branchId, LocalDate from, LocalDate to) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return buildCrm(companyId, branchId, from, to);
    }

    private CrmSnapshotDto buildCrm(Long companyId, Long branchId, LocalDate from, LocalDate to) {
        // C-1: PipelineQuery.pipeline
        PipelineSummaryDto pipelineDto = pipeline.pipeline(companyId, branchId);
        // C-2: PipelineQuery.kpis
        CrmKpiDto kpisDto = pipeline.kpis(companyId, branchId, from, to);
        // C-3: PipelineQuery.forecast
        ForecastDto forecastDto = pipeline.forecast(companyId, branchId, from, to);
        return new CrmSnapshotDto(pipelineDto, kpisDto, forecastDto);
    }

    // =========================================================================
    // Trend panel (D-4) — iterate FiscalPeriodRepository × AccountMovementQuery per period
    // Each call is ONE SQL GROUP BY — Java only places the result on the axis (NFR-REP-02)
    // =========================================================================

    @Override
    public TrendDto revenueTrend(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return buildTrend(companyId, "Revenue", resolveBaseCurrency(companyId), false);
    }

    @Override
    public TrendDto netProfitTrend(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return buildTrend(companyId, "Net Profit", resolveBaseCurrency(companyId), true);
    }

    /**
     * Build the 12-period trend.
     *
     * @param netProfit if true, compute net profit (INCOME net − EXPENSE net); if false, revenue only
     */
    private TrendDto buildTrend(Long companyId, String metricLabel, String currency, boolean netProfit) {
        List<FiscalPeriod> periods = fiscalPeriods.findByCompanyIdOrderByStartDateAsc(companyId);
        // Take last MAX_TREND_PERIODS (default 12)
        if (periods.size() > MAX_TREND_PERIODS) {
            periods = periods.subList(periods.size() - MAX_TREND_PERIODS, periods.size());
        }

        List<TrendPointDto> points = new ArrayList<>(periods.size());
        for (FiscalPeriod period : periods) {
            // ONE SQL GROUP BY per period (D-4) — assertCanActIn re-asserted inside
            Map<AccountType, BigDecimal[]> byType =
                    accountMovement.periodMovementByAccountType(
                            companyId, period.getStartDate(), period.getEndDate());

            BigDecimal[] incomeArr  = byType.getOrDefault(AccountType.INCOME,  zero2());
            BigDecimal   revenue    = incomeArr[1].subtract(incomeArr[0]); // credit − debit
            BigDecimal   value;

            if (netProfit) {
                BigDecimal[] expenseArr = byType.getOrDefault(AccountType.EXPENSE, zero2());
                BigDecimal   expenseNet = expenseArr[0].subtract(expenseArr[1]); // debit − credit
                value = revenue.subtract(expenseNet);
            } else {
                value = revenue;
            }

            String label = "P" + period.getPeriodNo() + " " + period.getStartDate().getYear();
            points.add(new TrendPointDto(label, period.getStartDate(), period.getEndDate(), value));
        }
        return new TrendDto(metricLabel, currency, points);
    }

    // =========================================================================
    // Graceful-degrade wrappers for the composite dashboard
    // =========================================================================

    private FinanceSummaryDto safeFinance(Long companyId, LocalDate from, LocalDate to,
                                          List<HealthIndicatorDto> health) {
        try {
            FinanceSummaryDto fs = buildFinance(companyId, from, to);
            health.add(new HealthIndicatorDto("TB", fs.tbTies(), fs.tbTotalDebit().subtract(fs.tbTotalCredit())));
            if (fs.cash() != null) {
                health.add(new HealthIndicatorDto("Cash vs GL", fs.cash().cashTies(), fs.cash().cashGlDifference()));
            }
            return fs;
        } catch (Exception ex) {
            log.warn("BI finance panel failed for company {}: {}", companyId, ex.getMessage());
            return null;
        }
    }

    private WorkingCapitalDto safeWorkingCapital(Long companyId, List<HealthIndicatorDto> health) {
        try {
            WorkingCapitalDto wc = buildWorkingCapital(companyId);
            health.add(new HealthIndicatorDto("AR vs GL 1200", wc.arTies(), wc.arDifference()));
            health.add(new HealthIndicatorDto("AP vs GL 2100", wc.apTies(), wc.apDifference()));
            return wc;
        } catch (Exception ex) {
            log.warn("BI working capital panel failed for company {}: {}", companyId, ex.getMessage());
            return null;
        }
    }

    private InventorySummaryDto safeInventory(Long companyId, List<HealthIndicatorDto> health) {
        try {
            InventorySummaryDto inv = buildInventory(companyId);
            health.add(new HealthIndicatorDto("Stock vs GL 1300", inv.stockTies(), inv.stockDifference()));
            return inv;
        } catch (Exception ex) {
            log.warn("BI inventory panel failed for company {}: {}", companyId, ex.getMessage());
            return null;
        }
    }

    private CrmSnapshotDto safeCrm(Long companyId, Long branchId, LocalDate from, LocalDate to) {
        try {
            return buildCrm(companyId, branchId, from, to);
        } catch (Exception ex) {
            log.warn("BI CRM panel failed for company {}: {}", companyId, ex.getMessage());
            return null;
        }
    }

    private TrendDto safeRevenueTrend(Long companyId, String currency) {
        try {
            return buildTrend(companyId, "Revenue", currency, false);
        } catch (Exception ex) {
            log.warn("BI revenue trend failed for company {}: {}", companyId, ex.getMessage());
            return null;
        }
    }

    private TrendDto safeNetProfitTrend(Long companyId, String currency) {
        try {
            return buildTrend(companyId, "Net Profit", currency, true);
        } catch (Exception ex) {
            log.warn("BI net-profit trend failed for company {}: {}", companyId, ex.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String resolveCompanyName(Long companyId) {
        return companyRepo.findById(companyId)
                .map(com.erp.modules.iam.domain.entity.Company::getName)
                .orElse("Company " + companyId);
    }

    private String resolveBaseCurrency(Long companyId) {
        return companyRepo.findById(companyId)
                .map(com.erp.modules.iam.domain.entity.Company::getBaseCurrency)
                .orElse("TZS");
    }

    private static BigDecimal[] zero2() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
    }
}

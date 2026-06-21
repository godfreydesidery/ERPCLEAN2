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
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.service.TrialBalanceQuery;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.reporting.domain.enums.StatementSection;
import com.erp.modules.reporting.service.AccountMovementQuery;
import com.erp.modules.reporting.service.StatementClassifier;
import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.modules.stock.service.StockValuationQuery;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.PermissionChecks;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
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
 *   <li>Adversarial-review HIGH-1 fix: composite dashboard() gates each panel on the fine perm
 *       (BI.FINANCE.VIEW / BI.OPS.VIEW / BI.CRM.VIEW) via the injected PermissionChecks bean —
 *       panels are nulled out rather than built when the fine perm is absent.
 *   <li>Adversarial-review HIGH-2 fix: COGS derived from per-account StatementClassifier split
 *       (EXPENSE 5100–5199 → COST_OF_SALES, remainder → OPERATING_EXPENSES) — same band the
 *       income statement uses — replacing the hardcoded BigDecimal.ZERO that made grossMargin 100%.
 *       grossMarginPct removed from FinanceSummaryDto as it equalled the income-statement value
 *       only when this split is done correctly; income-statement is the authoritative surface.
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardServiceImpl.class);

    /** Maximum periods to include in the trend (≤12 round-trips, D-4). */
    private static final int MAX_TREND_PERIODS = 12;

    private final ScopeGuard               scopeGuard;
    private final PermissionChecks         permChecks;
    private final CompanyRepository        companyRepo;
    private final AccountMovementQuery     accountMovement;
    private final StatementClassifier      classifier;
    private final TrialBalanceQuery        trialBalance;
    private final ArReconciliationQuery    arRecon;
    private final ApReconciliationQuery    apRecon;
    private final CashAccountStatementQuery cashStatement;
    private final CashGlReconciliationQuery cashGlRecon;
    private final StockValuationQuery      stockValuation;
    private final PipelineQuery            pipeline;
    private final FiscalPeriodRepository   fiscalPeriods;

    public DashboardServiceImpl(ScopeGuard scopeGuard,
                                 PermissionChecks permChecks,
                                 CompanyRepository companyRepo,
                                 AccountMovementQuery accountMovement,
                                 StatementClassifier classifier,
                                 TrialBalanceQuery trialBalance,
                                 ArReconciliationQuery arRecon,
                                 ApReconciliationQuery apRecon,
                                 CashAccountStatementQuery cashStatement,
                                 CashGlReconciliationQuery cashGlRecon,
                                 StockValuationQuery stockValuation,
                                 PipelineQuery pipeline,
                                 FiscalPeriodRepository fiscalPeriods) {
        this.scopeGuard     = scopeGuard;
        this.permChecks     = permChecks;
        this.companyRepo    = companyRepo;
        this.accountMovement = accountMovement;
        this.classifier     = classifier;
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

        // REPORTING-BI-041/062: validate company existence before building any panel; a bogus
        // companyId must 404 here rather than degrade silently or NPE inside a downstream query.
        com.erp.modules.iam.domain.entity.Company company =
                companyRepo.findById(companyId)
                        .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));
        String companyName = company.getName();
        String currency    = company.getBaseCurrency() != null ? company.getBaseCurrency() : "TZS";

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        String    periodLabel   = effectiveFrom + " – " + effectiveTo;

        List<HealthIndicatorDto> health = new ArrayList<>();

        // HIGH-1 fix: each panel is built ONLY when the caller holds the fine-grained permission
        // (BI.FINANCE.VIEW / BI.OPS.VIEW / BI.CRM.VIEW). When the perm is absent the panel is
        // left null — the frontend renders it as 'forbidden'. Root always short-circuits to true
        // in PermissionChecks.has() via PermissionResolver, so this never breaks root access.
        boolean canFinance = permChecks.has("BI.FINANCE.VIEW");
        boolean canOps     = permChecks.has("BI.OPS.VIEW");
        boolean canCrm     = permChecks.has("BI.CRM.VIEW");

        FinanceSummaryDto   financePanel   = canFinance ? safeFinance(companyId, effectiveFrom, effectiveTo, health) : null;
        WorkingCapitalDto   wcPanel        = canFinance ? safeWorkingCapital(companyId, health) : null;
        InventorySummaryDto inventoryPanel = canOps     ? safeInventory(companyId, health)      : null;
        CrmSnapshotDto      crmPanel       = canCrm     ? safeCrm(companyId, branchId, effectiveFrom, effectiveTo) : null;
        TrendDto            revTrend       = canFinance ? safeRevenueTrend(companyId, currency) : null;
        TrendDto            netTrend       = canFinance ? safeNetProfitTrend(companyId, currency) : null;

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
        requireCompanyExists(companyId);
        return buildFinance(companyId, from, to);
    }

    private FinanceSummaryDto buildFinance(Long companyId, LocalDate from, LocalDate to) {
        // F-1 / F-2: P&L summary via two AccountMovementQuery calls:
        //   (a) periodMovementByAccountType — one GROUP BY accountType for revenue and total expense
        //   (b) periodMovementByAccount — one GROUP BY accountId for the COGS split
        // This mirrors the StatementClassifier band the income statement uses (5100–5199 → COST_OF_SALES)
        // so revenue, opex and netProfit here equal the income-statement values by construction (D-1).
        // HIGH-2 fix: COGS derived from per-account code split; grossMarginPct removed from DTO
        // because it was previously hardcoded to ZERO / 100% — a missing metric beats a wrong one.
        Map<AccountType, BigDecimal[]> byType =
                accountMovement.periodMovementByAccountType(companyId, from, to);

        BigDecimal[] incomeArr  = byType.getOrDefault(AccountType.INCOME,  zero2());

        // Revenue = INCOME net (CREDIT-normal: credit − debit)
        BigDecimal revenue = incomeArr[1].subtract(incomeArr[0]);

        // Split EXPENSE into COGS (5100–5199) vs OPEX via the same StatementClassifier the
        // income statement uses, so these values equal the income statement by construction.
        Map<Long, BigDecimal[]>    byAccount  = accountMovement.periodMovementByAccount(companyId, from, to);
        Map<Long, ChartOfAccount>  accountMap = accountMovement.accountMapForCompany(companyId);

        BigDecimal cogsNet = BigDecimal.ZERO;
        BigDecimal opexNet = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal[]> entry : byAccount.entrySet()) {
            ChartOfAccount acct = accountMap.get(entry.getKey());
            if (acct == null || acct.getAccountType() != AccountType.EXPENSE) continue;
            BigDecimal net = entry.getValue()[0].subtract(entry.getValue()[1]); // debit − credit
            StatementSection section = classifier.classify(acct.getAccountType(), acct.getAccountCode());
            if (section == StatementSection.COST_OF_SALES) {
                cogsNet = cogsNet.add(net);
            } else {
                opexNet = opexNet.add(net);
            }
        }

        // Net profit = INCOME net − (COGS + OPEX) — matches AccountMovementQuery.netIncomeForPeriod
        BigDecimal netProfit = revenue.subtract(cogsNet).subtract(opexNet);

        // F-3: Trial balance health (TrialBalanceQuery.compute)
        TrialBalanceDto tb = trialBalance.compute(companyId);
        boolean tbTies = tb.totalDebits().compareTo(tb.totalCredits()) == 0;

        // F-4: Cash position (CashAccountStatementQuery.listBalances + CashGlReconciliationQuery.reconcileAll)
        CashPositionDto cashPos = buildCashPosition(companyId);

        // opex in the DTO is the operating-expense portion only (COGS excluded), consistent with
        // the income-statement's Operating Expenses subtotal. netProfitPeriod == netProfit (period
        // view; a future cumulative field would use cumulativeByAccountTypeAsAt).
        return new FinanceSummaryDto(netProfit, revenue, opexNet, netProfit,
                tbTies, tb.totalDebits(), tb.totalCredits(), cashPos);
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
        requireCompanyExists(companyId);
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
        requireCompanyExists(companyId);
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
        requireCompanyExists(companyId);
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
        String currency = requireCompanyExists(companyId).getBaseCurrency();
        return buildTrend(companyId, "Revenue", currency != null ? currency : "TZS", false);
    }

    @Override
    public TrendDto netProfitTrend(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = requireCompanyExists(companyId).getBaseCurrency();
        return buildTrend(companyId, "Net Profit", currency != null ? currency : "TZS", true);
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

    /**
     * Resolves the Company entity, throwing {@link NotFoundException} (→ HTTP 404) when the
     * companyId does not exist. Called at the top of every public method so a bogus companyId
     * never reaches downstream queries and NPEs (REPORTING-BI-041 / REPORTING-BI-049 /
     * REPORTING-BI-062 fix).
     */
    private com.erp.modules.iam.domain.entity.Company requireCompanyExists(Long companyId) {
        return companyRepo.findById(companyId)
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));
    }

    private static BigDecimal[] zero2() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
    }
}

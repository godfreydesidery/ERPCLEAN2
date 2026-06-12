package com.erp.modules.budgeting.service;

import com.erp.modules.budgeting.domain.dto.DepartmentalActualsDto;
import com.erp.modules.budgeting.domain.dto.DepartmentalActualsRowDto;
import com.erp.modules.budgeting.domain.dto.VarianceQuery;
import com.erp.modules.budgeting.domain.dto.VarianceReportDto;
import com.erp.modules.budgeting.domain.dto.VarianceRowDto;
import com.erp.modules.budgeting.domain.entity.BudgetLine;
import com.erp.modules.budgeting.domain.entity.BudgetVersion;
import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
import com.erp.modules.budgeting.repository.BudgetLineRepository;
import com.erp.modules.budgeting.repository.BudgetVersionRepository;
import com.erp.modules.costing.domain.dto.DimensionActualsRowDto;
import com.erp.modules.costing.domain.dto.DimensionValueDto;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.service.DimensionService;
import com.erp.modules.costing.service.DimensionSlicedReportQuery;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erp.modules.gl.domain.enums.AccountType;

/**
 * Variance report and departmental actuals implementation (ADR-0034 D-8).
 *
 * <p>Actuals are consumed from ADR-0025's {@code DimensionSlicedReportQuery.actualsByAccountValuePeriod}
 * — NOT by querying journal_lines directly (D-8 contract).
 *
 * <p>Variance = actual − budget per account (signed; BR-BUD-15). The Unallocated bucket
 * (null cost_centre_value_id rows from ADR-0025's aggregate) is surfaced separately (OQ-BUD-07).
 */
@Service
public class VarianceReportQueryImpl implements VarianceReportQuery {

    private final BudgetVersionRepository versions;
    private final BudgetLineRepository lines;
    private final FiscalYearRepository fiscalYears;
    private final FiscalPeriodRepository fiscalPeriods;
    private final ChartOfAccountRepository accounts;
    private final DimensionService dimensionService;
    private final DimensionSlicedReportQuery dimensionActuals;
    private final ScopeGuard scopeGuard;

    public VarianceReportQueryImpl(BudgetVersionRepository versions,
                                   BudgetLineRepository lines,
                                   FiscalYearRepository fiscalYears,
                                   FiscalPeriodRepository fiscalPeriods,
                                   ChartOfAccountRepository accounts,
                                   DimensionService dimensionService,
                                   DimensionSlicedReportQuery dimensionActuals,
                                   ScopeGuard scopeGuard) {
        this.versions        = versions;
        this.lines           = lines;
        this.fiscalYears     = fiscalYears;
        this.fiscalPeriods   = fiscalPeriods;
        this.accounts        = accounts;
        this.dimensionService = dimensionService;
        this.dimensionActuals = dimensionActuals;
        this.scopeGuard       = scopeGuard;
    }

    @Override
    @Transactional(readOnly = true)
    public VarianceReportDto run(VarianceQuery query) {
        scopeGuard.assertCanActIn(RequestContext.get(), query.companyId());

        FiscalYear fy = fiscalYears.findByUid(query.fiscalYearUid())
                .filter(y -> y.getCompanyId().equals(query.companyId()))
                .orElseThrow(() -> NotFoundException.of("FiscalYear", query.fiscalYearUid()));

        // Resolve cost centre filter (ADR-0025 D-3 consume)
        Long ccValueId = null;
        String ccValueName = null;
        if (query.costCentreValueUid() != null) {
            DimensionValueDto ccValue = dimensionService.resolveValue(query.costCentreValueUid())
                    .filter(v -> v.companyId().equals(query.companyId()))
                    .orElseThrow(() -> NotFoundException.of("DimensionValue", query.costCentreValueUid()));
            ccValueId = ccValue.id();
            ccValueName = ccValue.name();
        }

        // (1) Find approved version
        boolean noApprovedBudget = false;
        Optional<BudgetVersion> approvedOpt = versions.findApproved(
                query.companyId(), fy.getId(), ccValueId);
        noApprovedBudget = approvedOpt.isEmpty();

        // (2) Budget side: sum lines grouped by account for the period range
        List<FiscalPeriod> periodRange = fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(fy.getId())
                .stream()
                .filter(p -> p.getPeriodNo() >= query.fromPeriodNo() && p.getPeriodNo() <= query.toPeriodNo())
                .toList();
        List<Long> periodIds = periodRange.stream().map(FiscalPeriod::getId).toList();

        Map<Long, BigDecimal> budgetByAccount = new HashMap<>();
        if (approvedOpt.isPresent()) {
            List<BudgetLine> budgetLines = lines.findByVersionAndPeriods(approvedOpt.get().getId(), periodIds);
            for (BudgetLine line : budgetLines) {
                budgetByAccount.merge(line.getAccountId(), line.getAmount(), BigDecimal::add);
            }
        }

        // (3) Actual side — consumed from ADR-0025 (D-8 contract)
        // actualsByAccountValuePeriod returns rows with (accountId, valueId, periodNo, net)
        // valueId null = Unallocated (rows from journal_lines where cost_centre_value_id IS NULL
        // will have valueId=null if ADR-0025 returns them; we sum separately)
        List<DimensionActualsRowDto> actualsRows = dimensionActuals.actualsByAccountValuePeriod(
                query.companyId(), DimensionSlot.COST_CENTRE, fy.getStartDate(), fy.getEndDate());

        // Filter to period range by periodNo, then group by (accountId, valueId)
        Map<Long, BigDecimal> actualByAccount = new HashMap<>();   // for chosen centre or company-wide
        Map<Long, BigDecimal> unallocatedByAccount = new HashMap<>(); // NULL valueId

        for (DimensionActualsRowDto row : actualsRows) {
            // Only include periods in our range
            if (row.periodNo() < query.fromPeriodNo() || row.periodNo() > query.toPeriodNo()) {
                continue;
            }
            if (row.valueId() == null) {
                // Unallocated
                unallocatedByAccount.merge(row.accountId(), row.net(), BigDecimal::add);
            } else if (ccValueId == null) {
                // Company-wide: sum all cost-centre values
                actualByAccount.merge(row.accountId(), row.net(), BigDecimal::add);
            } else if (row.valueId().equals(ccValueId)) {
                // Filtered to chosen centre
                actualByAccount.merge(row.accountId(), row.net(), BigDecimal::add);
            }
        }

        // (4) Build rows: full outer join budget and actual on accountId
        Map<Long, ChartOfAccount> accountMap = loadAccounts(query.companyId(), budgetByAccount, actualByAccount, unallocatedByAccount);

        List<VarianceRowDto> rows = new ArrayList<>();
        // Union of all account IDs seen in budget + actual
        java.util.Set<Long> allAccountIds = new java.util.HashSet<>();
        allAccountIds.addAll(budgetByAccount.keySet());
        allAccountIds.addAll(actualByAccount.keySet());
        allAccountIds.addAll(unallocatedByAccount.keySet());

        for (Long accountId : allAccountIds) {
            ChartOfAccount acct = accountMap.get(accountId);
            if (acct == null) continue;

            // Account type filter
            if (query.accountType() != null && acct.getAccountType() != query.accountType()) {
                continue;
            }

            BigDecimal budget = budgetByAccount.getOrDefault(accountId, BigDecimal.ZERO);
            BigDecimal actual = actualByAccount.getOrDefault(accountId, BigDecimal.ZERO);
            BigDecimal variance = actual.subtract(budget);
            BigDecimal variancePct = budget.compareTo(BigDecimal.ZERO) != 0
                    ? variance.divide(budget, 4, RoundingMode.HALF_UP)
                    : null;

            rows.add(new VarianceRowDto(
                    accountId, acct.getAccountCode(), acct.getName(),
                    acct.getAccountType(), acct.getNormalBalance(),
                    ccValueId, ccValueName,
                    budget, actual, variance, variancePct));

            // Unallocated bucket when centre is chosen (OQ-BUD-07)
            if (ccValueId != null && unallocatedByAccount.containsKey(accountId)) {
                BigDecimal unallocActual = unallocatedByAccount.get(accountId);
                rows.add(new VarianceRowDto(
                        accountId, acct.getAccountCode(), acct.getName(),
                        acct.getAccountType(), acct.getNormalBalance(),
                        null, "Unallocated",
                        BigDecimal.ZERO, unallocActual, unallocActual, null));
            }
        }

        // Totals by account type
        Map<AccountType, BigDecimal> totalsBudget = new EnumMap<>(AccountType.class);
        Map<AccountType, BigDecimal> totalsActual = new EnumMap<>(AccountType.class);
        Map<AccountType, BigDecimal> totalsVariance = new EnumMap<>(AccountType.class);
        for (VarianceRowDto row : rows) {
            if (row.costCentreValueName() != null && row.costCentreValueName().equals("Unallocated")) continue;
            totalsBudget.merge(row.accountType(), row.budgetAmount(), BigDecimal::add);
            totalsActual.merge(row.accountType(), row.actualAmount(), BigDecimal::add);
            totalsVariance.merge(row.accountType(), row.varianceAmount(), BigDecimal::add);
        }

        VarianceReportDto.HeaderDto header = new VarianceReportDto.HeaderDto(
                query.companyId(), fy.getUid(), fy.getYearCode(),
                query.fromPeriodNo(), query.toPeriodNo(),
                query.costCentreValueUid(), ccValueName, noApprovedBudget);

        return new VarianceReportDto(header, rows, totalsBudget, totalsActual, totalsVariance);
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentalActualsDto departmentalActuals(Long companyId, String fiscalYearUid,
                                                       int fromPeriodNo, int toPeriodNo) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        FiscalYear fy = fiscalYears.findByUid(fiscalYearUid)
                .filter(y -> y.getCompanyId().equals(companyId))
                .orElseThrow(() -> NotFoundException.of("FiscalYear", fiscalYearUid));

        List<DimensionActualsRowDto> actualsRows = dimensionActuals.actualsByAccountValuePeriod(
                companyId, DimensionSlot.COST_CENTRE, fy.getStartDate(), fy.getEndDate());

        // Group by (valueId, accountId) summing net, filter to period range
        Map<Long, Map<Long, BigDecimal>> grouped = new HashMap<>(); // valueId→(accountId→net)
        Map<Long, BigDecimal> unallocated = new HashMap<>();

        for (DimensionActualsRowDto row : actualsRows) {
            if (row.periodNo() < fromPeriodNo || row.periodNo() > toPeriodNo) continue;
            if (row.valueId() == null) {
                unallocated.merge(row.accountId(), row.net(), BigDecimal::add);
            } else {
                grouped.computeIfAbsent(row.valueId(), k -> new HashMap<>())
                        .merge(row.accountId(), row.net(), BigDecimal::add);
            }
        }

        // Load dimension value names
        Map<Long, String> valueNames = new HashMap<>();
        for (Long vid : grouped.keySet()) {
            dimensionService.listActiveValues(companyId, DimensionSlot.COST_CENTRE)
                    .stream().filter(v -> v.id().equals(vid)).findFirst()
                    .ifPresent(v -> valueNames.put(vid, v.name()));
        }

        List<DepartmentalActualsRowDto> rows = new ArrayList<>();

        // Rows for each cost-centre value
        for (Map.Entry<Long, Map<Long, BigDecimal>> e : grouped.entrySet()) {
            Long valueId = e.getKey();
            String valueName = valueNames.getOrDefault(valueId, String.valueOf(valueId));
            for (Map.Entry<Long, BigDecimal> ae : e.getValue().entrySet()) {
                ChartOfAccount acct = accounts.findById(ae.getKey()).orElse(null);
                if (acct == null) continue;
                rows.add(new DepartmentalActualsRowDto(
                        valueId, valueName,
                        acct.getId(), acct.getAccountCode(), acct.getName(),
                        acct.getAccountType(), acct.getNormalBalance(), ae.getValue()));
            }
        }

        // Unallocated rows (NULL valueId)
        for (Map.Entry<Long, BigDecimal> e : unallocated.entrySet()) {
            ChartOfAccount acct = accounts.findById(e.getKey()).orElse(null);
            if (acct == null) continue;
            rows.add(new DepartmentalActualsRowDto(
                    null, "Unallocated",
                    acct.getId(), acct.getAccountCode(), acct.getName(),
                    acct.getAccountType(), acct.getNormalBalance(), e.getValue()));
        }

        return new DepartmentalActualsDto(companyId, fy.getUid(),
                fy.getStartDate(), fy.getEndDate(), rows);
    }

    private Map<Long, ChartOfAccount> loadAccounts(Long companyId,
                                                    Map<Long, BigDecimal>... maps) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Map<Long, BigDecimal> m : maps) ids.addAll(m.keySet());
        if (ids.isEmpty()) return Map.of();
        return accounts.findAllById(ids).stream()
                .collect(Collectors.toMap(ChartOfAccount::getId, Function.identity()));
    }
}

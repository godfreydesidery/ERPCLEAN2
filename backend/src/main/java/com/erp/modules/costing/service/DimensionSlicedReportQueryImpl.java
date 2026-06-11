package com.erp.modules.costing.service;

import com.erp.modules.costing.domain.dto.DimensionActualsRowDto;
import com.erp.modules.costing.domain.dto.DimensionSlicedTbDto;
import com.erp.modules.costing.domain.dto.DimensionSlicedTbRowDto;
import com.erp.modules.costing.domain.entity.DimensionValue;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dimension-sliced trial balance and actuals reads (ADR-0025 D-8, FR-CC-14/15/16/17).
 *
 * <p>All queries are native SQL on {@code journal_lines} + {@code chart_of_accounts} +
 * {@code dimension_values}. Uses the partial indexes created in V27 for cost_centre and
 * department slots (NFR-CC-02). The actuals query joins {@code journal_entries} for period
 * attribution (D-8 Budgeting/Projects contract).
 *
 * <p>IMPORTANT: a dimension slice does NOT have to net to zero (BR-CC-01). This is not a bug.
 */
@Service
@Transactional(readOnly = true)
public class DimensionSlicedReportQueryImpl implements DimensionSlicedReportQuery {

    private final DimensionValueRepository valueRepo;
    private final ScopeGuard              scopeGuard;
    private final EntityManager           em;

    public DimensionSlicedReportQueryImpl(DimensionValueRepository valueRepo,
                                           ScopeGuard scopeGuard,
                                           EntityManager em) {
        this.valueRepo  = valueRepo;
        this.scopeGuard = scopeGuard;
        this.em         = em;
    }

    @Override
    public DimensionSlicedTbDto slicedTrialBalance(Long companyId, DimensionSlot slot,
                                                    String valueUid, boolean rollUp,
                                                    Long periodId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String slotCol = slotColumn(slot);

        // Resolve the optional value filter + its descendants
        Set<Long> valueIds = resolveValueFilter(companyId, valueUid, rollUp);

        // Build native query dynamically using plain StringBuilder
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT jl.").append(slotCol).append(" AS value_id,\n");
        sql.append("       jl.account_id,\n");
        sql.append("       SUM(jl.debit_amount)  AS total_debit,\n");
        sql.append("       SUM(jl.credit_amount) AS total_credit\n");
        sql.append("FROM journal_lines jl\n");
        if (periodId != null) {
            sql.append("JOIN journal_entries je ON je.id = jl.entry_id\n");
        }
        sql.append("WHERE jl.company_id = :companyId\n");
        sql.append("  AND jl.").append(slotCol).append(" IS NOT NULL\n");
        if (!valueIds.isEmpty()) {
            sql.append("  AND jl.").append(slotCol).append(" IN (:valueIds)\n");
        }
        if (periodId != null) {
            sql.append("  AND je.fiscal_period_id = :periodId\n");
        }
        sql.append("GROUP BY jl.").append(slotCol).append(", jl.account_id\n");
        sql.append("ORDER BY jl.").append(slotCol).append(", jl.account_id");

        var query = em.createNativeQuery(sql.toString());
        query.setParameter("companyId", companyId);
        if (!valueIds.isEmpty()) {
            query.setParameter("valueIds", valueIds);
        }
        if (periodId != null) {
            query.setParameter("periodId", periodId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = query.getResultList();

        // Enrich with value + account metadata
        List<DimensionSlicedTbRowDto> rows = rawRows.stream()
                .map(r -> enrichRow(r, companyId))
                .toList();

        return new DimensionSlicedTbDto(slot, rollUp, rows);
    }

    @Override
    public List<DimensionActualsRowDto> actualsByAccountValuePeriod(Long companyId,
                                                                      DimensionSlot slot,
                                                                      LocalDate fromDate,
                                                                      LocalDate toDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String slotCol = slotColumn(slot);

        // Plain concatenation — no text-block interpolation
        String sql = "SELECT jl." + slotCol + " AS value_id,\n"
                + "       jl.account_id,\n"
                + "       je.fiscal_period_id  AS period_id,\n"
                + "       fp.period_no         AS period_no,\n"
                + "       SUM(jl.debit_amount)  AS total_debit,\n"
                + "       SUM(jl.credit_amount) AS total_credit\n"
                + "FROM journal_lines jl\n"
                + "JOIN journal_entries je ON je.id = jl.entry_id\n"
                + "JOIN fiscal_periods fp  ON fp.id = je.fiscal_period_id\n"
                + "WHERE jl.company_id    = :companyId\n"
                + "  AND jl." + slotCol + " IS NOT NULL\n"
                + "  AND je.posting_date BETWEEN :fromDate AND :toDate\n"
                + "GROUP BY jl." + slotCol + ", jl.account_id, je.fiscal_period_id, fp.period_no\n"
                + "ORDER BY jl." + slotCol + ", jl.account_id, fp.period_no";

        var query = em.createNativeQuery(sql);
        query.setParameter("companyId", companyId);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        @SuppressWarnings("unchecked")
        List<Object[]> rawRows = query.getResultList();

        return rawRows.stream()
                .map(r -> {
                    Long       valueId   = ((Number) r[0]).longValue();
                    Long       accountId = ((Number) r[1]).longValue();
                    Integer    periodNo  = ((Number) r[3]).intValue();
                    BigDecimal debit     = asBD(r[4]);
                    BigDecimal credit    = asBD(r[5]);
                    return new DimensionActualsRowDto(
                            accountId, valueId, periodNo, debit, credit,
                            debit.subtract(credit));
                })
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Map DimensionSlot to the journal_lines column name (D-3). */
    private String slotColumn(DimensionSlot slot) {
        return switch (slot) {
            case COST_CENTRE  -> "cost_centre_value_id";
            case DEPARTMENT   -> "department_value_id";
            case DIMENSION_3  -> "dimension3_value_id";
            case DIMENSION_4  -> "dimension4_value_id";
        };
    }

    /**
     * Resolve the optional value filter + descendants (FR-CC-16 roll-up).
     * Returns empty set when valueUid is null (no filter — all values).
     */
    private Set<Long> resolveValueFilter(Long companyId, String valueUid, boolean rollUp) {
        if (valueUid == null) {
            return Set.of();
        }
        DimensionValue root = valueRepo.findByCompanyIdAndUid(companyId, valueUid)
                .orElseThrow(() -> NotFoundException.of("DimensionValue", valueUid));

        if (!rollUp) {
            return Set.of(root.getId());
        }

        // BFS to collect root + all descendants
        Set<Long> ids = new HashSet<>();
        List<Long> queue = new ArrayList<>();
        queue.add(root.getId());
        while (!queue.isEmpty()) {
            Long current = queue.remove(0);
            ids.add(current);
            valueRepo.findByParentId(current).forEach(child -> queue.add(child.getId()));
        }
        return ids;
    }

    /** Enrich a raw [value_id, account_id, total_debit, total_credit] row with display metadata. */
    private DimensionSlicedTbRowDto enrichRow(Object[] r, Long companyId) {
        Long       valueId   = ((Number) r[0]).longValue();
        Long       accountId = ((Number) r[1]).longValue();
        BigDecimal debit     = asBD(r[2]);
        BigDecimal credit    = asBD(r[3]);

        // Look up value metadata
        DimensionValue v = valueRepo.findById(valueId).orElse(null);
        String valueUid  = v != null ? v.getUid()  : null;
        String valueCode = v != null ? v.getCode() : null;
        String valueName = v != null ? v.getName() : null;

        // Look up account metadata (native query — cross-module table read, D-7)
        Object[] acct = (Object[]) em.createNativeQuery(
                        "SELECT uid, account_code, name, account_type"
                        + " FROM chart_of_accounts WHERE id = :id")
                .setParameter("id", accountId)
                .getResultStream().findFirst().orElse(null);

        String accountUid  = acct != null ? (String) acct[0] : null;
        String accountCode = acct != null ? (String) acct[1] : null;
        String accountName = acct != null ? (String) acct[2] : null;
        String accountType = acct != null ? (String) acct[3] : null;

        return new DimensionSlicedTbRowDto(
                valueId, valueUid, valueCode, valueName,
                accountId, accountUid, accountCode, accountName, accountType,
                debit, credit, debit.subtract(credit));
    }

    private BigDecimal asBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }
}

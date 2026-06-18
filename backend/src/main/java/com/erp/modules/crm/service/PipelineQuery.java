package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.CrmKpiDto;
import com.erp.modules.crm.domain.dto.ForecastDto;
import com.erp.modules.crm.domain.dto.PipelineSummaryDto;
import com.erp.modules.crm.domain.dto.PipelineStageSummaryRowDto;
import com.erp.modules.crm.repository.OpportunityRepository;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pipeline / forecast / KPI read aggregate (ADR-0031 D-9).
 * All reads are scoped by company_id + branch_id via assertCanActIn.
 */
@Component
@Transactional(readOnly = true)
public class PipelineQuery {

    private final OpportunityRepository opportunities;
    private final ScopeGuard scopeGuard;

    public PipelineQuery(OpportunityRepository opportunities, ScopeGuard scopeGuard) {
        this.opportunities = opportunities;
        this.scopeGuard = scopeGuard;
    }

    /**
     * Open opportunities grouped by stage, Σ value and Σ weighted value per stage.
     */
    public PipelineSummaryDto pipeline(Long companyId, Long branchId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> rows = opportunities.pipelineSummaryRaw(companyId, branchId);
        List<PipelineStageSummaryRowDto> stages = new ArrayList<>();
        for (Object[] row : rows) {
            String stageUid   = (String) row[0];
            String stageName  = (String) row[1];
            Number orderNum   = (Number) row[2];
            short displayOrder = orderNum.shortValue();
            Number count      = (Number) row[3];
            long openCount    = count.longValue();
            BigDecimal total    = toBd(row[4]);
            BigDecimal weighted = toBd(row[5]);
            String currency   = toCurrency(row[6]);
            stages.add(new PipelineStageSummaryRowDto(stageUid, stageName, displayOrder,
                    openCount, total, weighted, currency));
        }
        return new PipelineSummaryDto(stages);
    }

    /**
     * Σ weighted value of OPEN opportunities with expected-close in [from, to].
     */
    public ForecastDto forecast(Long companyId, Long branchId, LocalDate from, LocalDate to) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> rows = opportunities.forecastRaw(companyId, branchId, from, to);
        if (rows.isEmpty()) {
            return new ForecastDto(from, to, BigDecimal.ZERO, 0L, "TZS");
        }
        Object[] r = rows.get(0);
        BigDecimal weighted = toBd(r[0]);
        long count = ((Number) r[1]).longValue();
        String currency = toCurrency(r[2]);
        return new ForecastDto(from, to, weighted != null ? weighted : BigDecimal.ZERO, count, currency);
    }

    /**
     * Win-rate + avg cycle-time KPIs for a period.
     */
    public CrmKpiDto kpis(Long companyId, Long branchId, LocalDate from, LocalDate to) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Instant fromInst = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toInst   = to.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        List<Object[]> rows = opportunities.kpiRaw(companyId, branchId, fromInst, toInst);

        long wonCount  = 0L;
        long lostCount = 0L;
        BigDecimal avgCycleDays = BigDecimal.ZERO;

        for (Object[] r : rows) {
            String status = r[0].toString();
            long cnt      = ((Number) r[1]).longValue();
            if ("WON".equals(status)) {
                wonCount = cnt;
                BigDecimal avg = toBd(r[2]);
                avgCycleDays = avg != null ? avg.setScale(1, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            } else if ("LOST".equals(status)) {
                lostCount = cnt;
            }
        }
        long total = wonCount + lostCount;
        BigDecimal winRate = total > 0
                ? BigDecimal.valueOf(wonCount * 100.0 / total).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new CrmKpiDto(from, to, wonCount, lostCount, winRate, avgCycleDays);
    }

    // -------------------------------------------------------------------------

    private static BigDecimal toBd(Object val) {
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    /**
     * Coerce a JPQL currency projection to its 3-letter code. {@code o.currency} is a
     * {@link CurrencyCode} value type (AttributeConverter), so a JPQL {@code SELECT o.currency}
     * projects a {@code CurrencyCode} instance — never a raw {@code String}. Casting straight to
     * {@code (String)} therefore throws {@link ClassCastException} (the BI dashboard 500 caught by
     * the massive-data e2e gate). Handles {@code CurrencyCode}, {@code String} and {@code null}.
     */
    private static String toCurrency(Object val) {
        if (val == null) return null;
        if (val instanceof CurrencyCode cc) return cc.value();
        return val.toString();
    }
}

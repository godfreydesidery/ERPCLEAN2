package com.erp.modules.bi.service;

import com.erp.modules.bi.domain.dto.CrmSnapshotDto;
import com.erp.modules.bi.domain.dto.DashboardDto;
import com.erp.modules.bi.domain.dto.FinanceSummaryDto;
import com.erp.modules.bi.domain.dto.HealthIndicatorDto;
import com.erp.modules.bi.domain.dto.InventorySummaryDto;
import com.erp.modules.bi.domain.dto.TrendDto;
import com.erp.modules.bi.domain.dto.TrendPointDto;
import com.erp.modules.bi.domain.dto.WorkingCapitalDto;
import com.erp.modules.crm.domain.dto.PipelineStageSummaryRowDto;
import com.erp.modules.reporting.export.StatementRenderModel;
import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Flattens {@link DashboardDto} into a {@link StatementRenderModel} for export via
 * {@link com.erp.modules.reporting.export.ReportExporter} (ADR-0037 D-9).
 *
 * <p>Shape rules (D-9):
 * <ul>
 *   <li>SECTION_HEADER per panel, LINE per metric, SUBTOTAL/TOTAL where natural.
 *   <li>RECONCILIATION rows for all health ties (AR/AP/Cash/Stock/TB) — [OK]/[!] a11y prefix
 *       is emitted by the renderers.
 *   <li>The 12-month trend is flattened as 12 LINE rows (one per period, value in {@code current})
 *       — the boring fit that needs NO render-model extension (D-9 decision).
 *   <li>StatementRenderModel is NOT extended; ZERO new PDF/XLSX/CSV code.
 * </ul>
 */
@Component
public class BiExportFlattener {

    public StatementRenderModel flatten(DashboardDto dto) {
        List<Row> rows = new ArrayList<>();
        String companyName = dto.header() != null ? dto.header().companyName() : "";
        String currency    = dto.header() != null ? dto.header().currency()    : "";
        String periodLabel = dto.header() != null ? dto.header().periodLabel() : "";

        // ── Finance panel ────────────────────────────────────────────────────
        FinanceSummaryDto fs = dto.finance();
        if (fs != null) {
            rows.add(Row.sectionHeader("Finance Summary"));
            rows.add(Row.line("Revenue",            bd(fs.revenue()),   BigDecimal.ZERO));
            rows.add(Row.line("Operating Expenses", bd(fs.opex()),      BigDecimal.ZERO));
            rows.add(Row.total("Net Profit",        bd(fs.netProfit()), BigDecimal.ZERO));
            // TB health
            BigDecimal tbDiff = fs.tbTotalDebit() != null && fs.tbTotalCredit() != null
                    ? fs.tbTotalDebit().subtract(fs.tbTotalCredit()) : BigDecimal.ZERO;
            rows.add(Row.reconciliation("Trial Balance (Debit = Credit)", tbDiff, fs.tbTies()));
            // Cash
            if (fs.cash() != null) {
                rows.add(Row.sectionHeader("Cash Position"));
                rows.add(Row.total("Total Cash Book Balance", bd(fs.cash().total()), BigDecimal.ZERO));
                rows.add(Row.reconciliation("Cash vs GL", bd(fs.cash().cashGlDifference()),
                        fs.cash().cashTies()));
            }
        }

        // ── Working Capital panel ────────────────────────────────────────────
        WorkingCapitalDto wc = dto.workingCapital();
        if (wc != null) {
            rows.add(Row.sectionHeader("Working Capital"));
            rows.add(Row.line("AR Outstanding (sub-ledger)", bd(wc.arOutstanding()), BigDecimal.ZERO));
            rows.add(Row.reconciliation("AR vs GL 1200", bd(wc.arDifference()), wc.arTies()));
            rows.add(Row.line("AP Outstanding (sub-ledger)", bd(wc.apOutstanding()), BigDecimal.ZERO));
            rows.add(Row.reconciliation("AP vs GL 2100", bd(wc.apDifference()), wc.apTies()));
        }

        // ── Inventory panel ──────────────────────────────────────────────────
        InventorySummaryDto inv = dto.inventory();
        if (inv != null) {
            rows.add(Row.sectionHeader("Inventory"));
            rows.add(Row.line("Stock Value", bd(inv.stockValue()), BigDecimal.ZERO));
            rows.add(Row.reconciliation("Stock vs GL 1300", bd(inv.stockDifference()), inv.stockTies()));
        }

        // ── CRM panel ────────────────────────────────────────────────────────
        CrmSnapshotDto crm = dto.crm();
        if (crm != null) {
            rows.add(Row.sectionHeader("CRM Pipeline"));
            if (crm.pipeline() != null && crm.pipeline().stages() != null) {
                for (PipelineStageSummaryRowDto stage : crm.pipeline().stages()) {
                    rows.add(Row.line(stage.stageName() + " (pipeline value)",
                            bd(stage.totalValueAmount()), bd(stage.weightedValueAmount())));
                }
            }
            if (crm.kpis() != null) {
                rows.add(Row.sectionHeader("CRM KPIs"));
                rows.add(Row.line("Won",           BigDecimal.valueOf(crm.kpis().wonCount()),  BigDecimal.ZERO));
                rows.add(Row.line("Lost",          BigDecimal.valueOf(crm.kpis().lostCount()), BigDecimal.ZERO));
                rows.add(Row.line("Win Rate %",    bd(crm.kpis().winRatePercent()),            BigDecimal.ZERO));
                rows.add(Row.line("Avg Cycle Days", bd(crm.kpis().avgCycleDays()),             BigDecimal.ZERO));
            }
            if (crm.forecast() != null) {
                rows.add(Row.line("Forecast (weighted)", bd(crm.forecast().weightedValueAmount()), BigDecimal.ZERO));
            }
        }

        // ── Revenue Trend (12 LINE rows — D-9 boring fit, no model extension) ─
        TrendDto revTrend = dto.revenueTrend();
        if (revTrend != null && revTrend.points() != null) {
            rows.add(Row.sectionHeader("Revenue Trend"));
            for (TrendPointDto pt : revTrend.points()) {
                rows.add(Row.line(pt.periodLabel(), bd(pt.value()), BigDecimal.ZERO));
            }
        }

        // ── Net Profit Trend ────────────────────────────────────────────────
        TrendDto netTrend = dto.netProfitTrend();
        if (netTrend != null && netTrend.points() != null) {
            rows.add(Row.sectionHeader("Net Profit Trend"));
            for (TrendPointDto pt : netTrend.points()) {
                rows.add(Row.line(pt.periodLabel(), bd(pt.value()), BigDecimal.ZERO));
            }
        }

        // ── Health strip ─────────────────────────────────────────────────────
        if (dto.health() != null && !dto.health().isEmpty()) {
            rows.add(Row.sectionHeader("Health Indicators"));
            for (HealthIndicatorDto h : dto.health()) {
                rows.add(Row.reconciliation(h.label(), bd(h.difference()), h.ties()));
            }
        }

        return new StatementRenderModel(
                "BI Dashboard",
                companyName,
                currency,
                periodLabel,
                null,
                Instant.now().toString(),
                rows);
    }

    // -------------------------------------------------------------------------

    private static BigDecimal bd(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

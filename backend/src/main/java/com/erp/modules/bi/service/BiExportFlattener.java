package com.erp.modules.bi.service;

import com.erp.modules.ap.domain.dto.ApUnconvertedAmountDto;
import com.erp.modules.ar.domain.dto.ArUnconvertedAmountDto;
import com.erp.modules.bi.domain.dto.BranchSalesRowDto;
import com.erp.modules.bi.domain.dto.CrmSnapshotDto;
import com.erp.modules.bi.domain.dto.DashboardDto;
import com.erp.modules.bi.domain.dto.FinanceSummaryDto;
import com.erp.modules.bi.domain.dto.HealthIndicatorDto;
import com.erp.modules.bi.domain.dto.InventorySummaryDto;
import com.erp.modules.bi.domain.dto.SalesByBranchDto;
import com.erp.modules.bi.domain.dto.TrendDto;
import com.erp.modules.bi.domain.dto.TrendPointDto;
import com.erp.modules.bi.domain.dto.WorkingCapitalDto;
import com.erp.modules.cashbank.domain.dto.CashAccountBalanceDto;
import com.erp.modules.crm.domain.dto.PipelineStageSummaryRowDto;
import com.erp.modules.reporting.export.StatementRenderModel;
import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
        String branchLabel = dto.header() != null ? dto.header().branchLabel() : null;

        // ── Scope banner (UAT, 2026-08) ──────────────────────────────────────
        // A downloaded dashboard outlives the screen that produced it, so which branch it covers
        // has to be on the page. branchLabel is never null on a real header and already says
        // "All branches" when nothing was filtered — no branch line at all was the defect.
        if (branchLabel != null && !branchLabel.isBlank()) {
            rows.add(Row.sectionHeader("Branch: " + branchLabel));
        }

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
                // Each account in its OWN currency — the label names it, because the amount column
                // of this document is otherwise read as base currency.
                for (CashAccountBalanceDto acc : fs.cash().accounts() == null ? List.<CashAccountBalanceDto>of() : fs.cash().accounts()) {
                    rows.add(Row.line(
                            (acc.accountCode() + " " + acc.accountName()).trim()
                                    + " (" + (acc.currency() != null ? acc.currency() : currency) + ")",
                            bd(acc.bookBalance()), BigDecimal.ZERO));
                }
                // The payload's cash total adds every account's own-currency balance together; the
                // file states the base-currency accounts as the total and each foreign currency
                // beside it, worked out from the account list (the AR/AP rule, 2026-10-02).
                CashSplit split = splitByCurrency(fs.cash().accounts(), currency);
                rows.add(Row.total("Total Cash Book Balance (" + currency + " accounts)",
                        split.baseTotal(), BigDecimal.ZERO));
                split.foreign().forEach((cur, amt) -> rows.add(Row.line(
                        "Not included above — " + cur + " accounts (in " + cur + ")", amt, BigDecimal.ZERO)));
                rows.add(Row.reconciliation("Cash vs GL", bd(fs.cash().cashGlDifference()),
                        fs.cash().cashTies()));
            }
        }

        // ── Working Capital panel ────────────────────────────────────────────
        WorkingCapitalDto wc = dto.workingCapital();
        if (wc != null) {
            rows.add(Row.sectionHeader("Working Capital"));
            rows.add(Row.line("AR Outstanding (sub-ledger)", bd(wc.arOutstanding()), BigDecimal.ZERO));
            for (ArUnconvertedAmountDto u : wc.arUnconverted()) {
                rows.add(Row.line("AR not converted (in " + u.currency() + ")", bd(u.amount()), BigDecimal.ZERO));
            }
            rows.add(Row.reconciliation("AR vs GL 1200", bd(wc.arDifference()), wc.arTies()));
            rows.add(Row.line("AP Outstanding (sub-ledger)", bd(wc.apOutstanding()), BigDecimal.ZERO));
            for (ApUnconvertedAmountDto u : wc.apUnconverted()) {
                rows.add(Row.line("AP not converted (in " + u.currency() + ")", bd(u.amount()), BigDecimal.ZERO));
            }
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

        // ── Sales by Branch (finalised invoices, gross incl. VAT, base currency) ─
        SalesByBranchDto sbb = dto.salesByBranch();
        if (sbb != null) {
            rows.add(Row.sectionHeader("Sales by Branch (finalised invoices, incl. VAT)"));
            for (BranchSalesRowDto r : sbb.rows()) {
                rows.add(Row.line(r.branchCode() + " — " + r.branchName() + " (" + r.count()
                        + (r.count() == 1 ? " invoice)" : " invoices)"), bd(r.total()), BigDecimal.ZERO));
            }
            rows.add(Row.total("Total (" + sbb.invoiceCount()
                    + (sbb.invoiceCount() == 1 ? " invoice)" : " invoices)"), bd(sbb.grandTotal()), BigDecimal.ZERO));
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

    /** Base-currency cash total plus the foreign balances, per currency, kept out of it. */
    record CashSplit(BigDecimal baseTotal, Map<String, BigDecimal> foreign) {}

    /**
     * Only accounts in the base currency are added into the cash total. A cash transaction records
     * only its own-currency amount — there is no booked base value to add — so a foreign balance is
     * stated per currency instead. An account with no currency counts as base.
     */
    static CashSplit splitByCurrency(List<CashAccountBalanceDto> accounts, String baseCurrency) {
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> foreign = new TreeMap<>();
        for (CashAccountBalanceDto acc : accounts == null ? List.<CashAccountBalanceDto>of() : accounts) {
            BigDecimal bal = bd(acc.bookBalance());
            String cur = acc.currency();
            if (cur == null || cur.isBlank() || cur.equalsIgnoreCase(baseCurrency)) {
                total = total.add(bal);
            } else {
                foreign.merge(cur.toUpperCase(Locale.ROOT), bal, BigDecimal::add);
            }
        }
        return new CashSplit(total, foreign);
    }

    private static BigDecimal bd(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}

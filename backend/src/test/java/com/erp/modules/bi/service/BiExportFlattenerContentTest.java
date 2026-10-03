package com.erp.modules.bi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.ApUnconvertedAmountDto;
import com.erp.modules.ar.domain.dto.ArUnconvertedAmountDto;
import com.erp.modules.bi.domain.dto.BiHeaderDto;
import com.erp.modules.bi.domain.dto.BranchSalesRowDto;
import com.erp.modules.bi.domain.dto.CashPositionDto;
import com.erp.modules.bi.domain.dto.DashboardDto;
import com.erp.modules.bi.domain.dto.FinanceSummaryDto;
import com.erp.modules.bi.domain.dto.SalesByBranchDto;
import com.erp.modules.bi.domain.dto.WorkingCapitalDto;
import com.erp.modules.cashbank.domain.dto.CashAccountBalanceDto;
import com.erp.modules.reporting.export.StatementRenderModel;
import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The exported dashboard carries what the screen shows (2026-10-03).
 *
 * <p>The download used to drop the per-account cash table, the "not converted" foreign-currency
 * amounts and the whole Sales by Branch panel, so a file handed to a director said less than the
 * screen it came from — and the foreign amounts it dropped are exactly the ones a base-currency
 * total leaves out.
 */
class BiExportFlattenerContentTest {

    private final BiExportFlattener flattener = new BiExportFlattener();

    @Test
    void cashAccountsAreListedEachInItsOwnCurrency() {
        StatementRenderModel model = flattener.flatten(dashboard());

        assertThat(amountOf(model, "CB-01 Main Till (TZS)")).contains(new BigDecimal("500000.00"));
        assertThat(amountOf(model, "CB-02 Dollar Account (USD)")).contains(new BigDecimal("1200.00"));
    }

    @Test
    void cashTotalIsLabelledBaseOnly_andForeignBalancesAreStatedBesideIt() {
        StatementRenderModel model = flattener.flatten(dashboard());

        assertThat(amountOf(model, "Total Cash Book Balance (TZS accounts)"))
                .contains(new BigDecimal("500000.00"));
        assertThat(amountOf(model, "Not included above — USD accounts (in USD)"))
                .contains(new BigDecimal("1200.00"));
    }

    @Test
    void arAndApAmountsWithNoReliableRateAreExported() {
        StatementRenderModel model = flattener.flatten(dashboard());

        assertThat(amountOf(model, "AR not converted (in USD)")).contains(new BigDecimal("300.00"));
        assertThat(amountOf(model, "AP not converted (in EUR)")).contains(new BigDecimal("75.50"));
    }

    @Test
    void salesByBranchIsExportedWithItsTotal() {
        StatementRenderModel model = flattener.flatten(dashboard());

        assertThat(labels(model)).contains("Sales by Branch (finalised invoices, incl. VAT)");
        assertThat(amountOf(model, "DSM — DSM Main (34 invoices)")).contains(new BigDecimal("5120000.00"));
        assertThat(amountOf(model, "ARU — Arusha (1 invoice)")).contains(new BigDecimal("2890000.00"));
        assertThat(amountOf(model, "Total (35 invoices)")).contains(new BigDecimal("8010000.00"));
    }

    // -------------------------------------------------------------------------

    private static List<String> labels(StatementRenderModel model) {
        return model.rows().stream().map(Row::label).toList();
    }

    private static Optional<BigDecimal> amountOf(StatementRenderModel model, String label) {
        return model.rows().stream()
                .filter(r -> label.equals(r.label()))
                .map(Row::current)
                .findFirst();
    }

    private static DashboardDto dashboard() {
        BiHeaderDto header = new BiHeaderDto(
                10L, "Tembo Group", null, null, "All branches",
                "TZS", "2026-10-01 – 2026-10-03",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 10, 3), Instant.parse("2026-10-03T06:00:00Z"));
        // The payload's total is the raw sum of every account (501,200) — the file must not use it.
        CashPositionDto cash = new CashPositionDto(
                new BigDecimal("501200.00"),
                List.of(
                        new CashAccountBalanceDto(1L, "U1", "CB-01", "Main Till", new BigDecimal("500000.00"), "TZS"),
                        new CashAccountBalanceDto(2L, "U2", "CB-02", "Dollar Account", new BigDecimal("1200.00"), "USD")),
                true, BigDecimal.ZERO);
        FinanceSummaryDto finance = new FinanceSummaryDto(
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                true, BigDecimal.TEN, BigDecimal.TEN, cash);
        WorkingCapitalDto wc = new WorkingCapitalDto(
                new BigDecimal("1000.00"), true, BigDecimal.ZERO,
                new BigDecimal("400.00"), true, BigDecimal.ZERO,
                List.of(new ArUnconvertedAmountDto("USD", new BigDecimal("300.00"), 2)),
                List.of(new ApUnconvertedAmountDto("EUR", new BigDecimal("75.50"), 1)));
        SalesByBranchDto sales = new SalesByBranchDto("TZS", new BigDecimal("8010000.00"), 35L, List.of(
                new BranchSalesRowDto(1L, "DSM", "DSM Main", new BigDecimal("5120000.00"), 34L),
                new BranchSalesRowDto(2L, "ARU", "Arusha", new BigDecimal("2890000.00"), 1L)));
        return new DashboardDto(header, finance, wc, null, null, null, null, sales, List.of());
    }

    @Test
    void cashSplit_keepsForeignAccountsOutOfTheBaseTotal() {
        BiExportFlattener.CashSplit split = BiExportFlattener.splitByCurrency(List.of(
                new CashAccountBalanceDto(1L, "A", "1", "Till", new BigDecimal("500000.00"), "TZS"),
                new CashAccountBalanceDto(2L, "B", "2", "Legacy", new BigDecimal("100.00"), null),
                new CashAccountBalanceDto(3L, "C", "3", "Dollar A", new BigDecimal("1200.00"), "USD"),
                new CashAccountBalanceDto(4L, "D", "4", "Dollar B", new BigDecimal("-200.00"), "usd")), "TZS");

        assertThat(split.baseTotal()).isEqualByComparingTo("500100.00");
        assertThat(split.foreign()).containsOnlyKeys("USD");
        assertThat(split.foreign().get("USD")).isEqualByComparingTo("1000.00");
    }
}

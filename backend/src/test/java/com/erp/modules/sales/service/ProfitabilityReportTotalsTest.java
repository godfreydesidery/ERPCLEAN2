package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.sales.domain.dto.ProfitabilityRowDto;
import com.erp.modules.sales.domain.dto.ProfitabilityTotalsDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The foot of the Profitability Report (K-2026-08-30 #2).
 *
 * <p>The rule under test is the one that makes the report trustworthy: a product whose stock was
 * sold before it had ever been costed is EXCLUDED from Cost of Sales and Profit and COUNTED, never
 * added in at zero cost. Added in at zero, its entire sale would be reported as profit — the exact
 * defect that was corrected on the Sales Report ("the margin sometimes seems not correct"), and on
 * a report whose only purpose is the profit figure it would be worse.
 */
class ProfitabilityReportTotalsTest {

    private static ProfitabilityRowDto row(String code, String gross, String vat, String net,
                                            String cost, String profit) {
        return new ProfitabilityRowDto(
                code, code + " description",
                new BigDecimal("1"),
                new BigDecimal(gross),
                new BigDecimal(vat),
                new BigDecimal(net),
                cost != null ? new BigDecimal(cost) : null,
                profit != null ? new BigDecimal(profit) : null);
    }

    @Test
    void sumsEveryColumnWhenEveryCostIsKnown() {
        ProfitabilityTotalsDto t = ProfitabilityReportQuery.totalsOf(List.of(
                row("A", "11800", "1800", "10000", "6000", "4000"),
                row("B", "5900",  "900",  "5000",  "3500", "1500")));

        assertThat(t.grossSales()).isEqualByComparingTo("17700");
        assertThat(t.vatAmount()).isEqualByComparingTo("2700");
        assertThat(t.netAmount()).isEqualByComparingTo("15000");
        assertThat(t.costOfSales()).isEqualByComparingTo("9500");
        assertThat(t.profit()).isEqualByComparingTo("5500");
        assertThat(t.rowsWithUnknownCost()).isZero();
    }

    /** Gross − VAT = Net, and Net − Cost = Profit, at the foot as well as on every line. */
    @Test
    void theTotalsTieTogetherTheWayThePageClaims() {
        ProfitabilityTotalsDto t = ProfitabilityReportQuery.totalsOf(List.of(
                row("A", "11800", "1800", "10000", "6000", "4000"),
                row("B", "5900",  "900",  "5000",  "3500", "1500")));

        assertThat(t.grossSales().subtract(t.vatAmount())).isEqualByComparingTo(t.netAmount());
        assertThat(t.netAmount().subtract(t.costOfSales())).isEqualByComparingTo(t.profit());
    }

    @Test
    void anUncostedRowIsExcludedFromCostAndProfit_notCountedAsFreeStock() {
        ProfitabilityTotalsDto t = ProfitabilityReportQuery.totalsOf(List.of(
                row("A", "11800", "1800", "10000", "6000", "4000"),
                // Never costed: 5,000 of net sales that must NOT arrive as 5,000 of profit.
                row("B", "5900",  "900",  "5000",  null,   null)));

        assertThat(t.costOfSales()).isEqualByComparingTo("6000");
        assertThat(t.profit()).isEqualByComparingTo("4000");
        assertThat(t.rowsWithUnknownCost()).isEqualTo(1);

        // Sales are still fully reported — it is only the cost side that is incomplete.
        assertThat(t.grossSales()).isEqualByComparingTo("17700");
        assertThat(t.netAmount()).isEqualByComparingTo("15000");
    }

    /**
     * The partial total no longer ties out, and that is correct: the page states the excluded count
     * beside it rather than silently balancing the arithmetic with a cost nobody knows.
     */
    @Test
    void aPartialTotalIsDisclosedRatherThanBalanced() {
        ProfitabilityTotalsDto t = ProfitabilityReportQuery.totalsOf(List.of(
                row("A", "11800", "1800", "10000", "6000", "4000"),
                row("B", "5900",  "900",  "5000",  null,   null)));

        assertThat(t.netAmount().subtract(t.costOfSales())).isNotEqualByComparingTo(t.profit());
        assertThat(t.rowsWithUnknownCost()).isPositive();
    }

    @Test
    void anEmptyPeriodIsZeros_notNulls() {
        ProfitabilityTotalsDto t = ProfitabilityReportQuery.totalsOf(List.of());

        assertThat(t.grossSales()).isEqualByComparingTo("0");
        assertThat(t.vatAmount()).isEqualByComparingTo("0");
        assertThat(t.netAmount()).isEqualByComparingTo("0");
        assertThat(t.costOfSales()).isEqualByComparingTo("0");
        assertThat(t.profit()).isEqualByComparingTo("0");
        assertThat(t.rowsWithUnknownCost()).isZero();
    }

    /** A loss is a real answer and must survive as a negative, not be floored at zero. */
    @Test
    void aLossIsReportedAsALoss() {
        ProfitabilityTotalsDto t = ProfitabilityReportQuery.totalsOf(List.of(
                row("A", "11800", "1800", "10000", "12000", "-2000")));

        assertThat(t.profit()).isEqualByComparingTo("-2000");
    }
}

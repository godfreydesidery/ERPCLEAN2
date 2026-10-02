package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.sales.domain.dto.PaymentSummaryRowDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryTotalsDto;
import com.erp.modules.sales.domain.dto.SalesSummaryRowDto;
import com.erp.modules.sales.domain.dto.SalesSummaryTotalsDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Totals arithmetic of the Sales Summary and the Payment Summary. */
class SalesSummaryTotalsTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static SalesSummaryRowDto row(long invoices, String net, String cost) {
        BigDecimal n = bd(net);
        BigDecimal c = cost != null ? bd(cost) : null;
        BigDecimal margin = c != null ? n.subtract(c) : null;
        return new SalesSummaryRowDto("K", "L", null, invoices, bd("1"), n.add(bd("18")),
                BigDecimal.ZERO, bd("18"), n, c, margin,
                SalesSummaryReportQuery.percentOf(margin, n), c != null ? 0 : 1, 0);
    }

    @Test
    void unknownCostIsLeftOutOfCostAndMargin_andOfTheMarginPercentBase() {
        SalesSummaryTotalsDto t = SalesSummaryReportQuery.totalsOf(List.of(
                row(2, "1000", "600"),
                row(1, "500", null)));

        assertThat(t.invoiceCount()).isEqualTo(3);
        assertThat(t.netAmount()).as("sales still cover every group").isEqualByComparingTo("1500");
        assertThat(t.costOfSales()).isEqualByComparingTo("600");
        assertThat(t.margin()).isEqualByComparingTo("400");
        // 400 / 1000, not 400 / 1500: the uncosted group's net would understate the percentage.
        assertThat(t.marginPercent()).isEqualByComparingTo("40.00");
        assertThat(t.groupsWithUnknownCost()).isEqualTo(1);
        assertThat(t.unknownCostItems()).isEqualTo(1);
    }

    @Test
    void marginPercentIsUnknownOnZeroNet() {
        assertThat(SalesSummaryReportQuery.percentOf(bd("0"), bd("0"))).isNull();
        assertThat(SalesSummaryReportQuery.percentOf(null, bd("10"))).isNull();
    }

    @Test
    void paymentTotalsAreOnePerCurrency_baseFirst_andFootTheLines() {
        List<PaymentSummaryRowDto> rows = List.of(
                new PaymentSummaryRowDto("2026-10-01", "U1", "Ann", "USD",
                        bd("10"), bd("0"), bd("0"), bd("0"), bd("10"), 1),
                new PaymentSummaryRowDto("2026-10-01", "U1", "Ann", "TZS",
                        bd("3000"), bd("540"), bd("0"), bd("0"), bd("3540"), 2),
                new PaymentSummaryRowDto("2026-10-02", "U2", "Ben", "TZS",
                        bd("0"), bd("0"), bd("590"), bd("0"), bd("590"), 1));

        List<PaymentSummaryTotalsDto> t = PaymentSummaryReportQuery.totalsOf(rows, "TZS");

        assertThat(t).extracting(PaymentSummaryTotalsDto::currency).containsExactly("TZS", "USD");
        assertThat(t.get(0).cash()).isEqualByComparingTo("3000");
        assertThat(t.get(0).card()).isEqualByComparingTo("590");
        assertThat(t.get(0).total()).isEqualByComparingTo("4130");
        assertThat(t.get(0).payments()).isEqualTo(3);
        assertThat(t.get(1).total()).isEqualByComparingTo("10");
    }

    @Test
    void anEmptyCashUpStillPrintsABaseCurrencyFootOfZero() {
        List<PaymentSummaryTotalsDto> t = PaymentSummaryReportQuery.totalsOf(List.of(), "TZS");
        assertThat(t).hasSize(1);
        assertThat(t.get(0).total()).isEqualByComparingTo("0");
    }
}

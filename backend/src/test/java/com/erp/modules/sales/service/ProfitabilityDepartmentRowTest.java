package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.sales.domain.dto.ProfitabilityDepartmentRowDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The department view reproduces the client's own profitability report exactly (Kilimanjaro
 * sample, period 01-Dec-2025 to 31-Dec-2025).
 *
 * <p>The column meanings were reverse-engineered from that scan, so they are pinned here against
 * its real figures rather than against anything restated: if a later change breaks an identity the
 * client reconciles on, these fail with their own numbers in the message.
 *
 * <pre>
 *   netSales        = grossSales - discount
 *   netAmount + vatAmount = netSales
 *   vat + exempt + zero   = netAmount
 *   netContribution = netAmount - costOfSales
 *   margin %        = contribution / netAmount
 *   markup %        = contribution / costOfSales
 * </pre>
 */
class ProfitabilityDepartmentRowTest {

    /** BAKERY PRODUCTS, straight off the client's report. */
    @Test
    void reproducesTheBakeryRowFromTheClientsOwnReport() {
        Dept d = dept("7563500.00", "0.00", "7542525.42", "20974.58",
                      "116525.42", "7426000.00", "0.00", "5874212.84");

        assertThat(d.netSales()).isEqualByComparingTo("7563500.00");
        assertThat(d.netContribution()).isEqualByComparingTo("1668312.58");
        assertThat(d.marginPercent()).isEqualByComparingTo("22.12");
        assertThat(d.markupPercent()).isEqualByComparingTo("28.40");
    }

    /** BEER — a department where most of the sale is standard-rated, unlike bakery. */
    @Test
    void reproducesTheBeerRowFromTheClientsOwnReport() {
        Dept d = dept("9770500.00", "0.00", "8559176.27", "1211323.73",
                      "6729576.27", "1829600.00", "0.00", "7183534.76");

        assertThat(d.netContribution()).isEqualByComparingTo("1375641.51");
        assertThat(d.marginPercent()).isEqualByComparingTo("16.07");
        assertThat(d.markupPercent()).isEqualByComparingTo("19.15");
    }

    /** WINES. */
    @Test
    void reproducesTheWinesRowFromTheClientsOwnReport() {
        Dept d = dept("46829000.00", "0.00", "39693220.34", "7135779.66",
                      "39643220.34", "50000.00", "0.00", "33352846.90");

        assertThat(d.netContribution()).isEqualByComparingTo("6340373.44");
        assertThat(d.marginPercent()).isEqualByComparingTo("15.97");
        assertThat(d.markupPercent()).isEqualByComparingTo("19.01");
    }

    /** The three tax splits must add back to the VAT-exclusive sale, or the foot will not tie. */
    @Test
    void theTaxSplitsAddBackToTheNetSale() {
        Dept d = dept("9770500.00", "0.00", "8559176.27", "1211323.73",
                      "6729576.27", "1829600.00", "0.00", "7183534.76");

        assertThat(d.vatPortion().add(d.exemptPortion()).add(d.zeroRatedPortion()))
                .isEqualByComparingTo(d.netAmount());
        assertThat(d.netAmount().add(d.vatAmount())).isEqualByComparingTo(d.netSales());
    }

    /**
     * A department holding one uncosted product reports NO cost and NO contribution — not a
     * contribution computed from the part it could see, which would overstate profit by exactly the
     * amount nobody can measure.
     */
    @Test
    void anUncostedProductWithholdsTheWholeDepartmentsContribution() {
        ProfitabilityReportQuery.DeptAcc acc = new ProfitabilityReportQuery.DeptAcc("BEER");
        acc.add(row("1000.00", "0.00", "1000.00", "0.00", "1000.00", "0.00", "0.00"), bd("600.00"));
        acc.add(row("500.00", "0.00", "500.00", "0.00", "500.00", "0.00", "0.00"), null);
        ProfitabilityDepartmentRowDto d = acc.toRow();

        assertThat(d.costOfSales()).isNull();
        assertThat(d.netContribution()).isNull();
        assertThat(d.marginPercent()).isNull();
        assertThat(d.markupPercent()).isNull();
        assertThat(d.productsWithUnknownCost()).isEqualTo(1);
        // The sales themselves are still reported — only the cost-derived figures are withheld.
        assertThat(d.netAmount()).isEqualByComparingTo("1500.00");
    }

    /** Markup on a zero cost is unanswerable, not infinite. */
    @Test
    void markupIsUnknownWhenNothingWasCosted() {
        ProfitabilityReportQuery.DeptAcc acc = new ProfitabilityReportQuery.DeptAcc("GIFTS");
        acc.add(row("1000.00", "0.00", "1000.00", "0.00", "1000.00", "0.00", "0.00"), bd("0.00"));
        ProfitabilityDepartmentRowDto d = acc.toRow();

        assertThat(d.markupPercent()).isNull();
        assertThat(d.marginPercent()).isEqualByComparingTo("100.00");
    }

    // -------------------------------------------------------------------------

    private record Dept(BigDecimal netSales, BigDecimal netAmount, BigDecimal vatAmount,
                        BigDecimal vatPortion, BigDecimal exemptPortion, BigDecimal zeroRatedPortion,
                        BigDecimal netContribution, BigDecimal marginPercent,
                        BigDecimal markupPercent) {}

    private static Dept dept(String gross, String discount, String net, String vat,
                             String vatPortion, String exempt, String zero, String cost) {
        ProfitabilityReportQuery.DeptAcc acc = new ProfitabilityReportQuery.DeptAcc("D");
        acc.add(row(gross, discount, net, vat, vatPortion, exempt, zero), bd(cost));
        ProfitabilityDepartmentRowDto r = acc.toRow();
        return new Dept(r.netSales(), r.netAmount(), r.vatAmount(), r.vatPortion(),
                r.exemptPortion(), r.zeroRatedPortion(), r.netContribution(),
                r.marginPercent(), r.markupPercent());
    }

    /** Mirrors the column order the SQL row mapper produces. */
    private static Object[] row(String gross, String discount, String net, String vat,
                                String vatPortion, String exempt, String zero) {
        return new Object[]{
                1L, "P001", "Product", null,
                bd(gross), bd(vat), bd(net),
                "D", bd(discount), bd(vatPortion), bd(exempt), bd(zero)};
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}

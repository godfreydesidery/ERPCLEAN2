package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.bi.domain.dto.SalesByBranchDto;
import com.erp.modules.bi.service.DashboardService;
import com.erp.modules.sales.domain.dto.ProfitabilityDepartmentRowDto;
import com.erp.modules.sales.domain.dto.ProfitabilityReportDto;
import com.erp.modules.sales.domain.dto.ProfitabilityRowDto;
import com.erp.modules.sales.domain.dto.SalesReportDto;
import com.erp.modules.sales.domain.dto.SalesSummaryReportDto;
import com.erp.modules.sales.domain.dto.SalesSummaryRowDto;
import com.erp.modules.sales.domain.enums.SalesSummaryGroupBy;
import com.erp.support.ReportQueryTestBase;
import com.erp.support.ReportSeed.Invoice;
import java.math.BigDecimal;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Live defect: the sales reports added foreign-currency invoices at 1:1 into base-currency (TZS)
 * totals — a USD 14 sale showed as TZS 14, and a USD sale's margin subtracted a TZS cost of sales
 * from USD revenue.
 *
 * <p>One window holds a TZS invoice and two USD invoices (one at a FRACTIONAL rate, so per-line
 * rounding is exercised). Every report must total in base at the rate stamped on each invoice,
 * equal to an independent SQL base-currency sum, with margin = base net − base cost, and every
 * Sales Summary grouping must still add up to the Sales Report / Profitability totals.
 *
 * <p>Expected base figures (TZS, 0 dp, HALF_UP per line):
 * <pre>
 *   inv1 TZS  rate 1        P1 net 20,000 vat 3,600                         cost 12,000
 *   inv2 USD  rate 2512.37  P1 net 12.00 → 30,148  vat 2.16 → 5,427  disc 0.50 → 1,256  cost 15,000
 *                           P2 net  7.35 → 18,466  vat 1.32 → 3,316                    cost  9,000
 *   inv3 USD  rate 2500     P2 net 20.00 → 50,000  vat 3.60 → 9,000  (other branch)    cost 18,000
 *   net 118,614 · vat 21,343 · gross 139,957 · discount 1,256 · cost 54,000 · margin 64,614
 * </pre>
 */
class SalesReportsBaseCurrencyIT extends ReportQueryTestBase {

    private static final BigDecimal RATE_FRACTIONAL = new BigDecimal("2512.37000000");
    private static final BigDecimal RATE_ROUND      = new BigDecimal("2500.00000000");

    @Autowired private SalesReportQuery         salesReport;
    @Autowired private SalesSummaryReportQuery  salesSummary;
    @Autowired private ProfitabilityReportQuery profitability;
    @Autowired private DashboardService         dashboard;

    private Invoice inv2;

    @BeforeEach
    void seedMixedCurrencySales() {
        long companyId = company.getId();
        long unit = seed.unit(companyId, "PCS");
        long loc = seed.location(companyId, ownBranch.getId(), "MAIN");
        long locOther = seed.location(companyId, otherBranch.getId(), "MAIN2");
        long p1 = seed.product(companyId, unit, "P1", "Widget", null);
        long p2 = seed.product(companyId, unit, "P2", "Gadget", null);
        long tzsCustomer = seed.customer(companyId, "C1", "Shilling Shop");
        long usdCustomer = seed.customer(companyId, "C2", "Dollar Importer");
        long agent = seed.agent(companyId, "A1", "Agent One");

        Invoice inv1 = seed.invoice(companyId, ownBranch.getId(), tzsCustomer, agent, null,
                clerk.getId(), "FINALISED", at(2, 10));
        seed.line(inv1, 1, p1, unit, bd("2"), bd("2"), bd("20000"), bd("3600"), bd("0"));
        seed.movement(companyId, ownBranch.getId(), loc, p1, "SALE_ISSUE", bd("-2"), at(2, 10),
                bd("6000"), bd("-12000"), inv1.uid());
        stampBaseGross(inv1, bd("23600"));

        inv2 = seed.invoice(companyId, ownBranch.getId(), usdCustomer, agent, null,
                clerk.getId(), "FINALISED", at(1, 10), "USD", RATE_FRACTIONAL);
        seed.line(inv2, 1, p1, unit, bd("1"), bd("1"), bd("12.00"), bd("2.16"), bd("0.50"));
        seed.line(inv2, 2, p2, unit, bd("1"), bd("1"), bd("7.35"), bd("1.32"), bd("0"));
        seed.movement(companyId, ownBranch.getId(), loc, p1, "SALE_ISSUE", bd("-1"), at(1, 10),
                bd("15000"), bd("-15000"), inv2.uid());
        seed.movement(companyId, ownBranch.getId(), loc, p2, "SALE_ISSUE", bd("-1"), at(1, 10),
                bd("9000"), bd("-9000"), inv2.uid());
        // round(22.83 × 2512.37) = 57,357 — what finalise stamps.
        stampBaseGross(inv2, bd("57357"));

        Invoice inv3 = seed.invoice(companyId, otherBranch.getId(), usdCustomer, agent, null,
                clerk.getId(), "FINALISED", at(1, 15), "USD", RATE_ROUND);
        seed.line(inv3, 1, p2, unit, bd("2"), bd("2"), bd("20.00"), bd("3.60"), bd("0"));
        seed.movement(companyId, otherBranch.getId(), locOther, p2, "SALE_ISSUE", bd("-2"),
                at(1, 15), bd("9000"), bd("-18000"), inv3.uid());
        stampBaseGross(inv3, bd("59000"));

        // The seed snapshots every line's product code as 'P'; give each line its product's real
        // code so the per-product rows can be told apart.
        jdbc.update("UPDATE sales_invoice_lines l SET product_code = p.code FROM products p "
                + "WHERE p.id = l.product_id AND l.company_id = ?", companyId);
    }

    @Test
    void salesSummary_byCustomer_totalsInBase_andUsdMarginIsBaseNetLessBaseCost() {
        SalesSummaryReportDto r = summary(SalesSummaryGroupBy.CUSTOMER);

        assertThat(r.currency()).isEqualTo("TZS");
        SalesSummaryRowDto usd = rowLabelled(r, "Dollar Importer");
        // Was 39.35 (USD face) under a TZS header, margined as 39.35 − 42,000.
        assertThat(usd.netAmount()).isEqualByComparingTo("98614");
        assertThat(usd.vatAmount()).isEqualByComparingTo("17743");
        assertThat(usd.grossAmount()).isEqualByComparingTo("116357");
        assertThat(usd.discount()).isEqualByComparingTo("1256");
        assertThat(usd.costOfSales()).isEqualByComparingTo("42000");
        assertThat(usd.margin()).isEqualByComparingTo("56614");
        assertThat(usd.foreignCurrencyInvoices()).isEqualTo(2);

        SalesSummaryRowDto tzs = rowLabelled(r, "Shilling Shop");
        assertThat(tzs.netAmount()).as("base-currency invoice passes through untouched")
                .isEqualByComparingTo("20000");
        assertThat(tzs.margin()).isEqualByComparingTo("8000");
        assertThat(tzs.foreignCurrencyInvoices()).isZero();

        assertThat(r.totals().netAmount()).isEqualByComparingTo("118614")
                .isEqualByComparingTo(sqlBaseSum("l.net_amount"));
        assertThat(r.totals().vatAmount()).isEqualByComparingTo("21343")
                .isEqualByComparingTo(sqlBaseSum("l.vat_amount"));
        assertThat(r.totals().grossAmount()).isEqualByComparingTo("139957")
                .isEqualByComparingTo(sqlBaseSum("l.net_amount").add(sqlBaseSum("l.vat_amount")));
        assertThat(r.totals().costOfSales()).isEqualByComparingTo("54000");
        assertThat(r.totals().margin()).isEqualByComparingTo("64614");
    }

    @Test
    void everySummaryGrouping_equalsTheSalesReportAndProfitabilityTotals() {
        SalesReportDto register = salesReport.report(company.getId(), today().minusDays(10),
                today(), null, null, null, null);
        ProfitabilityReportDto profit = profitability.report(company.getId(),
                today().minusDays(10), today(), null);

        assertThat(register.currency()).isEqualTo("TZS");
        assertThat(register.totals().amount()).isEqualByComparingTo("139957");
        assertThat(register.totals().vat()).isEqualByComparingTo("21343");
        assertThat(register.totals().discount()).isEqualByComparingTo("1256");
        assertThat(register.totals().margin()).isEqualByComparingTo("64614");
        assertThat(profit.totals().netAmount()).isEqualByComparingTo("118614");
        assertThat(profit.totals().grossSales()).isEqualByComparingTo("139957");
        assertThat(profit.totals().costOfSales()).isEqualByComparingTo("54000");
        assertThat(profit.totals().profit()).isEqualByComparingTo("64614");

        for (SalesSummaryGroupBy by : SalesSummaryGroupBy.values()) {
            SalesSummaryReportDto s = summary(by);
            assertThat(sum(s, SalesSummaryRowDto::grossAmount)).as(by + " gross")
                    .isEqualByComparingTo(register.totals().amount())
                    .isEqualByComparingTo(s.totals().grossAmount());
            assertThat(s.totals().vatAmount()).as(by + " vat")
                    .isEqualByComparingTo(register.totals().vat());
            assertThat(s.totals().discount()).as(by + " discount")
                    .isEqualByComparingTo(register.totals().discount());
            assertThat(s.totals().netAmount()).as(by + " net")
                    .isEqualByComparingTo(profit.totals().netAmount());
            assertThat(s.totals().margin()).as(by + " margin")
                    .isEqualByComparingTo(register.totals().margin())
                    .isEqualByComparingTo(profit.totals().profit());
        }
    }

    @Test
    void profitability_perProductAndDepartment_areBase_andPortionsAddBackToNet() {
        ProfitabilityReportDto r = profitability.report(company.getId(), today().minusDays(10),
                today(), null);

        ProfitabilityRowDto p2 = r.rows().stream().filter(x -> "P2".equals(x.productCode()))
                .findFirst().orElseThrow();
        // P2 was sold only in USD: 18,466 + 50,000 base net against 27,000 base cost.
        assertThat(p2.netAmount()).isEqualByComparingTo("68466");
        assertThat(p2.vatAmount()).isEqualByComparingTo("12316");
        assertThat(p2.grossSales()).isEqualByComparingTo("80782");
        assertThat(p2.costOfSales()).isEqualByComparingTo("27000");
        assertThat(p2.profit()).isEqualByComparingTo("41466");

        ProfitabilityDepartmentRowDto dept = r.departments().get(0);
        assertThat(dept.netAmount()).isEqualByComparingTo("118614");
        assertThat(dept.vatPortion().add(dept.exemptPortion()).add(dept.zeroRatedPortion()))
                .isEqualByComparingTo(dept.netAmount());
        assertThat(dept.grossSales().subtract(dept.vatAmount()))
                .as("gross − VAT = net holds in base, as on the documents")
                .isEqualByComparingTo(dept.netAmount());
    }

    @Test
    void salesReport_branchFilter_convertsTheUsdInvoiceOnThatBranch() {
        SalesReportDto own = salesReport.report(company.getId(), today().minusDays(10), today(),
                null, null, null, ownBranch.getUid());
        // inv1 23,600 + inv2 (35,575 + 21,782) = 80,957
        assertThat(own.totals().amount()).isEqualByComparingTo("80957");
        SalesReportDto other = salesReport.report(company.getId(), today().minusDays(10), today(),
                null, null, null, otherBranch.getUid());
        assertThat(other.totals().amount()).isEqualByComparingTo("59000");
        assertThat(other.totals().margin()).isEqualByComparingTo("32000");
    }

    @Test
    void biSalesByBranch_totalsTheStampedBaseGross_notTheDocumentFace() {
        SalesByBranchDto panel = dashboard.salesByBranch(company.getId(), null,
                today().minusDays(10), today());

        assertThat(panel.currency()).isEqualTo("TZS");
        // Was 23,600 + 22.83 + 23.60 = 23,646.43.
        assertThat(panel.grandTotal()).isEqualByComparingTo("139957")
                .isEqualByComparingTo(jdbc.queryForObject(
                        "SELECT SUM(base_gross_total_amount) FROM sales_invoices "
                                + "WHERE company_id = ? AND status = 'FINALISED'",
                        BigDecimal.class, company.getId()));
        assertThat(panel.rows()).anySatisfy(row -> {
            assertThat(row.branchId()).isEqualTo(otherBranch.getId());
            assertThat(row.total()).isEqualByComparingTo("59000");
        });
    }

    @Test
    void aBaseCurrencyOnlyCompany_isUnchanged() {
        // Drop the USD sales: what is left must read exactly as before the fix.
        jdbc.update("UPDATE sales_invoices SET status = 'VOID' WHERE company_id = ? "
                + "AND currency = 'USD'", company.getId());
        SalesSummaryReportDto r = summary(SalesSummaryGroupBy.BRANCH);
        assertThat(r.totals().netAmount()).isEqualByComparingTo("20000");
        assertThat(r.totals().grossAmount()).isEqualByComparingTo("23600");
        assertThat(r.totals().margin()).isEqualByComparingTo("8000");
        assertThat(r.rows()).allSatisfy(x -> assertThat(x.foreignCurrencyInvoices()).isZero());
    }

    // -------------------------------------------------------------------------

    private SalesSummaryReportDto summary(SalesSummaryGroupBy by) {
        return salesSummary.report(company.getId(), today().minusDays(10), today(), by, null);
    }

    /**
     * The independent base-currency sum: every FINALISED line in the window at its invoice's
     * stamped rate, rounded per line to TZS's 0 places.
     */
    private BigDecimal sqlBaseSum(String column) {
        return jdbc.queryForObject(
                "SELECT SUM(ROUND(" + column + " * i.fx_rate, 0)) FROM sales_invoice_lines l "
                        + "JOIN sales_invoices i ON i.id = l.invoice_id "
                        + "WHERE i.company_id = ? AND i.status = 'FINALISED'",
                BigDecimal.class, company.getId());
    }

    private void stampBaseGross(Invoice inv, BigDecimal baseGross) {
        jdbc.update("UPDATE sales_invoices SET base_gross_total_amount = ? WHERE id = ?",
                baseGross, inv.id());
    }

    private static SalesSummaryRowDto rowLabelled(SalesSummaryReportDto r, String label) {
        return r.rows().stream().filter(x -> label.equals(x.groupLabel())).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + label + " in " + r.rows()));
    }

    private static BigDecimal sum(SalesSummaryReportDto r,
                                  Function<SalesSummaryRowDto, BigDecimal> f) {
        return r.rows().stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}

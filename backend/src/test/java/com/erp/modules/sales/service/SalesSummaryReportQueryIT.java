package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.sales.domain.dto.SalesSummaryReportDto;
import com.erp.modules.sales.domain.dto.SalesSummaryRowDto;
import com.erp.modules.sales.domain.enums.SalesSummaryGroupBy;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.support.ReportQueryTestBase;
import com.erp.support.ReportSeed.Invoice;
import java.math.BigDecimal;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link SalesSummaryReportQuery} against real Postgres: the six groupings bind and execute, group
 * totals always equal the grand total, cost of sales is not doubled by two lines of one product,
 * an uncosted sale is unknown rather than all-profit, and only finalised in-window invoices count.
 */
class SalesSummaryReportQueryIT extends ReportQueryTestBase {

    @Autowired private SalesSummaryReportQuery query;

    @BeforeEach
    void seedSales() {
        long companyId = company.getId();
        long unit = seed.unit(companyId, "PCS");
        long loc = seed.location(companyId, ownBranch.getId(), "MAIN");
        long locOther = seed.location(companyId, otherBranch.getId(), "MAIN2");
        long p1 = seed.product(companyId, unit, "P1", "Costed widget", null);
        long p2 = seed.product(companyId, unit, "P2", "Never costed", null);
        long c1 = seed.customer(companyId, "C1", "Alpha Shop");
        long c2 = seed.customer(companyId, "C2", "Beta Kiosk");
        long a1 = seed.agent(companyId, "A1", "Agent One");
        long a2 = seed.agent(companyId, "A2", "Agent Two");
        long r1 = seed.route(companyId, "R1", "North Route");

        // inv1 — own branch, C1/A1/R1, rung by the clerk 2 days ago. TWO lines of P1 (a price
        // split) but ONE SALE_ISSUE for the pair, as the posting idempotency key guarantees.
        Invoice inv1 = seed.invoice(companyId, ownBranch.getId(), c1, a1, r1, clerk.getId(),
                "FINALISED", at(2, 10));
        seed.line(inv1, 1, p1, unit, bd("2"), bd("2"), bd("2000"), bd("360"), bd("100"));
        seed.line(inv1, 2, p1, unit, bd("1"), bd("1"), bd("1000"), bd("180"), bd("0"));
        seed.movement(companyId, ownBranch.getId(), loc, p1, "SALE_ISSUE", bd("-3"), at(2, 10),
                bd("500"), bd("-1500"), inv1.uid());

        // inv2 — own branch, C2/A1/no route, created by root yesterday; P2 was never costed.
        Invoice inv2 = seed.invoice(companyId, ownBranch.getId(), c2, a1, null, root.getId(),
                "FINALISED", at(1, 9));
        seed.line(inv2, 1, p2, unit, bd("1"), bd("1"), bd("500"), bd("90"), bd("0"));
        seed.movement(companyId, ownBranch.getId(), loc, p2, "SALE_ISSUE", bd("-1"), at(1, 9),
                null, null, inv2.uid());

        // inv3 — OTHER branch, C1/A2/R1, by the clerk yesterday: 4 packs of 12.
        Invoice inv3 = seed.invoice(companyId, otherBranch.getId(), c1, a2, r1, clerk.getId(),
                "FINALISED", at(1, 15));
        seed.line(inv3, 1, p1, unit, bd("4"), bd("48"), bd("4000"), bd("720"), bd("0"));
        seed.movement(companyId, otherBranch.getId(), locOther, p1, "SALE_ISSUE", bd("-48"),
                at(1, 15), bd("50"), bd("-2400"), inv3.uid());

        // Never counted: a voided sale, a draft, and a sale outside the window.
        Invoice voided = seed.invoice(companyId, ownBranch.getId(), c1, a1, null, clerk.getId(),
                "VOID", at(1, 11));
        seed.line(voided, 1, p1, unit, bd("9"), bd("9"), bd("9999"), bd("0"), bd("0"));
        Invoice draft = seed.invoice(companyId, ownBranch.getId(), c1, a1, null, clerk.getId(),
                "DRAFT", null);
        seed.line(draft, 1, p1, unit, bd("9"), bd("9"), bd("8888"), bd("0"), bd("0"));
        Invoice old = seed.invoice(companyId, ownBranch.getId(), c1, a1, null, clerk.getId(),
                "FINALISED", at(30, 10));
        seed.line(old, 1, p1, unit, bd("9"), bd("9"), bd("7777"), bd("0"), bd("0"));
    }

    private SalesSummaryReportDto run(SalesSummaryGroupBy by, String branchUid) {
        return query.report(company.getId(), today().minusDays(10), today(), by, branchUid);
    }

    @Test
    void byCustomer_costIsNotDoubledByTwoLinesOfOneProduct_andUnknownCostIsNotProfit() {
        SalesSummaryReportDto r = run(SalesSummaryGroupBy.CUSTOMER, null);

        SalesSummaryRowDto alpha = rowLabelled(r, "Alpha Shop");
        assertThat(alpha.invoiceCount()).isEqualTo(2);
        assertThat(alpha.netAmount()).isEqualByComparingTo("7000");
        // 1,500 (inv1, one movement for its two P1 lines) + 2,400 (inv3). A join onto lines would
        // have counted inv1's 1,500 twice.
        assertThat(alpha.costOfSales()).isEqualByComparingTo("3900");
        assertThat(alpha.margin()).isEqualByComparingTo("3100");
        assertThat(alpha.marginPercent()).isEqualByComparingTo("44.29");
        assertThat(alpha.qty()).as("base units: 2 + 1 + 48").isEqualByComparingTo("51");

        SalesSummaryRowDto beta = rowLabelled(r, "Beta Kiosk");
        assertThat(beta.netAmount()).isEqualByComparingTo("500");
        assertThat(beta.costOfSales()).as("unknown, not zero").isNull();
        assertThat(beta.margin()).isNull();
        assertThat(beta.marginPercent()).isNull();
        assertThat(beta.unknownCostItems()).isEqualTo(1);

        assertThat(r.totals().invoiceCount())
                .as("void, draft and out-of-window invoices excluded").isEqualTo(3);
        assertThat(r.totals().netAmount()).isEqualByComparingTo("7500");
        assertThat(r.totals().grossAmount()).isEqualByComparingTo("8850");
        // RPT-16: VAT-inclusive — the 100 taken off a 2,100 net line is 118 off the charged gross.
        assertThat(r.totals().discount()).isEqualByComparingTo("118");
        assertThat(r.totals().costOfSales()).isEqualByComparingTo("3900");
        assertThat(r.totals().margin()).isEqualByComparingTo("3100");
        assertThat(r.totals().marginPercent())
                .as("measured on the costed groups' net only").isEqualByComparingTo("44.29");
        assertThat(r.totals().groupsWithUnknownCost()).isEqualTo(1);
        assertThat(r.totals().unknownCostItems()).isEqualTo(1);
    }

    @Test
    void everyGrouping_bindsAndItsGroupsAddUpToTheGrandTotal() {
        for (SalesSummaryGroupBy by : SalesSummaryGroupBy.values()) {
            SalesSummaryReportDto r = run(by, null);
            assertThat(r.groupBy()).isEqualTo(by);
            assertThat(sum(r, SalesSummaryRowDto::netAmount)).as(by + " net")
                    .isEqualByComparingTo(r.totals().netAmount())
                    .isEqualByComparingTo("7500");
            assertThat(sum(r, SalesSummaryRowDto::grossAmount)).as(by + " gross")
                    .isEqualByComparingTo("8850");
            assertThat(sum(r, SalesSummaryRowDto::vatAmount)).as(by + " vat")
                    .isEqualByComparingTo(r.totals().vatAmount());
            assertThat(r.rows().stream().mapToLong(SalesSummaryRowDto::invoiceCount).sum())
                    .as(by + " invoices").isEqualTo(3);
            assertThat(r.totals().costOfSales()).as(by + " cost").isEqualByComparingTo(
                    r.rows().stream().filter(x -> x.costOfSales() != null)
                            .map(SalesSummaryRowDto::costOfSales)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
    }

    @Test
    void byRoute_invoicesWithNoRouteFormTheirOwnGroup() {
        SalesSummaryReportDto r = run(SalesSummaryGroupBy.ROUTE, null);
        assertThat(rowLabelled(r, "North Route").invoiceCount()).isEqualTo(2);
        SalesSummaryRowDto none = rowLabelled(r, "(no route)");
        assertThat(none.groupKey()).isNull();
        assertThat(none.invoiceCount()).isEqualTo(1);
        assertThat(none.costOfSales()).as("inv2's uncosted issue lands in the no-route group").isNull();
    }

    @Test
    void byDay_isChronologicalInTheCompanyTimeZone() {
        SalesSummaryReportDto r = run(SalesSummaryGroupBy.DAY, null);
        assertThat(r.rows()).extracting(SalesSummaryRowDto::groupKey).containsExactly(
                today().minusDays(2).toString(), today().minusDays(1).toString());
        assertThat(r.rows().get(0).netAmount()).isEqualByComparingTo("3000");
        assertThat(r.rows().get(0).costOfSales()).isEqualByComparingTo("1500");
    }

    @Test
    void byCashier_isTheInvoiceCreator() {
        SalesSummaryReportDto r = run(SalesSummaryGroupBy.CASHIER, null);
        SalesSummaryRowDto c = rowLabelled(r, "Report Clerk");
        assertThat(c.groupKey()).isEqualTo(clerk.getUid());
        assertThat(c.invoiceCount()).isEqualTo(2);
        assertThat(rowLabelled(r, "Report Root").invoiceCount()).isEqualTo(1);
    }

    @Test
    void byAgent_andBranchFilter_narrowToThatBranch() {
        SalesSummaryReportDto r = run(SalesSummaryGroupBy.AGENT, ownBranch.getUid());
        assertThat(r.branchName()).isEqualTo("Own Branch");
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).groupLabel()).isEqualTo("Agent One");
        assertThat(r.totals().invoiceCount()).isEqualTo(2);
        assertThat(r.totals().netAmount()).isEqualByComparingTo("3500");
    }

    @Test
    void nonRootClerk_isRefusedABranchTheyAreNotAssignedTo() {
        asClerk();
        assertThatCode(() -> run(SalesSummaryGroupBy.BRANCH, ownBranch.getUid()))
                .doesNotThrowAnyException();
        assertThatCode(() -> run(SalesSummaryGroupBy.BRANCH, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> run(SalesSummaryGroupBy.BRANCH, otherBranch.getUid()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }

    @Test
    void unknownBranch_isNotFound_andABackwardsRangeIsRefused() {
        assertThatThrownBy(() -> run(SalesSummaryGroupBy.CUSTOMER, "NOSUCHBRANCHUID0000000000"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> query.report(company.getId(), today(), today().minusDays(1),
                SalesSummaryGroupBy.CUSTOMER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------

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

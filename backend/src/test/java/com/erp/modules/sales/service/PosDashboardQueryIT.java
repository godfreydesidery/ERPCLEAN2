package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.sales.domain.dto.PosDashboardDto;
import com.erp.modules.sales.domain.dto.PosDashboardDto.Day;
import com.erp.modules.sales.domain.dto.PosDashboardDto.Department;
import com.erp.modules.sales.domain.dto.PosDashboardDto.Item;
import com.erp.modules.sales.domain.dto.PosDashboardDto.Tender;
import com.erp.platform.security.RequestContext;
import com.erp.support.ReportQueryTestBase;
import com.erp.support.ReportSeed.Invoice;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link PosDashboardQuery} against real Postgres (Tulia 2026-11-02): the active branch only,
 * finalised sales only, per day, per payment type net of change, and per department with its
 * items — departments merged however their name was typed, and no cost anywhere.
 */
class PosDashboardQueryIT extends ReportQueryTestBase {

    @Autowired private PosDashboardQuery query;

    @BeforeEach
    void seedSales() {
        long companyId = company.getId();
        long cust = seed.customer(companyId, "C1", "Walk-in");
        long agent = seed.agent(companyId, "A1", "Counter");
        long pcs = seed.unit(companyId, "PCS");
        long coke = seed.product(companyId, pcs, "COKE", "Coke", null);
        long juice = seed.product(companyId, pcs, "JUICE", "Juice", null);
        long bread = seed.product(companyId, pcs, "BREAD", "Bread", null);
        jdbc.update("UPDATE products SET category = 'Drinks' WHERE id = ?", coke);
        jdbc.update("UPDATE products SET category = ' drinks ' WHERE id = ?", juice);

        // Two days ago: 2 Coke = 1,180, paid 1,500 cash with 320 change.
        Invoice inv1 = seed.invoice(companyId, ownBranch.getId(), cust, agent, null,
                clerk.getId(), "FINALISED", at(2, 10));
        seed.line(inv1, 1, coke, pcs, bd("2"), bd("2"), bd("1000"), bd("180"), bd("0"));
        seed.payment(inv1, "CASH", bd("1500"), bd("320"), "TZS", at(2, 10), clerk.getId());

        // Yesterday: a Juice (590) and a Bread (1,000, no department); mobile money and comp.
        Invoice inv2 = seed.invoice(companyId, ownBranch.getId(), cust, agent, null,
                clerk.getId(), "FINALISED", at(1, 12));
        seed.line(inv2, 1, juice, pcs, bd("1"), bd("1"), bd("500"), bd("90"), bd("0"));
        seed.line(inv2, 2, bread, pcs, bd("1"), bd("1"), bd("1000"), bd("0"), bd("0"));
        seed.payment(inv2, "MOBILE_MONEY", bd("1000"), null, "TZS", at(1, 12), clerk.getId());
        seed.payment(inv2, "COMPLIMENTARY", bd("590"), null, "TZS", at(1, 12), clerk.getId());

        // Never counted here: the other branch, a voided sale, and a sale outside the range.
        Invoice other = seed.invoice(companyId, otherBranch.getId(), cust, agent, null,
                clerk.getId(), "FINALISED", at(1, 9));
        seed.line(other, 1, coke, pcs, bd("1"), bd("1"), bd("500"), bd("90"), bd("0"));
        seed.payment(other, "CASH", bd("590"), null, "TZS", at(1, 9), clerk.getId());
        Invoice voided = seed.invoice(companyId, ownBranch.getId(), cust, agent, null,
                clerk.getId(), "VOID", at(1, 11));
        seed.line(voided, 1, coke, pcs, bd("1"), bd("1"), bd("500"), bd("90"), bd("0"));
        Invoice old = seed.invoice(companyId, ownBranch.getId(), cust, agent, null,
                clerk.getId(), "FINALISED", at(30, 10));
        seed.line(old, 1, coke, pcs, bd("1"), bd("1"), bd("500"), bd("90"), bd("0"));
    }

    private PosDashboardDto run() {
        return query.report(today().minusDays(7), today());
    }

    @Test
    void daysAndTotals_coverTheActiveBranchsFinalisedSalesOnly() {
        asClerk();
        PosDashboardDto r = run();

        assertThat(r.branchName()).isEqualTo("Own Branch");
        assertThat(r.currency()).isEqualTo("TZS");
        assertThat(r.invoiceCount()).isEqualTo(2);
        assertThat(r.grossTotal()).isEqualByComparingTo("2770");
        assertThat(r.days()).extracting(Day::date)
                .containsExactly(today().minusDays(2).toString(), today().minusDays(1).toString());
        assertThat(r.days()).extracting(Day::gross)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("1180"), bd("1590"));
    }

    @Test
    void tenders_areNetOfChange_complimentaryIncluded() {
        PosDashboardDto r = run();

        assertThat(r.tenders()).extracting(Tender::tenderType)
                .containsExactly("CASH", "MOBILE_MONEY", "COMPLIMENTARY");
        assertThat(r.tenders()).extracting(Tender::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("1180"), bd("1000"), bd("590"));
    }

    @Test
    void departments_mergeHoweverTyped_withTheirItems_biggestFirst() {
        PosDashboardDto r = run();

        assertThat(r.departments()).extracting(Department::department)
                .containsExactly("Drinks", "(no department)");
        Department drinks = r.departments().get(0);
        assertThat(drinks.gross()).isEqualByComparingTo("1770");
        assertThat(drinks.quantity()).isEqualByComparingTo("3");
        assertThat(drinks.items()).extracting(Item::name).containsExactly("Coke", "Juice");
        assertThat(drinks.items().get(0).unit()).isEqualTo("PCS");
        assertThat(r.departments().get(1).gross()).isEqualByComparingTo("1000");
    }

    @Test
    void anotherBranch_seesOnlyItsOwnSales() {
        RequestContext.set(new RequestContext.Principal(root.getId(), "report_root", true,
                company.getId(), otherBranch.getId(), null, org.getId()));
        PosDashboardDto r = run();

        assertThat(r.branchName()).isEqualTo("Other Branch");
        assertThat(r.grossTotal()).isEqualByComparingTo("590");
    }

    @Test
    void badRanges_areRefusedPlainly() {
        assertThatThrownBy(() -> query.report(today(), today().minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The end date cannot be before the start date.");
        assertThatThrownBy(() -> query.report(today().minusDays(400), today()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Choose a range of one year or less.");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}

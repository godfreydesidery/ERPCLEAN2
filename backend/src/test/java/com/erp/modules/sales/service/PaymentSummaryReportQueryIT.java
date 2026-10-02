package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.sales.domain.dto.PaymentSummaryReportDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryReportDto.CashierRefDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryRowDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryTotalsDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.support.ReportQueryTestBase;
import com.erp.support.ReportSeed.Invoice;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link PaymentSummaryReportQuery} against real Postgres: tenders net of change, grouped by day,
 * cashier and currency, only on finalised invoices; currencies never summed together; the totals
 * foot the lines; branch and cashier filters scoped to the company.
 */
class PaymentSummaryReportQueryIT extends ReportQueryTestBase {

    @Autowired private PaymentSummaryReportQuery query;

    @BeforeEach
    void seedPayments() {
        long companyId = company.getId();
        long cust = seed.customer(companyId, "C1", "Walk-in");
        long agent = seed.agent(companyId, "A1", "Counter");

        // Two days ago, own branch, clerk: 3,500 cash tendered with 500 change, plus mobile money.
        Invoice inv1 = seed.invoice(companyId, ownBranch.getId(), cust, agent, null,
                clerk.getId(), "FINALISED", at(2, 10));
        seed.payment(inv1, "CASH", bd("3500"), bd("500"), "TZS", at(2, 10), clerk.getId());
        seed.payment(inv1, "MOBILE_MONEY", bd("540"), null, "TZS", at(2, 10), clerk.getId());

        // Yesterday, own branch, root: card.
        Invoice inv2 = seed.invoice(companyId, ownBranch.getId(), cust, agent, null,
                root.getId(), "FINALISED", at(1, 9));
        seed.payment(inv2, "CARD", bd("590"), null, "TZS", at(1, 9), root.getId());

        // Yesterday, OTHER branch, clerk: cash in TZS and a little in USD.
        Invoice inv3 = seed.invoice(companyId, otherBranch.getId(), cust, agent, null,
                clerk.getId(), "FINALISED", at(1, 15));
        seed.payment(inv3, "CASH", bd("4720"), bd("0"), "TZS", at(1, 15), clerk.getId());
        seed.payment(inv3, "CASH", bd("10"), null, "USD", at(1, 15), clerk.getId());

        // Never counted: a voided sale's tender and one outside the window.
        Invoice voided = seed.invoice(companyId, ownBranch.getId(), cust, agent, null,
                clerk.getId(), "VOID", at(1, 11));
        seed.payment(voided, "CASH", bd("100"), null, "TZS", at(1, 11), clerk.getId());
        Invoice old = seed.invoice(companyId, ownBranch.getId(), cust, agent, null,
                clerk.getId(), "FINALISED", at(30, 10));
        seed.payment(old, "CASH", bd("777"), null, "TZS", at(30, 10), clerk.getId());
    }

    private PaymentSummaryReportDto run(String branchUid, String cashierUid) {
        return query.report(company.getId(), today().minusDays(10), today(), branchUid, cashierUid);
    }

    @Test
    void linesAreDayByCashierByCurrency_netOfChange_andOnlyFinalisedSales() {
        PaymentSummaryReportDto r = run(null, null);

        assertThat(r.rows()).hasSize(4);
        PaymentSummaryRowDto first = r.rows().get(0);
        assertThat(first.date()).isEqualTo(today().minusDays(2).toString());
        assertThat(first.cashierName()).isEqualTo("Report Clerk");
        assertThat(first.cash()).as("3,500 tendered less 500 change").isEqualByComparingTo("3000");
        assertThat(first.mobileMoney()).isEqualByComparingTo("540");
        assertThat(first.total()).isEqualByComparingTo("3540");
        assertThat(first.payments()).isEqualTo(2);

        PaymentSummaryRowDto usd = r.rows().stream().filter(x -> "USD".equals(x.currency()))
                .findFirst().orElseThrow();
        assertThat(usd.cash()).isEqualByComparingTo("10");

        List<PaymentSummaryTotalsDto> totals = r.totals();
        assertThat(totals).extracting(PaymentSummaryTotalsDto::currency)
                .as("base currency first; USD never added into it").containsExactly("TZS", "USD");
        PaymentSummaryTotalsDto tzs = totals.get(0);
        assertThat(tzs.cash()).as("void and out-of-window tenders excluded")
                .isEqualByComparingTo("7720");
        assertThat(tzs.mobileMoney()).isEqualByComparingTo("540");
        assertThat(tzs.card()).isEqualByComparingTo("590");
        assertThat(tzs.total()).isEqualByComparingTo("8850");

        // The foot is the sum of the lines, currency by currency.
        for (PaymentSummaryTotalsDto t : totals) {
            BigDecimal lines = r.rows().stream().filter(x -> t.currency().equals(x.currency()))
                    .map(PaymentSummaryRowDto::total).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(lines).as(t.currency()).isEqualByComparingTo(t.total());
        }
    }

    @Test
    void cashierFilter_narrowsTheLines_butStillOffersEveryCashier() {
        PaymentSummaryReportDto r = run(null, clerk.getUid());

        assertThat(r.cashierName()).isEqualTo("Report Clerk");
        assertThat(r.rows()).allMatch(x -> clerk.getUid().equals(x.cashierUid()));
        assertThat(r.totals().get(0).card()).as("root's card sale is not the clerk's")
                .isEqualByComparingTo("0");
        assertThat(r.cashiers()).extracting(CashierRefDto::uid)
                .containsExactlyInAnyOrder(clerk.getUid(), root.getUid());
    }

    @Test
    void branchFilter_bindsAndNarrows() {
        PaymentSummaryReportDto r = run(ownBranch.getUid(), null);
        assertThat(r.branchName()).isEqualTo("Own Branch");
        assertThat(r.totals()).hasSize(1);
        assertThat(r.totals().get(0).total()).isEqualByComparingTo("4130");
    }

    @Test
    void nonRootClerk_isRefusedABranchTheyAreNotAssignedTo() {
        asClerk();
        assertThatCode(() -> run(ownBranch.getUid(), null)).doesNotThrowAnyException();
        assertThatCode(() -> run(null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> run(otherBranch.getUid(), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }

    @Test
    void aCashierOutsideTheCompany_isNotFound_notAnEmptyReport() {
        AppUser stranger = new AppUser("report_stranger",
                passwordEncoder.encode("Stranger@1!Xx"), "Stranger");
        stranger.setOrganisationId(org.getId());
        AppUser saved = users.save(stranger);

        assertThatThrownBy(() -> run(null, saved.getUid())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> run(null, "NOSUCHUSERUID000000000000"))
                .isInstanceOf(NotFoundException.class);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}

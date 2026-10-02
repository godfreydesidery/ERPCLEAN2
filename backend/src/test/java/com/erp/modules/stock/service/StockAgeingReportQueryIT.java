package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.stock.domain.dto.StockAgeingReportDto;
import com.erp.modules.stock.domain.dto.StockAgeingRowDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.support.ReportQueryTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link StockAgeingReportQuery} against real Postgres: FIFO allocation from dated movements,
 * transfers netted per scope, stock older than its history flagged, a past as-of date rebuilt, and
 * — on every row — buckets that add back to the on-hand quantity and value.
 */
class StockAgeingReportQueryIT extends ReportQueryTestBase {

    @Autowired private StockAgeingReportQuery query;

    @BeforeEach
    void seedStock() {
        long co = company.getId();
        long own = ownBranch.getId();
        long other = otherBranch.getId();
        long unit = seed.unit(co, "PCS");
        long l1 = seed.location(co, own, "MAIN");
        long transit = seed.location(co, own, "TRANSIT");
        long o1 = seed.location(co, other, "MAIN2");

        // A — a normal history at the own branch. On hand 25, average cost 100.
        long a = seed.product(co, unit, "A", "Aged widget", null);
        seed.movement(co, own, l1, a, "OPENING_BALANCE", bd("10"), at(200, 9), bd("100"), bd("1000"), null);
        seed.movement(co, own, l1, a, "GOODS_RECEIPT", bd("20"), at(100, 9), bd("90"), bd("1800"), null);
        seed.movement(co, own, l1, a, "GOODS_RECEIPT", bd("5"), at(45, 9), bd("110"), bd("550"), null);
        seed.movement(co, own, l1, a, "SALE_ISSUE", bd("-12"), at(10, 9), bd("100"), bd("-1200"), null);
        seed.movement(co, own, l1, a, "ADJUSTMENT", bd("2"), at(5, 9), bd("100"), bd("200"), null);
        seed.onHand(co, own, l1, a, bd("25"), bd("2500"), bd("100"), null, null);

        // B — 30 on hand but only 10 ever recorded arriving; never costed, never sold.
        long b = seed.product(co, unit, "B", "Legacy stock", null);
        seed.movement(co, own, l1, b, "GOODS_RECEIPT", bd("10"), at(20, 9), null, null, null);
        seed.onHand(co, own, l1, b, bd("30"), null, null, null, null);

        // C — received at the OTHER branch 300 days ago, transferred to own 40/35 days ago.
        long c = seed.product(co, unit, "C", "Transferred item", null);
        String t1 = "TRANSFER0000000000000000T1";
        seed.movement(co, other, o1, c, "GOODS_RECEIPT", bd("8"), at(300, 9), bd("10"), bd("80"), null);
        seed.movement(co, other, o1, c, "TRANSFER_OUT", bd("-8"), at(40, 9), bd("10"), bd("-80"), t1);
        seed.movement(co, own, transit, c, "TRANSFER_IN", bd("8"), at(40, 9), bd("10"), bd("80"), t1);
        seed.movement(co, own, transit, c, "TRANSFER_OUT", bd("-8"), at(35, 9), bd("10"), bd("-80"), t1);
        seed.movement(co, own, l1, c, "TRANSFER_IN", bd("8"), at(35, 9), bd("10"), bd("80"), t1);
        seed.onHand(co, own, l1, c, bd("8"), bd("80"), bd("10"), null, null);
        seed.onHand(co, own, transit, c, bd("0"), bd("0"), bd("10"), null, null);
        seed.onHand(co, other, o1, c, bd("0"), bd("0"), bd("10"), null, null);

        // D — negative stock: no age, left out but counted.
        long d = seed.product(co, unit, "D", "Oversold", null);
        seed.movement(co, own, l1, d, "SALE_ISSUE", bd("-3"), at(3, 9), null, null, null);
        seed.onHand(co, own, l1, d, bd("-3"), null, null, null, null);
    }

    @Test
    void wholeCompany_agesByFifo_andEveryRowFootsToItsOnHand() {
        StockAgeingReportDto r = query.report(company.getId(), null, null);

        assertThat(r.asOf()).isEqualTo(today().toString());
        assertThat(r.valuedAtCurrentCost()).isFalse();

        StockAgeingRowDto a = row(r, "A");
        assertThat(a.onHand()).isEqualByComparingTo("25");
        assertThat(a.bucketQty()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("2"), bd("5"), bd("0"), bd("18"), bd("0"));
        assertThat(a.bucketValue()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("200"), bd("500"), bd("0"), bd("1800"), bd("0"));
        assertThat(a.value()).isEqualByComparingTo("2500");
        assertThat(a.uncoveredQty()).isEqualByComparingTo("0");
        assertThat(a.daysSinceLastSale()).isEqualTo(10L);

        StockAgeingRowDto b = row(r, "B");
        assertThat(b.bucketQty()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("10"), bd("0"), bd("0"), bd("0"), bd("20"));
        assertThat(b.uncoveredQty()).isEqualByComparingTo("20");
        assertThat(b.value()).as("never costed: unknown, not zero").isNull();
        assertThat(b.lastSaleDate()).isNull();

        // Company-wide, C's transfer legs net to nothing: its age is the original receipt's.
        StockAgeingRowDto c = row(r, "C");
        assertThat(c.bucketQty()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("0"), bd("0"), bd("0"), bd("0"), bd("8"));
        assertThat(c.uncoveredQty()).isEqualByComparingTo("0");

        assertThat(r.rows()).extracting(StockAgeingRowDto::productCode)
                .as("negative stock has no age").doesNotContain("D");
        assertThat(r.negativeStockProducts()).isEqualTo(1);
        assertThat(r.unvaluedProducts()).isEqualTo(1);
        assertThat(r.uncoveredProducts()).isEqualTo(1);
        assertThat(r.neverSoldProducts()).isEqualTo(2);

        assertFoots(r);
        assertThat(r.totalOnHand()).isEqualByComparingTo("63");
        assertThat(r.totalValue()).as("A + C; B has no cost").isEqualByComparingTo("2580");
    }

    @Test
    void branchScope_aTransferInFromAnotherBranchIsAnArrival() {
        StockAgeingReportDto r = query.report(company.getId(), null, ownBranch.getUid());

        assertThat(r.branchName()).isEqualTo("Own Branch");
        // At the own branch, C arrived 35 days ago — the day it was received off the transfer.
        StockAgeingRowDto c = row(r, "C");
        assertThat(c.bucketQty()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("0"), bd("8"), bd("0"), bd("0"), bd("0"));
        assertFoots(r);
    }

    @Test
    void pastAsOf_rebuildsTheQuantity_andAgesFromThatDate() {
        LocalDate asOf = today().minusDays(30);
        StockAgeingReportDto r = query.report(company.getId(), asOf, ownBranch.getUid());

        assertThat(r.asOf()).isEqualTo(asOf.toString());
        assertThat(r.valuedAtCurrentCost()).isTrue();
        // 25 today, less the +2 adjustment and plus the 12 sold since: 35 on that date.
        StockAgeingRowDto a = row(r, "A");
        assertThat(a.onHand()).isEqualByComparingTo("35");
        // Ages counted from the as-of date: 5 @15 days, 20 @70, 10 @170.
        assertThat(a.bucketQty()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("5"), bd("0"), bd("20"), bd("10"), bd("0"));
        assertThat(a.value()).as("as-of qty at today's average").isEqualByComparingTo("3500");
        assertThat(a.lastSaleDate()).as("the sale came after the as-of date").isNull();
        assertFoots(r);
    }

    @Test
    void nonRootClerk_isRefusedABranchTheyAreNotAssignedTo() {
        asClerk();
        assertThatCode(() -> query.report(company.getId(), null, ownBranch.getUid()))
                .doesNotThrowAnyException();
        assertThatCode(() -> query.report(company.getId(), null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> query.report(company.getId(), null, otherBranch.getUid()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }

    @Test
    void unknownBranch_isNotFound() {
        assertThatThrownBy(() -> query.report(company.getId(), null, "NOSUCHBRANCHUID0000000000"))
                .isInstanceOf(NotFoundException.class);
    }

    // -------------------------------------------------------------------------

    /** Buckets add back to on-hand and value on every row, and the bucket totals to the grand total. */
    private static void assertFoots(StockAgeingReportDto r) {
        for (StockAgeingRowDto x : r.rows()) {
            assertThat(sum(x.bucketQty())).as(x.productCode() + " qty")
                    .isEqualByComparingTo(x.onHand());
            if (x.value() != null) {
                assertThat(sum(x.bucketValue())).as(x.productCode() + " value")
                        .isEqualByComparingTo(x.value());
            }
        }
        assertThat(sum(r.totalBucketQty())).isEqualByComparingTo(r.totalOnHand());
        assertThat(sum(r.totalBucketValue())).isEqualByComparingTo(r.totalValue());
    }

    private static StockAgeingRowDto row(StockAgeingReportDto r, String code) {
        return r.rows().stream().filter(x -> code.equals(x.productCode())).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + code));
    }

    private static BigDecimal sum(List<BigDecimal> v) {
        return v.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}

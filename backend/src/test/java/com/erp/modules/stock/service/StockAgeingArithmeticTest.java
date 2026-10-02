package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.stock.service.StockAgeingReportQuery.Allocation;
import com.erp.modules.stock.service.StockAgeingReportQuery.Layer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The FIFO allocation and the bucket valuation behind the Stock Ageing report. */
class StockAgeingArithmeticTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 2);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void onHandIsTheNewestArrivals_soOlderReceiptsAreOnlyPartlyLeft() {
        // 25 on hand; arrivals newest first: +2 (5 days), +5 (45 days), +20 (100 days), +10 (200).
        Allocation a = StockAgeingReportQuery.allocate(bd("25"), List.of(
                new Layer(AS_OF.minusDays(5), bd("2")),
                new Layer(AS_OF.minusDays(45), bd("5")),
                new Layer(AS_OF.minusDays(100), bd("20")),
                new Layer(AS_OF.minusDays(200), bd("10"))), AS_OF);

        assertThat(a.bucketQty()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("2"), bd("5"), bd("0"), bd("18"), bd("0"));
        assertThat(a.uncovered()).isEqualByComparingTo("0");
        assertThat(sum(a.bucketQty())).isEqualByComparingTo("25");
    }

    @Test
    void stockNoArrivalAccountsFor_goesToTheOldestBucket_andIsFlagged() {
        Allocation a = StockAgeingReportQuery.allocate(bd("30"),
                List.of(new Layer(AS_OF.minusDays(20), bd("10"))), AS_OF);

        assertThat(a.bucketQty()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bd("10"), bd("0"), bd("0"), bd("0"), bd("20"));
        assertThat(a.uncovered()).isEqualByComparingTo("20");
        assertThat(sum(a.bucketQty())).isEqualByComparingTo("30");
    }

    @Test
    void bucketBoundariesAreInclusiveAtTheTop() {
        assertThat(StockAgeingReportQuery.bucketFor(0)).isZero();
        assertThat(StockAgeingReportQuery.bucketFor(30)).isZero();
        assertThat(StockAgeingReportQuery.bucketFor(31)).isEqualTo(1);
        assertThat(StockAgeingReportQuery.bucketFor(60)).isEqualTo(1);
        assertThat(StockAgeingReportQuery.bucketFor(90)).isEqualTo(2);
        assertThat(StockAgeingReportQuery.bucketFor(180)).isEqualTo(3);
        assertThat(StockAgeingReportQuery.bucketFor(181)).isEqualTo(4);
    }

    @Test
    void bucketValuesAddUpToTheRowValueToTheCent() {
        // 3 units at 33.3333: each bucket rounds, the total does not. The difference is settled
        // on the last non-empty bucket so the printed buckets foot exactly.
        List<BigDecimal> values = StockAgeingReportQuery.splitValue(
                List.of(bd("1"), bd("1"), bd("0"), bd("1"), bd("0")),
                bd("33.3333"), bd("100.00"));

        assertThat(sum(values)).isEqualByComparingTo("100.00");
        assertThat(values.get(2)).isEqualByComparingTo("0");
        assertThat(values.get(4)).isEqualByComparingTo("0");
    }
}

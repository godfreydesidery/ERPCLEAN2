package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.sales.service.RestaurantBillCalculator.PricedLine;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestaurantDiscountTest {

    private static PricedLine line(String gross, String raw) {
        return new PricedLine(1L, 1L, "Item", "PCS", BigDecimal.ONE, new BigDecimal(gross),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(gross), new BigDecimal(raw));
    }

    private static BigDecimal sum(List<BigDecimal> xs) {
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void percentOrAmount_neverBoth_neverMoreThanTheBill() {
        BigDecimal gross = new BigDecimal("50000");
        assertThat(RestaurantDiscount.target(gross, new BigDecimal("10"), null))
                .isEqualByComparingTo("5000");
        assertThat(RestaurantDiscount.target(gross, null, new BigDecimal("7500")))
                .isEqualByComparingTo("7500");
        assertThat(RestaurantDiscount.target(gross, null, null)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> RestaurantDiscount.target(gross, BigDecimal.ONE, BigDecimal.ONE))
                .hasMessageContaining("not both");
        assertThatThrownBy(() -> RestaurantDiscount.target(gross, null, new BigDecimal("50001")))
                .hasMessageContaining("larger than the bill");
    }

    @Test
    void inclusivePrices_theWholeDiscountComesOff_exactly() {
        // 12,000 + 6,000 + 7,000 = 25,000; 1,000 off spread by value, rounding on the last line.
        var lines = List.of(line("12000", "12000"), line("6000", "6000"), line("7000", "7000"));
        var out = RestaurantDiscount.spread(lines, new BigDecimal("25000"), new BigDecimal("1000"));
        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isEqualByComparingTo("480");
        assertThat(out.get(1)).isEqualByComparingTo("240");
        assertThat(sum(out)).isEqualByComparingTo("1000");
    }

    @Test
    void exclusivePrices_theDiscountIsTakenNetOfVat() {
        // A net 10,000 line with 18% VAT is 11,800 gross; 1,180 off gross = 1,000 off net.
        var out = RestaurantDiscount.spread(List.of(line("11800", "10000")),
                new BigDecimal("11800"), new BigDecimal("1180"));
        assertThat(out.get(0)).isEqualByComparingTo("1000");
    }

    @Test
    void aFreeLineTakesNoShare_andNoDiscountMeansZeros() {
        var lines = List.of(line("0", "0"), line("5000", "5000"));
        assertThat(RestaurantDiscount.spread(lines, new BigDecimal("5000"), new BigDecimal("500")))
                .containsExactly(BigDecimal.ZERO, new BigDecimal("500.00"));
        assertThat(RestaurantDiscount.spread(lines, new BigDecimal("5000"), BigDecimal.ZERO))
                .allSatisfy(d -> assertThat(d).isEqualByComparingTo("0"));
    }
}

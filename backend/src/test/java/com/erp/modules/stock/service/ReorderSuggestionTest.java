package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The suggested order quantity on the Reorder Report. */
class ReorderSuggestionTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void fillsBackUpToTheMaximumWhenOneIsSet() {
        assertThat(ReorderReportQuery.suggestedOrderQty(bd("25"), bd("5"), bd("50"), bd("12")))
                .isEqualByComparingTo("25");
    }

    @Test
    void usesTheProductOrderSizeWhenNoMaximum() {
        assertThat(ReorderReportQuery.suggestedOrderQty(bd("30"), bd("0"), null, bd("24")))
                .isEqualByComparingTo("24");
    }

    @Test
    void isOtherwiseTheShortfall() {
        assertThat(ReorderReportQuery.suggestedOrderQty(bd("2"), bd("8"), null, null))
                .isEqualByComparingTo("8");
    }

    @Test
    void neverSuggestsLessThanTheShortfall() {
        // A maximum set below the reorder level, and an order size smaller than the gap.
        assertThat(ReorderReportQuery.suggestedOrderQty(bd("0"), bd("10"), bd("6"), null))
                .isEqualByComparingTo("10");
        assertThat(ReorderReportQuery.suggestedOrderQty(bd("0"), bd("10"), null, bd("4")))
                .isEqualByComparingTo("10");
    }
}

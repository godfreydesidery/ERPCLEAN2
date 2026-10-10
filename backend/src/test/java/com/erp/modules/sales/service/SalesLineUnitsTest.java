package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * SAL-01 / LSF-01 / SAL-17: quantity conversion between a sales-order line's own unit and the
 * base unit, and the pro-rating of a fixed line discount over partial invoices.
 */
class SalesLineUnitsTest {

    /** 2 Cartons of 24 — the finding's own example. */
    private static SalesOrderLine cartonLine() {
        return line("2", "48");
    }

    @Test
    void factorIsBaseUnitsPerLineUnit() {
        assertThat(SalesLineUnits.factorToBase(cartonLine())).isEqualByComparingTo("24");
        assertThat(SalesLineUnits.factorToBase(line("10", "10"))).isEqualByComparingTo("1");
    }

    @Test
    void oneCartonDeliveredIs24BaseAndBack() {
        SalesOrderLine sol = cartonLine();
        assertThat(SalesLineUnits.toBase(sol, new BigDecimal("1"))).isEqualByComparingTo("24");
        assertThat(SalesLineUnits.toLineUnit(sol, new BigDecimal("24"))).isEqualByComparingTo("1");
        // a single bottle back out of a carton is a fraction of the carton, never zero
        assertThat(SalesLineUnits.toLineUnit(sol, new BigDecimal("1")))
                .isEqualByComparingTo("0.041667");
    }

    @Test
    void baseUnitLineIsTheIdentity() {
        SalesOrderLine sol = line("10", "10");
        assertThat(SalesLineUnits.toBase(sol, new BigDecimal("4"))).isEqualByComparingTo("4");
        assertThat(SalesLineUnits.toLineUnit(sol, new BigDecimal("4"))).isEqualByComparingTo("4");
    }

    @Test
    void aPositiveBaseQuantityNeverConvertsToZero() {
        SalesOrderLine sol = line("1", "10000000");
        assertThat(SalesLineUnits.toLineUnit(sol, new BigDecimal("1")).signum()).isPositive();
    }

    @Test
    void fixedDiscountIsSharedByTheDeliveredQuantity() {
        // 10 ordered with 1,000 off, delivered and invoiced 4 + 6 → 400 + 600, not 1,000 twice.
        BigDecimal first = SalesLineUnits.proRatedLineDiscount(
                new BigDecimal("1000"), new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("4"), 0);
        BigDecimal second = SalesLineUnits.proRatedLineDiscount(
                new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("6"), 0);
        assertThat(first).isEqualByComparingTo("400");
        assertThat(second).isEqualByComparingTo("600");
    }

    @Test
    void thirdsTelescopeBackToTheWholeDiscount() {
        BigDecimal total = new BigDecimal("1000");
        BigDecimal ordered = new BigDecimal("3");
        BigDecimal a = SalesLineUnits.proRatedLineDiscount(total, ordered, BigDecimal.ZERO, BigDecimal.ONE, 0);
        BigDecimal b = SalesLineUnits.proRatedLineDiscount(total, ordered, BigDecimal.ONE, BigDecimal.ONE, 0);
        BigDecimal c = SalesLineUnits.proRatedLineDiscount(total, ordered, new BigDecimal("2"), BigDecimal.ONE, 0);
        assertThat(a.add(b).add(c)).isEqualByComparingTo("1000");
    }

    @Test
    void noFixedDiscountMeansNothingToShare() {
        assertThat(SalesLineUnits.proRatedLineDiscount(
                null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE, 0)).isNull();
        assertThat(SalesLineUnits.proRatedLineDiscount(
                BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE, 0)).isNull();
    }

    @Test
    void fullInvoiceOfAPackLineGetsTheWholeDiscount() {
        // 2 cartons (48 base) with 5,000 off, invoiced in one go.
        assertThat(SalesLineUnits.proRatedLineDiscount(new BigDecimal("5000"), new BigDecimal("48"),
                BigDecimal.ZERO, new BigDecimal("48"), 0)).isEqualByComparingTo("5000");
    }

    private static SalesOrderLine line(String qty, String qtyBase) {
        return new SalesOrderLine(1L, 1L, 1L, (short) 1, 1L, "P1", "Product", 1L, "Carton",
                new BigDecimal(qty), new BigDecimal(qtyBase),
                new BigDecimal("30000"), new BigDecimal("30000"),
                VatStatus.STANDARD, new BigDecimal("0.18"), "TZS", 1L);
    }
}

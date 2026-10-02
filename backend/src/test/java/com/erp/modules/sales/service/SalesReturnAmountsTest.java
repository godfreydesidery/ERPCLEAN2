package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import com.erp.modules.sales.service.SalesReturnServiceImpl.ReturnAmounts;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SalesReturnServiceImpl#proRateAmounts}: a return credits the order line's own net and VAT
 * pro-rata, rounded in the order currency. The old formula, {@code net × vatRate / 100}, read the
 * stored fraction 0.18 as a percentage and credited a hundredth of the VAT.
 */
class SalesReturnAmountsTest {

    private static final BigDecimal RATE = new BigDecimal("0.1800");
    private final SalesOrderTotalsCalculator calculator = new SalesOrderTotalsCalculator();

    @Test
    void tzsPartialReturn_creditsTheLineVatProRata_inWholeShillings() {
        SalesOrderLine sol = totalledLine("1500", "5", "5", false);   // net 7 500, VAT 1 350

        ReturnAmounts r = SalesReturnServiceImpl.proRateAmounts(sol, new BigDecimal("2"), 0);

        assertThat(r.net()).isEqualByComparingTo("3000");
        assertThat(r.vat()).isEqualByComparingTo("540");
    }

    @Test
    void usdReturn_roundsInCents() {
        SalesOrderLine sol = totalledLine("12.00", "3", "3", false);  // net 36.00, VAT 6.48

        ReturnAmounts r = SalesReturnServiceImpl.proRateAmounts(sol, BigDecimal.ONE, 2);

        assertThat(r.net()).isEqualByComparingTo("12.00");
        assertThat(r.vat()).isEqualByComparingTo("2.16");
    }

    @Test
    void inclusivePricedLine_creditsTheStrippedNetAndVat_notThePriceAsNet() {
        // Inclusive 1 180 each: net 1 000, VAT 180 per unit. The old code took 1 180 as the NET.
        SalesOrderLine sol = totalledLine("1180", "2", "2", true);

        ReturnAmounts r = SalesReturnServiceImpl.proRateAmounts(sol, BigDecimal.ONE, 0);

        assertThat(r.net()).isEqualByComparingTo("1000");
        assertThat(r.vat()).isEqualByComparingTo("180");
    }

    @Test
    void packUnit_isProRatedOnBaseQuantity() {
        // 1 pack of 12 at 12 000: returning 6 singles is half the line, not 6 packs.
        SalesOrderLine sol = totalledLine("12000", "1", "12", false);  // net 12 000, VAT 2 160

        ReturnAmounts r = SalesReturnServiceImpl.proRateAmounts(sol, new BigDecimal("6"), 0);

        assertThat(r.net()).isEqualByComparingTo("6000");
        assertThat(r.vat()).isEqualByComparingTo("1080");
    }

    @Test
    void untotalledLine_fallsBackToPriceWithVatAtTheFractionalRate() {
        SalesOrderLine sol = line("1500", "5", "5", false);  // never recomputed: net/VAT zero

        ReturnAmounts r = SalesReturnServiceImpl.proRateAmounts(sol, new BigDecimal("2"), 0);

        assertThat(r.net()).isEqualByComparingTo("3000");
        assertThat(r.vat()).as("3 000 × 0.18, not ÷ 100").isEqualByComparingTo("540");
    }

    // -------------------------------------------------------------------------

    private SalesOrderLine totalledLine(String price, String qty, String qtyBase, boolean incl) {
        SalesOrderLine l = line(price, qty, qtyBase, incl);
        com.erp.modules.sales.domain.entity.SalesOrder order =
                new com.erp.modules.sales.domain.entity.SalesOrder(1L, 10L, 200L, 300L,
                        price.contains(".") ? "USD" : "TZS", java.time.LocalDate.now(), 1L);
        calculator.recompute(order, List.of(l));
        return l;
    }

    private static SalesOrderLine line(String price, String qty, String qtyBase, boolean incl) {
        BigDecimal p = new BigDecimal(price);
        SalesOrderLine l = new SalesOrderLine(null, 1L, 10L, (short) 1,
                100L, "PROD-1", "Product 1", 200L, "PCS",
                new BigDecimal(qty), new BigDecimal(qtyBase), p, p,
                VatStatus.STANDARD, RATE, "TZS", 1L);
        l.setPriceInclusive(incl);
        return l;
    }
}

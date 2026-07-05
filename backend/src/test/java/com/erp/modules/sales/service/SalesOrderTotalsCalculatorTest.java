package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.entity.Quotation;
import com.erp.modules.sales.domain.entity.QuotationLine;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Acceptance matrix for {@link SalesOrderTotalsCalculator} — VAT-inclusive pricing (ADR-0056 D-5).
 * The SalesOrder overload carries the full T1-T6 matrix (identical math to
 * {@link InvoiceTotalsCalculatorTest}, ADR-0021 D-9); the Quotation overload gets a representative
 * subset proving the SAME shared {@code compute()} core is wired correctly for both header types.
 */
class SalesOrderTotalsCalculatorTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID = 10L;
    private static final Long CUSTOMER_ID = 200L;
    private static final Long AGENT_ID = 300L;
    private static final BigDecimal STANDARD_RATE = new BigDecimal("0.1800");

    private final SalesOrderTotalsCalculator calculator = new SalesOrderTotalsCalculator();

    // -------------------------------------------------------------------------
    // SalesOrder overload — full matrix
    // -------------------------------------------------------------------------

    @Test
    void t1_equivalence_exclusiveAndInclusiveListPrices_settleToSameNetVatGross() {
        SalesOrder exclusiveOrder = newOrder();
        SalesOrderLine exclusiveLine = orderLine(exclusiveOrder, 1, "1000.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, false);
        calculator.recompute(exclusiveOrder, List.of(exclusiveLine));

        SalesOrder inclusiveOrder = newOrder();
        SalesOrderLine inclusiveLine = orderLine(inclusiveOrder, 1, "1180.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, true);
        calculator.recompute(inclusiveOrder, List.of(inclusiveLine));

        assertThat(exclusiveLine.getNetAmount()).isEqualByComparingTo("1000");
        assertThat(exclusiveLine.getVatAmount()).isEqualByComparingTo("180");
        assertThat(exclusiveLine.getGrossAmount()).isEqualByComparingTo("1180");

        assertThat(inclusiveLine.getNetAmount()).isEqualByComparingTo(exclusiveLine.getNetAmount());
        assertThat(inclusiveLine.getVatAmount()).isEqualByComparingTo(exclusiveLine.getVatAmount());
        assertThat(inclusiveLine.getGrossAmount()).isEqualByComparingTo(exclusiveLine.getGrossAmount());
        assertThat(inclusiveOrder.getNetTotalAmount())
                .isEqualByComparingTo(exclusiveOrder.getNetTotalAmount());
        assertThat(inclusiveOrder.getVatTotalAmount())
                .isEqualByComparingTo(exclusiveOrder.getVatTotalAmount());
        assertThat(inclusiveOrder.getGrossTotalAmount())
                .isEqualByComparingTo(exclusiveOrder.getGrossTotalAmount());
    }

    @Test
    void t2_exactGross_inclusiveLine_reproducesEnteredGrossExactly() {
        SalesOrder order = newOrder();
        SalesOrderLine line = orderLine(order, 1, "1000.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, true);

        calculator.recompute(order, List.of(line));

        assertThat(line.getGrossAmount()).isEqualByComparingTo("1000");
        assertThat(line.getNetAmount()).isEqualByComparingTo("847");
        assertThat(line.getVatAmount()).isEqualByComparingTo("153");
        assertThat(line.getNetAmount().add(line.getVatAmount())).isEqualByComparingTo(line.getGrossAmount());
    }

    @Test
    void t3_zeroRatedInclusive_grossEqualsNet_vatZero() {
        SalesOrder order = newOrder();
        SalesOrderLine line = orderLine(order, 1, "500.0000", BigDecimal.ONE,
                VatStatus.ZERO_RATED, BigDecimal.ZERO, true);

        calculator.recompute(order, List.of(line));

        assertThat(line.getGrossAmount()).isEqualByComparingTo("500");
        assertThat(line.getNetAmount()).isEqualByComparingTo("500");
        assertThat(line.getVatAmount()).isEqualByComparingTo("0");
    }

    @Test
    void t4_backCompat_exclusiveLines_multiLineDiscountApportionment_regressionPin() {
        SalesOrder order = newOrder();
        order.setDocDiscountAmount(new BigDecimal("240"));

        SalesOrderLine lineA = orderLine(order, 1, "1000.0000", new BigDecimal("2"),
                VatStatus.STANDARD, STANDARD_RATE, false);
        lineA.setLineDiscountAmount(new BigDecimal("100"));

        SalesOrderLine lineB = orderLine(order, 2, "500.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, false);

        calculator.recompute(order, List.of(lineA, lineB));

        assertThat(lineA.getNetAmount()).isEqualByComparingTo("1710");
        assertThat(lineA.getVatAmount()).isEqualByComparingTo("308");
        assertThat(lineA.getGrossAmount()).isEqualByComparingTo("2018");

        assertThat(lineB.getNetAmount()).isEqualByComparingTo("450");
        assertThat(lineB.getVatAmount()).isEqualByComparingTo("81");
        assertThat(lineB.getGrossAmount()).isEqualByComparingTo("531");

        assertThat(order.getNetTotalAmount()).isEqualByComparingTo("2160");
        assertThat(order.getVatTotalAmount()).isEqualByComparingTo("389");
        assertThat(order.getGrossTotalAmount()).isEqualByComparingTo("2549");
    }

    @Test
    void t5_discountOnInclusiveLine_netPlusVatStillEqualsDiscountedGross() {
        SalesOrder order = newOrder();
        order.setDocDiscountAmount(new BigDecimal("242"));

        SalesOrderLine line = orderLine(order, 1, "1180.0000", new BigDecimal("2"),
                VatStatus.STANDARD, STANDARD_RATE, true);
        line.setLineDiscountAmount(new BigDecimal("118"));

        calculator.recompute(order, List.of(line));

        assertThat(line.getGrossAmount()).isEqualByComparingTo("2000");
        assertThat(line.getNetAmount()).isEqualByComparingTo("1695");
        assertThat(line.getVatAmount()).isEqualByComparingTo("305");
        assertThat(line.getNetAmount().add(line.getVatAmount())).isEqualByComparingTo(line.getGrossAmount());
    }

    @Test
    void t6_packOverrideInclusive_resolvedGrossViaFactor_derivesNetExactly() {
        SalesOrder order = newOrder();
        SalesOrderLine line = orderLine(order, 1, "11800.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, true);

        calculator.recompute(order, List.of(line));

        assertThat(line.getGrossAmount()).isEqualByComparingTo("11800");
        assertThat(line.getNetAmount()).isEqualByComparingTo("10000");
        assertThat(line.getVatAmount()).isEqualByComparingTo("1800");
    }

    // -------------------------------------------------------------------------
    // Quotation overload — representative subset (shared compute() core)
    // -------------------------------------------------------------------------

    @Test
    void quotation_exactGross_inclusiveLine_reproducesEnteredGrossExactly() {
        Quotation quote = newQuotation();
        QuotationLine line = quotationLine(quote, 1, "1000.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, true);

        calculator.recompute(quote, List.of(line));

        assertThat(line.getGrossAmount()).isEqualByComparingTo("1000");
        assertThat(line.getNetAmount()).isEqualByComparingTo("847");
        assertThat(line.getVatAmount()).isEqualByComparingTo("153");
    }

    @Test
    void quotation_exclusiveLine_unchangedFromPreV86Behaviour() {
        Quotation quote = newQuotation();
        QuotationLine line = quotationLine(quote, 1, "1000.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, false);

        calculator.recompute(quote, List.of(line));

        assertThat(line.getNetAmount()).isEqualByComparingTo("1000");
        assertThat(line.getVatAmount()).isEqualByComparingTo("180");
        assertThat(line.getGrossAmount()).isEqualByComparingTo("1180");
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private static SalesOrder newOrder() {
        return new SalesOrder(COMPANY_ID, BRANCH_ID, CUSTOMER_ID, AGENT_ID, "TZS", LocalDate.now(), 1L);
    }

    private static Quotation newQuotation() {
        return new Quotation(COMPANY_ID, BRANCH_ID, CUSTOMER_ID, AGENT_ID, "TZS",
                LocalDate.now(), LocalDate.now().plusDays(30), 1L);
    }

    private static SalesOrderLine orderLine(SalesOrder order, int lineNo, String unitPrice,
                                            BigDecimal qty, VatStatus vatStatus, BigDecimal vatRate,
                                            boolean priceInclusive) {
        BigDecimal price = new BigDecimal(unitPrice);
        SalesOrderLine line = new SalesOrderLine(order.getId(), COMPANY_ID, BRANCH_ID, (short) lineNo,
                100L + lineNo, "PROD-000" + lineNo, "Product " + lineNo,
                200L, "PCS",
                qty, qty,
                price, price,
                vatStatus, vatRate,
                "TZS", 1L);
        line.setPriceInclusive(priceInclusive);
        return line;
    }

    private static QuotationLine quotationLine(Quotation quote, int lineNo, String unitPrice,
                                               BigDecimal qty, VatStatus vatStatus, BigDecimal vatRate,
                                               boolean priceInclusive) {
        BigDecimal price = new BigDecimal(unitPrice);
        QuotationLine line = new QuotationLine(quote.getId(), COMPANY_ID, BRANCH_ID, (short) lineNo,
                100L + lineNo, "PROD-000" + lineNo, "Product " + lineNo,
                200L, "PCS",
                qty, qty,
                price, price,
                vatStatus, vatRate,
                "TZS", 1L);
        line.setPriceInclusive(priceInclusive);
        return line;
    }
}

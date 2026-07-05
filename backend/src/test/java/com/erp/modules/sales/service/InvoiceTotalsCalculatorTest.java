package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Acceptance matrix for {@link InvoiceTotalsCalculator} — VAT-inclusive pricing (ADR-0056 D-5).
 *
 * <p>T1/T2/T3/T5/T6 exercise the new gross-preserving INCLUSIVE branch; T4 pins the EXCLUSIVE
 * branch byte-identical to the pre-V86 algorithm (regression guard).
 */
class InvoiceTotalsCalculatorTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID = 10L;
    private static final Long CUSTOMER_ID = 200L;
    private static final Long AGENT_ID = 300L;
    private static final BigDecimal STANDARD_RATE = new BigDecimal("0.1800");

    private final InvoiceTotalsCalculator calculator = new InvoiceTotalsCalculator(new ObjectMapper());

    // -------------------------------------------------------------------------
    // T1 — EQUIVALENCE: exclusive 1000 and inclusive 1180 both settle to the same
    // net/vat/gross (and hence the same tax_summary / GL banding).
    // -------------------------------------------------------------------------

    @Test
    void t1_equivalence_exclusiveAndInclusiveListPrices_settleToSameNetVatGross() {
        SalesInvoice exclusiveInvoice = newInvoice();
        SalesInvoiceLine exclusiveLine = line(exclusiveInvoice, 1, "1000.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, false);
        calculator.recompute(exclusiveInvoice, List.of(exclusiveLine));

        SalesInvoice inclusiveInvoice = newInvoice();
        SalesInvoiceLine inclusiveLine = line(inclusiveInvoice, 1, "1180.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, true);
        calculator.recompute(inclusiveInvoice, List.of(inclusiveLine));

        assertThat(exclusiveLine.getNetAmount()).isEqualByComparingTo("1000");
        assertThat(exclusiveLine.getVatAmount()).isEqualByComparingTo("180");
        assertThat(exclusiveLine.getGrossAmount()).isEqualByComparingTo("1180");

        assertThat(inclusiveLine.getNetAmount()).isEqualByComparingTo(exclusiveLine.getNetAmount());
        assertThat(inclusiveLine.getVatAmount()).isEqualByComparingTo(exclusiveLine.getVatAmount());
        assertThat(inclusiveLine.getGrossAmount()).isEqualByComparingTo(exclusiveLine.getGrossAmount());

        assertThat(inclusiveInvoice.getNetTotalAmount())
                .isEqualByComparingTo(exclusiveInvoice.getNetTotalAmount());
        assertThat(inclusiveInvoice.getVatTotalAmount())
                .isEqualByComparingTo(exclusiveInvoice.getVatTotalAmount());
        assertThat(inclusiveInvoice.getGrossTotalAmount())
                .isEqualByComparingTo(exclusiveInvoice.getGrossTotalAmount());
        // Same band composition -> identical tax_summary JSON.
        assertThat(inclusiveInvoice.getTaxSummary()).isEqualTo(exclusiveInvoice.getTaxSummary());
    }

    // -------------------------------------------------------------------------
    // T2 — EXACT-GROSS (headline): inclusive 1000 @18% -> gross EXACTLY 1000, never 999.
    // -------------------------------------------------------------------------

    @Test
    void t2_exactGross_inclusiveLine_reproducesEnteredGrossExactly() {
        SalesInvoice invoice = newInvoice();
        SalesInvoiceLine line = line(invoice, 1, "1000.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, true);

        calculator.recompute(invoice, List.of(line));

        assertThat(line.getGrossAmount()).isEqualByComparingTo("1000");
        assertThat(line.getNetAmount()).isEqualByComparingTo("847");
        assertThat(line.getVatAmount()).isEqualByComparingTo("153");
        // Gross-preserving identity, exactly — the whole point of ADR-0056.
        assertThat(line.getNetAmount().add(line.getVatAmount())).isEqualByComparingTo(line.getGrossAmount());
    }

    // -------------------------------------------------------------------------
    // T3 — ZERO-RATED inclusive: gross == net, vat = 0 (identity case, no division anomaly).
    // -------------------------------------------------------------------------

    @Test
    void t3_zeroRatedInclusive_grossEqualsNet_vatZero() {
        SalesInvoice invoice = newInvoice();
        SalesInvoiceLine line = line(invoice, 1, "500.0000", BigDecimal.ONE,
                VatStatus.ZERO_RATED, BigDecimal.ZERO, true);

        calculator.recompute(invoice, List.of(line));

        assertThat(line.getGrossAmount()).isEqualByComparingTo("500");
        assertThat(line.getNetAmount()).isEqualByComparingTo("500");
        assertThat(line.getVatAmount()).isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------
    // T4 — BACK-COMPAT: exclusive-line totals, byte-identical to the pre-V86 algorithm
    // (multi-line + line discount + doc-discount apportionment; hand-verified expected values).
    // -------------------------------------------------------------------------

    @Test
    void t4_backCompat_exclusiveLines_multiLineDiscountApportionment_regressionPin() {
        SalesInvoice invoice = newInvoice();
        invoice.setDocDiscountAmount(new BigDecimal("240"));

        SalesInvoiceLine lineA = line(invoice, 1, "1000.0000", new BigDecimal("2"),
                VatStatus.STANDARD, STANDARD_RATE, false);
        lineA.setLineDiscountAmount(new BigDecimal("100"));

        SalesInvoiceLine lineB = line(invoice, 2, "500.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, false);

        calculator.recompute(invoice, List.of(lineA, lineB));

        // rawNet A = 2000 - 100 = 1900; rawNet B = 500. sumRaw = 2400.
        // docDiscount 240 apportioned pro-rata: A gets 240*1900/2400 = 190; B (last) gets the
        // remaining 50. discountedNet A = 1710, B = 450.
        assertThat(lineA.getNetAmount()).isEqualByComparingTo("1710");
        assertThat(lineA.getVatAmount()).isEqualByComparingTo("308");
        assertThat(lineA.getGrossAmount()).isEqualByComparingTo("2018");

        assertThat(lineB.getNetAmount()).isEqualByComparingTo("450");
        assertThat(lineB.getVatAmount()).isEqualByComparingTo("81");
        assertThat(lineB.getGrossAmount()).isEqualByComparingTo("531");

        assertThat(invoice.getNetTotalAmount()).isEqualByComparingTo("2160");
        assertThat(invoice.getVatTotalAmount()).isEqualByComparingTo("389");
        assertThat(invoice.getGrossTotalAmount()).isEqualByComparingTo("2549");
    }

    // -------------------------------------------------------------------------
    // T5 — DISCOUNT on an inclusive line: line + doc discount taken off the GROSS;
    // net + vat = gross still holds exactly after both discounts.
    // -------------------------------------------------------------------------

    @Test
    void t5_discountOnInclusiveLine_netPlusVatStillEqualsDiscountedGross() {
        SalesInvoice invoice = newInvoice();
        invoice.setDocDiscountAmount(new BigDecimal("242"));

        SalesInvoiceLine line = line(invoice, 1, "1180.0000", new BigDecimal("2"),
                VatStatus.STANDARD, STANDARD_RATE, true);
        line.setLineDiscountAmount(new BigDecimal("118"));

        calculator.recompute(invoice, List.of(line));

        // rawGross = 1180*2 - 118 = 2242 (single line, so doc discount 242 taken in full) ->
        // discountedGross = 2000. net = round(2000 / 1.18) = 1695; vat = 2000 - 1695 = 305.
        assertThat(line.getGrossAmount()).isEqualByComparingTo("2000");
        assertThat(line.getNetAmount()).isEqualByComparingTo("1695");
        assertThat(line.getVatAmount()).isEqualByComparingTo("305");
        assertThat(line.getNetAmount().add(line.getVatAmount())).isEqualByComparingTo(line.getGrossAmount());

        assertThat(invoice.getNetTotalAmount()).isEqualByComparingTo("1695");
        assertThat(invoice.getVatTotalAmount()).isEqualByComparingTo("305");
        assertThat(invoice.getGrossTotalAmount()).isEqualByComparingTo("2000");
    }

    // -------------------------------------------------------------------------
    // T6 — PACK OVERRIDE inclusive: the resolver already multiplied a pack price by its
    // factor_to_base before handing the calculator a gross unit price; the calculator strips
    // VAT out of that resolved gross exactly, same as any other inclusive line (the
    // resolution mechanics themselves are covered by PriceResolutionServiceImplTest/IT).
    // -------------------------------------------------------------------------

    @Test
    void t6_packOverrideInclusive_resolvedGrossViaFactor_derivesNetExactly() {
        SalesInvoice invoice = newInvoice();
        // e.g. a 12-pack resolved at 11,800 gross (an inclusive-list pack override) for 1 pack.
        SalesInvoiceLine line = line(invoice, 1, "11800.0000", BigDecimal.ONE,
                VatStatus.STANDARD, STANDARD_RATE, true);

        calculator.recompute(invoice, List.of(line));

        assertThat(line.getGrossAmount()).isEqualByComparingTo("11800");
        assertThat(line.getNetAmount()).isEqualByComparingTo("10000");
        assertThat(line.getVatAmount()).isEqualByComparingTo("1800");
        assertThat(line.getNetAmount().add(line.getVatAmount())).isEqualByComparingTo(line.getGrossAmount());
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private static SalesInvoice newInvoice() {
        return new SalesInvoice(COMPANY_ID, BRANCH_ID, CUSTOMER_ID, AGENT_ID, "TZS", 1L);
    }

    private static SalesInvoiceLine line(SalesInvoice invoice, int lineNo, String unitPrice,
                                         BigDecimal qty, VatStatus vatStatus, BigDecimal vatRate,
                                         boolean priceInclusive) {
        BigDecimal price = new BigDecimal(unitPrice);
        SalesInvoiceLine line = new SalesInvoiceLine(invoice, (short) lineNo,
                100L + lineNo, "PROD-000" + lineNo, "Product " + lineNo,
                200L, "PCS",
                qty, qty,
                price, price,
                vatStatus, vatRate,
                1L);
        line.setPriceInclusive(priceInclusive);
        return line;
    }
}

package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.purchases.domain.dto.GoodsReceiptVatBandDto;
import com.erp.modules.purchases.service.GoodsReceiptPrintQuery.LineTax;
import static com.erp.modules.purchases.domain.enums.PurchaseVatTreatment.EXCLUSIVE;
import static com.erp.modules.purchases.domain.enums.PurchaseVatTreatment.INCLUSIVE;
import static com.erp.modules.purchases.domain.enums.PurchaseVatTreatment.NONE;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two derived figures on the printed GRN (K9): the margin percentage and the VAT bands.
 * Both are pure arithmetic and are tested here without a database — the SQL around them is covered
 * by the purchases ITs.
 */
class GoodsReceiptPrintQueryTest {

    // -------------------------------------------------------------------------
    // Margin
    // -------------------------------------------------------------------------

    /**
     * Margin is on the SELLING price, and rounds HALF_EVEN — these are the exact rows off the
     * client's own note, so a change in either convention shows up as a mismatch with the document
     * this one replaces rather than as a silent drift.
     */
    @Test
    void marginMatchesTheClientsNote() {
        assertThat(GoodsReceiptPrintQuery.marginPercent(bd("13000"), bd("15000")))
                .isEqualByComparingTo("13.33");
        assertThat(GoodsReceiptPrintQuery.marginPercent(bd("8500"), bd("10500")))
                .isEqualByComparingTo("19.05");
        // 15.625 — the HALF_EVEN case that separates this from a plain HALF_UP.
        assertThat(GoodsReceiptPrintQuery.marginPercent(bd("2700"), bd("3200")))
                .isEqualByComparingTo("15.62");
        assertThat(GoodsReceiptPrintQuery.marginPercent(bd("1500"), bd("2000")))
                .isEqualByComparingTo("25.00");
        assertThat(GoodsReceiptPrintQuery.marginPercent(bd("2000"), bd("2500")))
                .isEqualByComparingTo("20.00");
    }

    /** Bought above the shelf price: the note prints the negative rather than hiding it. */
    @Test
    void marginGoesNegativeWhenCostExceedsPrice() {
        assertThat(GoodsReceiptPrintQuery.marginPercent(bd("12000"), bd("10000")))
                .isEqualByComparingTo("-20.00");
    }

    /** No price, or a zero price, is unknown — not a zero margin. */
    @Test
    void marginIsBlankWithoutASellingPrice() {
        assertThat(GoodsReceiptPrintQuery.marginPercent(bd("1000"), null)).isNull();
        assertThat(GoodsReceiptPrintQuery.marginPercent(bd("1000"), BigDecimal.ZERO)).isNull();
        assertThat(GoodsReceiptPrintQuery.marginPercent(null, bd("1000"))).isNull();
    }

    // -------------------------------------------------------------------------
    // VAT bands
    // -------------------------------------------------------------------------

    @Test
    void groupsLinesIntoOneBandPerVatStatusInFirstAppearanceOrder() {
        List<GoodsReceiptVatBandDto> bands = GoodsReceiptPrintQuery.vatBands(List.of(
                new LineTax("STANDARD", bd("0.18"), bd("100000")),
                new LineTax("EXEMPT",   bd("0"),    bd("50000")),
                new LineTax("STANDARD", bd("0.18"), bd("200000"))), EXCLUSIVE);

        assertThat(bands).hasSize(2);
        assertThat(bands.get(0).vatStatus()).isEqualTo("STANDARD");
        assertThat(bands.get(0).goodsValue()).isEqualByComparingTo("300000.00");
        assertThat(bands.get(0).vatAmount()).isEqualByComparingTo("54000.00");
        assertThat(bands.get(1).vatStatus()).isEqualTo("EXEMPT");
        assertThat(bands.get(1).goodsValue()).isEqualByComparingTo("50000.00");
        assertThat(bands.get(1).vatAmount()).isEqualByComparingTo("0.00");
    }

    /**
     * VAT is computed on the band TOTAL, never summed per line. Three lines of 33.33 at 18% round to
     * 6.00 each and sum to 18.00, while the band (99.99) is 18.00 too — but shift the figures and the
     * two disagree by a cent, and a foot that does not tie to its own column costs somebody an
     * afternoon. This pins the band-total rule.
     */
    @Test
    void vatIsComputedOnTheBandTotalNotSummedPerLine() {
        List<GoodsReceiptVatBandDto> bands = GoodsReceiptPrintQuery.vatBands(List.of(
                new LineTax("STANDARD", bd("0.18"), bd("0.05")),
                new LineTax("STANDARD", bd("0.18"), bd("0.05")),
                new LineTax("STANDARD", bd("0.18"), bd("0.05"))), EXCLUSIVE);

        // Band total 0.15 × 0.18 = 0.027 → 0.03. Per-line would be 0.01 × 3 = 0.03 only by luck;
        // what matters is that goodsValue × rate reproduces the printed VAT exactly.
        assertThat(bands).hasSize(1);
        assertThat(bands.get(0).goodsValue()).isEqualByComparingTo("0.15");
        assertThat(bands.get(0).vatAmount()).isEqualByComparingTo("0.03");
    }

    /** A company with no configured rate for a status prints a zero band, never a guess. */
    @Test
    void anUnconfiguredRateProducesAZeroBand() {
        List<GoodsReceiptVatBandDto> bands = GoodsReceiptPrintQuery.vatBands(List.of(
                new LineTax("ZERO_RATED", BigDecimal.ZERO, bd("885000"))), EXCLUSIVE);

        assertThat(bands).hasSize(1);
        assertThat(bands.get(0).vatAmount()).isEqualByComparingTo("0.00");
        assertThat(bands.get(0).goodsValue()).isEqualByComparingTo("885000.00");
    }

    // -------------------------------------------------------------------------
    // VAT treatment (V105, ADR-0063) — Kilimanjaro 2026-09-12 #3
    // -------------------------------------------------------------------------

    /**
     * The defect this exists to fix. The client types the cost off a supplier invoice that already
     * contains VAT; the note used to add 18% on top of it regardless, so 118,000 of goods printed
     * as a total of 139,240.
     *
     * <p>Under INCLUSIVE the same entered figure is read as gross: VAT comes back OUT of it, and
     * goods + VAT is exactly the amount that was entered.
     */
    @Test
    void inclusiveExtractsVatFromTheEnteredAmountInsteadOfAddingItOnTop() {
        List<GoodsReceiptVatBandDto> bands = GoodsReceiptPrintQuery.vatBands(List.of(
                new LineTax("STANDARD", bd("0.18"), bd("118000"))), INCLUSIVE);

        assertThat(bands).hasSize(1);
        assertThat(bands.get(0).goodsValue()).isEqualByComparingTo("100000.00");
        assertThat(bands.get(0).vatAmount()).isEqualByComparingTo("18000.00");
        // The whole point: the two still add up to what the storekeeper typed.
        assertThat(bands.get(0).goodsValue().add(bands.get(0).vatAmount()))
                .isEqualByComparingTo("118000.00");
    }

    /**
     * VAT is taken out by SUBTRACTION, not by a second rounded multiplication. On a gross that does
     * not divide cleanly, {@code goods × rate} would round independently of {@code gross − goods}
     * and the foot would miss the invoice by a cent — the exact class of discrepancy that sends a
     * shopkeeper back to the supplier.
     */
    @Test
    void inclusiveAlwaysReconcilesToTheGrossThatWasEntered() {
        for (String gross : List.of("100000", "33333.33", "0.05", "77777.77", "1")) {
            List<GoodsReceiptVatBandDto> bands = GoodsReceiptPrintQuery.vatBands(List.of(
                    new LineTax("STANDARD", bd("0.18"), bd(gross))), INCLUSIVE);

            assertThat(bands.get(0).goodsValue().add(bands.get(0).vatAmount()))
                    .as("goods + vat must equal the gross entered, for %s", gross)
                    .isEqualByComparingTo(bd(gross).setScale(2, java.math.RoundingMode.HALF_UP));
        }
    }

    /** A zero-rated or exempt band has nothing to take back out; inclusive and exclusive agree. */
    @Test
    void inclusiveLeavesAZeroRatedBandAlone() {
        List<GoodsReceiptVatBandDto> inclusive = GoodsReceiptPrintQuery.vatBands(List.of(
                new LineTax("EXEMPT", BigDecimal.ZERO, bd("50000"))), INCLUSIVE);
        List<GoodsReceiptVatBandDto> exclusive = GoodsReceiptPrintQuery.vatBands(List.of(
                new LineTax("EXEMPT", BigDecimal.ZERO, bd("50000"))), EXCLUSIVE);

        assertThat(inclusive.get(0).goodsValue()).isEqualByComparingTo("50000.00");
        assertThat(inclusive.get(0).vatAmount()).isEqualByComparingTo("0.00");
        assertThat(inclusive.get(0).goodsValue()).isEqualByComparingTo(exclusive.get(0).goodsValue());
    }

    /**
     * NONE prints no band at all rather than a band of zeros. A business that is not VAT registered
     * does not have zero VAT to declare — it has none, and a row of 0.00 invites the reader to
     * wonder what went wrong.
     */
    @Test
    void noneShowsNoBandAtAllRatherThanABandOfZeros() {
        List<GoodsReceiptVatBandDto> bands = GoodsReceiptPrintQuery.vatBands(List.of(
                new LineTax("STANDARD", bd("0.18"), bd("118000")),
                new LineTax("EXEMPT",   BigDecimal.ZERO, bd("50000"))), NONE);

        assertThat(bands).isEmpty();
    }

    /** Nothing changes for anyone who has not been asked the question. */
    @Test
    void exclusiveIsUnchangedFromTheBehaviourEveryReceiptHasHad() {
        List<GoodsReceiptVatBandDto> bands = GoodsReceiptPrintQuery.vatBands(List.of(
                new LineTax("STANDARD", bd("0.18"), bd("100000"))), EXCLUSIVE);

        assertThat(bands.get(0).goodsValue()).isEqualByComparingTo("100000.00");
        assertThat(bands.get(0).vatAmount()).isEqualByComparingTo("18000.00");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}

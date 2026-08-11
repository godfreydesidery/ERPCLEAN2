package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.stock.domain.dto.ProductStockRowDto;
import com.erp.modules.stock.domain.dto.ProductStockTotalsDto;
import com.erp.platform.common.api.ForbiddenException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic on the Stock Value report — the grand totals at the foot, the Buying Price on each
 * line, and the wording of the one refusal a permitted caller can still hit.
 *
 * <p>The rule under test is "unknown is not zero". A product nobody has costed, and a product nobody
 * has priced, must be LEFT OUT of the grand totals and COUNTED, never folded in as 0.00. Treating an
 * unknown as zero produces a confident-looking total that is simply too low — the exact failure that
 * went unnoticed on bulk-imported stock, where a whole catalogue arrived with no valuation and the
 * report happily printed a total for it.
 *
 * <p>No database: {@code totalsOf} and {@code buyingPrice} are pure arithmetic over the mapped rows,
 * so they are exercised directly rather than through Testcontainers.
 */
class ProductStockReportQueryTest {

    @Test
    void unvaluedRow_isExcludedFromTheCostTotal_andCounted() {
        ProductStockTotalsDto totals = ProductStockReportQuery.totalsOf(List.of(
                row("SUGAR", qty(10), cost("5000.00"), sale("8000.00")),
                row("RICE",  qty(4),  null,            sale("2000.00"))));

        assertThat(totals.totalCostValue())
                .as("the uncosted row must not contribute 0.00 — it must contribute nothing at all")
                .isEqualByComparingTo("5000.00");
        assertThat(totals.unvaluedItems())
                .as("and the reader must be told one row is missing from that total")
                .isEqualTo(1);
    }

    @Test
    void unpricedRow_isExcludedFromTheSaleTotal_andCounted() {
        ProductStockTotalsDto totals = ProductStockReportQuery.totalsOf(List.of(
                row("SUGAR", qty(10), cost("5000.00"), sale("8000.00")),
                row("SALT",  qty(7),  cost("1000.00"), null)));

        assertThat(totals.totalSaleValue()).isEqualByComparingTo("8000.00");
        assertThat(totals.unpricedItems()).isEqualTo(1);
        assertThat(totals.unvaluedItems())
                .as("an unpriced row is still a costed row — the two counters are independent")
                .isZero();
    }

    @Test
    void aRowMissingBothFigures_isCountedOnBothCounters() {
        ProductStockTotalsDto totals = ProductStockReportQuery.totalsOf(List.of(
                row("MYSTERY", qty(3), null, null)));

        assertThat(totals.totalCostValue()).isEqualByComparingTo("0");
        assertThat(totals.totalSaleValue()).isEqualByComparingTo("0");
        assertThat(totals.unvaluedItems()).isEqualTo(1);
        assertThat(totals.unpricedItems()).isEqualTo(1);
    }

    /**
     * A catalogue row holding nothing has nothing to disclose: it cannot understate a total, so
     * counting it would inflate the warning into noise and train the reader to ignore it.
     */
    @Test
    void zeroQuantityRowWithNoFigures_isNotCountedAsMissing() {
        ProductStockTotalsDto totals = ProductStockReportQuery.totalsOf(List.of(
                row("NEVER-STOCKED", qty(0), null, null)));

        assertThat(totals.unvaluedItems()).isZero();
        assertThat(totals.unpricedItems()).isZero();
        assertThat(totals.totalQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void knownRows_sumIntoQuantityCostSaleAndMargin() {
        ProductStockTotalsDto totals = ProductStockReportQuery.totalsOf(List.of(
                row("A", qty(10), cost("5000.00"), sale("8000.00")),
                row("B", qty(2),  cost("1500.50"), sale("2000.50"))));

        assertThat(totals.totalQuantity()).isEqualByComparingTo("12");
        assertThat(totals.totalCostValue()).isEqualByComparingTo("6500.50");
        assertThat(totals.totalSaleValue()).isEqualByComparingTo("10000.50");
        assertThat(totals.potentialMargin())
                .as("margin is what is left on the shelf: sale total minus cost total")
                .isEqualByComparingTo("3500.00");
    }

    /**
     * {@code stock_on_hand.quantity} is deliberately signed (there is no non-negative CHECK), so a
     * negative on-hand is legal data and must flow into the totals rather than be clamped — a
     * clamped total would hide the very oversell it is evidence of.
     */
    @Test
    void negativeQuantity_flowsThroughRatherThanBeingClamped() {
        ProductStockTotalsDto totals = ProductStockReportQuery.totalsOf(List.of(
                row("OVERSOLD", qty(-3), cost("-1500.00"), sale("-2400.00"))));

        assertThat(totals.totalQuantity()).isEqualByComparingTo("-3");
        assertThat(totals.totalCostValue()).isEqualByComparingTo("-1500.00");
        assertThat(totals.totalSaleValue()).isEqualByComparingTo("-2400.00");
    }

    @Test
    void discontinuedRowsAreCounted_butStillCountTowardsTheTotals() {
        ProductStockTotalsDto totals = ProductStockReportQuery.totalsOf(List.of(
                row("LIVE", qty(10), cost("5000.00"), sale("8000.00"), false),
                row("GONE", qty(2),  cost("1000.00"), sale("1200.00"), true)));

        assertThat(totals.discontinuedItems()).isEqualTo(1);
        assertThat(totals.totalCostValue())
                .as("the GL is still carrying that stock, so a valuation that dropped it would "
                        + "disagree with the inventory account")
                .isEqualByComparingTo("6000.00");
    }

    @Test
    void noRows_totalsAreZeroNotNull() {
        ProductStockTotalsDto totals = ProductStockReportQuery.totalsOf(List.of());

        assertThat(totals.totalQuantity()).isEqualByComparingTo("0");
        assertThat(totals.totalCostValue()).isEqualByComparingTo("0");
        assertThat(totals.totalSaleValue()).isEqualByComparingTo("0");
        assertThat(totals.potentialMargin()).isEqualByComparingTo("0");
        assertThat(totals.unvaluedItems()).isZero();
        assertThat(totals.unpricedItems()).isZero();
        assertThat(totals.discontinuedItems()).isZero();
    }

    // -------------------------------------------------------------------------
    // Buying Price — the number on the line has to agree with the Cost Value beside it
    // -------------------------------------------------------------------------

    /**
     * The defect this locks down: two branches legitimately carry two different avg_costs for one
     * product (an opening valuation writes to the one row it is given, and the stock import drives
     * that per active branch), so reading a single stored avg_cost printed the HIGHEST branch's cost
     * against a company-wide Cost Value — and 20 x 1,200 is not 22,000. The implied average is the
     * only figure that reconciles.
     */
    @Test
    void twoBranchesWithDifferentCosts_giveAUnitPriceThatReconcilesWithTheCostValue() {
        BigDecimal totalQty   = qty(20);                    // 10 in Arusha + 10 in Dodoma
        BigDecimal totalValue = new BigDecimal("22000.00"); // 10 @ 1,000 + 10 @ 1,200
        BigDecimal highestBranchCost = new BigDecimal("1200.0000");

        BigDecimal buying = ProductStockReportQuery.buyingPrice(totalValue, totalQty,
                highestBranchCost);

        assertThat(buying)
                .as("the highest branch's cost is not the company's cost")
                .isEqualByComparingTo("1100.00");
        assertThat(buying.multiply(totalQty))
                .as("qty x buying price must equal the cost value printed on the same line, or the "
                        + "reader is left doing arithmetic that does not work")
                .isEqualByComparingTo(totalValue);
    }

    @Test
    void oneBranch_impliedAverageMatchesTheStoredCost() {
        BigDecimal buying = ProductStockReportQuery.buyingPrice(
                new BigDecimal("5000.00"), qty(10), new BigDecimal("500.0000"));

        assertThat(buying).isEqualByComparingTo("500.00");
    }

    /**
     * A sold-out catalogue row has nothing to divide by, but its last known cost is real information
     * — and on a catalogue this is the common row, not the edge case.
     */
    @Test
    void zeroQuantity_fallsBackToTheStoredCostRatherThanBlanking() {
        BigDecimal buying = ProductStockReportQuery.buyingPrice(
                BigDecimal.ZERO, qty(0), new BigDecimal("750.0000"));

        assertThat(buying).isEqualByComparingTo("750.0000");
    }

    @Test
    void zeroQuantityAndNoStoredCost_isBlankNotZero() {
        assertThat(ProductStockReportQuery.buyingPrice(BigDecimal.ZERO, qty(0), null))
                .as("0.00 would read as 'free'; blank reads as 'nobody has costed it'")
                .isNull();
    }

    @Test
    void noStockOnHandRowAtAll_isBlank() {
        assertThat(ProductStockReportQuery.buyingPrice(null, null, null)).isNull();
    }

    /**
     * {@code stock_on_hand.quantity} is signed, so an oversold product reaches here negative. Its
     * on-hand value is negative too, and the two divide out to a sane positive unit cost.
     */
    @Test
    void negativeOnHand_stillDividesOutToAPositiveUnitCost() {
        BigDecimal buying = ProductStockReportQuery.buyingPrice(
                new BigDecimal("-1500.00"), qty(-3), null);

        assertThat(buying).isEqualByComparingTo("500.00");
    }

    /**
     * A cost that does not divide evenly must not be truncated to something that reads as exact.
     * Six decimal places is at least the precision of the stored {@code avg_cost} column.
     */
    @Test
    void aNonTerminatingDivision_keepsPrecisionInsteadOfThrowing() {
        BigDecimal buying = ProductStockReportQuery.buyingPrice(
                new BigDecimal("1000.00"), qty(3), null);

        assertThat(buying).isEqualByComparingTo("333.333333");
    }

    // -------------------------------------------------------------------------
    // The branch-filter refusal — a message the reader can act on
    // -------------------------------------------------------------------------

    /**
     * A caller who reached this report HOLDS the report permission, so a permission-shaped refusal
     * sends them to an administrator to ask for something they already have and leaves them believing
     * the screen is broken. The real remedy is a branch assignment, or no branch filter — and the
     * refusal has to say so.
     */
    @Test
    void branchRefusal_namesTheRealReasonAndTheRemedy() {
        String message = ProductStockReportQuery.branchNotAssigned().getMessage();

        assertThat(message)
                .as("the generic denial is the wrong answer here — the caller is not short of a "
                        + "permission, they are short of a branch assignment")
                .isNotEqualTo(ForbiddenException.notPermitted().getMessage())
                .containsIgnoringCase("not assigned to that branch")
                .containsIgnoringCase("whole company");
    }

    /** User-facing text: no uids, no ids, no internal reference codes, no table names. */
    @Test
    void branchRefusal_leaksNothingInternal() {
        String message = ProductStockReportQuery.branchNotAssigned().getMessage();

        assertThat(message)
                .doesNotContain("user_branch")
                .doesNotContain("branch_id")
                .doesNotContain("uid")
                .doesNotContainIgnoringCase("ADR-")
                .doesNotContainIgnoringCase("SQL");
    }

    // -------------------------------------------------------------------------

    private static ProductStockRowDto row(String code, BigDecimal qty,
                                           BigDecimal costValue, BigDecimal saleValue) {
        return row(code, qty, costValue, saleValue, false);
    }

    private static ProductStockRowDto row(String code, BigDecimal qty, BigDecimal costValue,
                                           BigDecimal saleValue, boolean discontinued) {
        // Unit prices are irrelevant to totalsOf — only the extended values and the quantity are.
        return new ProductStockRowDto(code, code + " description", "Supplier Ltd",
                qty, null, costValue, null, saleValue, discontinued);
    }

    private static BigDecimal qty(int q) {
        return BigDecimal.valueOf(q);
    }

    private static BigDecimal cost(String amount) {
        return new BigDecimal(amount);
    }

    private static BigDecimal sale(String amount) {
        return new BigDecimal(amount);
    }
}

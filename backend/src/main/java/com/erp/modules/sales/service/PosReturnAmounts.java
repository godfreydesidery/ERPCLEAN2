package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.PosReturnedLineTotalsDto;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pro-rating of a sold POS line for an item return (ADR-0072).
 *
 * <p>The sold line's own net, VAT and discount are what was charged, so a return gives back the same
 * proportion of each as the proportion of the quantity returned. Rounding is done on the
 * <em>cumulative</em> returned quantity and the amounts already given back are subtracted, so the
 * returns of one line always add up to the rounding of the total — and the return that takes the
 * line's last unit takes the exact remainder (sold amount minus everything returned before), so
 * partial returns can never drift from what the customer paid, by a cent or a shilling.
 *
 * <p>Gross is always {@code net + vat}, exactly as on the sold line.
 */
final class PosReturnAmounts {

    /** Quantities carry six decimals ({@code NUMERIC(19,6)}). */
    static final int QTY_SCALE = 6;

    private PosReturnAmounts() {
    }

    /** The amounts and quantities one return gives back for one sold line. */
    record Slice(BigDecimal qty, BigDecimal qtyBase, BigDecimal net, BigDecimal discount,
                 BigDecimal vat) {
        BigDecimal gross() {
            return net.add(vat);
        }
    }

    /**
     * @param soldQty      the sold line's quantity, in its own unit (&gt; 0)
     * @param soldQtyBase  the same quantity in the product's base unit
     * @param soldNet      the sold line's net amount (after discount, before VAT)
     * @param soldDiscount the sold line's discount amount; null when none
     * @param soldVat      the sold line's VAT amount
     * @param qty          the quantity being returned now, in the sold line's unit
     * @param prior        everything already returned for this line; null when nothing was
     * @param scale        decimal places of the invoice currency's minor unit
     */
    static Slice slice(BigDecimal soldQty, BigDecimal soldQtyBase, BigDecimal soldNet,
                       BigDecimal soldDiscount, BigDecimal soldVat, BigDecimal qty,
                       PosReturnedLineTotalsDto prior, int scale) {
        BigDecimal priorQty  = prior == null ? BigDecimal.ZERO : prior.qtyReturned();
        BigDecimal priorBase = prior == null ? BigDecimal.ZERO : prior.qtyReturnedBase();
        BigDecimal priorNet  = prior == null ? BigDecimal.ZERO : prior.netAmount();
        BigDecimal priorDisc = prior == null ? BigDecimal.ZERO : prior.discountAmount();
        BigDecimal priorVat  = prior == null ? BigDecimal.ZERO : prior.vatAmount();

        BigDecimal cumulative = priorQty.add(qty);
        if (cumulative.compareTo(soldQty) > 0) {
            throw new IllegalArgumentException("Cannot return more than was sold.");
        }
        BigDecimal discount = soldDiscount == null ? BigDecimal.ZERO : soldDiscount;

        if (cumulative.compareTo(soldQty) == 0) {
            // The line's last unit: the exact remainder, so nothing is lost or invented.
            return new Slice(qty, soldQtyBase.subtract(priorBase), soldNet.subtract(priorNet),
                    discount.subtract(priorDisc), soldVat.subtract(priorVat));
        }
        return new Slice(qty,
                share(soldQtyBase, cumulative, soldQty, QTY_SCALE).subtract(priorBase),
                share(soldNet, cumulative, soldQty, scale).subtract(priorNet).max(BigDecimal.ZERO),
                share(discount, cumulative, soldQty, scale).subtract(priorDisc).max(BigDecimal.ZERO),
                share(soldVat, cumulative, soldQty, scale).subtract(priorVat).max(BigDecimal.ZERO));
    }

    private static BigDecimal share(BigDecimal whole, BigDecimal part, BigDecimal of, int scale) {
        return whole.multiply(part).divide(of, scale, RoundingMode.HALF_UP);
    }
}

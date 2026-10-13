package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.entity.SalesOrderLine;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Converts quantities between a sales-order line's own unit (the unit it was ordered and priced in,
 * e.g. a Crate of 24) and the product's base unit (SAL-01 / LSF-01).
 *
 * <p><b>The contract.</b> A sales-order line carries {@code qtyOrdered} in its own unit and
 * {@code qtyOrderedBase} in base units, and its {@code unitPriceAmount} is per ONE line unit. Every
 * running counter (fulfilled, invoiced, reserved) is in base units, because stock is. So:
 * <ul>
 *   <li>a delivery quantity is entered in the line's unit and converted to base for stock;</li>
 *   <li>an invoice raised from a delivery bills {@code base ÷ factor} line units at the line's
 *       per-unit price, with {@code qty_in_base} = the base quantity.</li>
 * </ul>
 * The factor is read off the order line itself ({@code qtyOrderedBase / qtyOrdered}) rather than
 * the product's current pack setup, so a pack factor edited after the order was taken cannot
 * re-price an order that is already agreed. A base-unit line has factor 1 and every conversion is
 * the identity.
 *
 * <p>Quantities are kept at scale 6, the scale of every quantity column in the schema.
 */
final class SalesLineUnits {

    /** Scale of every quantity column ({@code NUMERIC(19,6)}). */
    static final int QTY_SCALE = 6;

    private SalesLineUnits() {}

    /** Base units per ONE line unit (1 for a base-unit line, or a line that carries no quantity). */
    static BigDecimal factorToBase(SalesOrderLine sol) {
        return factor(sol.getQtyOrdered(), sol.getQtyOrderedBase());
    }

    /** @see #factorToBase(SalesOrderLine) */
    static BigDecimal factor(BigDecimal qtyOrdered, BigDecimal qtyOrderedBase) {
        if (qtyOrdered == null || qtyOrderedBase == null || qtyOrdered.signum() == 0) {
            return BigDecimal.ONE;
        }
        return qtyOrderedBase.divide(qtyOrdered, MathContext.DECIMAL64).stripTrailingZeros();
    }

    /** {@code qtyInLineUnit} expressed in base units. Multiplies before dividing to stay exact. */
    static BigDecimal toBase(SalesOrderLine sol, BigDecimal qtyInLineUnit) {
        BigDecimal ordered = sol.getQtyOrdered();
        BigDecimal orderedBase = sol.getQtyOrderedBase();
        if (ordered == null || orderedBase == null || ordered.signum() == 0) {
            return qtyInLineUnit.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        return qtyInLineUnit.multiply(orderedBase).divide(ordered, QTY_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code qtyBase} expressed in the line's own unit. */
    static BigDecimal toLineUnit(SalesOrderLine sol, BigDecimal qtyBase) {
        return toLineUnit(qtyBase, sol.getQtyOrdered(), sol.getQtyOrderedBase());
    }

    /** @see #toLineUnit(SalesOrderLine, BigDecimal) */
    static BigDecimal toLineUnit(BigDecimal qtyBase, BigDecimal qtyOrdered, BigDecimal qtyOrderedBase) {
        if (qtyOrdered == null || qtyOrderedBase == null || qtyOrderedBase.signum() == 0) {
            return qtyBase.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal inUnit = qtyBase.multiply(qtyOrdered)
                .divide(qtyOrderedBase, QTY_SCALE, RoundingMode.HALF_UP);
        // A positive base quantity never reads as nothing: every document line quantity column is
        // CHECKed > 0, and "0 Crate" for a real bottle would be wrong anyway.
        if (qtyBase.signum() > 0 && inUnit.signum() == 0) {
            return BigDecimal.ONE.movePointLeft(QTY_SCALE);
        }
        return inUnit;
    }

    /** Plain, trailing-zero-free rendering for user-facing messages ("2 Crate", not "2.000000"). */
    static String plain(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }

    /**
     * The share of a FIXED line discount that belongs to the base quantity now being invoiced
     * (SAL-17). Pro-rating is telescoped over the line's cumulative invoiced quantity: this invoice
     * gets {@code round(amount × after / ordered) − round(amount × before / ordered)}, so however
     * the order line is split across partial invoices, their discounts add back to exactly the
     * order line's discount — no shilling is lost or given twice.
     *
     * @param lineDiscountAmount the order line's fixed discount (null/zero → null, nothing to share)
     * @param qtyOrderedBase     the order line's ordered base quantity
     * @param invoicedBeforeBase base quantity already invoiced on the order line
     * @param qtyToInvoiceBase   base quantity this invoice bills
     * @param scale              minor units of the order currency
     */
    static BigDecimal proRatedLineDiscount(BigDecimal lineDiscountAmount, BigDecimal qtyOrderedBase,
                                           BigDecimal invoicedBeforeBase, BigDecimal qtyToInvoiceBase,
                                           int scale) {
        if (lineDiscountAmount == null || lineDiscountAmount.signum() <= 0) {
            return null;
        }
        if (qtyOrderedBase == null || qtyOrderedBase.signum() <= 0) {
            return lineDiscountAmount;
        }
        BigDecimal before = invoicedBeforeBase != null ? invoicedBeforeBase : BigDecimal.ZERO;
        BigDecimal after = before.add(qtyToInvoiceBase).min(qtyOrderedBase);
        before = before.min(qtyOrderedBase);
        BigDecimal cumAfter = lineDiscountAmount.multiply(after)
                .divide(qtyOrderedBase, scale, RoundingMode.HALF_UP);
        BigDecimal cumBefore = lineDiscountAmount.multiply(before)
                .divide(qtyOrderedBase, scale, RoundingMode.HALF_UP);
        return cumAfter.subtract(cumBefore).max(BigDecimal.ZERO);
    }
}

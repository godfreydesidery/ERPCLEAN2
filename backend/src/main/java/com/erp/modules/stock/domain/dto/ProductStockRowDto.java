package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * One product line shared by the Product List and Stock Value reports.
 *
 * <p>{@code buyingPrice} is the weighted-average cost of the stock in scope — {@code costValue}
 * divided by {@code quantityOnHand} — not a purchase-order price. It is what the stock on the shelf
 * actually cost, and it always reconciles with the {@code costValue} beside it even when two branches
 * carry different costs for the same product. At zero quantity there is nothing to divide, so the
 * last known stored cost is shown instead. {@code sellingPrice} comes from the company's default
 * price list (see {@code ProductStockReportQuery}).
 *
 * <p>Either price may be {@code null}, and that is a real answer rather than a zero: a product with
 * no valuation has never been costed, and a product with no price list entry has never been priced.
 * Rendering those as 0.00 would read as "free", so the reports show them blank and count them.
 */
public record ProductStockRowDto(
        String productCode,
        String productName,
        /**
         * Supplier shown against the line: the product's preferred supplier, or — when none is set —
         * the last supplier who actually delivered it on a RECEIVED goods receipt. The preferred
         * supplier is only settable one product at a time in the Product Master (the bulk import
         * sheet has no Supplier column), so without the fallback a bulk-loaded catalogue shows no
         * supplier anywhere and the supplier-wise report returns nothing. Null when neither is known.
         */
        String supplierName,
        BigDecimal quantityOnHand,
        /** Implied weighted-average unit cost; null when the product has never been valued. */
        BigDecimal buyingPrice,
        /** The on-hand value carried on stock_on_hand. Null when unvalued. */
        BigDecimal costValue,
        /** Unit selling price from the default price list; null when never priced. */
        BigDecimal sellingPrice,
        /** quantity x sellingPrice. Null when unpriced — never silently zero. */
        BigDecimal saleValue,
        /**
         * True when the product is no longer in the catalogue (archived, inactive or made
         * non-stockable) but still holds stock. Always false on the Product List, which lists the
         * catalogue only; on the Stock Value report these rows are what keep the cost total tied to
         * the GL-reconciled inventory valuation, so the page marks them rather than hiding them.
         */
        boolean discontinued) {
}

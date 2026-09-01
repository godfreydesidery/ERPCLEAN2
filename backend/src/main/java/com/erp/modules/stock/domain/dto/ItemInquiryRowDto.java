package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * One item as answered by the counter lookup (K-2026-08-30 #3: "to include code, description,
 * cost, selling and available quantity").
 *
 * <p>Money and quantity are {@code BigDecimal}, which Jackson serialises as JSON numbers — but a
 * {@code null} here is a REAL answer, not a zero, exactly as on {@link ProductStockRowDto}: a null
 * {@code buyingPrice} means nobody has ever costed the item, a null {@code sellingPrice} means it
 * has never been priced. Rendering either as 0.00 tells a shopkeeper the goods are free.
 *
 * @param productUid    so the screen can open the item itself from a result row
 * @param productCode   the code the shop actually calls it by
 * @param productName   the description
 * @param unitName      the base unit the quantity is expressed in — "12" means nothing on its own
 * @param quantityOnHand on-hand in the base unit, summed over the branch(es) in scope; zero is a
 *                      real answer here (the item exists and none is left)
 * @param stockable     false for a service or other non-stocked line; the quantity is then
 *                      meaningless rather than zero, and the screen says so
 * @param buyingPrice   implied unit cost of the stock in scope, or null when never costed —
 *                      ALWAYS null when the caller may not see cost, which {@link ItemInquiryDto}
 *                      distinguishes with its {@code costVisible} flag
 * @param sellingPrice  unit price from the company's default price list, or null when unpriced
 */
public record ItemInquiryRowDto(
        String     productUid,
        String     productCode,
        String     productName,
        String     unitName,
        BigDecimal quantityOnHand,
        boolean    stockable,
        BigDecimal buyingPrice,
        BigDecimal sellingPrice
) {
}

package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.UnitPriceStatus;
import java.math.BigDecimal;

/**
 * One row of a batch price read — the price the server would charge for {@code productUid} when
 * sold in {@code unitUid}.
 *
 * <p>{@code amount} is the price for ONE {@code unitUid} (not per base unit): for a bulk pack it is
 * either the explicit pack price or the base price times the pack's factor-to-base — exactly what
 * the sales services snapshot onto a line (ADR-0048 D-1/D-2).
 *
 * <p>{@code amount}/{@code currency} are null unless {@code status} is
 * {@link UnitPriceStatus#RESOLVED}; clients must render "no price" rather than falling back to a
 * locally-computed guess.
 *
 * @param productUid   the product priced
 * @param unitUid      the unit the amount is expressed in — the requested unit, or the product's
 *                     own base unit when the request did not name one
 * @param amount       resolved price for one {@code unitUid}; null when not RESOLVED
 * @param currency     ISO 4217 alpha-3 code; null when not RESOLVED
 * @param vatInclusive true when the amount is GROSS (came from a VAT-inclusive price list);
 *                     false when NET. Always false when not RESOLVED.
 * @param status       why the amount is present or absent
 */
public record ResolvedUnitPriceDto(
        String productUid,
        String unitUid,
        BigDecimal amount,
        String currency,
        boolean vatInclusive,
        UnitPriceStatus status) {

    /** Row for a product that could not be priced — amount/currency omitted. */
    public static ResolvedUnitPriceDto unpriced(String productUid, String unitUid,
                                                UnitPriceStatus status) {
        return new ResolvedUnitPriceDto(productUid, unitUid, null, null, false, status);
    }
}

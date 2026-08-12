package com.erp.modules.sales.service;

import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;
import com.erp.modules.products.service.PriceResolutionService;
import java.math.BigDecimal;

/**
 * Shared line-pricing rule for the three sales documents (quotation, sales order, sales invoice).
 *
 * <p><b>The defect this fixes.</b> All three documents called the throwing
 * {@link PriceResolutionService#resolveUnitListPrice} <em>before</em> looking at the unit price the
 * caller had typed. On a company with no price list — every company on day one — that throw fired
 * first and the stated price was never read: the field existed, was accepted by the API, and could
 * not influence anything. Nothing could be sold at all.
 *
 * <p><b>The rule.</b> A price list still wins when one covers the line: an explicit price is an
 * override of a known list price, and the list price is what gets snapshotted as {@code listPrice}
 * so the discount off list stays visible and auditable. Only when the catalogue genuinely cannot
 * price the line does the stated price stand in as the line's list price. A caller who states a
 * price is not guessing — refusing them a sale when there is no catalogue to consult is the wrong
 * answer.
 *
 * <p>Deliberately a static helper over the injected {@link PriceResolutionService} rather than a
 * bean: the three services already hold that dependency, so no constructor change ripples out into
 * their wiring or their tests.
 */
final class LinePriceResolver {

    private LinePriceResolver() {
        // Static helper.
    }

    /**
     * List price for a line, tolerating an unpriceable catalogue when the caller states a price.
     *
     * @param prices            the resolver (already injected into every sales service)
     * @param companyId         company of the document being priced
     * @param productId         product on the line
     * @param unitId            sale unit on the line
     * @param unitPriceOverride the price the caller typed, or {@code null} when they typed none
     * @return the resolved list price plus its VAT-inclusive stance; when the catalogue has no
     *         price and a price was stated, the stated price with an EXCLUSIVE stance (there is no
     *         price list to declare otherwise, and exclusive is the system-wide default)
     * @throws IllegalArgumentException when the catalogue cannot price the line and no price was
     *         stated — the message names both remedies
     * @throws IllegalStateException when the unit is neither the product's base unit nor a
     *         configured pack unit
     */
    static UnitListPriceDto resolve(PriceResolutionService prices, Long companyId, Long productId,
                                    Long unitId, BigDecimal unitPriceOverride) {
        if (unitPriceOverride == null) {
            // Unchanged path: no stated price, so an unpriceable product is a genuine refusal.
            return prices.resolveUnitListPrice(companyId, productId, unitId);
        }
        // Non-throwing twin: "no price row" must not blow up the transaction on the one path where
        // the caller has already supplied the answer.
        UnitPriceQuoteResult quote = prices.findUnitListPriceQuote(companyId, productId, unitId);
        return switch (quote.status()) {
            case RESOLVED -> {
                UnitPriceQuoteDto price = quote.price();
                yield new UnitListPriceDto(price.amount(), price.vatInclusive());
            }
            case NO_PRICE -> new UnitListPriceDto(unitPriceOverride, false);
            // A stated price cannot rescue a unit the product is not sold in. Re-ask the throwing
            // facade so the wording of that refusal lives in exactly one place; it always throws,
            // and the trailing throw only satisfies the compiler.
            case UNIT_NOT_APPLICABLE -> {
                prices.resolveUnitListPriceQuote(companyId, productId, unitId);
                throw new IllegalStateException("This unit is not valid for this product.");
            }
        };
    }
}

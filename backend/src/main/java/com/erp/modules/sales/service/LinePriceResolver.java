package com.erp.modules.sales.service;

import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.dto.SellingPriceQuery;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;
import com.erp.modules.products.service.PriceResolutionService;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Shared line-pricing rule for the three sales documents (quotation, sales order, sales invoice) —
 * and, through the invoice, every POS sale.
 *
 * <p><b>Who the price is for (PRD-01 / SAL-04 / POS-12 / LSF-02).</b> Every line is priced for the
 * document's customer: their contract price, else their default price list, else the company's
 * default list, else the legacy lowest-id row — see
 * {@link PriceResolutionService#findSellingPriceQuote}. Lines used to be priced by whichever price
 * row was created first, for every customer, so a wholesale customer was charged retail.
 *
 * <p><b>The defect this class first fixed.</b> All three documents called the throwing resolver
 * <em>before</em> looking at the unit price the caller had typed. On a company with no price list —
 * every company on day one — that throw fired first and the stated price was never read. Nothing
 * could be sold at all.
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
     * The document's customer, loaded through the company-scoped finder, for pricing. Null when the
     * document has no customer or the id does not resolve inside {@code companyId} — the line is then
     * priced as a walk-in sale rather than refused: pricing is not the place to police a customer
     * reference the document itself already validated.
     */
    static Customer pricingCustomer(CustomerRepository customers, Long companyId, Long customerId) {
        if (customerId == null) {
            return null;
        }
        return customers.findByCompanyIdAndId(companyId, customerId).orElse(null);
    }

    /**
     * The pricing question for one line of a document.
     *
     * @param companyId the document's company
     * @param productId product on the line
     * @param unitId    sale unit on the line
     * @param customer  the document's customer, loaded company-scoped by the caller; null when the
     *                  document has none (or it could not be loaded) — the walk-in question
     * @param currency  the document's currency code
     * @param quantity  the line quantity in {@code unitId}
     */
    static SellingPriceQuery query(Long companyId, Long productId, Long unitId, Customer customer,
                                   String currency, BigDecimal quantity) {
        return new SellingPriceQuery(companyId, productId, unitId,
                customer == null ? null : customer.getId(),
                customer == null ? null : customer.getDefaultPriceListId(),
                currency, quantity, LocalDate.now());
    }

    /**
     * List price for a line, tolerating an unpriceable catalogue when the caller states a price.
     *
     * @param prices            the resolver (already injected into every sales service)
     * @param query             the line's pricing question — see {@link #query}
     * @param unitPriceOverride the price the caller typed, or {@code null} when they typed none
     * @return the resolved price plus its VAT-inclusive stance and source; when the catalogue has no
     *         price and a price was stated, the stated price with an EXCLUSIVE stance (there is no
     *         price list to declare otherwise, and exclusive is the system-wide default)
     * @throws IllegalArgumentException when the catalogue cannot price the line and no price was
     *         stated — the message names both remedies
     * @throws IllegalStateException when the unit is neither the product's base unit nor a
     *         configured pack unit
     */
    static UnitListPriceDto resolve(PriceResolutionService prices, SellingPriceQuery query,
                                    BigDecimal unitPriceOverride) {
        if (unitPriceOverride == null) {
            // No stated price, so an unpriceable product is a genuine refusal.
            return prices.resolveSellingPrice(query);
        }
        // Non-throwing twin: "no price row" must not blow up the transaction on the one path where
        // the caller has already supplied the answer.
        UnitPriceQuoteResult quote = prices.findSellingPriceQuote(query);
        return switch (quote.status()) {
            case RESOLVED -> {
                UnitPriceQuoteDto price = quote.price();
                yield new UnitListPriceDto(price.amount(), price.vatInclusive(), price.source());
            }
            case NO_PRICE -> new UnitListPriceDto(unitPriceOverride, false);
            // A stated price cannot rescue a unit the product is not sold in. Re-ask the throwing
            // facade so the wording of that refusal lives in exactly one place; it always throws,
            // and the trailing throw only satisfies the compiler.
            case UNIT_NOT_APPLICABLE -> {
                prices.resolveSellingPrice(query);
                throw new IllegalStateException("This unit is not valid for this product.");
            }
        };
    }
}

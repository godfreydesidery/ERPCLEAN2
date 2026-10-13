package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Batch price-read request: "what does the server charge for these products, in this unit?".
 *
 * <p>Exists so a POS search page, the Flutter till and the quotation screen ask the SERVER for the
 * price instead of each approximating the resolution rules locally (they disagreed with each other
 * and with what the invoice actually posted). One request covers a whole result page.
 *
 * @param productUids the products to price; unknown uids are ignored rather than failing the batch
 * @param unitUid     optional — the unit every price should be expressed in. When null each product
 *                    is priced in its OWN base unit (the common POS case, where each row has a
 *                    different base unit).
 * @param customerUid optional (PRD-01) — price for this customer: their contract prices and their
 *                    default price list apply exactly as they will on the invoice. Null asks the
 *                    walk-in question, which is what every client that predates the field gets.
 * @param currency    optional (PRD-01) — the document currency; a price row in it is preferred.
 *                    Null = any currency, the pre-existing behaviour.
 */
public record ResolveUnitPricesRequest(

        @NotNull(message = "Provide the products to price.")
        @Size(max = 200, message = "Ask for at most 200 products in one price request.")
        List<String> productUids,

        String unitUid,

        @Size(max = 26, message = "The customer reference is not valid.")
        String customerUid,

        @Size(max = 3, message = "Use a three-letter currency code.")
        String currency) {

    /** Pre-PRD-01 shape — the walk-in question in any currency. */
    public ResolveUnitPricesRequest(List<String> productUids, String unitUid) {
        this(productUids, unitUid, null, null);
    }
}

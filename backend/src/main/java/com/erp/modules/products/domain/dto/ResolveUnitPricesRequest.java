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
 */
public record ResolveUnitPricesRequest(

        @NotNull(message = "Provide the products to price.")
        @Size(max = 200, message = "Ask for at most 200 products in one price request.")
        List<String> productUids,

        String unitUid) {
}

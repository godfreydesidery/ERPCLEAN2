package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.PurchaseCostSuggestionDto;
import java.util.Optional;

/**
 * Suggests the unit cost for a purchase-order line from prices the system already holds
 * (SAM client feedback 2026-08).
 *
 * <p>Read-only. Purely advisory: nothing is written, and the suggestion is a default the buyer
 * overwrites freely — it is never applied to a line by this service.
 */
public interface PurchaseCostSuggestionService {

    /**
     * Suggests a unit cost for a product/unit about to be added to a purchase order, using the
     * first source that has a price for that supplier:
     * <ol>
     *   <li>the last price the PO's supplier quoted for this product/unit;</li>
     *   <li>the last price actually ordered from that supplier for this product/unit;</li>
     *   <li>the product master's cost (buying) price — only when the requested unit is the
     *       product's base unit, which is the unit that price is expressed in.</li>
     * </ol>
     *
     * <p>The moving-average cost on hand is deliberately NOT a source: a purchase price derived from
     * the average, received back into the average, would drift with no external anchor.
     *
     * @return the suggestion, or {@link Optional#empty()} when no source has a price — callers must
     *         leave the cost field blank rather than substituting zero
     */
    Optional<PurchaseCostSuggestionDto> suggestUnitCost(String purchaseOrderUid,
                                                        String productUid,
                                                        String unitUid);

    /**
     * The same suggestion for a direct goods receipt, which has no purchase order to read the
     * company and supplier from — the storekeeper picks both on the screen (K-2026-08-30 #4:
     * "have items pick cost price already existing in the system, not having to input the cost
     * price all the time").
     *
     * <p>Identical fallback chain and identical guarantees: advisory only, nothing written, and
     * {@link Optional#empty()} rather than a zero when no source has a price.
     *
     * @param companyUid  the company the delivery belongs to — scope is asserted against it
     * @param supplierUid the supplier who delivered; the first two sources are supplier-specific,
     *                    so a blank one falls straight through to the product master's cost
     */
    Optional<PurchaseCostSuggestionDto> suggestUnitCostForDirectReceipt(String companyUid,
                                                                        String supplierUid,
                                                                        String productUid,
                                                                        String unitUid);
}

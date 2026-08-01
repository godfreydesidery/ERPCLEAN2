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
}

package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolveUnitPricesRequest;
import com.erp.modules.products.domain.dto.ResolvedUnitPriceDto;
import java.util.List;

/**
 * Batch, unit-aware price READ for pricing screens (POS search page, the Flutter till, the
 * quotation picker).
 *
 * <p>Why it exists: {@link PriceResolutionService#resolveUnitListPrice} is what sales documents
 * actually charge, but it was exposed on no controller — so every client re-implemented the rules
 * (base price, pack overrides, factor-to-base, VAT-inclusive stance) and all three disagreed with
 * the server. This service is a thin batch wrapper over that ONE resolver: it resolves uids, applies
 * tenant scope, and turns per-product failures into a status on the row instead of an error for the
 * whole page. It contains NO pricing rules of its own.
 */
public interface UnitPriceLookupService {

    /**
     * Resolve prices for a set of products in one call.
     *
     * <p>Scope is the caller's ACTIVE company (from the request context), never a caller-supplied
     * company id. Product uids outside it — and uids that do not exist — are omitted from the
     * result rather than failing the batch. Products that exist but cannot be priced come back with
     * a null amount and an explanatory
     * {@link com.erp.modules.products.domain.enums.UnitPriceStatus}.
     *
     * @param request the product uids plus an optional unit uid every price is expressed in
     * @return one row per resolvable product, in the order the uids were requested (duplicates
     *         collapsed); never null
     */
    List<ResolvedUnitPriceDto> resolve(ResolveUnitPricesRequest request);
}

package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.ResolvedPriceDto;

/**
 * Deterministic single-price resolver (ADR-0029 D-6, FR-SD-12).
 *
 * <p>Priority: customer-specific price &gt; promotion &gt; quantity-break tier &gt; list price &gt; NONE.
 * No stacking: the first matching rule wins. Called by POS sale and SO line creation.
 *
 * <p>All inputs are database IDs (Long); the caller resolves UIDs before invoking.
 */
public interface PriceResolutionService {

    /**
     * Resolve the best price for a single product line.
     *
     * @param request context carrying companyId, customerId, productId, quantity, businessDate,
     *                priceListId
     * @return the resolved price (source + amount); never null — NONE is returned when no
     *         price is configured.
     */
    ResolvedPriceDto resolve(ResolvePriceRequest request);
}

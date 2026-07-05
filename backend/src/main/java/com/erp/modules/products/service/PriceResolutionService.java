package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.ResolvedPriceDto;
import com.erp.modules.products.domain.dto.UnitListPriceDto;

/**
 * Deterministic single-price resolver (ADR-0029 D-6, FR-SD-12).
 *
 * <p>Priority: customer-specific price &gt; promotion &gt; quantity-break tier &gt; list price &gt; NONE.
 * No stacking: the first matching rule wins. NOT currently wired into any caller (ADR-0048 D-1
 * keeps it dead pending a separate activation decision) — {@link #resolveUnitListPrice} is the
 * narrow, unit-aware method sales actually calls.
 *
 * <p>All inputs are database IDs (Long); the caller resolves UIDs before invoking.
 */
public interface PriceResolutionService {

    /**
     * Resolve the best price for a single product line.
     *
     * @param request context carrying companyId, customerId, productId, quantity, businessDate,
     *                priceListId, unitId
     * @return the resolved price (source + amount); never null — NONE is returned when no
     *         price is configured.
     */
    ResolvedPriceDto resolve(ResolvePriceRequest request);

    /**
     * Unit-aware list-price resolution (ADR-0048 D-1/D-2) — the narrow method that replaces the
     * three sales modules' identical {@code resolveListPrice} copies. Resolution for
     * {@code (product, unit)}:
     * <ol>
     *   <li>An explicit per-unit {@code product_prices} row for {@code unit} exists → use it
     *       (non-linear override).</li>
     *   <li>Else the base-unit row ({@code unit_id IS NULL}) {@code amount × factor_to_base(unit)}
     *       — factor is 1 when {@code unit} is the product's base unit.</li>
     *   <li>{@code unit} is neither the base nor a configured {@code product_bulk_pack} unit →
     *       rejected, mirroring {@code computeQtyInBase}'s guard.</li>
     * </ol>
     *
     * <p>Ignores price list and customer/promo/tier — same blast radius as the live path it
     * replaces (a separate follow-up may make list selection price-list-aware).
     *
     * <p>ADR-0056: also carries whether the resolved amount came from a VAT-inclusive price list
     * ({@code price_lists.price_includes_vat}) — a pack override inherits its OWN list's flag,
     * independent of the base row's list.
     *
     * @param companyId defence-in-depth company check (the product already implies one company)
     * @param productId the product being priced
     * @param unitId    the line's selected unit
     * @return the resolved per-line-unit price amount + VAT-inclusive stance; never null
     * @throws IllegalArgumentException if the product has no price configured
     * @throws IllegalStateException    if {@code unitId} is neither the base unit nor a configured
     *                                   bulk-pack unit
     */
    UnitListPriceDto resolveUnitListPrice(Long companyId, Long productId, Long unitId);
}

package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.ResolvedPriceDto;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;

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

    /**
     * Same resolution as {@link #resolveUnitListPrice}, additionally carrying the currency of the
     * price row the amount came from.
     *
     * <p>{@code resolveUnitListPrice} delegates here — there is exactly ONE implementation of the
     * unit-aware rules. Sales documents take currency from their header, so they use the narrower
     * method; the batch price-read API (POS search page, till, quotation picker) renders a price
     * before any document header exists and needs the currency with it.
     *
     * @param companyId defence-in-depth company check (the product already implies one company)
     * @param productId the product being priced
     * @param unitId    the unit the price should be expressed in
     * @return amount + currency + VAT-inclusive stance; never null
     * @throws IllegalArgumentException if the product has no price configured
     * @throws IllegalStateException    if {@code unitId} is neither the base unit nor a configured
     *                                   bulk-pack unit
     */
    UnitPriceQuoteDto resolveUnitListPriceQuote(Long companyId, Long productId, Long unitId);

    /**
     * Non-throwing twin of {@link #resolveUnitListPriceQuote} — same rules, same numbers, but
     * "this product cannot be priced" comes back as a {@link UnitPriceQuoteResult} instead of an
     * exception.
     *
     * <p><b>Use this from any caller that prices MORE THAN ONE product in a request.</b> Both
     * methods run inside this service's {@code @Transactional} proxy; an exception crossing that
     * boundary marks the caller's transaction rollback-only even when the caller catches it, so a
     * batch that swallowed the exception still died on COMMIT with
     * {@code UnexpectedRollbackException} (HTTP 500 for the whole page). Returning the outcome as a
     * value keeps the transaction clean.
     *
     * <p>A missing product is still an exception ({@link
     * com.erp.platform.common.api.NotFoundException}): that is a caller bug, not a per-row
     * pricing condition.
     *
     * @param companyId defence-in-depth company check (the product already implies one company)
     * @param productId the product being priced
     * @param unitId    the unit the price should be expressed in
     * @return {@code RESOLVED} + the quote, or {@code NO_PRICE} / {@code UNIT_NOT_APPLICABLE} with
     *         no quote; never null
     */
    UnitPriceQuoteResult findUnitListPriceQuote(Long companyId, Long productId, Long unitId);
}

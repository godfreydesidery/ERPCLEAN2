package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.ResolvedPriceDto;
import com.erp.modules.products.domain.dto.SellingPriceQuery;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;

/**
 * Deterministic single-price resolver (ADR-0029 D-6, FR-SD-12).
 *
 * <p><b>What sales actually charges</b> is {@link #findSellingPriceQuote} (PRD-01): a customer's
 * contract price, else the list price chosen customer list &gt; company default list &gt; legacy
 * lowest-id row, unit-aware (ADR-0048) and skipping archived / out-of-date lists. The narrow
 * three-argument methods ask the same question for a walk-in.
 *
 * <p>{@link #resolve} (customer price &gt; promotion &gt; quantity-break tier &gt; list price, ADR-0029)
 * is still NOT wired into any caller: promotions and quantity tiers are saved but not applied to
 * sales (PRD-02 — the Pricing Rules screen says so). Activating them changes walk-in prices the
 * deployed till cannot preview and has to pass the discount ceiling, so it needs its own decision.
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
     * <p>Asks the WALK-IN question of {@link #findSellingPriceQuote}: no customer, any currency,
     * today — so the company's default price list applies (PRD-01), and archived or out-of-date
     * lists are skipped. Customer prices, promotions and tiers do not apply here.
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

    /**
     * The selling price for one line — the single resolution every sales document, the POS sale
     * and the batch price read go through (PRD-01 / SAL-04 / POS-12 / LSF-02). Non-throwing, for the
     * same transaction reason as {@link #findUnitListPriceQuote}.
     *
     * <p>Resolution, first hit wins:
     * <ol>
     *   <li><b>Customer contract price</b> — an ACTIVE {@code customer_prices} row for
     *       ({@code customerId}, product) valid on the business date, in the document currency, whose
     *       minimum quantity (base units) the line meets. Stored per base unit, so a pack line is
     *       charged {@code amount × factor_to_base}. Its VAT stance is its own list's when it names
     *       one, else the stance of the list price below (it is entered on the same basis as the
     *       prices that customer otherwise sees).</li>
     *   <li><b>List price</b>, choosing the list in this order:
     *     <ol>
     *       <li>the customer's default price list ({@code customerPriceListId});</li>
     *       <li>the company's default list ({@code is_default}; lowest id if several are flagged);</li>
     *       <li>fallback: the lowest-id price row of any usable list — exactly the pre-PRD-01
     *           behaviour, so a company that never set a default sees no change.</li>
     *     </ol>
     *     Only rows on an ACTIVE list whose validity window (and the row's own window) covers the
     *     business date count. On the chosen list an explicit per-unit (pack) row wins, else the base
     *     row × {@code factor_to_base} (ADR-0048). A list with no row for the product falls through to
     *     the next one. Rows in the document currency are tried first across all three tiers; only
     *     when NO usable row is in that currency are rows in other currencies considered (the
     *     pre-existing tolerance for single-currency shops whose rows carry another code).</li>
     * </ol>
     *
     * @return {@code RESOLVED} with amount/currency/VAT stance/source/list, else {@code NO_PRICE}
     *         or {@code UNIT_NOT_APPLICABLE}; never null
     * @throws com.erp.platform.common.api.NotFoundException if the product is not in the company
     */
    UnitPriceQuoteResult findSellingPriceQuote(SellingPriceQuery query);

    /**
     * Throwing twin of {@link #findSellingPriceQuote}, in the shape the sales documents snapshot.
     *
     * @throws IllegalArgumentException if nothing can price the line
     * @throws IllegalStateException    if the unit is neither the base unit nor a configured pack
     */
    UnitListPriceDto resolveSellingPrice(SellingPriceQuery query);
}

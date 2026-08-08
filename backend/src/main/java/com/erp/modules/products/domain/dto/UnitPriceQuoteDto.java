package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;

/**
 * Full result of
 * {@link com.erp.modules.products.service.PriceResolutionService#resolveUnitListPriceQuote} — the
 * resolved per-unit list price, the currency it is expressed in, and whether it came from a
 * VAT-inclusive price list ({@code price_lists.price_includes_vat}).
 *
 * <p>Superset of {@link UnitListPriceDto}, which stays as-is for the three sales services that only
 * ever need {@code amount} + {@code vatInclusive} (they take currency from the document header).
 * The batch price-read API needs the currency too, because a POS/quotation screen renders a price
 * before any document header exists.
 *
 * <p>{@code amount} is the resolved unit price as stored — a GROSS (VAT-inclusive) amount when
 * {@code vatInclusive} is {@code true}, a NET (VAT-exclusive) amount otherwise (ADR-0056 D-5).
 *
 * @param amount       resolved price for one of the requested units
 * @param currency     ISO 4217 alpha-3 code of the price row the amount came from
 * @param vatInclusive true when the source price list stores VAT-inclusive prices
 */
public record UnitPriceQuoteDto(BigDecimal amount, String currency, boolean vatInclusive) {
}

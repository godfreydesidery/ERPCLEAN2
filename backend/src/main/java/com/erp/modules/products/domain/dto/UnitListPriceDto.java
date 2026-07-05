package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;

/**
 * Result of {@link com.erp.modules.products.service.PriceResolutionService#resolveUnitListPrice}
 * (ADR-0056): the resolved per-unit list price plus whether it was sourced from a VAT-inclusive
 * price list ({@code price_lists.price_includes_vat}).
 *
 * <p>{@code amount} is the resolved unit price as stored — a GROSS (VAT-inclusive) amount when
 * {@code vatInclusive} is {@code true}, a NET (VAT-exclusive) amount otherwise. Callers snapshot
 * both onto the sales line ({@code unit_price_amount}, {@code price_inclusive}) so the totals
 * calculator can strip VAT correctly per line (ADR-0056 D-5).
 */
public record UnitListPriceDto(BigDecimal amount, boolean vatInclusive) {
}

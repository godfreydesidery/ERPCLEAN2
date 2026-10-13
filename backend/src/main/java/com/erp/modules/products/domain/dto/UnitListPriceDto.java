package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.PriceSource;
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
 *
 * <p>{@code source} says which rule priced the line ({@code LIST_PRICE} or
 * {@code CUSTOMER_PRICE}); it is recorded in the line's audit detail, never used in the money.
 */
public record UnitListPriceDto(BigDecimal amount, boolean vatInclusive, PriceSource source) {

    /** A list price — the pre-PRD-01 shape. */
    public UnitListPriceDto(BigDecimal amount, boolean vatInclusive) {
        this(amount, vatInclusive, PriceSource.LIST_PRICE);
    }
}

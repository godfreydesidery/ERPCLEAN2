package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.PriceSource;
import java.math.BigDecimal;

/**
 * Result of the single price resolver (ADR-0029 D-7, FR-SD-12).
 *
 * <p>{@code unitPriceAmount} is the resolved unit price (null = NONE, caller blocks the line).
 * {@code ruleDiscountAmount} and {@code ruleDiscountPercent} are set when the resolver applied a
 * promotion discount — the caller maps them onto the line's discount fields.
 * {@code priceSource} identifies which rule resolved (for line-level audit).
 * {@code vatInclusive} (ADR-0056): whether {@code unitPriceAmount} was sourced from a VAT-inclusive
 * price list — carried through even though this path is not currently wired into any caller
 * ({@link com.erp.modules.products.service.PriceResolutionService#resolveUnitListPrice} is the
 * live method), so it stays honest if it is ever activated.
 */
public record ResolvedPriceDto(
        BigDecimal unitPriceAmount,
        BigDecimal ruleDiscountAmount,
        BigDecimal ruleDiscountPercent,
        String currency,
        PriceSource priceSource,
        boolean vatInclusive
) {
    public static ResolvedPriceDto none() {
        return new ResolvedPriceDto(null, null, null, null, PriceSource.NONE, false);
    }

    public static ResolvedPriceDto listPrice(BigDecimal amount, String currency, boolean vatInclusive) {
        return new ResolvedPriceDto(amount, null, null, currency, PriceSource.LIST_PRICE, vatInclusive);
    }

    public static ResolvedPriceDto tier(BigDecimal amount, String currency, boolean vatInclusive) {
        return new ResolvedPriceDto(amount, null, null, currency, PriceSource.TIER, vatInclusive);
    }

    public static ResolvedPriceDto customerPrice(BigDecimal amount, String currency, boolean vatInclusive) {
        return new ResolvedPriceDto(amount, null, null, currency, PriceSource.CUSTOMER_PRICE, vatInclusive);
    }
}

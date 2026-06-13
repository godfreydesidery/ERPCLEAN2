package com.erp.platform.common.money;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable result of a {@link CurrencyConversionService} call (ADR-0036 D-1).
 *
 * <ul>
 *   <li>{@code baseAmount} — face × rate, rounded HALF_UP to the base currency's minor units.</li>
 *   <li>{@code rate}       — the rate used (units of base per 1 unit of foreign; scale 8).</li>
 *   <li>{@code rateAt}     — when the rate was stamped (either the effective-date lookup instant
 *                            or {@code Instant.now()} on the identity short-circuit).</li>
 * </ul>
 *
 * <p>On the identity short-circuit ({@code from == base}): {@code baseAmount == faceAmount},
 * {@code rate == BigDecimal.ONE}.
 */
public record ConvertedAmount(BigDecimal baseAmount, BigDecimal rate, Instant rateAt) {

    /** Convenience factory for the identity (from==base) short-circuit. */
    public static ConvertedAmount identity(BigDecimal faceAmount) {
        return new ConvertedAmount(faceAmount, BigDecimal.ONE, Instant.now());
    }
}

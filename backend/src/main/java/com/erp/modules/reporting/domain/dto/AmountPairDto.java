package com.erp.modules.reporting.domain.dto;

import java.math.BigDecimal;

/**
 * A (current, comparative) amount pair — every statement figure carries both columns (ADR-0018 D-1).
 * Amounts are BigDecimal; money is base-currency only (BR-REP-09, ADR-0005 D-4).
 */
public record AmountPairDto(BigDecimal current, BigDecimal comparative) {

    public static AmountPairDto zero() {
        return new AmountPairDto(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static AmountPairDto of(BigDecimal current, BigDecimal comparative) {
        return new AmountPairDto(
                current     == null ? BigDecimal.ZERO : current,
                comparative == null ? BigDecimal.ZERO : comparative);
    }
}

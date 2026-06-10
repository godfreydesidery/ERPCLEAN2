package com.erp.modules.reporting.domain.dto;

/**
 * The structural self-check bar for each financial statement (ADR-0018 D-5/D-6/D-7).
 *
 * <p>{@code ties=true} means the statement balances; {@code ties=false} is a data-integrity alarm.
 * Never plugged — Reporting is read-only (BR-REP-08).
 */
public record ReconciliationDto(
        String        label,
        AmountPairDto computed,
        AmountPairDto expected,
        AmountPairDto difference,
        boolean       ties
) {
    /**
     * Builds a reconciliation dto and computes whether they tie (compareTo == 0, BigDecimal-exact).
     */
    public static ReconciliationDto of(String label, AmountPairDto computed, AmountPairDto expected) {
        var diffCurrent     = computed.current().subtract(expected.current());
        var diffComparative = computed.comparative().subtract(expected.comparative());
        var difference      = new AmountPairDto(diffCurrent, diffComparative);
        boolean ties        = diffCurrent.compareTo(java.math.BigDecimal.ZERO) == 0
                           && diffComparative.compareTo(java.math.BigDecimal.ZERO) == 0;
        return new ReconciliationDto(label, computed, expected, difference, ties);
    }
}

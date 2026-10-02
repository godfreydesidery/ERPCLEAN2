package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Current AR balance for a customer (ADR-0014 D-9).
 *
 * <p>{@code balance} is a BASE-currency total ({@code currency} = company base currency):
 * Σ open items − Σ on-account receipts − Σ unapplied credit notes, where a base-currency document
 * counts at face and a foreign-currency document counts at its STORED base value — but only when
 * that value is reliable. A foreign-currency document still carrying the V62 back-fill
 * ({@code fx_rate = 1}) is left out of {@code balance} and listed in {@code unconverted} in its own
 * currency instead (owner ruling 2026-10-02: "per currency, convert only reliable rows").
 *
 * <p>Sales reads this DTO at finalise to check the credit limit (D-10, FR-AR-19); it converts the
 * {@code unconverted} amounts at today's rate for that check.
 */
public record ArBalanceDto(
        Long companyId,
        Long customerId,
        BigDecimal balance,
        String currency,
        List<ArUnconvertedAmountDto> unconverted
) {
    public ArBalanceDto {
        unconverted = unconverted == null ? List.of() : List.copyOf(unconverted);
    }

    /** Base-only balance (no unconverted foreign amounts). */
    public ArBalanceDto(Long companyId, Long customerId, BigDecimal balance, String currency) {
        this(companyId, customerId, balance, currency, List.of());
    }
}

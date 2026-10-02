package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Current AP balance for a supplier.
 *
 * <p>{@code outstandingBalance} is a BASE-currency total ({@code currency} = company base
 * currency): Σ open bills − Σ on-account payments − Σ unapplied debit notes, where a base-currency
 * document counts at face and a foreign-currency document counts at its STORED base value — but
 * only when that value is reliable. A foreign-currency document still carrying the V62 back-fill
 * ({@code fx_rate = 1}) is left out and listed in {@code unconverted} in its own currency instead
 * (owner ruling 2026-10-02: "per currency, convert only reliable rows").
 */
public record ApBalanceDto(
        Long companyId,
        Long supplierId,
        BigDecimal outstandingBalance,
        String currency,
        List<ApUnconvertedAmountDto> unconverted
) {
    public ApBalanceDto {
        unconverted = unconverted == null ? List.of() : List.copyOf(unconverted);
    }

    /** Base-only balance (no unconverted foreign amounts). */
    public ApBalanceDto(Long companyId, Long supplierId, BigDecimal outstandingBalance,
                        String currency) {
        this(companyId, supplierId, outstandingBalance, currency, List.of());
    }
}

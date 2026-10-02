package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reconciliation read: sub-ledger total vs GL 1200 balance (ADR-0014 D-8, FR-AR-18).
 * A non-zero difference is a finance-grade defect.
 *
 * <p>All three figures are in the company BASE currency. {@code subLedgerTotal} counts only rows
 * with a reliable base value; foreign-currency rows still carrying the V62 back-fill
 * ({@code fx_rate = 1}) are EXCLUDED from it and from {@code difference}, and listed per currency
 * in {@code unconverted} so the comparison is honest about what it leaves out
 * (owner ruling 2026-10-02).
 */
public record ArReconciliationDto(
        Long companyId,
        BigDecimal subLedgerTotal,
        BigDecimal glControlBalance,
        BigDecimal difference,
        String currency,
        List<ArUnconvertedAmountDto> unconverted
) {
    public ArReconciliationDto {
        unconverted = unconverted == null ? List.of() : List.copyOf(unconverted);
    }

    public ArReconciliationDto(Long companyId, BigDecimal subLedgerTotal,
                               BigDecimal glControlBalance, BigDecimal difference,
                               String currency) {
        this(companyId, subLedgerTotal, glControlBalance, difference, currency, List.of());
    }
}

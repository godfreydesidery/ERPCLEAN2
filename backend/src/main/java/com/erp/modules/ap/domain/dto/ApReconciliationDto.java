package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AP reconciliation read: sub-ledger total vs GL 2100 balance (ADR-0015 D-7/D-8).
 * A non-zero difference is a finance-grade defect (BR-AP-02, NFR-AP-01).
 *
 * <p>All three figures are in the company BASE currency. {@code subLedgerTotal} counts only rows
 * with a reliable base value; foreign-currency rows still carrying the V62 back-fill
 * ({@code fx_rate = 1}) are EXCLUDED from it and from {@code difference}, and listed per currency
 * in {@code unconverted} so the comparison is honest about what it leaves out
 * (owner ruling 2026-10-02).
 */
public record ApReconciliationDto(
        Long companyId,
        BigDecimal subLedgerTotal,
        BigDecimal glControlBalance,
        BigDecimal difference,
        String currency,
        List<ApUnconvertedAmountDto> unconverted
) {
    public ApReconciliationDto {
        unconverted = unconverted == null ? List.of() : List.copyOf(unconverted);
    }

    public ApReconciliationDto(Long companyId, BigDecimal subLedgerTotal,
                               BigDecimal glControlBalance, BigDecimal difference,
                               String currency) {
        this(companyId, subLedgerTotal, glControlBalance, difference, currency, List.of());
    }
}

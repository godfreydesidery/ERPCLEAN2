package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Reconciliation bar for the stock valuation report (ADR-0020 D-6, BR-INV-06).
 *
 * <p>{@code ties == true} means Σ(on_hand_value) equals the GL 1300 Inventory account balance.
 * {@code ties == false} is a finance-grade defect — the stock ledger and the GL are out of sync.
 *
 * <p>Mirrors the {@code ReconciliationDto} pattern from Reporting (ADR-0018) but is a
 * stock-module-owned record (stock → gl.repository read, no Reporting dependency needed — D-1/D-12).
 */
public record StockValuationReconDto(
        String     label,
        BigDecimal computed,   // Σ on_hand_value across all products for the company
        BigDecimal expected,   // accountBalance(companyId, INVENTORY account id) from GL
        BigDecimal difference, // computed − expected
        boolean    ties        // difference.compareTo(ZERO) == 0
) {
    public static StockValuationReconDto of(String label, BigDecimal computed, BigDecimal expected) {
        BigDecimal safeExpected = expected != null ? expected : BigDecimal.ZERO;
        BigDecimal safeComputed = computed != null ? computed : BigDecimal.ZERO;
        BigDecimal diff = safeComputed.subtract(safeExpected);
        return new StockValuationReconDto(label, safeComputed, safeExpected, diff,
                diff.compareTo(BigDecimal.ZERO) == 0);
    }
}

package com.erp.modules.manufacturing.domain.dto;

import java.math.BigDecimal;

/**
 * WIP reconciliation result (FR-MFG-14, BR-MFG-05):
 * Σ open-WO WIP vs WIP GL (1320) account balance.
 *
 * <p>{@code ties == true} when {@code computed.compareTo(expected) == 0}.
 * A {@code false} value is a finance-grade defect.
 */
public record WipReconciliationDto(
        String label,
        BigDecimal computed,   // Σ (wip_debit_total − wip_credit_total) from open work orders
        BigDecimal expected,   // WIP_INVENTORY (1320) GL account balance
        boolean ties
) {
    public static WipReconciliationDto of(String label, BigDecimal computed, BigDecimal expected) {
        return new WipReconciliationDto(
                label,
                computed,
                expected,
                computed.compareTo(expected) == 0
        );
    }
}

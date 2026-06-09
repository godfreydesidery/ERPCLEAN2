package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;

/**
 * Reconciliation read: sub-ledger total vs GL 1200 balance (ADR-0014 D-8, FR-AR-18).
 * A non-zero difference is a finance-grade defect.
 */
public record ArReconciliationDto(
        Long companyId,
        BigDecimal subLedgerTotal,
        BigDecimal glControlBalance,
        BigDecimal difference,
        String currency
) {}

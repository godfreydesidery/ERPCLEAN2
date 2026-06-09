package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;

/**
 * AP reconciliation read: sub-ledger total vs GL 2100 balance (ADR-0015 D-7/D-8).
 * A non-zero difference is a finance-grade defect (BR-AP-02, NFR-AP-01).
 */
public record ApReconciliationDto(
        Long companyId,
        BigDecimal subLedgerTotal,
        BigDecimal glControlBalance,
        BigDecimal difference,
        String currency
) {}

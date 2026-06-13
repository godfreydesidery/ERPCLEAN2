package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;

/**
 * Working-capital panel: AR + AP company-wide totals from the recon sub-ledger (ADR-0037 D-6 W-AR/W-AP).
 * Sourced from ArReconciliationQuery.reconcile + ApReconciliationQuery.reconcile.
 * MUST NOT use ArAgeingQuery.ageing(companyId, null, ...) — the null-party-id returns zero rows.
 */
public record WorkingCapitalDto(
        BigDecimal arOutstanding,
        boolean    arTies,
        BigDecimal arDifference,
        BigDecimal apOutstanding,
        boolean    apTies,
        BigDecimal apDifference
) {}

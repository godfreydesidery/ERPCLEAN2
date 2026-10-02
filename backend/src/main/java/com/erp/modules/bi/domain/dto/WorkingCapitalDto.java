package com.erp.modules.bi.domain.dto;

import com.erp.modules.ap.domain.dto.ApUnconvertedAmountDto;
import com.erp.modules.ar.domain.dto.ArUnconvertedAmountDto;

import java.math.BigDecimal;
import java.util.List;

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
        BigDecimal apDifference,
        /* Foreign AR/AP amounts with no reliable base value (V62 fill), per currency — excluded
           from the base totals above (owner ruling 2026-10-02). Additive; empty when none. */
        List<ArUnconvertedAmountDto> arUnconverted,
        List<ApUnconvertedAmountDto> apUnconverted
) {
    public WorkingCapitalDto {
        arUnconverted = arUnconverted == null ? List.of() : List.copyOf(arUnconverted);
        apUnconverted = apUnconverted == null ? List.of() : List.copyOf(apUnconverted);
    }
}

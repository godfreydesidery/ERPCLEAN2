package com.erp.modules.bi.domain.dto;

import com.erp.modules.cashbank.domain.dto.CashAccountBalanceDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Cash position stat-card: summed book balance + per-account table + GL-tie health (ADR-0037 D-6 F-4).
 * Sourced from CashAccountStatementQuery.listBalances (book totals) + CashGlReconciliationQuery.reconcileAll.
 */
public record CashPositionDto(
        BigDecimal                  total,
        List<CashAccountBalanceDto> accounts,
        boolean                     cashTies,
        BigDecimal                  cashGlDifference
) {}

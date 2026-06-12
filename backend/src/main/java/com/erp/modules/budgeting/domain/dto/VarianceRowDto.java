package com.erp.modules.budgeting.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import java.math.BigDecimal;

/**
 * One row in the variance report — per account [× cost_centre_value] (ADR-0034 D-8).
 *
 * <p>{@code variance} = actual − budget (signed per ADR-0034 D-8 / BR-BUD-15).
 * {@code variancePct} = variance / budget; null when budget = 0 (BR-BUD-15).
 * Favourable/adverse label is a UI concern derived from accountType (BR-BUD-15).
 */
public record VarianceRowDto(
        Long accountId,
        String accountCode,
        String accountName,
        AccountType accountType,
        NormalBalance normalBalance,
        Long costCentreValueId,
        String costCentreValueName,
        BigDecimal budgetAmount,
        BigDecimal actualAmount,
        BigDecimal varianceAmount,
        BigDecimal variancePct
) {}

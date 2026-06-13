package com.erp.modules.budgeting.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import java.math.BigDecimal;

/**
 * One row in the departmental actuals view — per (cost_centre_value, account) (ADR-0034 D-8).
 * Consumed from ADR-0025's {@code DimensionSlicedReportQuery.actualsByAccountValuePeriod}.
 */
public record DepartmentalActualsRowDto(
        Long costCentreValueId,
        String costCentreValueName,
        Long accountId,
        String accountCode,
        String accountName,
        AccountType accountType,
        NormalBalance normalBalance,
        BigDecimal actualAmount
) {}

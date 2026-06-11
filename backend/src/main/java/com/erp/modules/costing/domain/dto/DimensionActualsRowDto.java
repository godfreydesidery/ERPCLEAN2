package com.erp.modules.costing.domain.dto;

import java.math.BigDecimal;

/**
 * One row from the actuals roll-up consumed by Budgeting and Projects (ADR-0025 D-8, FR-CC-17).
 * Groups by (accountId, valueId, periodNo) within the requested date window.
 * Budgeting reads this via {@link com.erp.modules.costing.service.DimensionSlicedReportQuery}
 * — NOT by querying journal_lines directly (D-8 contract).
 */
public record DimensionActualsRowDto(
        Long accountId,
        Long valueId,
        Integer periodNo,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal net
) {}

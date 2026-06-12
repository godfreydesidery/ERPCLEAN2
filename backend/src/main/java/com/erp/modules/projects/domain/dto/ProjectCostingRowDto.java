package com.erp.modules.projects.domain.dto;

import com.erp.modules.projects.domain.enums.ProjectCostType;
import java.math.BigDecimal;

/**
 * One row of the cost-type breakdown in the Project P&L read model (ADR-0033 D-6).
 */
public record ProjectCostingRowDto(
        ProjectCostType costType,
        BigDecimal amount
) {}

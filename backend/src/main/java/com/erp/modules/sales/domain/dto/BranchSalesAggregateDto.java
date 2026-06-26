package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * JPQL constructor-expression projection: per-branch FINALISED invoice aggregate.
 * Internal to the sales module — consumed by {@code SalesByBranchQuery} and then
 * handed (as a list) to the BI module which maps it to its own wire DTO.
 */
public record BranchSalesAggregateDto(Long branchId, BigDecimal total, Long count) {}

package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;

/**
 * One row in the Sales-by-Branch panel: a single branch's FINALISED invoice aggregate.
 * Rows are sorted by {@code total} descending in {@code SalesByBranchDto}.
 *
 * <p>Wire contract (shared with frontend):
 * {@code branchId} serialises as a JSON string (global Long-as-string Jackson config).
 */
public record BranchSalesRowDto(Long branchId, String branchCode, String branchName,
                                BigDecimal total, long count) {}

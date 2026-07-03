package com.erp.modules.manufacturing.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * A single named component-line actual-issue quantity — the production-supervisor override of
 * the "remaining planned" default (feat/production-actual-issue-qty).
 *
 * <p>Used by {@link IssueComponentsRequest#lines()}. {@code qty} is the exact quantity to issue
 * for this component line; it need not equal the remaining planned quantity (over-issue is
 * allowed — see {@code WorkOrderCostingServiceImpl#issueComponents}).
 */
public record IssueComponentLine(
        @NotBlank String componentUid,
        @NotNull BigDecimal qty
) {}

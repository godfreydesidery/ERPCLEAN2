package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to set or change a sales order's agent so an agentless (or wrong-agent) order can be
 * fixed before it is invoiced. Allowed only in pre-invoice states (DRAFT, CONFIRMED,
 * PARTIALLY_FULFILLED, FULFILLED).
 */
public record SetSalesOrderAgentRequest(
        @NotBlank String agentUid
) {
}

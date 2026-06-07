package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * Request DTO to update an existing line on a DRAFT Purchase Order (ADR-0011 D-12).
 */
public record UpdatePurchaseOrderLineRequest(
        BigDecimal orderedQty,
        BigDecimal unitCostAmount,
        String     note
) {}

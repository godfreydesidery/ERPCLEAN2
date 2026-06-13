package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * Outbox payload for DROPSHIP.FULFILLED event (ADR-0029 D-3).
 * Published when a drop-ship PO's GR is receipted; triggers COGS posting.
 */
public record DropshipFulfilledPayload(
        String salesOrderLineUid,
        String purchaseOrderUid,
        Long companyId,
        Long branchId,
        Long productId,
        BigDecimal qtyBase,
        BigDecimal supplierUnitCost,
        String currency
) {}

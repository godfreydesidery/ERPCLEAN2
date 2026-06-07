package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * Request DTO to add a line to a draft Purchase Order (ADR-0011 D-12).
 *
 * <p>productUid and unitUid are resolved by the service (company-scoped, F15).
 * unitCostAmount is required; zero is allowed only with a note reason (OQ-PURCH-04, ADR-0011 D-5).
 */
public record AddPurchaseOrderLineRequest(
        String     productUid,
        String     unitUid,
        BigDecimal orderedQty,
        BigDecimal unitCostAmount,
        String     note           // required when unitCostAmount == 0 (OQ-PURCH-04)
) {}

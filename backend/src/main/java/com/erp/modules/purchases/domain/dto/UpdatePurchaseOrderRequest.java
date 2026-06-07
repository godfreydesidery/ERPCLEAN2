package com.erp.modules.purchases.domain.dto;

import java.time.LocalDate;

/**
 * Request DTO to update header-level fields on a DRAFT Purchase Order (ADR-0011 D-12).
 * Only mutable while DRAFT (BR-PURCH-05).
 */
public record UpdatePurchaseOrderRequest(
        String    supplierUid,   // optional — re-set supplier while DRAFT
        String    notes,
        LocalDate expectedDate
) {}

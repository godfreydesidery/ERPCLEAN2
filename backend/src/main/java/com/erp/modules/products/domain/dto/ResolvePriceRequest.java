package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input to the single price resolver (ADR-0029 D-7, FR-SD-12).
 * All IDs are database IDs (Long); the caller resolves UIDs to IDs before calling.
 */
public record ResolvePriceRequest(
        Long companyId,
        /** May be null for anonymous / walk-in (resolver falls back to list/tier). */
        Long customerId,
        Long productId,
        BigDecimal quantity,
        LocalDate businessDate,
        /** The customer's assigned price list — used for tier + list lookups. */
        Long priceListId
) {
}

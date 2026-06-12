package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Per-location on-hand row (ADR-0028 D-8, FR-INVD-05/06).
 */
public record LocationOnHandRowDto(
        Long locationId,
        String locationUid,
        String locationCode,
        String locationName,
        Long productId,
        String productUid,
        String productCode,
        String productName,
        BigDecimal quantity,
        BigDecimal onHandValue,
        BigDecimal avgCost,
        String currency
) {}

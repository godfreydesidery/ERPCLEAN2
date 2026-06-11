package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Row in the lot-expiry report (ADR-0028 D-8, FR-INVD-22).
 */
public record ExpiryReportRowDto(
        String productUid,
        String productCode,
        String productName,
        String lotNumber,
        String locationCode,
        String locationName,
        LocalDate expiryDate,
        BigDecimal qtyOnHand,
        BigDecimal avgCost,
        BigDecimal valueAtRisk,
        boolean expired,
        String currency
) {}

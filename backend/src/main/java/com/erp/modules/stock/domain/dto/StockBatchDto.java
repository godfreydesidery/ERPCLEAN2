package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for a stock batch/lot (ADR-0028 D-7).
 */
public record StockBatchDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long locationId,
        Long productId,
        String lotNumber,
        LocalDate manufactureDate,
        LocalDate expiryDate,
        BigDecimal qtyOnHand,
        boolean expired
) {}

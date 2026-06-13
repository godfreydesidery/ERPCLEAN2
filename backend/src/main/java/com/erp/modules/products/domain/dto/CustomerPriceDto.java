package com.erp.modules.products.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for a customer-specific price (ADR-0029 D-6).
 */
public record CustomerPriceDto(
        Long id,
        String uid,
        Long companyId,
        Long customerId,
        Long productId,
        BigDecimal unitPriceAmount,
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        MasterStatus status
) {
}

package com.erp.modules.products.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;

/**
 * Response DTO for a price tier row (ADR-0029 D-6).
 */
public record PriceTierDto(
        Long id,
        String uid,
        Long companyId,
        Long productId,
        Long priceListId,
        BigDecimal minQty,
        BigDecimal unitPriceAmount,
        String currency,
        MasterStatus status
) {
}

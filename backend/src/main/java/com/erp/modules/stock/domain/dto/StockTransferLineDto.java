package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Response DTO for a single stock transfer line (ADR-0028 D-5).
 */
public record StockTransferLineDto(
        Long id,
        String uid,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        String unitName,
        BigDecimal qtyTransferred,
        BigDecimal qtyTransferredBase,
        BigDecimal valueAmount,
        String currency
) {}

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
        /**
         * Cost of ONE {@code unitName}, derived at read time as {@code valueAmount / qtyTransferred}
         * — so for a line counted in cartons this is the cost of a carton, not of a piece, which is
         * what a "Price" column beside a carton quantity has to mean.
         *
         * <p>Derived here in the stock module rather than by whoever prints it: the documents module
         * is forbidden from deriving amounts (BR-DOC-02), and two screens dividing the same numbers
         * two different ways is how a shop ends up with two answers for one price.
         *
         * <p>Null when the line was never costed, or when the quantity is zero — both are unknowns,
         * not zeros.
         */
        BigDecimal unitCost,
        BigDecimal valueAmount,
        String currency
) {}

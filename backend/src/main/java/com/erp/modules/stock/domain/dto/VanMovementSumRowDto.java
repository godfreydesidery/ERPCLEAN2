package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Per-product movement sum row (ADR-0051 D-8.6) — the result of summing a single
 * {@code MovementType} at a van location within a business-date window. Used to derive
 * {@code loaded_qty} (Σ TRANSFER_IN) and {@code returned_qty} (−Σ TRANSFER_OUT) per product.
 */
public record VanMovementSumRowDto(
        Long productId,
        BigDecimal qty
) {}

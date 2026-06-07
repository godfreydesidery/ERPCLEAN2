package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Request body for the reorder-level set/clear endpoint (ADR-0010 D-11, OQ-STOCK-06).
 *
 * <p>{@code reorderLevel} is nullable — a null value clears the threshold (indicator-only; no
 * auto-reorder). When non-null, the service validates >= 0 (DB CHECK chk_stock_on_hand_reorder
 * also enforces this, but the service provides a friendlier 400).
 */
public record SetReorderLevelRequest(
        BigDecimal reorderLevel
) {}

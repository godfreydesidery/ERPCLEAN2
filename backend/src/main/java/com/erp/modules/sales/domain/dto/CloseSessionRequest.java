package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request to close an open POS session — cashier declares counted cash (ADR-0029 D-5).
 */
public record CloseSessionRequest(
        @NotNull BigDecimal countedCashAmount,
        String notes
) {}

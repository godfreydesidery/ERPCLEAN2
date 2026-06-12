package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PosPayoutType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request to record a cash-in or cash-out payout on an open session (ADR-0029 D-5).
 */
public record PosPayoutRequest(
        @NotNull PosPayoutType payoutType,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @Size(max = 255) String reason
) {}

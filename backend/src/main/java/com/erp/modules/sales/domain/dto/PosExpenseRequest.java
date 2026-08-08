package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request to record a categorised business expense paid out of the till drawer (K8, V94).
 *
 * <p>Separate from {@link PosPayoutRequest} because it is a separate capability: recording an
 * expense is gated on {@code POS.EXPENSE.RECORD}, and it carries no {@code payoutType} — the
 * endpoint decides that — so an expense can never be filed as a refund by accident.
 *
 * <p>Both {@code category} and {@code reason} are REQUIRED. The category is the bucket the
 * cross-session expense report groups by, and cash leaving a drawer with no stated purpose is not
 * an expense anyone can account for. The service trims and re-checks both, so whitespace cannot
 * pass for either. {@code category} is capped at 40 characters to match the column (V94).
 */
public record PosExpenseRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(min = 2, max = 40) String category,
        @NotBlank @Size(min = 3, max = 255) String reason
) {}

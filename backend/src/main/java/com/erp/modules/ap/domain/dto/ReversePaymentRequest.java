package com.erp.modules.ap.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Reverse a posted supplier payment (AP-03): the wrong bill was paid, it was paid twice, or the
 * money never left. The reason is required — it goes into the reversing journal and the audit trail.
 */
public record ReversePaymentRequest(
        @NotBlank(message = "Give a reason for reversing this payment.")
        @Size(max = 200, message = "Keep the reason to 200 characters or fewer.")
        String reason
) {}

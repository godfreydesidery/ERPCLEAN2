package com.erp.modules.ar.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Reverse a posted customer receipt (ARC-04): the receipt was recorded against the wrong customer,
 * for the wrong amount, or the money never arrived. The reason is required — it is written into
 * the reversing journal's description and the audit trail.
 */
public record ReverseReceiptRequest(
        @NotBlank(message = "Give a reason for reversing this receipt.")
        @Size(max = 200, message = "Keep the reason to 200 characters or fewer.")
        String reason
) {}

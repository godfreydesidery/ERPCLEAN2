package com.erp.modules.ar.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pay a customer's credit back in cash or by bank (ARC-11): money held on account on a receipt
 * (an over-payment or a deposit) or an unused credit note.
 *
 * @param sourceType         {@code RECEIPT} or {@code CREDIT_NOTE}
 * @param sourceUid          the receipt or credit note holding the credit
 * @param amount             how much to pay back (at most the credit still unused)
 * @param refundDate         optional; today when omitted
 * @param cashBankAccountUid optional; the company default cash/bank account when omitted
 * @param reason             required — written into the journal, the cash book and the audit trail
 */
public record RefundCustomerRequest(
        @NotNull(message = "Say whether the refund is from a receipt or a credit note.")
        @Pattern(regexp = "RECEIPT|CREDIT_NOTE",
                message = "Say whether the refund is from a receipt or a credit note.")
        String sourceType,
        @NotBlank(message = "Choose the receipt or credit note to refund.")
        @Size(max = 26, message = "Choose the receipt or credit note to refund.")
        String sourceUid,
        @NotNull(message = "Enter the amount to refund.")
        @DecimalMin(value = "0.01", message = "The refund must be more than zero.")
        BigDecimal amount,
        LocalDate refundDate,
        @Size(max = 26, message = "The cash or bank account is not valid.")
        String cashBankAccountUid,
        @NotBlank(message = "Give a reason for the refund.")
        @Size(max = 200, message = "Keep the reason to 200 characters or fewer.")
        String reason
) {
    public static final String RECEIPT = "RECEIPT";
    public static final String CREDIT_NOTE = "CREDIT_NOTE";
}

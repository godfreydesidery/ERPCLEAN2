package com.erp.modules.tax.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ACC-07: pay a filed VAT return to TRA from a cash/bank account — posts DR VAT Due / CR the
 * account's GL through a cash transaction.
 *
 * @param cashBankAccountUid the cash or bank account the money left
 * @param paymentDate        the date of the payment (the GL posting date)
 * @param amount             the amount paid; null pays everything still owed
 * @param reference          TRA payment / control number (optional)
 */
public record RecordTaxPaymentRequest(
        @NotBlank String cashBankAccountUid,
        @NotNull LocalDate paymentDate,
        @Positive BigDecimal amount,
        @Size(max = 80) String reference
) {}

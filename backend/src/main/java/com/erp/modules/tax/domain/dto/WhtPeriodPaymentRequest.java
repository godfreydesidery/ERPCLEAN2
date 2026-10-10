package com.erp.modules.tax.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * ACC-07: pay all WHT deducted from suppliers in a period (and not yet remitted) to TRA in one bank
 * payment. Every such certificate is marked remitted with {@code remittanceRef}; one cash
 * transaction posts DR WHT Payable / CR the account's GL for their total.
 */
public record WhtPeriodPaymentRequest(
        @NotNull Long companyId,
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @NotBlank String cashBankAccountUid,
        @NotNull LocalDate paymentDate,
        @NotBlank @Size(max = 80) String remittanceRef
) {}

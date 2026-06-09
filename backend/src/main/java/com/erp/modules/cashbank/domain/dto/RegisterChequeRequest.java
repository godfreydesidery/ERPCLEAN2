package com.erp.modules.cashbank.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request to register a cheque (FR-CASH-10). */
public record RegisterChequeRequest(
        @NotBlank String companyUid,
        @NotBlank String cashBankAccountUid,
        @NotBlank String chequeNumber,
        @NotBlank String payee,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate issueDate,
        @NotNull LocalDate valueDate,
        /** Optional: uid of the AP payment this cheque settles. */
        String apPaymentUid,
        /** Optional: uid of the cash_transaction this cheque settles. */
        String cashTransactionUid
) {}

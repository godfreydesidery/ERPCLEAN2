package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.StatutoryLiability;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ACC-07: pay a payroll statutory liability (PAYE, NSSF, WCF, SDL, HESLB) from a cash/bank account —
 * DR the liability control account / CR the account's GL, as a cash transaction.
 */
public record RecordStatutoryPaymentRequest(
        @NotNull StatutoryLiability liability,
        @NotBlank String cashBankAccountUid,
        @NotNull LocalDate paymentDate,
        @NotNull @Positive BigDecimal amount,
        /** Authority receipt / control number (optional). */
        @Size(max = 80) String reference
) {}

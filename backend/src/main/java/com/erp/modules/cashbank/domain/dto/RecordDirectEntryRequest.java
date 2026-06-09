package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request to record a direct cash/bank entry not tied to AR/AP (FR-CASH-09). */
public record RecordDirectEntryRequest(
        @NotBlank String companyUid,
        @NotBlank String cashBankAccountUid,
        @NotNull CashTxnDirection direction,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate txnDate,
        /** uid of the counter GL account (income/expense/equity). */
        @NotBlank String counterGlAccountUid,
        String memo
) {}

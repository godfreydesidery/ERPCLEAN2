package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to record a direct cash/bank entry not tied to AR/AP (FR-CASH-09).
 *
 * <p>ACC-13 / PAR-08: {@code vatAmount} (optional, money OUT only) is the input VAT included in
 * {@code amount}. It posts DR VAT Input for that part and DR the counter account for the rest, so a
 * cash or bank expense can claim its input VAT; the VAT return reads it as input VAT.
 */
public record RecordDirectEntryRequest(
        @NotBlank String companyUid,
        @NotBlank String cashBankAccountUid,
        @NotNull CashTxnDirection direction,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate txnDate,
        /** uid of the counter GL account (income/expense/equity). */
        @NotBlank String counterGlAccountUid,
        String memo,
        /** ACC-13: input VAT included in {@code amount}; null or zero = none. */
        @PositiveOrZero BigDecimal vatAmount
) {
    /** The original shape — no input VAT. */
    public RecordDirectEntryRequest(String companyUid, String cashBankAccountUid,
                                    CashTxnDirection direction, BigDecimal amount,
                                    LocalDate txnDate, String counterGlAccountUid, String memo) {
        this(companyUid, cashBankAccountUid, direction, amount, txnDate, counterGlAccountUid, memo,
                null);
    }
}

package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to record a petty-cash fund movement (ADR-0050 D-7 PR-B).
 *
 * <p>{@code amount} must be strictly positive for {@code DISBURSEMENT}/{@code REPLENISHMENT}. For
 * {@code ADJUSTMENT} it may be positive (increases the float) or negative (decreases it) — the
 * signed delta the operator intends. The persisted transaction's {@code amount} column always
 * stores the absolute magnitude (DB CHECK {@code chk_petty_cash_txn_amount}); {@code balanceAfter}
 * reflects the signed effect on the fund.
 *
 * <p>ARC-10: every movement now posts to the GL (see {@code PettyCashGlPoster}).
 */
public record RecordPettyCashTxnRequest(
        @NotNull PettyCashTxnType type,
        @NotNull BigDecimal amount,
        @NotNull LocalDate txnDate,
        /**
         * Uid of the GL account on the other side: the expense account for a DISBURSEMENT (required —
         * it is debited, ARC-10), the funding account for a REPLENISHMENT when no source cash/bank
         * account is named. Ignored for an ADJUSTMENT (posted to cash over/short).
         */
        String glAccountUid,
        String reference,
        String description,
        /**
         * REPLENISHMENT only, optional: the cash/bank account the top-up came from. Its GL account is
         * credited and its cash book gets the OUT row. Absent = {@code glAccountUid}, else the
         * company's default cash/bank account.
         */
        String sourceCashBankAccountUid
) {
    /** The shape before ARC-10, without a source cash/bank account. */
    public RecordPettyCashTxnRequest(PettyCashTxnType type, BigDecimal amount, LocalDate txnDate,
                                     String glAccountUid, String reference, String description) {
        this(type, amount, txnDate, glAccountUid, reference, description, null);
    }
}

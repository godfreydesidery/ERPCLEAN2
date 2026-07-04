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
 */
public record RecordPettyCashTxnRequest(
        @NotNull PettyCashTxnType type,
        @NotNull BigDecimal amount,
        @NotNull LocalDate txnDate,
        /** Optional; uid of the expense/funding GL account captured for the future GL fast-follow. */
        String glAccountUid,
        String reference,
        String description
) {}

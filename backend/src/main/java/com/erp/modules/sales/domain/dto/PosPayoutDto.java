package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PosPayoutType;
import java.math.BigDecimal;

/**
 * One recorded cash payout from a till drawer (K8).
 *
 * <p>The repository previously offered a SUM and nothing else, so the individual payouts that make
 * up a shift's "Payouts" line could not be listed, printed or questioned. This is the row a payout
 * report is built from; {@link PayoutSubtotalDto} remains the per-type roll-up of the same rows.
 *
 * @param id              numeric id (serialised as a JSON string)
 * @param uid             external identifier
 * @param companyId       owning company
 * @param branchId        owning branch
 * @param posSessionId    the session the cash left
 * @param payoutType      REFUND (cash back to a customer), PAID_OUT (drawer drop / misc payout) or
 *                        EXPENSE (a categorised business expense paid out of the drawer)
 * @param amount          always positive — a payout is an outflow
 * @param category        the expense bucket; set on an EXPENSE, null on REFUND/PAID_OUT
 * @param reason          why the cash left the drawer; mandatory on new payouts (K8)
 * @param recordedAt      when it was recorded, ISO-8601
 * @param recordedBy      the user who recorded it
 * @param glAccountId     the account the payout was debited to, when it was posted
 * @param journalEntryUid the GL journal raised for this payout, read back from the persisted column
 *                        (V94). Null when the payout raises no entry (REFUND) or predates V94.
 */
public record PosPayoutDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long posSessionId,
        PosPayoutType payoutType,
        BigDecimal amount,
        String category,
        String reason,
        String recordedAt,
        Long recordedBy,
        Long glAccountId,
        String journalEntryUid
) {}

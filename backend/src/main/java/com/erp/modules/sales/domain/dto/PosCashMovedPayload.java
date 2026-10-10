package com.erp.modules.sales.domain.dto;

/**
 * Outbox payload for {@code POS.CASH.MOVED} (gap review wave 3, ARC-08): a POS till payout /
 * expense or a session over/short has just been posted to the GL, in the same transaction as this
 * event. The cash book reads the posted journal back by {@code journalEntryUid} and mirrors its
 * cash leg — the payload carries no amounts, so the cash row can never disagree with the ledger.
 *
 * @param kind            {@code PAYOUT} (cash out of the till) or {@code VARIANCE} (over/short)
 * @param sourceUid       the payout uid (PAYOUT) or the session uid (VARIANCE)
 * @param journalEntryUid the GL entry just posted for it
 * @param memo            human-readable line for the cash book, e.g. "Till payout POS-0003"
 */
public record PosCashMovedPayload(
        String kind,
        String sourceUid,
        String journalEntryUid,
        String memo
) {
    public static final String KIND_PAYOUT   = "PAYOUT";
    public static final String KIND_VARIANCE = "VARIANCE";
}

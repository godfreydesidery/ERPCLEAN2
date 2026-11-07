package com.erp.modules.sales.domain.dto;

import java.util.List;

/**
 * The server's answer to an offline sync (ADR-0068 D-5). The till removes from its queue every sale
 * the answer settles — POSTED, ALREADY_POSTED or HELD — and keeps any it does not mention.
 */
public record OfflineSyncResultDto(
        List<SessionResult> sessions,
        List<SaleResult> sales,
        int periodsRecorded
) {

    /** How an offline-opened shift is known on the server from now on. */
    public record SessionResult(String clientKey, String sessionUid, String sessionNumber) {}

    /**
     * One sale's outcome.
     *
     * @param status   POSTED (new invoice), ALREADY_POSTED (an earlier sync posted it) or HELD
     *                 (not posted; on the manager review list)
     * @param flags    review kinds raised for a posted sale, e.g. PRICE_DIFFERENCE
     * @param message  for HELD: why, in words a cashier can read
     */
    public record SaleResult(String offlineNumber, String idempotencyKey, String status,
                             String invoiceUid, String invoiceNumber, List<String> flags,
                             String message) {}
}

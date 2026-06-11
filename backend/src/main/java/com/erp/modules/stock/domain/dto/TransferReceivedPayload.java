package com.erp.modules.stock.domain.dto;

import java.time.Instant;
import java.util.List;

/**
 * Outbox payload for STOCK.TRANSFER.RECEIVED (ADR-0028 D-12).
 * Consumed by {@link com.erp.modules.stock.events.TransferReceiveStockHandler}.
 */
public record TransferReceivedPayload(
        String transferUid,
        Long companyId,
        Long destBranchId,
        Long destLocationId,
        Long inTransitLocationId,
        Instant receivedAt,
        List<TransferDispatchedPayload.LineItem> lines
) {}

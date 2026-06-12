package com.erp.modules.ar.domain.dto;

import java.time.Instant;

/**
 * Outbox payload for {@code PAYMENT.RECEIVED} events (ADR-0024 D-8).
 * Emitted by {@link com.erp.modules.ar.service.ArReceiptServiceImpl} in the receipt's TX.
 * Owned by AR (the producer). Consumed by the notifications dispatcher.
 *
 * <p>Carries already-formatted display strings for the notification template (BR-NOTIF-13 —
 * no {@code Money}, no arithmetic in the notifications module).
 */
public record PaymentReceivedPayload(
        String receiptUid,
        Long companyId,
        Long branchId,
        String customerName,
        String amountFormatted,
        String currency,
        Instant receivedAt
) {}

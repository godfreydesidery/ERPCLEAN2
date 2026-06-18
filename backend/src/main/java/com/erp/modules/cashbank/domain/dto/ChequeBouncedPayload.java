package com.erp.modules.cashbank.domain.dto;

/**
 * Outbox payload for {@code CHEQUE.BOUNCED} events (ADR-0041 D3).
 * Emitted by {@link com.erp.modules.cashbank.service.ChequeServiceImpl#bounce} in the bounce TX.
 * Owned by cashbank (the producer). Consumed by AR (inbound) and AP (outbound) reversal handlers.
 *
 * <p>Carries the cheque direction + the soft-FK uids of the receipt/payment the cheque settled so
 * the consuming module can locate the original document and post the append-only cash-leg reversal.
 */
public record ChequeBouncedPayload(
        String chequeUid,
        /** OUTBOUND or INBOUND — selects which consumer (AP vs AR) acts. */
        String direction,
        /** Scalar uid of the AR receipt this inbound cheque funded; null for outbound. */
        String arReceiptUid,
        /** Scalar uid of the AP payment this outbound cheque funded; null for inbound. */
        String apPaymentUid,
        String bounceReason
) {}

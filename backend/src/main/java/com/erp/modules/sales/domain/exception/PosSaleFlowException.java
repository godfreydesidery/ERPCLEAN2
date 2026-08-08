package com.erp.modules.sales.domain.exception;

import com.erp.modules.sales.domain.enums.PosSaleFlowStatus;
import com.erp.platform.common.api.ConflictException;

/**
 * A POS sale attempt that did not produce an invoice, carrying a machine-readable
 * {@link PosSaleFlowStatus} so the till client can tell "still in flight" from "refused" without
 * parsing the message (K11).
 *
 * <p>Extends {@link ConflictException} deliberately: {@code PosSaleController} handles this type
 * locally and maps each status to its own HTTP code, but if that handler is ever removed the
 * platform handler still produces today's 409 rather than a 500.
 *
 * <p>The message is the friendly text shown to the cashier and must stay free of internal detail.
 *
 * <p><b>Two levels of code.</b> {@link PosSaleFlowStatus} answers "was anything written?" — the only
 * question that decides whether a till may retry. {@link #getErrorCode()} is finer: when the REJECTED
 * outcome came from a rule the client can offer a remedy for, the rule's own token travels with it
 * (today: the discount policy's {@code DISCOUNT_*} codes, UAT finding #13). It is null for every
 * refusal that has no such token, and clients that ignore it see exactly today's behaviour.
 */
public class PosSaleFlowException extends ConflictException {

    private final PosSaleFlowStatus status;
    private final String errorCode;

    private PosSaleFlowException(PosSaleFlowStatus status, String message, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public PosSaleFlowStatus getStatus() {
        return status;
    }

    /**
     * The refusing rule's machine-readable token, or {@code null} when the refusal carried none.
     * Never parse the message to work this out — that is the bug this replaces.
     */
    public String getErrorCode() {
        return errorCode;
    }

    /** An earlier attempt with the same idempotency key has not finished — retry or poll. */
    public static PosSaleFlowException inFlight() {
        return new PosSaleFlowException(PosSaleFlowStatus.IN_FLIGHT,
                "This sale is still being processed. Please try again in a moment.", null);
    }

    /** A business rule refused the sale. Nothing is pending; the cashier must act on the message. */
    public static PosSaleFlowException rejected(String friendlyMessage) {
        return rejected(friendlyMessage, null);
    }

    /**
     * A business rule refused the sale and named itself. {@code errorCode} is the refusing rule's
     * token (null when it has none) and reaches the client on the response body and header, so the
     * client can offer the matching remedy without reading the sentence.
     */
    public static PosSaleFlowException rejected(String friendlyMessage, String errorCode) {
        return new PosSaleFlowException(PosSaleFlowStatus.REJECTED,
                friendlyMessage == null || friendlyMessage.isBlank()
                        ? "This sale could not be completed."
                        : friendlyMessage,
                errorCode);
    }

    /** The attempt is too old to be re-priced and re-stocked into the current period. */
    public static PosSaleFlowException staleReplay(String friendlyMessage) {
        return new PosSaleFlowException(PosSaleFlowStatus.STALE_REPLAY, friendlyMessage, null);
    }
}

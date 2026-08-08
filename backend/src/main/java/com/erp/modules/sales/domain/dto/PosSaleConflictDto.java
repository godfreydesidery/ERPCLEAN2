package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PosSaleFlowStatus;

/**
 * Body payload returned when a POS sale attempt is refused (K11).
 *
 * <p>It travels in the {@code data} slot of the standard envelope while {@code errors[0]} carries
 * the same friendly text, so a client may branch on {@code data.code}, on the
 * {@code X-Pos-Sale-Status} header, or on the HTTP status — never on the prose.
 *
 * @param code    the machine-readable outcome
 * @param message the friendly text to show the cashier (identical to {@code errors[0]})
 * @param errorCode the refusing rule's own token when it had one (e.g.
 *                {@code DISCOUNT_APPROVAL_REQUIRED}), else {@code null}. {@code code} says whether
 *                anything was written; this says WHICH rule refused, so a client can offer the
 *                matching remedy instead of searching the message for English words (UAT #13).
 *                Additive — a client that ignores it behaves exactly as before.
 */
public record PosSaleConflictDto(PosSaleFlowStatus code, String message, String errorCode) {

    /** A refusal from a rule that carries no token of its own. */
    public PosSaleConflictDto(PosSaleFlowStatus code, String message) {
        this(code, message, null);
    }
}

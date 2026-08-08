package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PosSaleLookupVerdict;
import java.math.BigDecimal;

/**
 * Definitive answer to "did the sale I sent under this reference actually post?" (K11).
 *
 * <p>Cheap and read-only: it consults the idempotency marker and nothing else, so it works long
 * after the session closed and it never re-runs a business guard. That is the whole point — the
 * previous only way to ask was to re-POST the entire sale, which could answer "the session is
 * closed" instead of answering the question.
 *
 * @param idempotencyKey    the key that was asked about (echoed so a client can match responses)
 * @param verdict           POSTED / NEVER_POSTED / UNKNOWN — branch on this, never on the message
 * @param invoiceUid        the finalised invoice's uid; null unless {@code verdict = POSTED}
 * @param invoiceNumber     the human-readable invoice number; null unless {@code verdict = POSTED}
 * @param grossTotalAmount  the invoice gross; null unless {@code verdict = POSTED}
 * @param message           friendly text for the cashier
 */
public record PosSaleLookupDto(
        String idempotencyKey,
        PosSaleLookupVerdict verdict,
        String invoiceUid,
        String invoiceNumber,
        BigDecimal grossTotalAmount,
        String message
) {}

package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PosPayoutType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request to record a REFUND or PAID_OUT payout on an open session (ADR-0029 D-5).
 *
 * <p>K8: {@code reason} is now REQUIRED. Cash leaving a drawer with no stated purpose is not an
 * expense anyone can account for, and it was optional on both the API and the column, so a till
 * payout could be an entirely blank record. The column stays nullable (historical rows keep their
 * NULLs — no migration); the rule is enforced here and re-checked in the service, which also trims,
 * so whitespace cannot pass for a reason.
 *
 * <p>{@code payoutType} of EXPENSE is REFUSED here: a categorised till expense is its own capability
 * with its own permission ({@code POS.EXPENSE.RECORD}) and its own mandatory category, so it goes
 * through {@link PosExpenseRequest} instead. Otherwise anyone able to open a session could file an
 * uncategorised expense.
 */
public record PosPayoutRequest(
        @NotNull PosPayoutType payoutType,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(min = 3, max = 255) String reason,
        // POS-02 / POS-05 (additive, optional): the uid a manager step-up returned. Re-resolved by
        // the server - it names a person, it grants nothing. A REFUND is refused without one unless
        // the caller holds the authority; a PAID_OUT without one is still accepted (older tills) and
        // audited as unapproved.
        @Size(max = 26, message = "The approval reference is not valid.") String authorisedByUid
) {

    /** Pre-POS-05 shape - what OrbixPOS 1.5.x and earlier send. */
    public PosPayoutRequest(PosPayoutType payoutType, BigDecimal amount, String reason) {
        this(payoutType, amount, reason, null);
    }
}

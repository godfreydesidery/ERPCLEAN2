package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.DiscountApprovalAction;
import java.math.BigDecimal;

/**
 * A company's resolved discount-approval policy (K7).
 *
 * @param action            what to do when a line discount exceeds {@code maxDiscountPercent}
 * @param maxDiscountPercent the ceiling a cashier may apply unaided, as a percent of the line's
 *                          gross (unit price × quantity). {@code null} means "no ceiling has been
 *                          configured", which the guard reads as ZERO under APPROVE/BLOCK — if you
 *                          switch the policy on without setting a number, every discount needs
 *                          authority. That is the safe direction to fail: the alternative reading
 *                          ("no ceiling = unlimited") would make turning the policy on do nothing,
 *                          which is exactly the class of bug that shipped with the negative-stock
 *                          toggle.
 */
public record DiscountPolicyDto(DiscountApprovalAction action, BigDecimal maxDiscountPercent) {

    private static final DiscountPolicyDto OFF =
            new DiscountPolicyDto(DiscountApprovalAction.OFF, null);

    /** The policy a company reads until it opts in (the V95 column default) — no check at all. */
    public static DiscountPolicyDto off() {
        return OFF;
    }

    /** True when nothing should be checked, so callers can return before doing any work. */
    public boolean isOff() {
        return action == null || action == DiscountApprovalAction.OFF;
    }

    /** The ceiling to compare against; a policy that is on but unconfigured allows nothing. */
    public BigDecimal ceiling() {
        return maxDiscountPercent != null ? maxDiscountPercent : BigDecimal.ZERO;
    }
}
